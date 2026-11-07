(ns hc.compose.reports.acceptance-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.reports.acceptance :as acceptance]
            [hc.compose.reports.support :as support]))

(defn- sample-report
  [report-id text]
  (support/report-for report-id (str "call-" report-id) text))

(deftest accepts-once-and-stores-the-full-report
  (let [r (sample-report "r1" "line 1\nline 2 \"quoted\" # hash")
        result (acceptance/accept-report acceptance/empty-state r)
        state (:state result)
        entry (acceptance/accepted-report state "call-r1")]
    (is (true? (:ok? result)))
    (is (= :accepted (:outcome result)))
    (is (true? (:append? result)))
    (is (= {:report-id "r1"
            :parent-call-id "call-r1"
            :status :accepted}
           (:ack result)))
    (is (= "line 1\nline 2 \"quoted\" # hash"
           (get-in entry [:payload :text])))
    (is (= :pending (:projection entry)))
    (is (= [entry] (acceptance/pending-projections state)))))

(deftest duplicate-delivery-never-appends-twice
  (let [r (sample-report "r1" "full")
        first (acceptance/accept-report acceptance/empty-state r)
        second (acceptance/accept-report (:state first) r)]
    (is (= :duplicate (:outcome second)))
    (is (false? (:append? second)))
    (is (= (:ack first) (:ack second)))
    (is (= (:state first) (:state second))))
  (testing "a later routing id is merged without a second append"
    (let [r (sample-report "r1" "full")
          first (acceptance/accept-report acceptance/empty-state r)
          second (acceptance/accept-report
                  (:state first)
                  (assoc r :child-session-id "child-1"))]
      (is (= :duplicate (:outcome second)))
      (is (false? (:append? second)))
      (is (= "child-1" (get-in second [:ack :child-session-id])))
      (is (= "child-1"
             (get-in second
                     [:state :accepted "call-r1" :child-session-id]))))))

(deftest a-terminated-parent-still-acknowledges
  (let [r (sample-report "r1" "full")
        state (acceptance/terminate acceptance/empty-state)
        result (acceptance/accept-report state r)
        entry (acceptance/accepted-report (:state result) "call-r1")]
    (is (= :parent-terminated (:outcome result)))
    (is (= :parent-terminated (get-in result [:ack :status])))
    (is (false? (:append? result)))
    (is (= :skipped (:projection entry)))
    (is (empty? (acceptance/pending-projections (:state result))))
    (let [again (acceptance/accept-report (:state result) r)]
      (is (= :duplicate (:outcome again)))
      (is (= (:ack result) (:ack again)))
      (is (false? (:append? again))))))

(deftest a-closed-call-does-not-receive-a-late-result
  (let [r (sample-report "r1" "late")
        result (acceptance/accept-report
                acceptance/empty-state
                r
                {:call-active? false})]
    (is (= :parent-terminated (:outcome result)))
    (is (false? (:append? result)))
    (is (= :skipped (get-in result [:entry :projection])))
    (is (empty? (acceptance/pending-projections (:state result))))))

(deftest conflicting-reports-are-refused-without-loss
  (testing "a parent call accepts only one report"
    (let [r1 (sample-report "r1" "one")
          r2 (sample-report "r2" "two")
          first (acceptance/accept-report acceptance/empty-state r1)
          conflict (acceptance/accept-report
                    (:state first)
                    (assoc r2 :parent-call-id "call-r1"))]
      (is (false? (:ok? conflict)))
      (is (= :REPORT_CONFLICT (get-in conflict [:error :code])))
      (is (= (:state first) (:state conflict)))))
  (testing "a report-id cannot be reused under another parent call"
    (let [r1 (sample-report "r1" "one")
          duplicate-id (support/report-for "r1" "call-r2" "two")
          first (acceptance/accept-report acceptance/empty-state r1)
          conflict (acceptance/accept-report (:state first) duplicate-id)]
      (is (false? (:ok? conflict)))
      (is (= :REPORT_ID_CONFLICT (get-in conflict [:error :code])))
      (is (= (:state first) (:state conflict)))))
  (testing "a redelivery cannot change the payload under the same ids"
    (let [r1 (sample-report "r1" "one")
          first (acceptance/accept-report acceptance/empty-state r1)
          changed (acceptance/accept-report
                   (:state first)
                   (support/report-for "r1" "call-r1" "changed"))]
      (is (false? (:ok? changed)))
      (is (= :REPORT_CONFLICT (get-in changed [:error :code])))
      (is (= :report-content-mismatch (get-in changed [:error :details :reason])))
      (is (= (:state first) (:state changed))))))

(deftest projection-recovery-is-idempotent
  (let [r (sample-report "r1" "full")
        state (:state (acceptance/accept-report acceptance/empty-state r))
        projected (acceptance/mark-projected state "call-r1")]
    (is (= :projected (:outcome projected)))
    (is (empty? (acceptance/pending-projections (:state projected))))
    (is (= :already-projected
           (:outcome (acceptance/mark-projected
                      (:state projected)
                      "call-r1"))))
    (is (false? (:ok? (acceptance/mark-projected state "call-missing"))))))

(deftest termination-skips-pending-projections
  (let [r (sample-report "r1" "full")
        state (:state (acceptance/accept-report acceptance/empty-state r))
        terminated (acceptance/terminate state)
        entry (acceptance/accepted-report terminated "call-r1")]
    (is (= :skipped (:projection entry)))
    (is (empty? (acceptance/pending-projections terminated)))
    (is (= :not-applicable
           (:outcome (acceptance/mark-projected terminated "call-r1"))))))

(deftest edn-round-trip-preserves-the-full-report
  (let [r (sample-report "r1" "line\n\"quoted\" # hash")
        state (:state (acceptance/accept-report acceptance/empty-state r))
        restored (edn/read-string (pr-str state))]
    (is (= state restored))
    (is (= "line\n\"quoted\" # hash"
           (get-in (acceptance/accepted-report restored "call-r1")
                   [:payload :text])))))

(defspec repeated-delivery-appends-once 80
  (prop/for-all [text support/gen-text
                 report-id support/gen-id
                 parent-call-id support/gen-id
                 deliveries (gen/choose 1 20)]
    (let [r (support/report-for report-id parent-call-id text)
          results (loop [state acceptance/empty-state
                         remaining deliveries
                         acc []]
                    (if (zero? remaining)
                      acc
                      (let [result (acceptance/accept-report state r)]
                        (recur (:state result)
                               (dec remaining)
                               (conj acc result)))))
          final-state (:state (peek results))
          entry (acceptance/accepted-report final-state parent-call-id)]
      (and (= 1 (count (filter :append? results)))
           (every? #(= (:ack (first results)) (:ack %)) results)
           (= 1 (count (acceptance/accepted-entries final-state)))
           (= text (get-in entry [:payload :text]))
           (= :accepted (get-in entry [:ack :status]))
           (= final-state (edn/read-string (pr-str final-state)))))))

(defspec terminated-parent-acknowledges-every-delivery 60
  (prop/for-all [text support/gen-text
                 report-id support/gen-id
                 parent-call-id support/gen-id]
    (let [r (support/report-for report-id parent-call-id text)
          state (acceptance/terminate acceptance/empty-state)
          first (acceptance/accept-report state r)
          again (acceptance/accept-report (:state first) r)]
      (and (= :parent-terminated (:outcome first))
           (false? (:append? first))
           (= :parent-terminated (get-in first [:ack :status]))
           (= (:ack first) (:ack again))
           (false? (:append? again))
           (= (:state first) (:state again))))))
