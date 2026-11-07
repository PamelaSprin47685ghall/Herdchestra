(ns hc.client.conversation.minimap-test
  "Tests for the pure topic overview and stable anchors."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.conversation.minimap :as minimap]))

(def messages
  [{:id "u1" :kind :user :text "first question"}
   {:id "a1" :kind :assistant :text "first answer"}
   {:id "t1" :kind :tool :text "tool call"}
   {:id "f1" :kind :attachment :text "file.pdf"}
   {:id "c1" :kind :context-boundary :label "压缩边界"}
   {:id "u2" :kind :user :text "second question"}])

(deftest empty-overview-is-empty
  (let [o (minimap/overview [])]
    (is (= [] (:items o)))
    (is (= [] (:density o)))
    (is (= {} (:counts o)))
    (is (false? (:truncated? o)))
    (is (= 0 (:total-count o)))
    (is (= [] (:duplicate-ids o)))
    (is (nil? (minimap/locate o "missing")))
    (is (nil? (minimap/nearest-anchor o "missing")))))

(deftest mixed-types-produce-items-and-density
  (let [o (minimap/overview messages "a1")]
    (is (= 6 (count (:items o))))
    (is (= ["u1" "a1" "t1" "f1" "c1" "u2"] (mapv :id (:items o))))
    (is (= [:user :assistant :tool :attachment :context-boundary :user]
           (mapv :kind (:items o))))
    (is (= "压缩边界" (:label (nth (:items o) 4))))
    (is (= {:user 2 :assistant 1 :tool 1 :attachment 1
            :context-boundary 1}
           (:counts o)))
    (is (= 6 (count (:density o))))
    (is (= 1 (minimap/locate o "a1")))
    (is (= "a1" (:id (minimap/nearest-anchor o "a1"))))))

(deftest locate-is-stable-when-new-content-is-appended
  (let [before (minimap/overview messages "a1")
        after (minimap/overview (conj messages
                                      {:id "a2" :kind :assistant :text "later"})
                                "a1")]
    (is (= (minimap/locate before "a1")
           (minimap/locate after "a1")))
    (is (= "a1" (:id (minimap/nearest-anchor after "a1"))))))

(deftest duplicate-ids-are-deduplicated-and-reported
  (let [o (minimap/overview (conj messages
                                  {:id "u1" :kind :user :text "duplicate"})
                            "u1")]
    (is (= ["u1"] (:duplicate-ids o)))
    (is (= 6 (:total-count o)))
    (is (= 7 (:input-count o)))
    (is (= 0 (minimap/locate o "u1")))))

(deftest truncated-overview-keeps-locatable-anchors
  (let [many (mapv (fn [i] {:id (str "m" i)
                            :kind (if (even? i) :user :assistant)
                            :text (str "message " i)})
                   (range 50))
        o (minimap/overview many "m37" {:limit 10})]
    (is (true? (:truncated? o)))
    (is (= 50 (:total-count o)))
    (is (<= (count (:items o)) 10))
    (is (= 37 (minimap/locate o "m37")))
    (is (= "m37" (:id (minimap/nearest-anchor o "m37"))))
    (let [last-o (minimap/overview many "m49" {:limit 10})]
      (is (= "m49" (:id (minimap/nearest-anchor last-o "m49")))))))
