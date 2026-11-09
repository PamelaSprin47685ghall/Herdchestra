(ns hc.client.search.history-test
  "Contract tests for global history search state.

  These tests exercise the pure projection only: no JVM store, no browser, and
  no command transport.  They pin the state shape the view and the command
  bridge rely on."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.search.history :as history]))

(defn- search-payload
  []
  {:mode :search
   :query "alpha"
   :hits [{:session-id "s1"
           :message-id "m1"
           :version-id "v1"
           :snippet "first alpha snippet"
           :historical-version? false
           :assistant-id "a1"
           :session-title "Session One"
           :created-at 1000}
          {:session-id "s2"
           :message-id "m2"
           :version-id "v2"
           :snippet "second alpha snippet"
           :historical-version? true
           :assistant-id "a2"
           :session-title "Session Two"
           :created-at 2000}]
   :count 2
   :truncated? true})

(deftest initial-state-pins-the-documented-shape
  (is (= {:open? false
          :query ""
          :mode :history
          :status :idle
          :hits []
          :count 0
          :truncated? false
          :error nil
          :selected-index 0}
         (select-keys history/initial-state
                      [:open? :query :mode :status :hits :count
                       :truncated? :error :selected-index]))))

(deftest open-close-and-query-are-local-state-only
  (is (false? (history/open? history/initial-state)))
  (is (true? (history/open? (history/open history/initial-state))))
  (is (false? (history/open? (history/close (history/open history/initial-state)))))
  (is (= "needle" (:query (history/set-query history/initial-state "needle"))))
  (is (= "" (:query (history/set-query history/initial-state nil)))))

(deftest apply-result-normalizes-hits-and-reports-truncation
  (let [state (history/apply-result history/initial-state (search-payload))]
    (is (= :loaded (:status state)))
    (is (= 2 (:count state)))
    (is (true? (:truncated? state))
        "the server truncation flag must reach the view unchanged")
    (is (= "alpha" (:query state)))
    (is (nil? (:error state)))
    (is (zero? (:selected-index state)))
    (is (= ["m1" "m2"] (mapv :message-id (:hits state))))
    (is (= ["s1" "s2"] (mapv :session-id (:hits state))))
    (is (= ["v1" "v2"] (mapv :version-id (:hits state))))
    (is (= "first alpha snippet" (get-in state [:hits 0 :snippet])))
    (is (false? (get-in state [:hits 0 :historical-version?])))
    (is (true? (get-in state [:hits 1 :historical-version?])))
    (testing "a hit without message identity is dropped, not invented"
      (let [malformed (history/apply-result
                       history/initial-state
                       (assoc (search-payload)
                              :hits [{:session-id "s1" :snippet "orphan"}]
                              :count 0
                              :truncated? false))]
        (is (empty? (:hits malformed)))
        (is (zero? (:count malformed)))
        (is (false? (:truncated? malformed)))))))

(deftest apply-error-clears-hits-and-keeps-the-reason
  (let [loaded (history/apply-result history/initial-state (search-payload))
        failed (history/apply-error
                loaded
                {:code :HISTORY_SEARCH_FAILED
                 :message "index unavailable"})]
    (is (= :error (:status failed)))
    (is (empty? (:hits failed))
        "a failed query cannot keep stale hits visible")
    (is (zero? (:count failed)))
    (is (false? (:truncated? failed)))
    (is (= :HISTORY_SEARCH_FAILED (get-in failed [:error :code])))
    (is (= "index unavailable" (get-in failed [:error :message])))
    (is (= "历史索引查询失败" (history/error-label (:error failed))))
    (is (= 0 (:selected-index failed)))))

(deftest editing-the-query-drops-hits-from-the-old-words
  (let [loaded (history/apply-result history/initial-state (search-payload))
        edited (history/set-query loaded "beta")]
    (is (= :idle (:status edited)))
    (is (empty? (:hits edited)))
    (is (= "beta" (:query edited)))
    (testing "a late answer for the old query is ignored"
      (let [late (history/apply-result edited (search-payload))]
        (is (= :idle (:status late)))
        (is (empty? (:hits late)))
        (is (= "beta" (:query late)))))
    (testing "padding the same words is not a different query"
      (let [padded (history/set-query loaded "  alpha  ")]
        (is (= 2 (:count padded)))
        (is (= 2 (count (:hits padded))))))))

(deftest move-selection-stops-at-the-list-edges
  (let [state (history/apply-result history/initial-state (search-payload))]
    (testing "up from the first hit stays at the first hit"
      (is (= 0 (:selected-index (history/move-selection state -1)))))
    (testing "down from the last hit stays at the last hit"
      (is (= 1 (:selected-index
                (history/move-selection (assoc state :selected-index 1) 1)))))
    (testing "large moves clamp without wrapping"
      (is (= 0 (:selected-index
                (history/move-selection (assoc state :selected-index 0) -5))))
      (is (= 1 (:selected-index
                (history/move-selection (assoc state :selected-index 1) 99)))))
    (testing "an empty result set is safe"
      (is (= 0 (:selected-index
                (history/move-selection
                 (assoc state :hits [] :selected-index 3)
                 1)))))))

(deftest format-hit-context-distinguishes-current-and-historical-versions
  (let [current (history/format-hit-context
                 {:session-title "Session One"
                  :assistant-id "assistant-a"
                  :created-at 1000
                  :version-id "v-current"
                  :historical-version? false}
                 60000)
        historical (history/format-hit-context
                    {:session-title "Session One"
                     :assistant-id "assistant-a"
                     :created-at 1000
                     :version-id "v-history"
                     :historical-version? true}
                    60000)]
    (is (= "assistant-a" (:assistant current)))
    (is (= "当前版本" (:version-label current)))
    (is (nil? (:version-badge current)))
    (is (= "历史版本" (:version-label historical)))
    (is (= "历史版本" (:version-badge historical)))
    (is (not= (:version-label current) (:version-label historical))
        "the two version identities must not share one badge")
    (is (= "v-history" (:version-id historical)))
    (is (= "刚刚" (:time current)))
    (is (= "时间未知"
           (:time (history/format-hit-context
                   {:session-id "s1"
                    :message-id "m1"
                    :historical-version? false}
                   61000))))))

(deftest set-filters-keeps-the-query-and-removes-cleared-conditions
  (let [state (-> history/initial-state
                  (history/set-query "alpha")
                  (history/set-filters {:assistant-id " a1 "
                                        :status "active"
                                        :include-versions? true
                                        :has-attachments? true}))
        filters (:filters state)]
    (is (= "alpha" (:query state))
        "changing filters never clears the query")
    (is (= "a1" (:assistant-id filters)))
    (is (= :active (:status filters)))
    (is (true? (:include-versions? filters)))
    (is (true? (:has-attachments? filters)))
    (testing "one removed condition does not hide the others"
      (let [removed (history/set-filters state {:assistant-id nil})]
        (is (= "alpha" (:query removed)))
        (is (not (contains? (:filters removed) :assistant-id)))
        (is (= :active (get-in removed [:filters :status])))
        (is (true? (get-in removed [:filters :include-versions?])))))
    (testing "clearing everything leaves no hidden predicate"
      (let [cleared (history/clear-filters state)]
        (is (= {} (:filters cleared)))
        (is (= "alpha" (:query cleared)))))
    (testing "changing conditions invalidates hits from the old conditions"
      (let [loaded (-> (history/apply-result
                        history/initial-state
                        (search-payload))
                       (history/set-filters {:assistant-id "a1"}))]
        (is (= "alpha" (:query loaded)))
        (is (= :idle (:status loaded)))
        (is (empty? (:hits loaded)))
        (is (zero? (:count loaded)))))))

(deftest receipt-extraction-tolerates-gateway-envelopes
  (let [payload {:mode :search :query "alpha"
                 :hits [] :count 0 :truncated? false}
        wrapped {:ok? true
                 :receipt {:status :accepted
                           :result {:status :ok :payload payload}
                           :command-id "cmd-1"}}
        status {:state :ready :generation 3 :session-count 2}]
    (is (= payload (history/receipt-payload wrapped)))
    (is (= "cmd-1" (history/receipt-command-id wrapped)))
    (is (= :accepted (history/receipt-status wrapped)))
    (is (= :accepted
           (history/receipt-status
            {:receipt {:status :accepted
                       :result {:status :error
                                :error {:code :HISTORY_SEARCH_FAILED}}}})))
    (is (= status
           (history/receipt-payload
            {:ok? true
             :receipt {:status :accepted
                       :result {:status :ok :payload status}}})))
    (is (nil? (history/receipt-payload
               {:ok? true :receipt {:status :accepted}})))
    (is (= {:code :HISTORY_SEARCH_FAILED}
           (history/receipt-error
            {:ok? true
             :receipt {:status :accepted
                       :result {:status :error
                                :error {:code :HISTORY_SEARCH_FAILED}}}})))
    (is (= {:code :NOT_CONTROLLER}
           (history/receipt-error
            {:ok? false :error {:code :NOT_CONTROLLER}})))))
