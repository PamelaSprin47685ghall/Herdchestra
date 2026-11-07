(ns hc.compose.reports.delivery-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.reports.delivery :as delivery]
            [hc.compose.reports.report :as report]
            [hc.compose.reports.support :as support]))

(defn- sample-report
  [report-id text]
  (support/report-for report-id (str "call-" report-id) text))

(defn- ack
  [report-id status]
  (report/make-ack report-id (str "call-" report-id) status))

(deftest registers-and-delivers
  (let [r (sample-report "r1" "full text")
        result (delivery/register delivery/empty-state r)
        state (:state result)]
    (is (true? (:ok? result)))
    (is (= :registered (:outcome result)))
    (is (= [r]
           (map #(dissoc % :phase :ack) (delivery/pending-reports state))))
    (is (= {:action :deliver
            :report (assoc r :phase :pending :ack nil)}
           (delivery/next-action state)))))

(deftest registration-is-idempotent-and-guards-identity
  (testing "the same report registers once"
    (let [r (sample-report "r1" "one")
          state (:state (delivery/register delivery/empty-state r))
          again (delivery/register state r)]
      (is (= :existing (:outcome again)))
      (is (= state (:state again)))))
  (testing "a different payload cannot reuse a report-id"
    (let [r (sample-report "r1" "one")
          state (:state (delivery/register delivery/empty-state r))
          conflict (delivery/register state (sample-report "r1" "two"))]
      (is (false? (:ok? conflict)))
      (is (= :REPORT_ID_CONFLICT (get-in conflict [:error :code])))
      (is (= state (:state conflict)))))
  (testing "a parent call owns at most one report"
    (let [r (sample-report "r1" "one")
          state (:state (delivery/register delivery/empty-state r))
          same-call (support/report-for "r2" "call-r1" "two")
          conflict (delivery/register state same-call)]
      (is (false? (:ok? conflict)))
      (is (= :PARENT_CALL_CONFLICT (get-in conflict [:error :code])))
      (is (= state (:state conflict))))))

(deftest acknowledgement-clears-pending-before-reclaim
  (let [r (sample-report "r1" "full")
        state (:state (delivery/register delivery/empty-state r))
        acked (delivery/apply-ack state (ack "r1" :accepted))
        state' (:state acked)]
    (is (true? (:ok? acked)))
    (is (= :acknowledged (:outcome acked)))
    (is (empty? (delivery/pending-reports state')))
    (is (= 1 (count (delivery/acknowledged-reports state'))))
    (is (= {:action :reclaim
            :report-id "r1"
            :parent-call-id "call-r1"}
           (delivery/next-action state')))
    (let [reclaimed (delivery/mark-reclaimed state' "r1")]
      (is (true? (:ok? reclaimed)))
      (is (delivery/closed? (:state reclaimed)))
      (is (= {:action :none} (delivery/next-action (:state reclaimed)))))))

(deftest duplicate-and-mismatched-acknowledgements
  (testing "an identical acknowledgement is a no-op"
    (let [r (sample-report "r1" "full")
          state (:state (delivery/register delivery/empty-state r))
          acked (:state (delivery/apply-ack state (ack "r1" :accepted)))
          duplicate (delivery/apply-ack acked (ack "r1" :accepted))]
      (is (= :duplicate (:outcome duplicate)))
      (is (= acked (:state duplicate)))))
  (testing "routing metadata does not change the idempotency key"
    (let [r (sample-report "r1" "full")
          state (:state (delivery/register delivery/empty-state r))
          first-ack (assoc (ack "r1" :accepted)
                           :child-session-id "child-1")
          acked (:state (delivery/apply-ack state first-ack))
          without-child (delivery/apply-ack acked (ack "r1" :accepted))
          other-child (delivery/apply-ack
                       acked
                       (assoc (ack "r1" :accepted)
                              :child-session-id "child-2"))]
      (is (= :duplicate (:outcome without-child)))
      (is (= first-ack (:ack without-child)))
      (is (= :duplicate (:outcome other-child)))
      (is (= "child-2" (:child-session-id (:ack other-child))))
      (is (= "child-2"
             (get-in other-child
                     [:state :reports "r1" :ack :child-session-id])))))
  (testing "a wrong parent call is refused"
    (let [r (sample-report "r1" "full")
          state (:state (delivery/register delivery/empty-state r))
          mismatch (delivery/apply-ack
                    state
                    {:report-id "r1"
                     :parent-call-id "call-other"
                     :status :accepted})]
      (is (false? (:ok? mismatch)))
      (is (= :ACK_MISMATCH (get-in mismatch [:error :code])))
      (is (= state (:state mismatch)))))
  (testing "a changed acknowledgement is refused"
    (let [r (sample-report "r1" "full")
          state (:state (delivery/register delivery/empty-state r))
          acked (:state (delivery/apply-ack state (ack "r1" :accepted)))
          changed (delivery/apply-ack acked (ack "r1" :parent-terminated))]
      (is (false? (:ok? changed)))
      (is (= :ACK_MISMATCH (get-in changed [:error :code])))
      (is (= acked (:state changed))))))

(deftest reclaim-requires-acknowledgement
  (let [r (sample-report "r1" "full")
        state (:state (delivery/register delivery/empty-state r))
        result (delivery/mark-reclaimed state "r1")]
    (is (false? (:ok? result)))
    (is (= :REPORT_NOT_ACKNOWLEDGED (get-in result [:error :code])))
    (is (= state (:state result))))
  (is (true? (:ok? (delivery/mark-reclaimed delivery/empty-state "absent")))))

(deftest a-reclaimed-call-cannot-reopen
  (let [r (sample-report "r1" "full")
        state (:state (delivery/register delivery/empty-state r))
        acked (:state (delivery/apply-ack state (ack "r1" :accepted)))
        reclaimed (:state (delivery/mark-reclaimed acked "r1"))
        same (delivery/register reclaimed r)
        different (delivery/register
                   reclaimed
                   (support/report-for "r2" "call-r1" "late"))]
    (is (delivery/closed? reclaimed))
    (is (false? (:ok? same)))
    (is (= :REPORT_ID_CLOSED (get-in same [:error :code])))
    (is (true? (get-in same [:error :terminal?])))
    (is (false? (:ok? different)))
    (is (= :PARENT_CALL_CLOSED (get-in different [:error :code])))
    (is (true? (get-in different [:error :terminal?])))
    (is (= reclaimed (:state same) (:state different)))))

(deftest reclaims-before-the-next-delivery
  (let [r1 (sample-report "r1" "one")
        r2 (sample-report "r2" "two")
        state (-> delivery/empty-state
                  (delivery/register r1)
                  :state
                  (delivery/register r2)
                  :state)
        acked (:state (delivery/apply-ack state (ack "r1" :accepted)))]
    (is (= :reclaim (:action (delivery/next-action acked))))
    (let [reclaimed (:state (delivery/mark-reclaimed acked "r1"))
          action (delivery/next-action reclaimed)]
      (is (= :deliver (:action action)))
      (is (= "r2" (get-in action [:report :report-id]))))))

(deftest edn-round-trip-redelivers
  (let [r (sample-report "r1" "line\n\"quoted\" # hash")
        state (:state (delivery/register delivery/empty-state r))
        restored (edn/read-string (pr-str state))]
    (is (= state restored))
    (is (= :deliver (:action (delivery/next-action restored))))
    (is (= "line\n\"quoted\" # hash"
           (get-in (delivery/next-action restored) [:report :payload :text])))))

(deftest a-late-acknowledgement-is-a-safe-noop
  (let [r (sample-report "r1" "full")
        state (:state (delivery/register delivery/empty-state r))
        acked (:state (delivery/apply-ack state (ack "r1" :accepted)))
        reclaimed (:state (delivery/mark-reclaimed acked "r1"))
        late (delivery/apply-ack reclaimed (ack "r1" :accepted))]
    (is (true? (:ok? late)))
    (is (= :unknown-report (:outcome late)))
    (is (= reclaimed (:state late)))))

(def gen-op
  (gen/elements [:deliver-ack :duplicate-ack :reclaim :noop]))

(defspec report-lifecycle-stays-consistent 80
  (prop/for-all [texts (gen/vector support/gen-text 1 4)
                 ops (gen/vector gen-op 0 30)]
    (let [reports (mapv (fn [i text] (sample-report (str "r" i) text))
                        (range (count texts))
                        texts)
          initial (reduce (fn [state r]
                            (:state (delivery/register state r)))
                          delivery/empty-state
                          reports)
          final (loop [state initial
                       remaining (seq ops)]
                  (if-let [op (first remaining)]
                    (let [action (delivery/next-action state)
                          state'
                          (case op
                            :deliver-ack
                            (if (= :deliver (:action action))
                              (:state (delivery/apply-ack
                                       state
                                       (ack (get-in action [:report :report-id])
                                            :accepted)))
                              state)

                            :duplicate-ack
                            (if-let [entry (first (delivery/acknowledged-reports state))]
                              (:state (delivery/apply-ack state (:ack entry)))
                              state)

                            :reclaim
                            (if (= :reclaim (:action action))
                              (:state (delivery/mark-reclaimed
                                       state
                                       (:report-id action)))
                              state)

                            :noop
                            state)]
                      (recur state' (next remaining)))
                    state))
          originals (into {} (map (juxt :report-id identity)) reports)
          live (concat (delivery/pending-reports final)
                       (delivery/acknowledged-reports final))]
      (and (every? #(and (= :pending (:phase %))
                         (nil? (:ack %)))
                   (delivery/pending-reports final))
           (every? #(and (= :acknowledged (:phase %))
                         (true? (:ok? (report/validate-ack (:ack %)))))
                   (delivery/acknowledged-reports final))
           (every? #(= (get-in originals [(:report-id %) :payload])
                       (:payload %))
                   live)
           (= final (edn/read-string (pr-str final)))))))
