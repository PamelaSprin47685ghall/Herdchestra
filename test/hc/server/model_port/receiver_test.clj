(ns hc.server.model-port.receiver-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.model-port.contract :as contract]
            [hc.server.model-port.receiver :as receiver]))

(defn text-chunk [text]
  {:event :chunk :attempt-id :a1 :call-id :c1 :output-index 0 :text text})

(deftest bounded-receiver-refuses-overflow
  (let [r (receiver/bounded-receiver 2)]
    (is (= {:ok true :size 1} (contract/deliver! r (text-chunk "a"))))
    (is (= {:ok true :size 2} (contract/deliver! r (text-chunk "b"))))
    (is (= {:ok false :reason :overflow :dropped 1 :capacity 2}
           (contract/deliver! r (text-chunk "c"))))
    (is (= {:capacity 2 :accepted 2 :dropped 1 :closed? false}
           (receiver/stats r)))
    (is (= [(text-chunk "a") (text-chunk "b")] (:events (receiver/drain! r))))
    (is (= [] (:events (receiver/drain! r))))))

(deftest closed-receiver-refuses-and-returns-pending
  (let [r (receiver/bounded-receiver 2)]
    (contract/deliver! r (text-chunk "a"))
    (is (= [(text-chunk "a")] (:events (receiver/close! r))))
    (is (= [] (:events (receiver/drain! r))))
    (is (= {:ok false :reason :closed}
           (contract/deliver! r (text-chunk "b"))))))

(deftest merge-chunks-coalesces-adjacent-text-only
  (let [events [(text-chunk "a")
                (text-chunk "b")
                {:event :tool-call :attempt-id :a1 :call-id "call-1"}
                (text-chunk "c")
                (assoc (text-chunk "d") :output-index 1)
                (assoc (text-chunk "e") :output-index 1)]]
    (is (= [{:event :chunk :attempt-id :a1 :call-id :c1
             :output-index 0 :text "ab"}
            {:event :tool-call :attempt-id :a1 :call-id "call-1"}
            {:event :chunk :attempt-id :a1 :call-id :c1
             :output-index 0 :text "c"}
            {:event :chunk :attempt-id :a1 :call-id :c1
             :output-index 1 :text "de"}]
           (receiver/merge-chunks events)))))

(defspec accepted-plus-dropped-equals-offered 100
  (prop/for-all [capacity (gen/choose 1 5)
                 offered (gen/choose 0 12)]
    (let [r (receiver/bounded-receiver capacity)
          responses (mapv (fn [n] (contract/deliver! r (text-chunk (str n))))
                          (range offered))
          accepted (count (filter :ok responses))
          drained (receiver/drain! r)]
      (and (= offered (+ (:accepted (receiver/stats r))
                         (:dropped (receiver/stats r))))
           (= accepted (count (:events drained)))
           (= (max 0 (- offered capacity))
              (:dropped (receiver/stats r)))))))
