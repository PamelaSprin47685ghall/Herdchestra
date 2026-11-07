(ns hc.compose.owner.conversation-compression-test
  "Compression-aware Owner conversation projection.

  E2E 05 residual: a Plan Nudge request froze `(conversation-tail state)`
  before the history outbound projection ran, so after `使用摘要` the Nudge
  still carried the replaced messages next to zero summaries.  The Owner
  `:session` already carries `:context :compressions` (session commands
  commit it), and mirrored messages keep their stable `:message-id`, so the
  projection can substitute applied ranges itself."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.state :as state]))

(defn- conversation-state
  "One Owner state whose session context holds COMPRESSION and whose
  conversation carries the matching message ids."
  [compression messages]
  (let [base (state/initial-state
              {:session-id "compression-projection"
               :initial-session {:session-id "compression-projection"
                                 :context {:compressions {"c1" compression}
                                           :compression-order ["c1"]}}})]
    (assoc base :conversation messages)))

(defn- msg
  [message-id role text]
  {:role role :message-id message-id :text text})

(def ^:private applied-compression
  {:compression-id "c1"
   :state :applied
   :message-ids ["m1" "m2"]
   :summary "事实：旧范围已压缩。假设（未确认）：未确认项。"
   :applied-at 10})

(defn- outbound
  [state]
  (let [result (state/project-conversation state (:conversation state))]
    (when-not (:ok? result)
      (throw (ex-info "scope projection refused the conversation"
                      {:error (:error result)})))
    (:messages result)))

(deftest applied-compression-sends-one-summary-not-the-replaced-range
  (let [state (conversation-state
               applied-compression
               [(msg "m1" :user "旧消息一")
                (msg "m2" :assistant "旧回答")
                (msg "m3" :user "继续一")
                (msg "m4" :assistant "回答一")])
        messages (outbound state)]
    (testing "the summary replaces the range exactly once"
      (is (= ["事实：旧范围已压缩。假设（未确认）：未确认项。"
              "继续一" "回答一"]
             (mapv :text messages))))
    (is (= [:system :user :assistant] (mapv :role messages)))
    (is (= "c1" (:compression-id (first messages))))
    (testing "no replaced message id survives in the projection"
      (is (not-any? #{"m1" "m2"} (map :message-id messages))))))

(deftest reverted-compression-sends-the-raw-range-again
  (let [state (conversation-state
               (assoc applied-compression :state :reverted)
               [(msg "m1" :user "旧消息一")
                (msg "m2" :assistant "旧回答")
                (msg "m3" :user "继续一")])
        messages (outbound state)]
    (is (= ["旧消息一" "旧回答" "继续一"] (mapv :text messages)))
    (is (not-any? #(= :system (:role %)) messages))))

(deftest partial-compression-range-keeps-raw-messages
  (testing "a range whose members are not all projected is not substituted"
    (let [state (conversation-state
                 applied-compression
                 [(msg "m1" :user "旧消息一")
                  (msg "m3" :user "继续一")])
          messages (outbound state)]
      (is (= ["旧消息一" "继续一"] (mapv :text messages)))
      (is (not-any? #(= :system (:role %)) messages)))))

(deftest unordered-applied-compression-still-substitutes
  (testing "compressions missing from :compression-order fall back to :applied-at"
    (let [base (state/initial-state
                {:session-id "compression-unordered"
                 :initial-session
                 {:session-id "compression-unordered"
                  :context {:compressions {"c1" applied-compression}
                            :compression-order []}}})
          state (assoc base :conversation
                       [(msg "m1" :user "旧消息一")
                        (msg "m2" :assistant "旧回答")
                        (msg "m3" :user "继续一")])
          messages (outbound state)]
      (is (= ["事实：旧范围已压缩。假设（未确认）：未确认项。" "继续一"]
             (mapv :text messages))))))

(deftest initial-conversation-seeds-visible-history
  (testing "a fresh Owner keeps the session's visible messages"
    (let [state (state/initial-state
                 {:session-id "seeded"
                  :initial-conversation [{:role :system
                                          :message-id "sum-1"
                                          :text "【压缩摘要】事实。"
                                          :at 1}
                                         {:role :user
                                          :message-id "u-1"
                                          :text "继续"
                                          :at 2}]})]
      (is (= ["sum-1" "u-1"] (mapv :message-id (:conversation state))))
      (testing "the seeded summary survives scope projection"
        (let [messages (outbound state)]
          (is (= [:system :user] (mapv :role messages)))
          (is (= "【压缩摘要】事实。" (get-in messages [0 :text]))))))))

(deftest initial-conversation-empty-by-default
  (is (= [] (:conversation (state/initial-state {:session-id "plain"})))))
