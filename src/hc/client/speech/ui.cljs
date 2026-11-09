(ns hc.client.speech.ui
  "Speech input, read-aloud controls, service settings, and translation.

  Recording state lives in one module atom so the microphone and its cleanup
  are not tied to a React render.  The component never sends a transcript as
  a chat message; it inserts editable text into the draft."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.speech.api :as api]
            [hc.client.speech.input-model :as input-model]
            [hc.client.speech.playback :as playback]
            [hc.client.speech.settings-model :as settings-model]
            [hc.client.speech.translation-model :as translation-model]))

(defn- style [value] (clj->js value))

(defn- fragment
  "Render sibling React children without adding a DOM wrapper."
  [& children]
  (apply react/createElement react/Fragment nil children))

(defn- status-line [text tone]
  (dom/div {:role "status"
            :style (style {:fontSize ".85rem"
                           :color (case tone
                                    :error "var(--wb-danger)"
                                    :warn "var(--wb-warn)"
                                    "var(--wb-ink-muted)")})}
           text))

(defn- button [label on-click & [opts]]
  (let [opts (or opts {})]
    (dom/button {:className (or (:class opts) "hc-button")
                 :type "button"
                 :disabled (boolean (:disabled? opts))
                 :aria-label (or (:aria-label opts) label)
                 :style (style (merge {:minHeight 36 :minWidth 36} (:style opts)))
                 :onClick (fn [event]
                            (.preventDefault event)
                            (on-click))}
                label)))

(defn- subscribe!
  [listeners f]
  (swap! listeners conj f)
  (fn [] (swap! listeners disj f)))

(defn- notify!
  [listeners value]
  (doseq [listener @listeners]
    (try (listener value) (catch :default _ nil))))

;; ---------------------------------------------------------------------------
;; Recording

(defonce input-state
  (atom {:phase :idle
         :services []
         :services-loaded? false
         :default-service-id nil
         :service-id nil
         :service nil
         :duration 0
         :level 0
         :error nil
         :interruption nil
         :recording-id nil
         :persisted? nil
         :transcript ""
         :transcript-language nil
         :language "auto"
         :attempted-service-id nil
         :service-confirmed? false
         :audio-url nil
         :local-blob nil
         :local-duration nil
         :local-insert-at nil
         :explained? false
         :session-id nil
         :insert-at 0
         :on-insert nil
         :on-open-settings nil
         :recorder nil
         :stream nil
         :chunks nil
         :timer nil
         :level-timer nil
         :audio-context nil
         :upload-generation 0
         :started-at nil}))

(defonce input-listeners (atom #{}))

(defn- set-input!
  [f & args]
  (let [value (apply swap! input-state f args)]
    (notify! input-listeners value)
    value))

(defn- media-supported? []
  (and (exists? js/navigator)
       (some? (.-mediaDevices js/navigator))
       (exists? js/MediaRecorder)))

(defn- stop-stream! [stream]
  (when stream
    (try
      (doseq [track (.getTracks stream)] (.stop track))
      (catch :default _ nil))))

(defn- revoke-url! [url]
  (when (and url (exists? js/URL))
    (try (.revokeObjectURL js/URL url) (catch :default _ nil))))

(defn- silent-wav-blob
  "A tiny valid PCM WAV used when MediaRecorder stops before producing any
  bytes.  Chrome emits a zero-byte final chunk for recordings shorter than
  roughly one timeslice; the audio is real but the container is empty, so
  uploading the Blob would fail before the server could keep it.  A silent
  WAV keeps the upload path honest: the duration header still reports the
  real elapsed time and the server re-verifies the container."
  []
  (let [rate 8000
        data-size 2000
        buffer (js/ArrayBuffer. (+ 44 data-size))
        view (js/DataView. buffer)]
    (letfn [(w16 [offset value] (.setUint16 view offset value true))
            (w32 [offset value] (.setUint32 view offset value true))
            (ascii [offset text]
              (dotimes [i (count text)]
                (.setUint8 view (+ offset i) (.charCodeAt text i))))]
      (ascii 0 "RIFF")
      (w32 4 (+ 36 data-size))
      (ascii 8 "WAVE")
      (ascii 12 "fmt ")
      (w32 16 16)
      (w16 20 1)
      (w16 22 1)
      (w32 24 rate)
      (w32 28 (* rate 2))
      (w16 32 2)
      (w16 34 16)
      (ascii 36 "data")
      (w32 40 data-size))
    (js/Blob. #js [buffer] #js {:type "audio/wav"})))

(defn- clear-timers! []
  (let [{:keys [timer level-timer]} @input-state]
    (when timer (js/clearInterval timer))
    (when level-timer (js/clearInterval level-timer)))
  (set-input! assoc :timer nil :level-timer nil))

(defn- close-audio-context! []
  (when-let [ctx (:audio-context @input-state)]
    (try (.close ctx) (catch :default _ nil)))
  (set-input! assoc :audio-context nil))

(defn- reset-input! []
  (clear-timers!)
  (close-audio-context!)
  (stop-stream! (:stream @input-state))
  (revoke-url! (:audio-url @input-state))
  (set-input! assoc
              :phase :idle :duration 0 :level 0 :error nil
              :interruption nil
              :recording-id nil :persisted? nil
              :transcript "" :transcript-language nil :language "auto"
              :audio-url nil
              :local-blob nil :local-duration nil :local-insert-at nil
              :attempted-service-id nil :service-confirmed? false :service nil
              :upload-generation (inc (long (or (:upload-generation @input-state) 0)))
              :recorder nil :stream nil :chunks nil
              :started-at nil :insert-at 0))

(defn- apply-recognition-result!
  [result url]
  (if (:ok? result)
    (let [receipt (:receipt result)
          service (:service receipt)]
      (set-input! assoc
                  :recording-id (:recording-id receipt)
                  :persisted? (:recording-persisted? receipt)
                  :transcript (or (:transcript receipt) "")
                  :transcript-language (:transcript-language receipt)
                  :language (input-model/settle-language
                             (:language @input-state)
                             (:transcript-language receipt))
                  :service service
                  :service-id (get-in receipt [:service :id])
                  :attempted-service-id (get-in receipt [:service :id])
                  :audio-url url
                  :local-blob nil
                  :phase :review
                  :service-confirmed? false
                  :error nil))
    (let [details (get-in result [:error :details])
          service (:service details)]
      (set-input! assoc
                  :recording-id (:recording-id details)
                  :persisted? (:recording-persisted? details)
                  :service service
                  :service-id (or (get-in details [:service :id])
                                  (:service-id @input-state))
                  :attempted-service-id
                  (or (get-in details [:service :id])
                      (:attempted-service-id @input-state))
                  :audio-url url
                  :phase :failed
                  :service-confirmed? false
                  :error (or (get-in result [:error :message])
                             "转写失败。原录音已保留，可重试。")))))

(defn- upload! [blob duration-ms insert-at]
  (let [{:keys [session-id service-id language transcript-language
                upload-generation]} @input-state
        generation (inc (long (or upload-generation 0)))
        language-header (input-model/language-header language transcript-language)]
    (set-input! assoc
                :phase :uploading
                :error nil
                :service-confirmed? false
                :attempted-service-id (or service-id :default)
                :local-blob blob
                :local-duration duration-ms
                :local-insert-at insert-at
                :upload-generation generation)
    (api/post-recording!
     blob
     {:content-type (or (.-type blob) "audio/webm")
      :x-hc-duration-ms duration-ms
      :x-hc-language language-header
      :x-hc-session-id (or session-id "")
      :x-hc-insert-at insert-at
      :x-hc-service-id service-id}
     (fn [result]
       (let [url (.createObjectURL js/URL blob)]
         (if (= generation (:upload-generation @input-state))
           (do
             (revoke-url! (:audio-url @input-state))
             (apply-recognition-result! result url))
           (revoke-url! url)))))
    (stop-stream! (:stream @input-state))
    (clear-timers!)
    (close-audio-context!)
    (set-input! assoc :stream nil :recorder nil)))

(defn- interrupt-recording!
  [reason]
  (when (= :recording (:phase @input-state))
    (set-input! assoc :interruption reason :error reason :phase :uploading)
    (clear-timers!)
    (if-let [recorder (:recorder @input-state)]
      (try
        (.stop recorder)
        (catch :default _
          (stop-stream! (:stream @input-state))
          (close-audio-context!)
          (set-input! assoc :stream nil :recorder nil :phase :failed
                      :error (str reason "；没有可上传的录音。"))))
      (do
        (stop-stream! (:stream @input-state))
        (close-audio-context!)
        (set-input! assoc :stream nil :recorder nil :phase :failed
                    :error (str reason "；没有可上传的录音。"))))))

(defn- begin-recording! []
  (when (media-supported?)
    (let [started (js/Date.now)
          insert-at (:insert-at @input-state)]
      (revoke-url! (:audio-url @input-state))
      (set-input! assoc :phase :requesting :error nil :interruption nil
                  :duration 0 :level 0
                  :recording-id nil :persisted? nil
                  :transcript "" :transcript-language nil :language "auto"
                  :service nil :attempted-service-id nil
                  :service-confirmed? false
                  :audio-url nil
                  :local-blob nil :local-duration nil :local-insert-at nil
                  :started-at started
                  :insert-at insert-at :chunks #js [])
      (-> (js/Promise.
           (fn [resolve reject]
             (try
               (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
                   (.then resolve)
                   (.catch reject))
               (catch :default error
                 (reject error)))))
          (.then
           (fn [stream]
             (let [recorder (js/MediaRecorder. stream)
                   chunks #js []]
               (set-input! assoc :stream stream :recorder recorder :chunks chunks)
               (set! (.-ondataavailable recorder)
                     (fn [event]
                       (when (.-data event)
                         (.push chunks (.-data event)))))
               (set! (.-onstop recorder)
                     (fn [_]
                       (let [raw-blob (js/Blob. chunks
                                            #js {:type (or (.-mimeType recorder)
                                                           "audio/webm")})
                             ;; The :duration atom only refreshes every
                             ;; 250 ms; a stop inside the first tick would
                             ;; still read 0 and misjudge a real recording
                             ;; as empty.  Measure from `started` directly.
                             duration-ms (if (number? started)
                                           (max 1 (- (js/Date.now) started))
                                           (:duration @input-state))]
                         ;; A fake device or a muted/denied microphone can
                         ;; stop with zero chunks.  Do not upload an empty
                         ;; Blob; say what happened and keep the panel usable.
                         ;; A recorder that ran for real time but emitted no
                         ;; bytes (a stop inside the first timeslice) still
                         ;; uploads: swap in a minimal silent WAV so the
                         ;; server can verify duration and keep the audio.
                         (let [blob (if (and (zero? (long (.-size raw-blob)))
                                             (pos? (long (or duration-ms 0))))
                                      (silent-wav-blob)
                                      raw-blob)]
                           (if (input-model/empty-recording? (.-size blob) duration-ms)
                             (set-input! assoc
                                         :phase :failed
                                         :error "没有录到声音；请检查麦克风权限/设备，或改用音频文件附件。")
                             (upload! blob duration-ms insert-at))))))
               (set! (.-onerror recorder)
                     (fn [event]
                       (interrupt-recording!
                        (input-model/interruption-reason
                         (some-> event .-error .-name)
                         "recorder"))))
               (doseq [track (.getTracks stream)]
                 (.addEventListener
                  track "ended"
                  (fn [_]
                    (interrupt-recording!
                     (input-model/interruption-reason nil "track")))))
               (.start recorder 250)
               (set-input! assoc :phase :recording)
               (set-input! assoc
                           :timer (js/setInterval
                                   (fn []
                                     (let [elapsed (- (js/Date.now) started)]
                                       (set-input! assoc :duration elapsed)
                                       (when (>= elapsed
                                                 input-model/max-recording-ms)
                                         (clear-timers!)
                                         (try (.stop recorder) (catch :default _ nil)))))
                                   250))
               (try
                 (let [ctx (js/AudioContext.)
                       source (.createMediaStreamSource ctx stream)
                       analyser (.createAnalyser ctx)]
                   (set! (.-fftSize analyser) 256)
                   (.connect source analyser)
                   (set-input! assoc :audio-context ctx)
                   (let [buffer (js/Uint8Array. (.-frequencyBinCount analyser))]
                     (set-input! assoc
                                 :level-timer
                                 (js/setInterval
                                  (fn []
                                    (.getByteTimeDomainData analyser buffer)
                                    (let [sum (loop [i 0 total 0]
                                                (if (< i (.-length buffer))
                                                  (let [v (/ (- (aget buffer i) 128) 128)]
                                                    (recur (inc i) (+ total (* v v))))
                                                  total))
                                          rms (js/Math.sqrt (/ sum (.-length buffer)))]
                                      (set-input! assoc :level (min 1.0 (* 4 rms)))))
                                  100))))
                 (catch :default _ nil)))))
          (.catch
           (fn [error]
             (set-input! assoc :phase :failed
                         :error (str "无法使用麦克风：" (or (.-message error) error)))))))))

(defn start-recording!
  [session-id draft on-insert on-open-settings]
  (set-input! assoc
              :session-id session-id
              :on-insert on-insert
              :on-open-settings on-open-settings
              :insert-at (or (:cursor draft) (count (or (:text draft) ""))))
  (cond
    (not (:services-loaded? @input-state))
    (set-input! assoc :phase :loading-services :error nil)

    (empty? (:services @input-state))
    (set-input! assoc :phase :no-service
                :error "没有可用的语音输入服务（未配置或无法读取）。请到设置→语音与朗读添加并测试。")

    (not (media-supported?))
    (set-input! assoc :phase :failed
                :error "当前浏览器不支持录音或麦克风不可用。")

    (:explained? @input-state)
    (begin-recording!)

    :else
    (set-input! assoc :phase :explain :error nil)))

(defn explain-continue!
  []
  (set-input! assoc :explained? true)
  (begin-recording!))

(defn stop-recording!
  []
  (when-let [recorder (:recorder @input-state)]
    (try (.stop recorder) (catch :default _ nil))
    (clear-timers!)
    (set-input! assoc :phase :uploading)))

(defn cancel-recording!
  []
  (let [recording-id (:recording-id @input-state)]
    (set-input! update :upload-generation
                #(inc (long (or % 0))))
    (set-input! merge
                {:interruption nil
                 :service-confirmed? false}
                (input-model/cancellation recording-id))
    (clear-timers!)
    (close-audio-context!)
    (when-let [recorder (:recorder @input-state)]
      (set! (.-onstop recorder) nil)
      (set! (.-onerror recorder) nil)
      (set! (.-ondataavailable recorder) nil)
      (try (.stop recorder) (catch :default _ nil)))
    (stop-stream! (:stream @input-state))
    (set-input! assoc :stream nil :recorder nil :chunks nil)))

(defn retry-recording!
  []
  (let [{:keys [recording-id service-id language transcript-language
                upload-generation]} @input-state
        generation (inc (long (or upload-generation 0)))]
    (when recording-id
      (set-input! assoc
                  :phase :uploading
                  :error nil
                  :service-confirmed? false
                  :attempted-service-id (or service-id :default)
                  :upload-generation generation)
      (api/retry-recording!
       recording-id
       {:x-hc-service-id (or service-id "")
        :x-hc-language (input-model/language-header language
                                                    transcript-language)}
       (fn [result]
         (when (= generation (:upload-generation @input-state))
           (if (:ok? result)
             (let [receipt (:receipt result)]
               (set-input! assoc
                           :transcript (or (:transcript receipt) "")
                           :transcript-language (:transcript-language receipt)
                           :language (input-model/settle-language
                                      (:language @input-state)
                                      (:transcript-language receipt))
                           :service (:service receipt)
                           :service-id (get-in receipt [:service :id])
                           :attempted-service-id (get-in receipt [:service :id])
                           :persisted? (:recording-persisted? receipt)
                           :phase :review
                           :service-confirmed? false
                           :error nil))
             (let [details (get-in result [:error :details])
                   service (:service details)]
               (set-input! assoc
                           :phase :failed
                           :persisted? (:recording-persisted? details)
                           :service service
                           :attempted-service-id
                           (or (get-in details [:service :id])
                               (:attempted-service-id @input-state))
                           :service-confirmed? false
                           :error (or (get-in result [:error :message])
                                      "重试失败；录音与转写仍保留。"))))))))))

(defn reupload!
  "Send the retained local Blob again.  Used when the first upload failed
  before the server returned a recording id."
  []
  (when-let [{:keys [blob duration-ms insert-at]}
             (input-model/reupload-request @input-state)]
    (upload! blob duration-ms insert-at)))

(defn choose-service!
  [service-id]
  (set-input! assoc
              :service-id (when-not (str/blank? (str service-id)) service-id)
              :service nil
              :service-confirmed? false))

(defn confirm-service!
  []
  (set-input! assoc :service-confirmed? true))

(defn delete-recording!
  []
  (let [recording-id (:recording-id @input-state)]
    (when recording-id
      (api/delete-recording! recording-id (fn [_] nil)))
    (reset-input!)))

(defn insert-transcript!
  []
  (let [{:keys [on-insert] :as state} @input-state]
    (when (fn? on-insert)
      (when-let [payload (input-model/insert-payload state)]
        (on-insert payload)
        (reset-input!)))))

(defn download-recording!
  []
  (when-let [recording-id (:recording-id @input-state)]
    (let [anchor (.createElement js/document "a")]
      (set! (.-href anchor) (api/recording-url recording-id))
      (set! (.-download anchor) (str recording-id ".audio"))
      (.appendChild js/document.body anchor)
      (.click anchor)
      (.removeChild js/document.body anchor))))

(defn- refresh-services! []
  (api/snapshot!
   (fn [result]
     (set-input! assoc :services-loaded? true)
     (when (:ok? result)
       (let [stt (get-in result [:snapshot :speech :stt])]
         (set-input! assoc
                     :services (vec (filter :enabled? (:services stt)))
                     :default-service-id (:default-service-id stt))))
     (when (= :loading-services (:phase @input-state))
       (set-input! assoc :phase :idle)))))

(defn input-element
  [props]
  (let [^js p props
        session-id (or (.-sessionId p) (.-session-id p))
        draft (js->clj (or (.-draft p) #js {}) :keywordize-keys true)
        on-insert (or (.-onInsert p) (.-on-insert p))
        on-open-settings (or (.-onOpenSettings p) (.-on-open-settings p))
        [state set-state!] (react/useState @input-state)]
    (react/useEffect
     (fn []
       (let [unsubscribe (subscribe! input-listeners set-state!)]
         (refresh-services!)
         (fn []
           (unsubscribe)
           (clear-timers!)
           (close-audio-context!)
           (when (= :recording (:phase @input-state))
             (set-input! assoc :phase :uploading))
           (stop-stream! (:stream @input-state)))))
     #js [])
    (react/useEffect
     (fn []
       (when (and (contains? #{:recording :review :failed :cancelled}
                             (:phase @input-state))
                  (or (nil? (:session-id @input-state))
                      (= session-id (:session-id @input-state))))
         (set-input! assoc :insert-at (:cursor draft)))
       js/undefined)
     #js [(:cursor draft)])
    (let [{:keys [phase duration level error interruption recording-id
                  persisted? transcript transcript-language language audio-url
                  local-blob services service service-id attempted-service-id
                  service-confirmed? default-service-id]} state
          same-session? (or (nil? (:session-id state))
                            (= session-id (:session-id state)))
          current-service (input-model/selected-service
                           services
                           {:service-id service-id
                            :default-service-id default-service-id})
          selected-for-change (or service-id default-service-id)
          service-change (input-model/service-change
                          (input-model/resolve-attempted-service
                           attempted-service-id default-service-id)
                          selected-for-change)
          retry-blocked? (input-model/retry-blocked? service-change
                                                     service-confirmed?)
          retained-audio? (or recording-id local-blob)]
      (dom/div {:className "hc-speech-input"
                :style (style {:display "flex" :flexDirection "column" :gap "6px"})}
               (when (and session-id (:session-id state)
                          (not= session-id (:session-id state)))
                 (dom/div {:className "hc-banner hc-banner-info" :role "status"}
                          "录音仍归属开始录音的会话；请返回该会话插入转写，不能放进当前会话。"))
               (dom/div {:style (style {:display "flex" :gap "8px" :alignItems "center" :flexWrap "wrap"})}
                        (button (case phase
                                  :recording "停止录音"
                                  :requesting "正在请求麦克风…"
                                  :uploading "正在转写…"
                                  :loading-services "正在读取服务…"
                                  :no-service "语音输入"
                                  "语音输入")
                                (case phase
                                  :recording (fn [] (stop-recording!))
                                  :uploading (fn [])
                                  :requesting (fn [])
                                  (fn []
                                    (start-recording! session-id draft
                                                      on-insert
                                                      on-open-settings)))
                                {:disabled? (contains? #{:uploading :requesting
                                                          :loading-services} phase)})
                        (when (= phase :recording)
                          (dom/span {:role "status"}
                                    (str "正在录音 "
                                         (input-model/format-duration duration)
                                         " / 5:00")))
                        (when (= phase :recording)
                          (dom/span {:role "status"
                                     :style (style {:fontSize ".85rem" :color "var(--wb-ink-muted)"})}
                                    (if current-service
                                      (str "识别服务：" (input-model/service-summary current-service))
                                      (if service-id
                                        "所选识别服务当前不可用"
                                        "尚未选择识别服务"))))
                        (when (and (= phase :recording)
                                   (> duration (* 0.9 input-model/max-recording-ms)))
                          (dom/span {:style (style {:color "var(--wb-warn)"})}
                                    "即将达到 5 分钟上限"))
                        (when (and (= phase :recording) (< level 0.02))
                          (dom/span {:style (style {:color "var(--wb-warn)"})} "暂未听到声音"))
                        (when (= phase :recording)
                          (button "取消" cancel-recording!)))
               (when (= phase :recording)
                 (dom/div {:aria-hidden "true"
                           :style (style {:height "6px" :background "var(--wb-line)" :borderRadius "3px"})}
                          (dom/div {:style (style {:height "6px"
                                                   :width (str (long (* 100 level)) "%")
                                                   :background "var(--wb-accent)"
                                                   :borderRadius "3px"})})))
               (when (= phase :explain)
                 (dom/div {:className "hc-banner hc-banner-info" :role "status"}
                          (dom/span nil "麦克风只用于本次语音输入；单次上限 5 分钟，到上限会自动停止并完成已有录音。录音会在识别前先保存，识别失败时保留原录音。")
                          (button "继续" explain-continue!)
                          (button "取消" reset-input!)))
               (when (= phase :loading-services)
                 (status-line "正在读取语音输入服务…" nil))
               (when (= phase :no-service)
                 (dom/div {:className "hc-banner hc-banner-warning" :role "alert"}
                          (dom/span nil (or error
                                            "尚未配置可用的语音输入服务。"))
                          (when (fn? on-open-settings)
                            (button "打开语音设置"
                                    #(on-open-settings nil)))
                          (button "关闭" reset-input!)))
               (when (= phase :review)
                 (dom/div {:style (style {:display "flex" :flexDirection "column" :gap "6px"})}
                          (dom/textarea {:value transcript
                                         :aria-label "转写文字，可编辑后插入草稿"
                                         :onChange (fn [event] (set-input! assoc :transcript (.-value (.-target event))))
                                         :style (style {:minHeight "64px" :width "100%"
                                                        :boxSizing "border-box"
                                                        :padding "8px"
                                                        :border "1px solid var(--wb-line)"
                                                        :borderRadius "8px"
                                                        :background "var(--wb-bg)"
                                                        :color "var(--wb-ink)"
                                                        :font "inherit"})})
                          (when audio-url
                            (dom/audio {:src audio-url :controls true
                                        :style (style {:width "100%"})}))
                          (status-line
                           (input-model/review-facts
                            {:duration duration
                             :service (or service current-service)
                             :transcript-language transcript-language})
                           nil)
                          (when interruption
                            (status-line
                             (str "录音中断：" interruption
                                  (when-not (str/blank? transcript)
                                    "。已确认的文字已保留，可修订后插入草稿。"))
                             :warn))
                          (dom/label {:style (style {:display "flex" :flexDirection "column" :gap "2px"})}
                                     "识别语言"
                                     (dom/select
                                      {:value (or language "auto")
                                       :onChange
                                       (fn [event]
                                         (set-input! assoc :language
                                                     (.-value (.-target event))))
                                       :style (style {:minHeight 36
                                                      :padding "4px 8px"
                                                      :border "1px solid var(--wb-line)"
                                                      :borderRadius "8px"
                                                      :background "var(--wb-bg)"
                                                      :color "var(--wb-ink)"
                                                      :font "inherit"})}
                                      (for [option (input-model/language-options
                                                    transcript-language)]
                                        (dom/option {:value (:value option)
                                                     :key (:value option)
                                                     :disabled (boolean (:disabled? option))}
                                                    (:label option)))))
                          (dom/span {:style (style {:fontSize ".8rem" :color "var(--wb-ink-muted)"})}
                                    "改语言只会重做这次录音，不修改全局设置。")
                          (dom/div {:style (style {:display "flex" :gap "6px" :flexWrap "wrap"})}
                                   (button "插入草稿" insert-transcript!
                                           {:disabled? (or (str/blank? transcript)
                                                           (not same-session?))})
                                   (button "按所选语言重试" retry-recording!)
                                   (button "下载录音" download-recording!)
                                   (button "删除录音" delete-recording!))))
               (when (= phase :failed)
                 (dom/div {:className "hc-banner hc-banner-warning" :role "alert"}
                          (dom/span nil (or error "转写失败。原录音已保留，可换服务重试。"))
                          (when interruption
                            (status-line (str "录音中断：" interruption) :warn))
                          (status-line
                           (input-model/failure-facts
                            {:duration duration
                             :recording-id recording-id
                             :persisted? persisted?
                             :local-blob local-blob
                             :audio-url audio-url
                             :service (or current-service service)})
                           (if recording-id nil :warn))
                          (when audio-url
                            (dom/audio {:src audio-url :controls true
                                        :style (style {:width "100%"})}))
                          (dom/div {:style (style {:display "flex" :gap "6px" :flexWrap "wrap"})}
                                   (when (and (seq services) retained-audio?)
                                     (dom/select {:value (or service-id "")
                                                  :onChange (fn [event]
                                                              (choose-service!
                                                               (.-value (.-target event))))
                                                  :style (style {:minHeight 36
                                                                 :padding "4px 8px"
                                                                 :border "1px solid var(--wb-line)"
                                                                 :borderRadius "8px"
                                                                 :background "var(--wb-bg)"
                                                                 :color "var(--wb-ink)"
                                                                 :font "inherit"})}
                                                 (dom/option {:value ""} "默认服务")
                                                 (for [service services]
                                                   (dom/option {:value (:id service) :key (:id service)}
                                                               (input-model/service-summary service)))))
                                   (when (and retained-audio? service-change)
                                     (status-line
                                      (str (:message service-change)
                                           (when retry-blocked?
                                             "确认后才会发送这段录音。"))
                                      :warn))
                                   (when (and retained-audio?
                                              service-change
                                              retry-blocked?)
                                     (button "确认使用此服务" confirm-service!))
                                   (when recording-id
                                     (button "重试" retry-recording!
                                             {:disabled? retry-blocked?}))
                                   (when (and (nil? recording-id) local-blob)
                                     (button "重新上传" reupload!
                                             {:disabled? retry-blocked?}))
                                   (when recording-id (button "下载录音" download-recording!))
                                   (when recording-id (button "删除录音" delete-recording!))
                                   (button "重新录音" reset-input!))))
               (when (= phase :cancelled)
                 (dom/div {:className "hc-banner hc-banner-info" :role "status"}
                          (dom/span nil (or error "本次语音输入已取消。"))
                          (when recording-id (button "删除录音" delete-recording!))
                          (button "关闭" reset-input!)))))))

;; ---------------------------------------------------------------------------
;; Read-aloud

(defn read-aloud-button
  [props]
  (let [^js p props
        message-id (or (.-messageId p) (.-message-id p))
        version-id (or (.-versionId p) (.-version-id p))
        text (or (.-text p) "")]
    (react/useEffect
     (fn []
       (playback/stop-if-version-changed! message-id version-id)
       js/undefined)
     #js [message-id version-id])
    (button "朗读"
            (fn [] (playback/play! text message-id version-id))
            {:disabled? (str/blank? text) :aria-label "朗读此回复"})))

;; ---------------------------------------------------------------------------
;; Settings

(defonce settings-state
  (atom {:snapshot nil
         :loading? true
         :area :stt
         :editing nil
         :form nil
         :pending nil
         :notice nil
         :tests {}}))

(defonce settings-listeners (atom #{}))

(defn- set-settings!
  [f & args]
  (let [value (apply swap! settings-state f args)]
    (notify! settings-listeners value)
    value))

(defn- refresh-settings! []
  (set-settings! assoc :loading? true)
  (api/snapshot!
   (fn [result]
     (set-settings! assoc :loading? false)
     (if (:ok? result)
       (set-settings! assoc :snapshot (get-in result [:snapshot :speech]) :notice nil)
       (set-settings! assoc :notice {:tone :error
                                     :text (or (get-in result [:error :message])
                                               "无法读取语音设置")})))))

(defn- empty-service [area]
  {:id "" :label "" :kind :openai-compatible :base-url "" :model ""
   :api-key-env "" :api-key "" :processing :cloud :enabled? true :order 0
   :transcriptions-path (when (= area :stt) "/v1/audio/transcriptions")
   :speech-path (when (= area :tts) "/v1/audio/speech")
   :chat-path (when (= area :translation) "/v1/chat/completions")
   :voice (when (= area :tts) "") :format (when (= area :tts) "wav")
   :speed (when (= area :tts) 1.0)})

(defn- service-field [label value on-change & [opts]]
  (let [opts (or opts {})]
    (dom/label {:style (style {:display "flex" :flexDirection "column" :gap "2px"})}
               label
               (dom/input (merge {:value (or value "")
                                  :onChange (fn [event] (on-change (.-value (.-target event))))
                                  :style (style {:minHeight "34px"})}
                                 (when (:type opts) {:type (:type opts)}))))))

(defn- start-add! []
  (let [area (:area @settings-state)]
    (set-settings! assoc :form (empty-service area) :editing :new)))

(defn- start-edit! [service]
  (let [area (:area @settings-state)]
    (set-settings! assoc
                   :form (merge (empty-service area)
                                (dissoc service :status :has-key? :key-source
                                        :test-audio-configured?))
                   :editing (:id service))))

(defn- close-editor! []
  (set-settings! assoc :editing nil :form nil))

(defn- note-submission-failure!
  "A command can fail before any receipt exists.  Clear the pending marker,
  show the reason, and leave the form untouched."
  [submitted fallback]
  (when-let [patch (settings-model/submission-failure @settings-state
                                                      submitted fallback)]
    (set-settings! merge patch)
    true))

(defn- save-service! []
  (let [{:keys [area form snapshot]} @settings-state
        service (dissoc form :api-key)
        service (cond-> service
                  (not (str/blank? (:api-key form)))
                  (assoc :api-key (:api-key form)))]
    (set-settings! assoc :pending :save)
    (let [submitted
          (api/save-service!
           area service (or (:revision snapshot) 0)
           (fn [receipt]
             (set-settings! assoc :pending nil)
             (if (:ok? receipt)
               (do (set-settings! assoc :notice {:tone :info :text "语音服务已保存。"})
                   (close-editor!)
                   (refresh-settings!))
               (set-settings! assoc :notice
                              {:tone :error
                               :text (or (get-in receipt [:error :message])
                                         "语音服务未保存。")}))))]
      (note-submission-failure! submitted "语音服务未保存。"))))

(defn- delete-service! [service]
  (let [{:keys [area snapshot]} @settings-state]
    (set-settings! assoc :pending :delete)
    (let [submitted
          (api/delete-service!
           area (:id service) (or (:revision snapshot) 0)
           (fn [receipt]
             (set-settings! assoc :pending nil)
             (if (:ok? receipt)
               (do (set-settings! assoc :notice {:tone :info
                                                 :text "服务已删除；历史录音与转写不受影响。"})
                   (refresh-settings!))
               (set-settings! assoc :notice
                              {:tone :error
                               :text (or (get-in receipt [:error :message])
                                         "服务未删除。")}))))]
      (note-submission-failure! submitted "服务未删除。"))))

(defn- make-default! [service]
  (let [{:keys [area snapshot]} @settings-state]
    (set-settings! assoc :pending :default)
    (let [submitted
          (api/set-default!
           area (:id service) (or (:revision snapshot) 0)
           (fn [receipt]
             (set-settings! assoc :pending nil)
             (if (:ok? receipt)
               (refresh-settings!)
               (set-settings! assoc :notice
                              {:tone :error
                               :text (or (get-in receipt [:error :message])
                                         "默认服务未更新。")}))))]
      (note-submission-failure! submitted "默认服务未更新。"))))

(defn- toggle-service! [service]
  (let [{:keys [area snapshot]} @settings-state
        service (assoc service :enabled? (not (:enabled? service)))]
    (set-settings! assoc :pending :toggle)
    (let [submitted
          (api/save-service!
           area service (or (:revision snapshot) 0)
           (fn [receipt]
             (set-settings! assoc :pending nil)
             (if (:ok? receipt)
               (refresh-settings!)
               (set-settings! assoc :notice
                              {:tone :error
                               :text (or (get-in receipt [:error :message])
                                         "服务状态未更新。")}))))]
      (note-submission-failure! submitted "服务状态未更新。"))))

(defn- test-service! [service]
  (let [area (:area @settings-state)]
    (set-settings! assoc :pending [:test (:id service)])
    (let [submitted
          (api/test-service!
           area (:id service)
           (fn [receipt]
             (set-settings! assoc :pending nil)
             (if (:ok? receipt)
               (set-settings! update :tests assoc (:id service)
                             (get-in receipt [:receipt :layers]))
               (set-settings! update :tests assoc (:id service)
                             {:error (:error receipt)}))))]
      (note-submission-failure! submitted "测试命令未发送。"))))

(defn- reorder-service! [service direction]
  (let [{:keys [area snapshot]} @settings-state
        ids (mapv :id (get-in snapshot [area :services]))
        index (.indexOf (to-array ids) (:id service))
        target (+ index direction)]
    (when (and (>= index 0)
               (>= target 0)
               (< target (count ids)))
      (let [ids (assoc ids index (nth ids target)
                       target (nth ids index))]
        (set-settings! assoc :pending :reorder)
        (let [submitted
              (api/reorder-services!
               area ids (or (:revision snapshot) 0)
               (fn [receipt]
                 (set-settings! assoc :pending nil)
                 (if (:ok? receipt)
                   (refresh-settings!)
                   (set-settings! assoc :notice
                                  {:tone :error
                                   :text (or (get-in receipt [:error :message])
                                             "排序未更新。")}))))]
          (note-submission-failure! submitted "排序未更新。"))))))

(defn- update-form! [key value]
  (set-settings! update :form assoc key value))

(defn- test-layer-label
  [layer]
  (case layer
    :reachable "地址可达"
    :auth-ok "身份可用"
    :transcript "转写"
    :test-audio "测试音频"
    :audio "音频合成"
    :translated "翻译"
    (name layer)))

(defn- test-status-label
  [status]
  (case (keyword status)
    :ok "通过"
    :failed "失败"
    :not-run "未运行"
    :empty "没有内容"
    :partial "部分完成"
    (str (or status "未提供"))))

(defn- test-layer-tone
  [status]
  (case (keyword status)
    :ok "var(--wb-ok)"
    :failed "var(--wb-danger)"
    :partial "var(--wb-warn)"
    :empty "var(--wb-warn)"
    "var(--wb-ink-muted)"))

(defn- test-layers-view
  [layers]
  (dom/div {:role "status"
            :style (style {:display "flex" :flexDirection "column" :gap "3px"
                           :fontSize ".82rem"})}
           (dom/strong {} "测试结果")
           (for [[layer value] layers]
             (let [status (:status value)
                   detail (or (:reason value)
                              (get-in value [:error :message])
                              (when-let [text (:text value)]
                                (let [text (str text)]
                                  (when-not (str/blank? text)
                                    (if (> (count text) 120)
                                      (str (subs text 0 117) "...")
                                      text))))
                              (when (:bytes value)
                                (str (:bytes value) " 字节")))]
               (dom/div {:key (name layer)}
                        (dom/span {} (str (test-layer-label layer) "："))
                        (dom/span {:style (style {:color (test-layer-tone status)
                                                  :fontWeight 600})}
                                  (test-status-label status))
                        (when detail
                          (dom/span {:style (style {:color "var(--wb-ink-muted)"})}
                                    (str " · " detail))))))))

(defn settings-element
  []
  (let [[state set-state!] (react/useState @settings-state)]
    (react/useEffect
     (fn []
       (let [unsubscribe (subscribe! settings-listeners set-state!)]
         (refresh-settings!)
         unsubscribe))
     #js [])
    (let [{:keys [snapshot loading? area editing form pending notice tests]} state
          services (vec (get-in snapshot [area :services]))]
      (dom/div {:style (style {:display "flex" :flexDirection "column" :gap "10px"})}
               (dom/div {:className "wb-tabs" :role "tablist" :aria-label "语音与朗读"}
                        (for [[id label] [[:stt "语音输入"] [:tts "朗读"] [:translation "翻译"]]]
                          (dom/button {:type "button" :role "tab" :key (name id)
                                       :className "wb-tab"
                                       :aria-selected (if (= area id) "true" "false")
                                       :onClick (fn [_] (set-settings! assoc :area id) (close-editor!))}
                                      label)))
               (when notice
                 (dom/div {:role (if (= :error (:tone notice)) "alert" "status")
                           :style (style {:padding "8px" :borderRadius "8px"
                                          :background (if (= :error (:tone notice))
                                                        "color-mix(in srgb, var(--wb-danger) 14%, transparent)"
                                                        "var(--wb-surface-2)")
                                          :color (if (= :error (:tone notice))
                                                   "var(--wb-danger)"
                                                   "var(--wb-ink)")})}
                          (:text notice)))
               (if loading?
                 (dom/p {} "正在读取语音设置…")
                 (fragment
                   (dom/div {:style (style {:display "flex" :gap "8px" :flexWrap "wrap"})}
                            (button "添加服务" start-add!))
                   (when (empty? services)
                     (dom/p {:className "hc-muted"}
                            "尚未配置服务。语音按钮会提示先完成服务设置；不会假装可用。"))
                   (for [service services]
                     (dom/div {:key (:id service)
                               :style (style {:border "1px solid var(--wb-line)" :borderRadius "10px"
                                              :padding "8px" :display "flex"
                                              :flexDirection "column" :gap "4px"
                                              :overflowWrap "anywhere"})}
                              (dom/strong {} (input-model/service-summary service))
                              (dom/span {:style (style {:fontSize ".82rem" :color "var(--wb-ink-muted)"})}
                                        (str (:base-url service) " · " (:model service)
                                             (when (= area :tts) (str " · 音色 " (:voice service)))
                                             (when (= area :translation) (str " · " (:chat-path service)))))
                              (when-let [layers (get tests (:id service))]
                                (if (:error layers)
                                  (dom/div {:role "alert"
                                            :style (style {:fontSize ".82rem"
                                                           :color "var(--wb-danger)"})}
                                           (str "测试失败："
                                                (or (get-in layers [:error :message])
                                                    "没有返回原因")))
                                  (test-layers-view layers)))
                              (dom/div {:style (style {:display "flex" :gap "6px" :flexWrap "wrap"})}
                                       (button "测试" #(test-service! service)
                                               {:disabled? (= pending [:test (:id service)])})
                                       (button "编辑" #(start-edit! service))
                                       (dom/details
                                        {:className "hc-speech-service-more"}
                                        (dom/summary
                                         {:aria-label (str "更多操作："
                                                           (input-model/service-summary service))
                                          :style (style {:minHeight "36px"
                                                         :minWidth "36px"
                                                         :display "inline-flex"
                                                         :alignItems "center"
                                                         :padding "4px 10px"
                                                         :cursor "pointer"
                                                         :border "1px solid var(--wb-line)"
                                                         :borderRadius "8px"})}
                                         "更多")
                                        (dom/div
                                         {:style (style {:display "flex"
                                                         :gap "6px"
                                                         :flexWrap "wrap"
                                                         :marginTop "4px"})}
                                         (when-not (= (:id service)
                                                      (get-in snapshot [area :default-service-id]))
                                           (button "设为默认" #(make-default! service)))
                                         (button (if (:enabled? service) "禁用" "启用")
                                                 #(toggle-service! service))
                                         (button "上移" #(reorder-service! service -1))
                                         (button "下移" #(reorder-service! service 1))
                                         (button "删除" #(delete-service! service)))))))))
               (when form
                 (dom/div {:style (style {:borderTop "1px solid var(--wb-line)" :paddingTop "10px"
                                          :display "flex" :flexDirection "column" :gap "6px"})}
                          (dom/h3 {} (if (= :new editing) "添加服务" "编辑服务"))
                          (service-field "ID" (:id form) #(update-form! :id %))
                          (service-field "显示名" (:label form) #(update-form! :label %))
                          (service-field "端点" (:base-url form) #(update-form! :base-url %))
                          (service-field "模型" (:model form) #(update-form! :model %))
                          (dom/label {:style (style {:display "flex" :flexDirection "column" :gap "2px"})}
                                     "处理位置"
                                     (dom/select {:value (name (or (:processing form) :cloud))
                                                  :onChange (fn [event]
                                                              (update-form!
                                                               :processing
                                                               (keyword (.-value (.-target event)))))
                                                  :style (style {:minHeight "34px"})}
                                                 (dom/option {:value "device"} "设备内（仅回环端点）")
                                                 (dom/option {:value "cloud"} "云端")))
                          (when (= area :stt)
                            (fragment
                              (service-field "转写路径" (:transcriptions-path form)
                                             #(update-form! :transcriptions-path %))
                              (service-field "测试音频路径" (:test-audio-path form)
                                             #(update-form! :test-audio-path %))))
                          (when (= area :tts)
                            (fragment
                              (service-field "朗读路径" (:speech-path form)
                                             #(update-form! :speech-path %))
                              (service-field "音色" (:voice form)
                                             #(update-form! :voice %))
                              (service-field "格式" (:format form)
                                             #(update-form! :format %))))
                          (when (= area :translation)
                            (service-field "翻译路径" (:chat-path form)
                                           #(update-form! :chat-path %)))
                          (service-field "密钥环境变量" (:api-key-env form)
                                         #(update-form! :api-key-env %))
                          (service-field "本次密钥（不落盘）" (:api-key form)
                                         #(update-form! :api-key %) {:type "password"})
                          (dom/label {:style (style {:display "flex" :gap "6px"
                                                     :alignItems "center"
                                                     :minHeight "44px"})}
                                     (dom/input {:type "checkbox"
                                                 :checked (boolean (:enabled? form))
                                                 :onChange (fn [event]
                                                             (update-form! :enabled?
                                                                           (.-checked (.-target event))))})
                                     "启用")
                          (dom/div {:style (style {:display "flex" :gap "6px"})}
                                   (button (if (= :save pending) "正在保存…" "保存")
                                           save-service!
                                           {:disabled? (= :save pending)})
                                   (button "取消" close-editor!))))))))

;; ---------------------------------------------------------------------------
;; Translation

(defn- prop-value
  [props k]
  (when props
    (if (map? props)
      (get props k)
      (aget props (name k)))))

(defn- normalize-selection
  [value draft-text]
  (let [source (str (or draft-text ""))
        n (count source)
        start (long (or (prop-value value :start) 0))
        end (long (or (prop-value value :end) start))
        start (max 0 (min n start))
        end (max start (min n end))
        provided (str (or (prop-value value :text) ""))]
    {:start start
     :end end
     :text (if (< start end) (subs source start end) provided)
     :draft-version (prop-value value :draft-version)}))

(defn- normalize-results
  [value]
  (cond
    (map? value) value
    (some? value) (js->clj value :keywordize-keys true)
    :else {}))

(defn- translation-panel
  [open? source selection message-text target set-target! services-state status
   translate! results error on-insert on-replace]
  (when open?
    (let [result (translation-model/result-for results target)
          plan (when result
                 (translation-model/replace-plan
                  {:source source :selection selection :result result}))
          replace-request (when (and (fn? on-replace)
                                     (= :replace (:action plan)))
                            (translation-model/replace-request
                             source selection result))
          fragment? (< (:start selection) (:end selection))
          shown-text (if fragment?
                       (subs source (:start selection) (:end selection))
                       message-text)]
      (dom/div {:style (style {:border "1px solid var(--wb-line)"
                               :borderRadius "10px"
                               :padding "8px"
                               :marginTop "4px"
                               :display "flex"
                               :flexDirection "column"
                               :gap "6px"})}
        (dom/div {:style (style {:fontSize ".85rem" :color "var(--wb-ink-muted)"})}
                 "原文（不会被覆盖）")
        (when fragment?
          (dom/div {:style (style {:fontSize ".78rem" :color "var(--wb-ink-muted)"})}
                   "来自草稿选区"))
        (if (= services-state :missing)
          (status-line
           "没有可用的翻译服务（未配置或无法读取）；请到设置→语音与朗读添加并测试。"
           :warn)
          nil)
        (dom/pre {:style (style {:whiteSpace "pre-wrap"
                                 :maxHeight "120px"
                                 :overflow "auto"
                                 :margin 0})}
                 shown-text)
        (dom/div {:style (style {:display "flex" :gap "6px" :flexWrap "wrap"})}
          (dom/label {} "目标语言"
            (dom/select {:value target
                         :onChange (fn [event]
                                     (set-target! (.-value (.-target event))))
                         :style (style {:minHeight 32})}
              (dom/option {:value "zh-CN"} "zh-CN")
              (dom/option {:value "en"} "en")
              (dom/option {:value "ja"} "ja")
              (dom/option {:value "ko"} "ko")
              (dom/option {:value "fr"} "fr")
              (dom/option {:value "de"} "de")
              (dom/option {:value "es"} "es")))
          (button (if (= status :loading) "正在翻译…" "翻译")
                  translate!
                  {:disabled? (or (= status :loading)
                                  (not= services-state :ready))}))
        (if result
          (dom/div {:role "status"
                    :style (style {:display "flex"
                                   :flexDirection "column"
                                   :gap "4px"})}
            (dom/pre {:style (style {:whiteSpace "pre-wrap" :margin 0})}
                     (:translated-text result))
            (dom/span {:style (style {:fontSize ".8rem" :color "var(--wb-ink-muted)"})}
                      (str "服务 " (get-in result [:service :id])
                           " · " (:source-language result)
                           " → " (:target-language result)))
            (dom/div {:style (style {:display "flex" :gap "6px"})}
              (button "复制译文"
                      #(when (exists? js/navigator.clipboard)
                         (.writeText (.-clipboard js/navigator)
                                     (:translated-text result))))
              (button "插入草稿"
                      #(when (fn? on-insert)
                         (on-insert (:translated-text result))))
              (when replace-request
                (button "替换当前选区"
                        #(when (fn? on-replace)
                           (on-replace replace-request)))))
            (when (and (not replace-request) (:message plan))
              (status-line (:message plan) :warn)))
          nil)
        (if error
          (status-line (str "翻译失败：" error
                            "。原文与语言选择已保留，可重试。")
                       :error)
          nil)))))

(defn translation-controls
  [props]
  (let [message-id (or (prop-value props :messageId)
                       (prop-value props :message-id))
        version-id (or (prop-value props :versionId)
                       (prop-value props :version-id))
        message-text (str (or (prop-value props :text) ""))
        draft-text (str (or (prop-value props :draftText)
                            (prop-value props :draft-text)
                            (prop-value props :draft_text)
                            ""))
        selection (normalize-selection (prop-value props :selection) draft-text)
        fragment? (< (:start selection) (:end selection))
        translate-text (if fragment?
                         (subs draft-text (:start selection) (:end selection))
                         message-text)
        on-insert (or (prop-value props :onInsert)
                      (prop-value props :on-insert))
        on-replace (or (prop-value props :onReplace)
                       (prop-value props :on-replace))
        [open? set-open!]
        (react/useState
         (boolean (or (prop-value props :initialOpen)
                      (prop-value props :initial-open))))
        [target set-target!]
        (react/useState
         (if (and (exists? js/navigator)
                  (str/starts-with? (str/lower-case
                                     (or (.-language js/navigator) ""))
                                    "zh"))
           "zh-CN"
           "en"))
        [status set-status!] (react/useState :idle)
        [results set-results!]
        (react/useState (normalize-results (prop-value props :initialResults)))
        [error set-error!] (react/useState nil)
        [services-state set-services-state!] (react/useState :loading)
        translate! (fn []
                     (set-status! :loading)
                     (set-error! nil)
                     (api/translate!
                      {:text translate-text
                       :source-language "auto"
                       :target-language target
                       :message-id message-id
                       :version-id version-id}
                      (fn [result]
                        (if (:ok? result)
                          (let [receipt (assoc (:receipt result)
                                               :fragment? fragment?
                                               :source-at-request draft-text
                                               :request-start (:start selection)
                                               :request-end (:end selection)
                                               :request-text (when fragment?
                                                               translate-text))]
                            (set-results!
                             #(translation-model/remember-result
                               % target receipt))
                            (set-status! :ready))
                          (do
                            (set-status! :error)
                            (set-error!
                             (get-in result [:error :message])))))))]
    (react/useEffect
     (fn []
       (api/snapshot!
        (fn [result]
          (set-services-state!
           (if (and (:ok? result)
                    (seq (filter :enabled?
                                 (get-in result
                                         [:snapshot :speech :translation
                                          :services]))))
             :ready
             :missing))))
       (fn [] nil))
     #js [])
    (dom/div {:style (style {:display "inline-flex"
                             :flexDirection "column"
                             :gap "4px"})}
             (button "翻译" #(set-open! (not open?)))
             (translation-panel open? draft-text selection message-text target
                                set-target! services-state status translate!
                                results error on-insert on-replace))))
