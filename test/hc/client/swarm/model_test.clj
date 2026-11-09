(ns hc.client.swarm.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.client.swarm.model :as model]))

(deftest write-gate-mode-label-is-total
  (doseq [[mode expected]
          [[:unreported "未上报"]
           [:recovery "恢复"]
           [:applying "应用"]
           [:shell "shell"]
           [:write "写入"]
           [:idle "空闲"]
           [:future-mode "未知"]
           [nil "未知"]]]
    (let [label (#'model/write-gate-mode-label mode)]
      (is (string? label) (str mode))
      (is (not (str/blank? label)) (str mode))
      (is (str/includes? label expected) (str mode)))))
