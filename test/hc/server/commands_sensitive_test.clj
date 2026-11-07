(ns hc.server.commands-sensitive-test
  "Sensitive command payloads and receipts must never reach durable state in
  plaintext.  Dedup still compares a canonical digest."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.commands :as commands]
            [hc.server.commands.dispatch :as dispatch]
            [hc.server.commands.durable :as durable]
            [hc.server.commands.receipts :as receipts]))

(defn- secret?
  [value secret]
  (str/includes? (pr-str value) secret))

(deftest sensitive-payload-is-stored-as-a-digest
  (let [issued (commands/issue-epoch (commands/new-state))
        state (:state issued)
        request {:command-id "cmd-secret"
                 :command-epoch (:epoch issued)
                 :redact-payload? true
                 :payload {:kind :backup/create
                           :password "hunter2"
                           :nested {:api-key "sk-live-123"}}}
        first-result (commands/accept state request)
        entry (get-in first-result [:state :receipts "cmd-secret"])
        replay (commands/accept (:state first-result) request)
        mismatch (commands/accept
                  (:state first-result)
                  (assoc-in request [:payload :password]
                            "another-secret-value"))]
    (is (true? (:ok? first-result)))
    (is (nil? (:payload entry)))
    (is (true? (:payload-redacted? entry)))
    (is (string? (:payload-digest entry)))
    (is (not (secret? (:state first-result) "hunter2")))
    (is (not (secret? (:state first-result) "sk-live-123")))
    (is (true? (:replayed? replay)))
    (is (= :COMMAND_ID_PAYLOAD_CONFLICT (get-in mismatch [:error :code])))
    (is (not (secret? mismatch "hunter2")))
    (is (not (secret? mismatch "another-secret-value")))
    (is (string? (get-in mismatch [:error :details :accepted-payload-digest])))
    (is (string? (get-in mismatch [:error :details :received-payload-digest])))
    (is (empty? (commands/check-invariants (:state first-result))))))

(deftest sensitive-receipt-is-redacted-before-completion
  (let [gate (durable/gate (durable/memory-store))
        window {:command-epoch 1 :retired-through 0}
        calls (atom 0)
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "app")
                  :sensitive-request? (constantly true)
                  :dispatch! (fn [_ reply]
                               (swap! calls inc)
                               (let [result {:ok? true
                                             :receipt {:status :accepted
                                                       :password "hunter2"
                                                       :result {:api-key "sk-live-123"}}}]
                                 (reply result)
                                 result))})
        request {:command-id "cmd-secret"
                 :command-epoch 1
                 :payload {:kind :backup/create :password "hunter2"}}
        first-result ((:submit! wrapper) request identity)
        replay ((:submit! wrapper) request identity)]
    (is (true? (:ok? first-result)))
    (is (= 1 @calls))
    (is (not (secret? (durable/state gate) "hunter2")))
    (is (not (secret? (durable/state gate) "sk-live-123")))
    (is (true? (:replayed? replay)))
    (is (= 1 @calls))
    (is (nil? (get-in replay [:receipt :password])))
    (is (nil? (get-in replay [:receipt :result :api-key])))
    (testing "nested secret detection"
      (is (true? (receipts/contains-secret? {:a {:token "x"}})))
      (is (false? (receipts/contains-secret? {:a {:text "x"}}))))
    (testing "redaction preserves supervisor status keys"
      (is (= {:status :accepted
              :maintenance-id "m-1"
              :id "m-1"
              :phase :done
              :needs-attention? false
              :result {:status :complete}
              :recovery-point {:id "rp-1"}
              :error nil
              :message "done"
              :observed-by-client? true}
             (receipts/redact-secrets
              {:status :accepted
               :maintenance-id "m-1"
               :id "m-1"
               :phase :done
               :needs-attention? false
               :result {:status :complete}
               :recovery-point {:id "rp-1"}
               :error nil
               :message "done"
               :observed-by-client? true
               :password "hunter2"}))))
    (durable/close! gate)))
