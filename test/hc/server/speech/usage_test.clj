(ns hc.server.speech.usage-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.speech.usage :as usage]))

(deftest provider-reported-zero-is-a-confirmed-zero
  (let [record (usage/stt-usage {:usage {:seconds 0}})]
    (is (= 0.0 (:quantity record)))
    (is (= :per-second (:unit record)))
    (is (= :provider-reported (:quantity-source record))))
  (is (nil? (usage/stt-usage {:usage {}}))))
