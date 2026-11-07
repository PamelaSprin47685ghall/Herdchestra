(ns hc.server.model-adapter.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.model-adapter.json :as json]))

(deftest json-encoding-does-not-collapse-normalized-keys
  (let [error (try
                (json/encode {:a 1 "a" 2})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? error))
    (is (= :json-encode-failed (:error/kind (ex-data error))))
    (is (= :json-key-collision
           (:error/kind (ex-data (ex-cause error))))))
  (testing "distinct keys still encode deterministically"
    (is (= "{\"a\":1,\"b\":2}"
           (json/encode {:b 2 :a 1})))))
