(ns hc.client.speech.input-model
  "Pure decisions for the speech input panel.

  Recording, upload and review state stay in the UI atom; this namespace owns
  the parts that can be decided without a browser: draft payload shape,
  service labels, save state, cancellation text, interruption reasons and
  language choices."
  (:require [clojure.string :as str]))

(def max-recording-ms 300000)

(def languages
  [["zh-CN" "简体中文"]
   ["en" "English"]
   ["ja" "日本語"]
   ["ko" "한국어"]
   ["fr" "Français"]
   ["de" "Deutsch"]
   ["es" "Español"]])

(defn format-duration
  [ms]
  (let [total (max 0 (long (or ms 0)))
        seconds (quot total 1000)
        minutes (quot seconds 60)
        seconds (mod seconds 60)]
    (str minutes ":" (when (< seconds 10) "0") seconds)))

(defn insert-payload
  "Payload for the draft insertion callback.  Nil when there is nothing to
  insert, so a blank transcript never edits the draft."
  [{:keys [session-id transcript insert-at]}]
  (when-not (str/blank? transcript)
    {:session-id session-id
     :text transcript
     :insert-at insert-at}))

(defn processing-label
  [processing]
  (case processing
    :device "设备内"
    :cloud "云端"
    :system "系统服务"
    "未知位置"))

(defn service-summary
  [service]
  (when service
    (str (or (:label service) (:id service) "未命名服务")
         " · " (processing-label (:processing service))
         (when (:has-key? service) " · 已配置密钥")
         (when (false? (:enabled? service)) " · 已禁用"))))

(defn selected-service
  "The explicit service when it is still enabled.  Without an explicit
  choice, the configured default, then the first enabled service.  An
  explicit but unavailable service yields nil instead of a misleading
  substitute."
  [services {:keys [service-id default-service-id]}]
  (let [enabled (vec (remove #(false? (:enabled? %)) services))]
    (if (some? service-id)
      (some #(when (= service-id (:id %)) %) enabled)
      (or (some #(when (= default-service-id (:id %)) %) enabled)
          (first enabled)))))

(defn save-state-label
  [{:keys [recording-id persisted? local-blob audio-url]}]
  (cond
    (nil? recording-id)
    (if (or local-blob audio-url)
      "仅保存在本页（尚未上传到识别服务；关闭后会丢失）"
      "没有可用的录音")

    (false? persisted?) "已保存到本地服务（未确认落盘）"

    :else "已保存到本地服务"))

(defn failure-facts
  [{:keys [duration service] :as state}]
  (str "录音时长 " (format-duration duration)
       " · " (save-state-label state)
       (when service (str " · 服务 " (service-summary service)))))

(defn service-change
  "Non-nil only when the operator picked a different, explicit service.
  Choosing the default leaves the server in charge, so no warning claims a
  change the client cannot verify.  `:default` marks an attempt that used the
  server-chosen default service."
  [attempted-service-id selected-service-id]
  (when (and (some? attempted-service-id)
             (not (str/blank? (str selected-service-id)))
             (not= attempted-service-id selected-service-id))
    {:changed? true
     :message "将把这段录音发送给另一服务。"}))

(defn resolve-attempted-service
  "Resolve the default sentinel to the configured default when it is known."
  [attempted-service-id default-service-id]
  (if (= attempted-service-id :default)
    (or default-service-id attempted-service-id)
    attempted-service-id))

(defn retry-blocked?
  [change confirmed?]
  (and (some? change) (not confirmed?)))

(defn cancellation
  [recording-id]
  {:phase :cancelled
   :error (str "本次语音输入已取消；原草稿保持原样。"
               (when recording-id
                 "若云端已处理部分录音，取消只保证本产品不再继续提交，不声称远端撤回。"))})

(defn reupload-request
  "The retained local recording to send again, or nil when none exists.
  Keeping the same Blob and insert position is what makes the action a retry
  of the upload rather than a new recording."
  [{:keys [local-blob local-duration local-insert-at]}]
  (when local-blob
    {:blob local-blob
     :duration-ms local-duration
     :insert-at local-insert-at}))

(defn interruption-reason
  [error-name source]
  (let [name (str error-name)]
    (cond
      (= name "NotAllowedError") "麦克风权限被拒绝或已被撤销"
      (= name "SecurityError") "浏览器安全设置拒绝了麦克风"
      (= name "NotReadableError") "麦克风被其他应用占用或无法读取"
      (= name "NotFoundError") "没有找到可用的麦克风"
      (= name "AbortError") "麦克风采集被系统中止"
      (= source "track") "麦克风音轨已结束（可能是耳机切换、设备拔出或系统占用）"
      (not (str/blank? name)) (str "录音被中断：" name)
      :else "录音被中断")))

(defn language-label
  [code]
  (cond
    (= code "auto") "自动识别"
    (= code "original") "原语言"
    :else (or (some (fn [[value label]] (when (= code value) label)) languages)
              code
              "自动识别")))

(defn language-options
  [detected-language]
  (vec
   (concat
    [{:value "auto" :label "自动识别"}]
    [{:value "original"
      :label (if detected-language
               (str "原语言（" (language-label detected-language) "）")
               "原语言（尚未识别）")
      :disabled? (nil? detected-language)}]
    (map (fn [[value label]] {:value value :label label}) languages))))

(defn language-header
  "Value for x-hc-language.  `original` uses the detected language; without a
  detection it falls back to automatic."
  [selection detected-language]
  (case selection
    "original" (or detected-language "auto")
    "auto" "auto"
    (or selection "auto")))

(defn settle-language
  "Keep an explicit original choice only while a language was detected.
  Otherwise the selector would show a disabled option as selected."
  [selection detected-language]
  (if (and (= selection "original") (nil? detected-language))
    "auto"
    selection))

(defn review-facts
  "Facts about the transcript that exists now.  The language selector is a
  request for the next retry and must not rewrite this line."
  [{:keys [duration service transcript-language]}]
  (str "录音已保留 · " (format-duration duration)
       " · 服务 " (or (service-summary service) "未记录")
       " · 语言 " (if transcript-language
                    (language-label transcript-language)
                    "未识别（自动识别）")))

(defn empty-recording?
  "True when a stopped recorder produced no usable audio.  A fake device,
  denied permission, or a muted microphone can stop with zero chunks; the
  caller must show a reason instead of uploading an empty Blob."
  [blob-size duration-ms]
  (or (zero? (long (or blob-size 0)))
      (zero? (long (or duration-ms 0)))))
