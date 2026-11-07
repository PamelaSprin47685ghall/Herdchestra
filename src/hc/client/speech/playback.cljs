(ns hc.client.speech.playback
  "Read-aloud playback state and controls.

  One audio element is active at a time.  Starting another message stops the
  previous audio; switching the visible message version invalidates the
  player.  The server prepares the text, so code blocks, URLs and large
  tables are already skipped before synthesis."
  (:require ["react" :as react]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.speech.api :as api]
            [hc.client.speech.playback-model :as playback-model]))

(defonce state
  (atom {:status :idle
         :speed 1.0
         :generation 0
         :audio nil
         :url nil
         :controller nil}))

(defonce request-seq (atom 0))

(defn- next-request!
  []
  (swap! request-seq playback-model/next-generation))

(defonce listeners
  (atom #{}))

(defn- notify!
  []
  (doseq [listener @listeners]
    (try (listener @state) (catch :default _ nil))))

(defn- set-state!
  [value]
  (reset! state value)
  (notify!)
  value)

(defn subscribe!
  [listener]
  (swap! listeners conj listener)
  (fn [] (swap! listeners disj listener)))

(defn- revoke!
  [url]
  (when (and url (exists? js/URL))
    (try (.revokeObjectURL js/URL url) (catch :default _ nil))))

(defn- release-audio!
  "Detach the decoded media from the element before dropping it.

  Pausing alone keeps the source attached; a large TTS response (up to the
  configured 50 MiB cap) can stay referenced until the element is collected.
  Removing the source and calling `load` asks the browser to release the
  decoder and buffers immediately."
  [audio]
  (when audio
    (try
      (.pause audio)
      (set! (.-currentTime audio) 0)
      (.removeAttribute audio "src")
      (.load audio)
      (catch :default _ nil))))

(defn stop!
  []
  (next-request!)
  (let [{:keys [audio url controller]} @state]
    ;; Abort an in-flight TTS fetch before dropping the player.  Without
    ;; this, a large response (up to the configured 50 MiB cap) keeps
    ;; downloading into a Blob that will only be revoked after the fetch
    ;; resolves.
    (when controller
      (try (.abort controller) (catch :default _ nil)))
    (release-audio! audio)
    (revoke! url)
    (set-state! {:status :idle
                 :speed (:speed @state 1.0)
                 :generation @request-seq
                 :audio nil
                 :url nil
                 :controller nil})))

(defn pause!
  []
  (let [{:keys [audio status]} @state]
    (when (and audio (= status :playing))
      (try (.pause audio) (catch :default _ nil))
      (set-state! (assoc @state :status :paused)))))

(defn resume!
  []
  (let [{:keys [audio status]} @state]
    (when (and audio (= status :paused))
      (try (.play audio) (catch :default _ nil))
      (set-state! (assoc @state :status :playing)))))

(defn set-speed!
  [speed]
  (let [speed (max 0.5 (min 2.0 (double (or speed 1.0))))
        {:keys [audio]} @state]
    (when audio
      (set! (.-playbackRate audio) speed))
    (set-state! (assoc @state :speed speed))))

(defn play!
  "Fetch and play TEXT for one message version.  A new request stops the old
  audio first, including when the old request is still loading."
  [text message-id version-id]
  (stop!)
  (let [generation (next-request!)
        controller (when (exists? js/AbortController) (js/AbortController.))
        request {:generation generation
                 :message-id message-id
                 :version-id version-id}]
    (set-state! {:status :loading
                 :generation generation
                 :message-id message-id
                 :version-id version-id
                 :speed (:speed @state 1.0)
                 :audio nil
                 :url nil
                 :controller controller})
    (api/synthesize!
     text
     (cond-> {:message-id message-id :version-id version-id
              :speed (:speed @state 1.0)}
       controller (assoc :signal (.-signal controller)))
     (fn [result]
       (let [url (when (:ok? result)
                   (.createObjectURL js/URL (:blob result)))]
         (if-not (playback-model/request-current? @state request)
           (revoke! url)
           (if-not (:ok? result)
             (let [error (:error result)
                   code (:code error)
                   error (cond-> error
                           (contains? #{:SPEECH_SERVICE_NOT_CONFIGURED
                                        :SPEECH_SERVICE_REQUIRED
                                        :SPEECH_SERVICE_DISABLED}
                                      code)
                           (assoc :message
                                  "没有可用的朗读服务（未配置或无法读取）；请到设置→语音与朗读添加并测试。"))]
               (set-state! (assoc @state
                                  :status :error
                                  :error error)))
             (let [audio (js/Audio. url)
                   headers (:headers result)
                   partial? (= "true" (get headers "x-hc-partial"))]
               (set! (.-playbackRate audio) (:speed @state 1.0))
               (.addEventListener audio "ended"
                                  (fn [_]
                                    (when (identical? audio (:audio @state))
                                      (stop!))))
               (try (.play audio) (catch :default _ nil))
               (set-state! (assoc @state
                                  :status :playing
                                  :audio audio
                                  :url url
                                  :audio-id (get headers "x-hc-audio-id")
                                  :service-id (get headers "x-hc-service-id")
                                  :format (get headers "x-hc-format")
                                  :voice (get headers "x-hc-voice")
                                  :partial? partial?
                                  :error nil))))))))))

(defn stop-if-version-changed!
  "Stop when the same message re-renders with a different visible version.

  The message surface already calls `stop-if-stale!` with the whole list, but
  that effect depends on the list identity.  The read-aloud button also sees
  its own `versionId` prop change, which is a direct signal: compare it with
  the version stored by the player and stop the old audio."
  [message-id version-id]
  (let [{:keys [status] :as current} @state
        playing-message (:message-id current)
        playing-version (:version-id current)]
    (when (and (contains? #{:playing :paused :loading} status)
               playing-message
               (= (str playing-message) (str message-id))
               (not= (str playing-version) (str version-id)))
      (stop!))))

(defn stop-if-stale!
  "Stop when the playing message version is no longer current."
  [messages]
  (let [{:keys [message-id version-id status]} @state]
    (when (and (contains? #{:playing :paused :loading} status)
               message-id)
      (let [message (some #(when (= message-id (:id %)) %) messages)]
        (when (or (nil? message)
                  (and version-id
                       (not= version-id (:current-version message))))
          (stop!))))))

(defn- control-button
  [label on-click disabled?]
  (dom/button {:className "hc-button"
               :type "button"
               :disabled (boolean disabled?)
               :style #js {:minHeight 32 :minWidth 32}
               :onClick (fn [event]
                          (.preventDefault event)
                          (on-click))}
              label))

(defn player-element
  "Mini player.  Renders nothing while idle."
  []
  (let [[current set-current!] (react/useState @state)]
    (react/useEffect
     (fn []
       (let [unsubscribe (subscribe! set-current!)]
         (fn [] (unsubscribe))))
     #js [])
    (if (= :idle (:status current))
      nil
      (dom/div {:className "hc-speech-player"
                :role "region"
                :aria-label "朗读播放器"
                :style #js {:position "sticky"
                            :bottom 0
                            :zIndex 7
                            :display "flex"
                            :alignItems "center"
                            :gap "8px"
                            :flexWrap "wrap"
                            :padding "8px 12px"
                            :borderTop "1px solid var(--wb-line)"
                            :background "var(--wb-surface)"
                            :color "var(--wb-ink)"}}
               (dom/span {:role "status"}
                         (case (:status current)
                           :loading "正在生成朗读…"
                           :playing "正在朗读"
                           :paused "朗读已暂停"
                           :error "朗读失败"
                           "朗读"))
               (when-let [error (get-in current [:error :message])]
                 (dom/span {:style #js {:color "var(--wb-danger)"}} error))
               (when (:partial? current)
                 (dom/span {:style #js {:color "var(--wb-warn)"}}
                           "服务只返回了部分音频，已保留可播放部分"))
               (when-let [service (:service-id current)]
                 (dom/span {:style #js {:fontSize ".8rem" :color "var(--wb-ink-muted)"}}
                           (str "服务 " service
                                (when-let [voice (:voice current)]
                                  (str " · 音色 " voice))
                                (when-let [format (:format current)]
                                  (str " · " format)))))
               (when (= :playing (:status current))
                 (control-button "暂停" pause! false))
               (when (= :paused (:status current))
                 (control-button "继续" resume! false))
               (control-button "停止" stop! false)
               (dom/label {:style #js {:fontSize ".8rem" :display "flex"
                                        :alignItems "center" :gap "4px"}}
                          "速度"
                          (dom/select {:value (str (:speed current))
                                       :onChange (fn [event]
                                                   (set-speed!
                                                    (.-value (.-target event))))
                                       :style #js {:minHeight 32}}
                                      (for [speed ["0.5" "0.75" "1" "1.25" "1.5" "2"]]
                                        (dom/option {:value speed :key speed} speed))))
               (when-let [audio-id (:audio-id current)]
                 (dom/a {:href (api/audio-url audio-id)
                         :download (str audio-id ".audio")
                         :style #js {:fontSize ".8rem"}}
                        "下载音频"))))))
