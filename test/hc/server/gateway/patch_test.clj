(ns hc.server.gateway.patch-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.gateway.patch :as patch]))

(deftest add-replace-remove
  (is (= {:ok? true :value {:a 1 :b 2}}
         (patch/apply-patch {:a 1}
                            [{"op" "add" "path" "/b" "value" 2}])))
  (is (= {:ok? true :value {:a 3}}
         (patch/apply-patch {:a 1}
                            [{"op" "replace" "path" "/a" "value" 3}])))
  (is (= {:ok? true :value {}}
         (patch/apply-patch {:a 1}
                            [{"op" "remove" "path" "/a"}])))
  (is (= {:ok? true :value {:a 1 :b 2}}
         (patch/apply-patch {:a 1}
                            [{:op :add :path "/b" :value 2}]))))

(deftest keyword-and-string-operation-keys
  (is (= {:ok? true :value {:a 2}}
         (patch/apply-patch {:a 1}
                            [{:op :replace :path "/a" :value 2}])))
  (is (= {:ok? true :value {:a 2}}
         (patch/apply-patch {:a 1}
                            [{"op" "replace" "path" "/a" "value" 2}]))))

(deftest escaped-pointer-segments
  (is (= {:ok? true :value {:session/by-id {"s1" {:status "idle"}}}}
         (patch/apply-patch {:session/by-id {"s1" {:status "running"}}}
                            [{"op" "replace"
                              "path" "/session~1by-id/s1/status"
                              "value" "idle"}])))
  (is (= {:ok? true :value {"a/b" 1}}
         (patch/apply-patch {}
                            [{"op" "add" "path" "/a~1b" "value" 1}])))
  (is (= {:ok? true :value {"a~b" 1}}
         (patch/apply-patch {}
                            [{"op" "add" "path" "/a~0b" "value" 1}]))))

(deftest array-operations
  (is (= {:ok? true :value [1 2 3]}
         (patch/apply-patch [1 2]
                            [{"op" "add" "path" "/-" "value" 3}])))
  (is (= {:ok? true :value [1 3]}
         (patch/apply-patch [1 2 3]
                            [{"op" "remove" "path" "/1"}])))
  (is (= {:ok? true :value [1 3 2]}
         (patch/apply-patch [1 2]
                            [{"op" "add" "path" "/1" "value" 3}])))
  (is (= :invalid-index
         (get-in (patch/apply-patch [1]
                                    [{"op" "add" "path" "/01" "value" 2}])
                 [:error :code]))))

(deftest move-copy-and-test
  (is (= {:ok? true :value {:a 1 :b 1}}
         (patch/apply-patch {:a 1}
                            [{"op" "copy" "from" "/a" "path" "/b"}])))
  (is (= {:ok? true :value {"b" 1}}
         (patch/apply-patch {:a 1}
                            [{"op" "move" "from" "/a" "path" "/b"}])))
  (is (= {:ok? true :value {:a 1}}
         (patch/apply-patch {:a 1}
                            [{"op" "test" "path" "/a" "value" 1}])))
  (is (= :test-failed
         (get-in (patch/apply-patch {:a 1}
                                    [{"op" "test" "path" "/a" "value" 2}])
                 [:error :code])))
  (is (= :invalid-move
         (get-in (patch/apply-patch {:a {:b 1}}
                                    [{"op" "move" "from" "/a" "path" "/a/b"}])
                 [:error :code]))))

(deftest failures-are-atomic-and-indexed
  (let [document {:a 1}
        result (patch/apply-patch
                document
                [{"op" "replace" "path" "/a" "value" 2}
                 {"op" "replace" "path" "/missing" "value" 3}])]
    (is (not (:ok? result)))
    (is (= 1 (get-in result [:error :op-index])))
    (is (= {:a 1} document)))
  (is (= :invalid-op
         (get-in (patch/apply-patch {} [{"op" "wat" "path" "/a"}])
                 [:error :code])))
  (is (= :invalid-pointer
         (get-in (patch/apply-patch {} [{"op" "add" "path" "a" "value" 1}])
                 [:error :code])))
  (is (= :missing-path
         (get-in (patch/apply-patch {} [{"op" "add" "path" "/a/b" "value" 1}])
                 [:error :code]))))
