(ns hc.server.diagnostics.log-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.log :as log]))

(def day-ms (* 24 60 60 1000))
(def openai-key "sk-1234567890abcdefghij")

(deftest detailed-log-is-off-by-default
  (let [policy (log/new-policy)
        result (log/record policy {:request-id "r1" :at 10} {:now 20})]
    (is (false? (:enabled? policy)))
    (is (false? (:recorded? result)))
    (is (= :disabled (:reason result)))
    (is (empty? (log/entries policy)))))

(deftest next-request-mode-captures-once
  (let [policy (log/enable (log/new-policy) {:now 1000})
        first (log/record policy {:request-id "r1" :at 10} {:now 2000})
        second (log/record (:policy first) {:request-id "r2" :at 20}
                           {:now 3000})]
    (is (true? (:recorded? first)))
    (is (= 1 (count (log/entries (:policy first)))))
    (is (false? (get-in first [:policy :enabled?])))
    (is (false? (:recorded? second)))
    (is (= :disabled (:reason second)))
    (is (= 1 (count (log/entries (:policy second)))))))

(deftest timed-mode-is-capped-at-seven-days
  (let [policy (log/enable (log/new-policy)
                           {:mode :timed :days 7 :now 0})]
    (is (true? (:enabled? policy)))
    (is (= (* 7 day-ms) (:expires-at policy)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"1 to 7 days"
         (log/enable (log/new-policy)
                     {:mode :timed :days 8 :now 0})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"1 to 7 days"
         (log/enable (log/new-policy)
                     {:mode :timed :days 0 :now 0})))))

(deftest timed-window-expires
  (let [policy (log/enable (log/new-policy)
                           {:mode :timed :days 1 :now 0})
        status (log/status policy (+ day-ms 1))]
    (is (false? (:enabled? status)))
    (is (false? (:capturing? status)))
    (is (nil? (:expires-at status)))))

(deftest temporary-session-is-never-persisted
  (let [policy (log/enable (log/new-policy) {:now 1000})
        result (log/record policy
                           {:request-id "r1" :at 10 :temporary? true}
                           {:now 2000})]
    (is (false? (:recorded? result)))
    (is (= :temporary-session (:reason result)))
    (is (empty? (log/entries (:policy result))))
    (is (true? (get-in result [:policy :enabled?])))))

(deftest paused-policy-does-not-accumulate
  (let [policy (-> (log/new-policy)
                   (log/enable {:mode :timed :days 1 :now 0})
                   log/pause)
        blocked (log/record policy {:request-id "r1" :at 10} {:now 20})
        resumed (log/resume policy 30)
        allowed (log/record resumed {:request-id "r2" :at 40} {:now 50})]
    (is (false? (:recorded? blocked)))
    (is (= :paused (:reason blocked)))
    (is (empty? (log/entries (:policy blocked))))
    (is (false? (:paused? resumed)))
    (is (true? (:recorded? allowed)))
    (is (= 1 (count (log/entries (:policy allowed)))))))

(deftest retention-drops-old-entries
  (let [policy (log/new-policy {:retention-days 1})
        recorded (log/record (log/enable policy {:mode :timed
                                                 :days 7
                                                 :now 0})
                             {:request-id "r1" :at 0}
                             {:now 0})
        later (log/expire (:policy recorded) (+ day-ms 1))]
    (is (= 1 (count (log/entries (:policy recorded)))))
    (is (empty? (log/entries later)))))

(deftest clear-removes-only-detailed-entries
  (let [policy (log/enable (log/new-policy)
                           {:mode :timed :days 1 :now 0})
        recorded (log/record policy {:request-id "r1" :at 0} {:now 10})
        cleared (log/clear (:policy recorded) 20)]
    (is (= 1 (count (log/entries (:policy recorded)))))
    (is (empty? (log/entries cleared)))
    (is (true? (:enabled? cleared)))
    (is (= 20 (:last-cleared-at cleared)))))

(deftest secrets-are-redacted-before-storage
  (let [policy (log/enable (log/new-policy) {:now 0})
        result (log/record policy
                           {:request-id "r1"
                            :at 10
                            :headers {"Authorization" (str "Bearer " openai-key)}
                            :api-key openai-key}
                           {:now 20})
        entry (first (log/entries (:policy result)))]
    (is (true? (:recorded? result)))
    (is (not (str/includes? (pr-str entry) openai-key)))
    (is (str/includes? (pr-str entry) "redacted"))
    (is (= 7 (:retention-days entry)))
    (is (= 20 (:recorded-at entry)))))

(deftest status-reports-capture-state
  (let [policy (-> (log/new-policy)
                   (log/enable {:mode :timed :days 2 :now 0})
                   log/pause)
        status (log/status policy 10)]
    (is (true? (:enabled? status)))
    (is (true? (:paused? status)))
    (is (false? (:capturing? status)))
    (is (= :timed (:mode status)))
    (is (= 7 (:retention-days status)))
    (is (= 0 (:entry-count status)))))

(deftest retained-entries-are-bounded
  (let [entries (mapv (fn [n] {:message n :recorded-at 0})
                      (range (+ log/max-entries 10)))
        policy (assoc (log/new-policy) :entries entries)
        expired (log/expire policy 0)
        kept (log/entries expired)]
    (is (= log/max-entries (count kept)))
    (is (= 10 (:message (first kept))))
    (is (= (+ log/max-entries 9) (:message (last kept))))))

(deftest record-keeps-the-newest-entry-when-full
  (let [entries (mapv (fn [n] {:message n :recorded-at 0})
                      (range log/max-entries))
        policy (-> (log/new-policy)
                   (log/enable {:mode :timed :days 1 :now 0})
                   (assoc :entries entries))
        result (log/record policy {:message :new} {:now 0})
        kept (log/entries (:policy result))]
    (is (true? (:recorded? result)))
    (is (= log/max-entries (count kept)))
    (is (= 1 (:message (first kept))))
    (is (= :new (:message (last kept))))))
