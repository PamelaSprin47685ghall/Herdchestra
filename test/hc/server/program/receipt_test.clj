(ns hc.server.program.receipt-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.core.ufss :as ufss]
            [hc.server.program.receipt :as receipt]
            [hc.server.program.runtime :as runtime]
            [hc.server.program.sandbox :as sandbox]))

(deftest sandbox-error-details-are-raw-edn
  (testing "a Windows path in the malformed name is a raw string value"
    (let [source "(defprogram #\"C:\\tmp\\a\"# [] 1)"
          result (sandbox/compile-program source)]
      (is (false? (:ok? result)))
      (is (= "C:\\tmp\\a" (get-in result [:error :found])))
      (is (not (str/starts-with? (get-in result [:error :found]) "\"")))
      (let [wrapped {:status :INVALID_ARGUMENT
                     :error (:error result)
                     :source source}
            rendered (receipt/render wrapped)]
        (is (not (str/includes? rendered "\\\"")))
        (is (not (str/includes? rendered "\\n")))
        (is (= wrapped (ufss/read-str rendered))))))
  (testing "a malformed form is a raw list, not a pr-str string"
    (let [result (sandbox/compile-program "(defprogram x [] (case* 1))")]
      (is (= '(case* 1) (get-in result [:error :found])))
      (is (not (string? (get-in result [:error :found])))))))

(deftest render-falls-back-for-non-edn
  (let [rendered (receipt/render {:status :COMPLETED
                                  :value (fn [] nil)})]
    (is (string? rendered))
    (is (str/includes? rendered ":UNSUPPORTED_OPERATION"))
    (is (str/includes? rendered "EDN+UFSS"))))

(deftest render-tool-result-prefers-the-program-receipt
  (let [park {:status :PARKED
              :state :parked
              :name "p"
              :reason :explicit
              :payload {:path "C:\\tmp\\a"}
              :source "(defprogram p [] (park {}))"
              :numbered-source "1 | (defprogram p [] (park {}))"}
        payload {:status :succeeded
                 :value :parked
                 :receipts [{:status :succeeded :type :session}
                            park]}
        rendered (runtime/render-tool-result payload)]
    (is (not (str/includes? rendered "\\\"")))
    (is (str/includes? rendered ":parked"))
    (is (= park (ufss/read-str rendered))))
  (testing "non-EDN payload uses the printable fallback"
    (let [rendered (runtime/render-tool-result
                    {:status :succeeded
                     :call-id "call-1"
                     :attempt-id "attempt-1"
                     :value (fn [] nil)})]
      (is (str/includes? rendered ":UNSUPPORTED_OPERATION"))
      (is (str/includes? rendered "call-1"))
      (is (str/includes? rendered "attempt-1"))
      (is (str/includes? rendered ":original-status :succeeded")))))
(deftest evaluate-results-carry-transport-receipt
  (let [rt (runtime/runtime {})]
    (try
      (let [result (runtime/evaluate-program! rt
                                              {:call-id "transport-1"
                                               :role :clj-worker
                                               :intent (apply str (repeat 100 "x"))
                                               :source "(defprogram transport [] 1)"})]
        (is (= :COMPLETED (:status result)))
        (is (= :COMPLETED (get-in result [:receipt :status])))
        (is (= 1 (count (:receipts result))))
        (is (not (contains? (:receipt result) :receipt)))
        (is (= (get-in result [:receipt])
               (ufss/read-str (runtime/render result)))))
      (finally (runtime/stop! rt)))))
