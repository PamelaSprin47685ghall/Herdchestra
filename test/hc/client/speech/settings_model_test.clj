(ns hc.client.speech.settings-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.speech.settings-model :as model]))

(deftest failed-submissions-become-visible-notices
  (testing "the transport's own reason wins"
    (is (= {:tone :error :text "连接尚未完成同步"}
           (model/failure-notice
            {:ok? false
             :error {:code :SPEECH_NOT_SYNCED
                     :message "连接尚未完成同步"}}
            "语音服务未保存。"))))
  (testing "a reasonless failure still reports the action"
    (is (= {:tone :error :text "语音服务未保存。"}
           (model/failure-notice {:ok? false :error {}} "语音服务未保存。"))))
  (testing "an accepted command produces no notice; its receipt does"
    (is (nil? (model/failure-notice {:ok? true :command-id "c1"}
                                    "语音服务未保存。"))))
  (testing "a failed submission clears pending but keeps the open form"
    (let [state {:pending :save
                 :form {:id "local" :label "本地"}
                 :snapshot {:revision 4}}
          patch (model/submission-failure
                 state
                 {:ok? false
                  :error {:code :SPEECH_TRANSPORT
                          :message "实时通道未打开"}}
                 "语音服务未保存。")
          next-state (merge state patch)]
      (is (nil? (:pending next-state)))
      (is (= {:id "local" :label "本地"} (:form next-state)))
      (is (= 4 (get-in next-state [:snapshot :revision])))
      (is (= {:tone :error :text "实时通道未打开"} (:notice next-state)))))
  (testing "an accepted command yields no state patch"
    (is (nil? (model/submission-failure {:pending :save}
                                        {:ok? true}
                                        "语音服务未保存。")))))
