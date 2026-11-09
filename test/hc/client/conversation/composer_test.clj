(ns hc.client.conversation.composer-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.conversation.composer :as composer]))

(deftest unknown-availability-does-not-disable-sending
  (testing "a manual model id cannot be listed; unknown is not a hard failure"
    (is (false? (composer/model-unavailable? :unknown)))
    (is (= "尚未确认" (composer/model-availability-label :unknown)))
    (is (re-find #"可直接发送"
                 (composer/model-unavailable-message :unknown))))
  (testing "real failures still block"
    (is (true? (composer/model-unavailable? :connection-removed)))
    (is (true? (composer/model-unavailable? :model-removed)))
    (is (false? (composer/model-unavailable? :not-returned)))
    (is (false? (composer/model-unavailable? :unconfirmed)))))

(deftest local-composing-flag-guards-enter-without-native-is-composing
  (testing "composition start state wins when the event omits isComposing"
    (let [decision (composer/keydown
                    {:platform :desktop :send-key :enter}
                    {:key "Enter" :key-code 13 :which 13
                     :is-composing? false
                     :composing? true})]
      (is (= :ime-candidate (:action decision)))
      (is (false? (:prevent-default? decision)))))
  (testing "a normal Enter still sends"
    (let [decision (composer/keydown
                    {:platform :desktop :send-key :enter}
                    {:key "Enter" :key-code 13 :which 13})]
      (is (= :send (:action decision)))
      (is (true? (:prevent-default? decision))))))
