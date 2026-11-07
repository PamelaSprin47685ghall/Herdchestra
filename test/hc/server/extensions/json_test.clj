(ns hc.server.extensions.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.extensions.mcp.json :as json]))

(def ^:private scalar
  (gen/one-of [gen/boolean
               gen/small-integer
               (gen/fmap (fn [s] (str "s:" s)) gen/string)
               (gen/return nil)]))

(def ^:private json-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner)
                  (gen/map gen/string inner)]))
   scalar))

(defspec json-round-trips 100
  (prop/for-all [value json-value]
    (= value (json/read-str (json/write-str value)))))

(deftest parse-errors-are-positioned
  (testing "trailing data"
    (let [result (json/parse "{\"a\":1} trailing")]
      (is (false? (:ok? result)))
      (is (= :MCP_JSON_PARSE (get-in result [:error :code])))))
  (testing "duplicate keys"
    (let [result (json/parse "{\"a\":1,\"a\":2}")]
      (is (false? (:ok? result)))
      (is (re-find #"duplicate" (get-in result [:error :message])))))
  (testing "invalid escape"
    (is (false? (:ok? (json/parse "\"bad\\q\"")))))
  (testing "unterminated object"
    (is (false? (:ok? (json/parse "{\"a\":1"))))))

(deftest field-reads-both-key-shapes
  (is (= 1 (json/field {"id" 1} :id)))
  (is (= 1 (json/field {:id 1} :id)))
  (is (= "x" (json/field {"name" "x"} "name"))))

(deftest writer-rejects-non-json-values
  (is (thrown? clojure.lang.ExceptionInfo (json/write-str Double/NaN)))
  (is (thrown? clojure.lang.ExceptionInfo (json/write-str {:a (Object.)}))))
