(ns hc.client.speech.playback-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.speech.playback-model :as model]))

(def ^:private request
  {:generation 2 :message-id "m1" :version-id "v1"})

(def ^:private waiting
  {:status :loading
   :generation 2
   :message-id "m1"
   :version-id "v1"})

(deftest generation-always-advances
  (is (= 1 (model/next-generation nil)))
  (is (= 2 (model/next-generation 1)))
  (is (= 9 (model/next-generation 8))))

(deftest only-the-waiting-request-may-publish
  (testing "the live request is current"
    (is (true? (model/request-current? waiting request))))
  (testing "a stop or a newer play invalidates the old generation"
    (is (false? (model/request-current? (assoc waiting :generation 3)
                                        request)))
    (is (false? (model/request-current? (assoc waiting :status :idle)
                                        request)))
    (is (false? (model/request-current? (assoc waiting :status :playing)
                                        request))))
  (testing "a different message or version is stale even with the same generation"
    (is (false? (model/request-current? (assoc waiting :message-id "m2")
                                        request)))
    (is (false? (model/request-current? (assoc waiting :version-id "v2")
                                        request)))))
