(ns hc.client.speech.input-model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.speech.input-model :as model]))

(deftest insert-payload-carries-the-recording-session
  (testing "the workbench callback receives the session that started the recording"
    (is (= {:session-id "s1" :text "hello" :insert-at 3}
           (model/insert-payload {:session-id "s1"
                                  :transcript "hello"
                                  :insert-at 3}))))
  (testing "the key is present even when the session is unknown"
    (is (contains? (model/insert-payload {:session-id nil
                                          :transcript "hello"
                                          :insert-at 0})
                   :session-id)))
  (testing "a blank transcript never edits the draft"
    (is (nil? (model/insert-payload {:session-id "s1"
                                     :transcript " \n "
                                     :insert-at 0})))))

(deftest duration-and-service-facts
  (is (= "0:05" (model/format-duration 5000)))
  (is (= "1:05" (model/format-duration 65000)))
  (is (= "5:00" (model/format-duration model/max-recording-ms)))
  (is (= "0:00" (model/format-duration nil)))
  (testing "processing location is operator language, not a raw keyword"
    (is (= "本地 · 设备内"
           (model/service-summary {:id "local" :label "本地" :processing :device})))
    (is (= "云端 · 云端"
           (model/service-summary {:id "cloud" :label "云端" :processing :cloud})))
    (is (= "系统 · 系统服务"
           (model/service-summary {:id "system" :label "系统" :processing :system}))))
  (testing "an explicit service that is no longer enabled is not silently replaced"
    (is (nil? (model/selected-service [{:id "off" :enabled? false}]
                                      {:service-id "off"}))))
  (testing "an enabled explicit service wins over the default"
    (is (= "chosen"
           (:id (model/selected-service
                 [{:id "default" :enabled? true}
                  {:id "chosen" :enabled? true}]
                 {:service-id "chosen" :default-service-id "default"})))))
  (testing "without an explicit service the default wins, then the first enabled"
    (let [services [{:id "first"} {:id "default"}]]
      (is (= "default" (:id (model/selected-service
                             services {:default-service-id "default"}))))
      (is (= "first" (:id (model/selected-service services {})))))))

(deftest failure-page-keeps-duration-and-save-state
  (let [saved (model/failure-facts {:duration 12000
                                    :recording-id "r1"
                                    :persisted? true
                                    :service {:id "local"
                                              :label "本地"
                                              :processing :device}})
        local-only (model/failure-facts {:duration 12000
                                         :local-blob :blob})]
    (is (str/includes? saved "录音时长 0:12"))
    (is (str/includes? saved "已保存到本地服务"))
    (is (str/includes? saved "本地 · 设备内"))
    (is (str/includes? local-only "仅保存在本页"))
    (is (not (str/includes? local-only "已保存到本地服务")))
    (testing "a microphone failure has no recording to save"
      (is (str/includes? (model/failure-facts {:duration 0})
                         "没有可用的录音")))
    (testing "an unconfirmed write is not reported as durable"
      (is (str/includes? (model/failure-facts {:duration 12000
                                               :recording-id "r1"
                                               :persisted? false})
                         "未确认落盘")))))

(deftest changing-service-needs-explicit-confirmation
  (is (nil? (model/service-change nil "other")))
  (is (nil? (model/service-change "same" "same")))
  (testing "the default service is chosen by the server, so no claim is made"
    (is (nil? (model/service-change "same" ""))))
  (testing "leaving an attempt that used the default service for an explicit one warns"
    (is (= "将把这段录音发送给另一服务。"
           (:message (model/service-change :default "second")))))
  (testing "the default sentinel resolves when the default id is known"
    (is (= "first" (model/resolve-attempted-service :default "first")))
    (is (= :default (model/resolve-attempted-service :default nil)))
    (is (= "second" (model/resolve-attempted-service "second" "first")))
    (is (nil? (model/service-change
               (model/resolve-attempted-service :default "first")
               "first"))))
  (let [change (model/service-change "first" "second")]
    (is (= "将把这段录音发送给另一服务。" (:message change)))
    (is (true? (model/retry-blocked? change false)))
    (is (false? (model/retry-blocked? change true)))
    (is (false? (model/retry-blocked? nil false)))))

(deftest cancellation-keeps-the-draft-and-does-not-reset
  (let [before-upload (model/cancellation nil)
        after-upload (model/cancellation "r1")]
    (is (= :cancelled (:phase before-upload)))
    (is (str/includes? (:error before-upload) "本次语音输入已取消"))
    (is (str/includes? (:error before-upload) "原草稿保持原样"))
    (is (not (str/includes? (:error before-upload) "远端撤回")))
    (is (str/includes? (:error after-upload) "不声称远端撤回"))))

(deftest reupload-reuses-the-retained-blob
  (is (nil? (model/reupload-request {})))
  (let [blob :recorded-blob]
    (is (= {:blob blob :duration-ms 12000 :insert-at 3}
           (model/reupload-request {:local-blob blob
                                    :local-duration 12000
                                    :local-insert-at 3})))))

(deftest interruption-reasons-are-explicit
  (is (= "麦克风权限被拒绝或已被撤销"
         (model/interruption-reason "NotAllowedError" "recorder")))
  (is (= "麦克风被其他应用占用或无法读取"
         (model/interruption-reason "NotReadableError" "recorder")))
  (is (= "麦克风音轨已结束（可能是耳机切换、设备拔出或系统占用）"
         (model/interruption-reason nil "track")))
  (is (= "录音被中断：UnknownError"
         (model/interruption-reason "UnknownError" "recorder")))
  (is (= "录音被中断" (model/interruption-reason nil "recorder"))))

(deftest review-language-choice-is-local-to-this-recording
  (let [options (model/language-options "zh-CN")
        original (some #(when (= "original" (:value %)) %) options)]
    (is (= ["auto" "original" "zh-CN" "en" "ja" "ko" "fr" "de" "es"]
           (mapv :value options)))
    (is (= "原语言（简体中文）" (:label original)))
    (is (not (:disabled? original)))
    (testing "original is disabled until a language was detected"
      (is (:disabled? (some #(when (= "original" (:value %)) %)
                            (model/language-options nil)))))
    (testing "the header value is resolved, never the UI token"
      (is (= "zh-CN" (model/language-header "original" "zh-CN")))
      (is (= "auto" (model/language-header "original" nil)))
      (is (= "ja" (model/language-header "ja" "zh-CN")))
      (is (= "auto" (model/language-header nil "zh-CN"))))
    (testing "an original choice without a detection falls back to automatic"
      (is (= "auto" (model/settle-language "original" nil)))
      (is (= "original" (model/settle-language "original" "en")))
      (is (= "ja" (model/settle-language "ja" nil))))))

(deftest review-page-shows-language-service-and-duration
  (let [facts (model/review-facts {:duration 65000
                                   :service {:id "local"
                                             :label "本地"
                                             :processing :device}
                                   :language "original"
                                   :transcript-language "en"})]
    (is (str/includes? facts "录音已保留"))
    (is (str/includes? facts "1:05"))
    (is (str/includes? facts "本地 · 设备内"))
    (is (str/includes? facts "English")))
  (testing "the line describes the transcript, not the pending retry choice"
    (let [facts (model/review-facts {:duration 1000
                                     :transcript-language "en"})]
      (is (str/includes? facts "语言 English"))
      (is (= facts (model/review-facts {:duration 1000
                                        :transcript-language "en"
                                        :language "ja"})))))
  (testing "an unreported transcript language is not invented"
    (is (str/includes? (model/review-facts {:duration 1000})
                       "未识别（自动识别）"))))

(deftest empty-recording-is-not-uploaded
  (is (true? (model/empty-recording? 0 1500)))
  (is (true? (model/empty-recording? 1024 0)))
  (is (false? (model/empty-recording? 1024 1500))))
