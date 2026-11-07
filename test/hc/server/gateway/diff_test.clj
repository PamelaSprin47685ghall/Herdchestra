(ns hc.server.gateway.diff-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.gateway.diff :as diff]
            [hc.server.gateway.patch :as patch]))

(defn- round-trip
  [old new]
  (let [ops (diff/diff old new)]
    (is (= {:ok? true :value new}
           (patch/apply-patch old ops))
        (pr-str ops))
    ops))

(deftest equal-trees-have-no-operations
  (is (= [] (diff/diff {:a 1} {:a 1})))
  (is (= [] (diff/diff {:a {:b [1 2 3]}} {:a {:b [1 2 3]}}))))

(deftest map-add-remove-and-replace
  (let [ops (round-trip {:a 1 :b 2} {:a 3 :c 4})]
    (is (= #{["remove" "/b"] ["replace" "/a"] ["add" "/c"]}
           (set (map (juxt :op :path) ops))))))

(deftest nested-maps-and-namespaced-keys
  (let [old {:session/by-id {"s1" {:status "running" :title "New session"}}
             :message/by-id {"m1" {:content "old"}}}
        new {:session/by-id {"s1" {:status "idle" :title "New session"}}
             :message/by-id {"m1" {:content "new" :folded? true}}}]
    (round-trip old new)
    (is (= #{["replace" "/session~1by-id/s1/status"]
             ["replace" "/message~1by-id/m1/content"]
             ["add" "/message~1by-id/m1/folded?"]}
           (set (map (juxt :op :path) (diff/diff old new)))))))

(deftest vectors-are-compared-by-index
  (testing "append and replace"
    (round-trip {:order ["a" "b"]}
                {:order ["a" "c" "d"]}))
  (testing "remove from the tail"
    (round-trip {:order ["a" "b" "c" "d"]}
                {:order ["a" "b"]}))
  (testing "nested vector values"
    (round-trip {:message/by-id {"m1" {:versions [{:content "a"}]}}}
                {:message/by-id {"m1" {:versions [{:content "b"}
                                                  {:content "c"}]}}})))

(deftest non-pointer-map-keys-replace-the-map
  (let [ops (diff/diff {1 {:a 1}} {1 {:a 2}})]
    (is (= [{:op "replace" :path "" :value {1 {:a 2}}}] ops))
    (is (= {:ok? true :value {1 {:a 2}}}
           (patch/apply-patch {1 {:a 1}} ops)))))

(deftest adding-a-keyword-key-to-an-empty-map-replaces-the-map
  (let [old {:settings/roles {}}
        new {:settings/roles {:main {:mode :explicit}}}
        ops (diff/diff old new)]
    (is (= [{:op "replace"
             :path "/settings~1roles"
             :value {:main {:mode :explicit}}}]
           ops))
    (is (= {:ok? true :value new}
           (patch/apply-patch old ops)))))

(deftest adding-a-string-key-to-an-empty-map-uses-add
  (let [old {:message/by-id {}}
        new {:message/by-id {"m1" {:id "m1"}}}
        ops (diff/diff old new)]
    (is (= [{:op "add"
             :path "/message~1by-id/m1"
             :value {:id "m1"}}]
           ops))
    (is (= {:ok? true :value new}
           (patch/apply-patch old ops)))))

(deftest sets-and-scalars-are-replaced-as-values
  (round-trip {:capabilities #{:tools} :retired #{:a}}
              {:capabilities #{:tools :vision} :retired #{:a :b}}))

(deftest a-large-common-subtree-produces-a-small-delta
  (let [messages (into {}
                       (map (fn [i]
                              [(str "m" i)
                               {:id (str "m" i)
                                :role "assistant"
                                :content (apply str (repeat 200 "x"))}]))
                       (range 50))
        old {:message/by-id messages
             :session/by-id {"s1" {:status "running"}}}
        new (-> old
                (assoc-in [:session/by-id "s1" :status] "idle")
                (assoc-in [:message/by-id "m25" :content] "changed"))
        ops (diff/diff old new)]
    (is (< (count (pr-str ops)) 400))
    (is (= new (:value (patch/apply-patch old ops))))))
