(ns hc.server.sessions.ids-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.sessions.ids :as ids]))

(deftest valid-ids-are-non-blank-strings
  (is (ids/valid-id? "s-1"))
  (is (ids/valid-id? "任意-字符串"))
  (is (not (ids/valid-id? nil)))
  (is (not (ids/valid-id? "")))
  (is (not (ids/valid-id? "   ")))
  (is (not (ids/valid-id? :s-1)))
  (is (not (ids/valid-id? 1))))

(deftest counter-source-is-deterministic-and-unique
  (let [source (ids/counter-source)]
    (is (= "hc-session-1" (ids/next-id! source :session)))
    (is (= "hc-session-2" (ids/next-id! source :session)))
    (is (= "hc-run-1" (ids/next-id! source :run)))
    (is (= "hc-attempt-1" (ids/next-id! source :attempt)))
    (is (= (str "hc-message-1") (ids/next-id! source :message)))
    (is (every? ids/valid-id?
                (repeatedly 50 #(ids/next-id! source :call))))))

(deftest counter-source-continues-from-observed-counters
  (let [source (ids/counter-source {:prefix "p"
                                    :counters {"session" 41}})]
    (is (= "p-session-42" (ids/next-id! source :session)))
    (is (= "p-run-1" (ids/next-id! source :run)))))

(deftest uuid-source-is-unique
  (let [source (ids/uuid-source "test")
        values (repeatedly 100 #(ids/next-id! source :attempt))]
    (is (every? ids/valid-id? values))
    (is (= (count values) (count (set values))))
    (is (every? #(str/starts-with? % "test-attempt-") values))))

(deftest unknown-kind-is-explicit
  (let [source (ids/counter-source)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ids/next-id! source nil)))
    (is (= "hc-custom-1" (ids/next-id! source :custom)))))
