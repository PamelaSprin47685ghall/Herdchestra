(ns hc.compose.reports.report-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.reports.report :as report]
            [hc.compose.reports.support :as support]))

(deftest validates-a-full-llm-report
  (let [validated (report/validate-report
                   {:report-id "r1"
                    :parent-call-id "c1"
                    :payload {:kind :llm-report
                              :text "line 1\nline 2 \"quoted\" # hash"}
                    :terminal {:status :completed}})]
    (is (true? (:ok? validated)))
    (is (= "line 1\nline 2 \"quoted\" # hash"
           (get-in validated [:report :payload :text])))
    (is (= {:status :completed} (get-in validated [:report :terminal])))
    (is (nil? (get-in validated [:report :resource-ref]))
        "the protocol carries no live resource handle")))

(deftest refuses-truncated-or-referenced-llm-report
  (testing "a truncation claim is refused"
    (let [result (report/validate-report
                  {:report-id "r1"
                   :parent-call-id "c1"
                   :payload {:kind :llm-report
                             :text "short"
                             :truncated? true}})]
      (is (false? (:ok? result)))
      (is (= :REPORT_TRUNCATED (get-in result [:error :code])))))
  (testing "an artifact reference is refused"
    (let [result (report/validate-report
                  {:report-id "r1"
                   :parent-call-id "c1"
                   :payload {:kind :llm-report
                             :text "short"
                             :artifact-ref "artifact://x"}})]
      (is (false? (:ok? result)))
      (is (= :REPORT_ARTIFACT (get-in result [:error :code])))))
  (testing "any artifact or summary key is refused"
    (doseq [payload [{:kind :llm-report :text "short" :artifact "x"}
                     {:kind :llm-report :text "short" :summary "x"}]]
      (let [result (report/validate-payload payload)]
        (is (false? (:ok? result)))
        (is (= :REPORT_ARTIFACT (get-in result [:error :code]))))))
  (testing "a non-boolean truncation marker is refused"
    (let [result (report/validate-payload
                  {:kind :llm-report :text "short" :truncated? "yes"})]
      (is (false? (:ok? result)))
      (is (= :REPORT_TRUNCATED (get-in result [:error :code]))))))

(deftest validates-raw-tool-output-truncation
  (let [valid (report/validate-payload
               {:kind :raw-tool-output
                :text "first 10 bytes"
                :truncated? true
                :original-bytes 100
                :delivered-bytes 14})]
    (is (true? (:ok? valid))))
  (let [invalid (report/validate-payload
                 {:kind :raw-tool-output
                  :text "first 10 bytes"
                  :truncated? true
                  :original-bytes 10
                  :delivered-bytes 100})]
    (is (false? (:ok? invalid)))
    (is (= :REPORT_TRUNCATION_INVALID (get-in invalid [:error :code]))))
  (let [mismatch (report/validate-payload
                  {:kind :raw-tool-output
                   :text "first 10 bytes"
                   :truncated? true
                   :original-bytes 100
                   :delivered-bytes 10})]
    (is (false? (:ok? mismatch)))
    (is (= :REPORT_TRUNCATION_INVALID (get-in mismatch [:error :code]))))
  (let [missing (report/validate-payload
                 {:kind :raw-tool-output
                  :text "short"
                  :truncated? true})]
    (is (false? (:ok? missing)))
    (is (= :REPORT_TRUNCATION_INVALID (get-in missing [:error :code])))))

(deftest refuses-malformed-records-and-acks
  (doseq [bad [{:report-id "" :parent-call-id "c"
                :payload {:kind :llm-report :text "x"}}
               {:report-id "r" :parent-call-id " "
                :payload {:kind :llm-report :text "x"}}
               {:report-id "r" :parent-call-id "c"
                :payload {:kind :other :text "x"}}
               {:report-id "r" :parent-call-id "c"
                :payload {:kind :llm-report}}
               {:report-id "r" :parent-call-id "c"
                :payload {:kind :llm-report :text "x"}
                :terminal {:status :completed :cancel (fn [] nil)}}]]
    (is (false? (:ok? (report/validate-report bad))) (pr-str bad)))
  (is (false? (:ok? (report/validate-report
                     {:report-id "r"
                      :parent-call-id "c"
                      :payload {:kind :llm-report :text "x"}
                      :terminal {:message "no status"}}))))
  (is (true? (:ok? (report/validate-report
                    {:report-id "r"
                     :parent-call-id "c"
                     :payload {:kind :llm-report :text "x"}
                     :resource-ref "legacy"})))
      "a legacy resource-ref is ignored, not stored")
  (is (false? (:ok? (report/validate-ack
                     {:report-id "r"
                      :parent-call-id "c"
                      :status :maybe}))))
  (is (false? (:ok? (report/validate-ack
                     {:report-id ""
                      :parent-call-id "c"
                      :status :accepted})))))

(deftest make-report-throws-only-at-a-wiring-boundary
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"payload :text must be a string"
                        (report/make-report
                         {:report-id "r"
                          :parent-call-id "c"
                          :payload {:kind :llm-report}})))
  (is (= "ok"
         (get-in (report/make-report
                  {:report-id "r"
                   :parent-call-id "c"
                   :payload {:kind :llm-report :text "ok"}})
                 [:payload :text]))))

(defspec llm-report-text-survives-validation 80
  (prop/for-all [text support/gen-text
                 report-id support/gen-id
                 parent-call-id support/gen-id]
    (let [validated (report/validate-report
                     {:report-id report-id
                      :parent-call-id parent-call-id
                      :payload {:kind :llm-report :text text}})]
      (and (true? (:ok? validated))
           (= text (get-in validated [:report :payload :text]))
           (= validated (edn/read-string (pr-str validated)))))))

(defspec raw-truncation-never-exceeds-the-original 80
  (prop/for-all [text support/gen-text
                 original (gen/choose 0 100000)
                 delivered (gen/choose 0 100000)]
    (let [validated (report/validate-payload
                     {:kind :raw-tool-output
                      :text text
                      :truncated? true
                      :original-bytes original
                      :delivered-bytes delivered})]
      (if (and (= delivered (count (.getBytes ^String text "UTF-8")))
               (<= delivered original))
        (and (true? (:ok? validated))
             (= text (get-in validated [:payload :text])))
        (false? (:ok? validated))))))
