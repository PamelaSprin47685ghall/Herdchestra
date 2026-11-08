(ns hc.client.speech.ui-test
  "Real React render tests for the speech message translation panel.

  The panel is opened by default here so the result lookup runs during
  server rendering.  That is the path that previously dereferenced a
  useState map and threw before any network request could start."
  (:require ["react" :as react]
            ["react-dom/server" :as rds]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.speech.ui :as speech-ui]))

(defn- render
  [props]
  (rds/renderToStaticMarkup
   (react/createElement speech-ui/translation-controls props)))

(deftest translation-panel-renders-without-dereferencing-a-map
  (testing "an open panel renders the original text and controls"
    (let [markup (render #js {:messageId "m1"
                              :versionId "v1"
                              :text "Hello world"
                              :initialOpen true})]
      (is (str/includes? markup "原文（不会被覆盖）"))
      (is (str/includes? markup "Hello world"))
      (is (str/includes? markup "目标语言"))
      (is (str/includes? markup "翻译"))))
  (testing "the panel stays closed until the operator asks for it"
    (let [markup (render #js {:messageId "m1"
                              :versionId "v1"
                              :text "Hello world"})]
      (is (not (str/includes? markup "原文（不会被覆盖）"))))))


(def ^:private translation-result
  {:translated-text "译文"
   :fragment? true
   :source-at-request "Hello world!"
   :request-start 6
   :request-end 11
   :request-text "world"
   :source-language "en"
   :target-language "en"
   :service {:id "svc"}})

(defn- translation-props
  [selection]
  #js {:messageId "m1"
       :versionId "v1"
       :text "Hello world!"
       :draftText "Hello world!"
       :selection (clj->js selection)
       :initialOpen true
       :initialResults {"en" translation-result
                        "zh-CN" translation-result}
       :onReplace (fn [_] nil)})

(deftest translation-panel-offers-replace-only-for-the-requested-selection
  (testing "the current selection can replace exactly the requested fragment"
    (let [markup (render (translation-props {:start 6 :end 11 :text "world"}))]
      (is (str/includes? markup "来自草稿选区"))
      (is (str/includes? markup "替换当前选区"))
      (is (not (str/includes? markup "选区已变化")))))
  (testing "a moved selection falls back to copy/insert"
    (let [markup (render (translation-props {:start 0 :end 5 :text "Hello"}))]
      (is (not (str/includes? markup "替换当前选区")))
      (is (str/includes? markup "选区已变化"))))
  (testing "a whole-message translation has no replace target"
    (let [markup (render #js {:messageId "m1"
                              :versionId "v1"
                              :text "Hello world!"
                              :draftText "Hello world!"
                              :selection (clj->js {:start 0 :end 0 :text ""})
                              :initialOpen true
                              :initialResults {}
                              :onReplace (fn [_] nil)})]
      (is (not (str/includes? markup "替换当前选区"))))))

;; ---------------------------------------------------------------------------
;; Voice input recording handlers
;;
;; Regression for the render-time side effect: the main mic button's onClick
;; used to be an IIFE (`(stop-recording!)` evaluated while rendering), so the
;; render that entered :recording called recorder.stop() before any chunk
;; existed and the upload produced the "没有录到声音" empty-blob failure.
;; These tests mount the real input element in jsdom with fake media devices
;; and prove that rendering never stops the recorder and that the click
;; handler does.

(defn- act!
  [f]
  ((.-act react) f))

(defn- setup-dom!
  []
  (if (and (exists? js/window) (exists? js/document))
    nil
    (let [jsdom (js/require "jsdom")
          dom (new (.-JSDOM jsdom) "<!doctype html><html><body></body></html>"
                   #js {:url "http://localhost/"})
          window (.-window dom)]
      (set! js/global.window window)
      (set! js/global.document (.-document window))
      (set! js/global.navigator (.-navigator window))
      (set! js/global.HTMLElement (.-HTMLElement window))
      (set! js/global.Node (.-Node window))
      (set! js/global.Event (.-Event window))
      (set! js/global.MouseEvent (.-MouseEvent window))
      (set! js/global.getComputedStyle (.-getComputedStyle window))
      (set! js/global.requestAnimationFrame (fn [f] (js/setTimeout f 0)))
      (set! js/global.cancelAnimationFrame (fn [id] (js/clearTimeout id)))
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/global) true)
      dom)))

(defn- wait-for-phase!
  "Resolve true once input-state reaches `phase`, false after 5 s.
  Polls on real timers so promise continuations and React state updates
  get event-loop turns; act is not needed for read-only polling."
  [phase]
  (let [deadline (+ (js/Date.now) 5000)]
    (js/Promise.
     (fn [resolve _reject]
       (let [poll (fn poll []
                    (cond
                      (= phase (:phase @speech-ui/input-state))
                      (resolve true)

                      (> (js/Date.now) deadline)
                      (resolve false)

                      :else
                      (js/setTimeout poll 10)))]
         (poll))))))

(defn- install-fake-media!
  []
  ;; jsdom's navigator has no mediaDevices; define the chain so the
  ;; component's `js/navigator.mediaDevices` lookup sees a real object.
  (let [fake-track (js-obj "stop" (fn [] nil)
                          "addEventListener" (fn [& _args] nil))
        fake-stream (js-obj
                     "getTracks" (fn [] #js [fake-track])
                     "getAudioTracks" (fn [] #js [fake-track]))
        media-devices (js-obj
                       "getUserMedia"
                       (fn [_constraints]
                         (js/Promise.resolve fake-stream)))]
    (js/Reflect.set js/navigator "mediaDevices" media-devices))
  ;; Fake MediaRecorder: `(js/MediaRecorder. stream)` constructs a plain
  ;; object whose .start/.stop mirror the real async lifecycle — start
  ;; reports one non-empty data chunk, and stop fires onstop on the next
  ;; tick exactly like a real browser recorder.
  (let [recorder-stops (atom 0)
        fake-chunk (js/Blob. #js [(js/Uint8Array. 8)]
                            (js-obj "type" "audio/webm"))
        recorder-proto
        (js-obj "state" "inactive"
                "ondataavailable" nil
                "onstop" nil
                "onerror" nil
                "mimeType" "audio/webm"
                "start"
                (fn []
                  (this-as self
                    (set! (.-state self) "recording")
                    (when-let [cb (.-ondataavailable self)]
                      (cb (js-obj "data" fake-chunk)))))
                "stop"
                (fn []
                  (this-as self
                    (swap! recorder-stops inc)
                    (set! (.-state self) "inactive")
                    ;; A real recorder delivers onstop after the final
                    ;; dataavailable; delay it past the app's 250 ms
                    ;; duration timer so :duration is non-zero and the
                    ;; upload path runs instead of the empty-recording
                    ;; failure branch.
                    (js/setTimeout
                     (fn []
                       (when-let [onstop (.-onstop self)]
                         (onstop (js-obj))))
                     300))))]
    (let [FakeMediaRecorder (fn [stream-arg]
                              (let [self (js/Object.create recorder-proto)]
                                (set! (.-state self) "inactive")
                                (set! (.-stream self) stream-arg)
                                (set! (.-ondataavailable self) nil)
                                (set! (.-onstop self) nil)
                                (set! (.-onerror self) nil)
                                (set! (.-mimeType self) "audio/webm")
                                self))]
      (set! (.-prototype FakeMediaRecorder) recorder-proto)
      (set! (.-isTypeSupported FakeMediaRecorder) (fn [& _args] true))
      (set! (.-mimeType FakeMediaRecorder) "audio/webm")
      (set! (.-MediaRecorder js/globalThis) FakeMediaRecorder)
      (set! (.-__hcMediaStops js/globalThis) recorder-stops)
      ;; Keep the upload deterministic: the app's fetch must not leave jsdom.
      (set! (.-fetch js/globalThis)
               (fn [& _args]
                 (js/Promise.resolve
                  (js-obj "status" 503
                          "text" (fn [] (js/Promise.resolve "{}"))))))
      nil)))

(defn- mount-input!
  []
  (let [container (.createElement js/document "div")]
    (.appendChild js/document.body container)
    (let [root (rdc/createRoot container)]
      (act!
       (fn []
         (.render root
                  (react/createElement speech-ui/input-element
                                       #js {:sessionId "s1"
                                            :draft #js {:text "草稿"
                                                        :cursor 2}}))))
      {:root root :container container})))

(defn- unmount-input!
  [{:keys [root container]}]
  (act! (fn [] (.unmount root)))
  (when container
    (.removeChild js/document.body container)))

(defn- seed-services!
  []
  (speech-ui/set-input!
   assoc :phase :idle :services-loaded? true
         :services [{:id "svc-1" :label "云端" :kind :openai-compatible
                     :base-url "http://127.0.0.1:1" :model "m"
                     :processing :cloud :enabled? true :order 0
                     :transcriptions-path "/v1/audio/transcriptions"}]
         :default-service-id "svc-1"
         :explained? true))

(defn- cleanup-mounted!
  [mounted done]
  (unmount-input! mounted)
  (speech-ui/reset-input!)
  (speech-ui/set-input! assoc :services [] :services-loaded? false)
  (done))

(deftest recording-button-never-stops-the-recorder-during-render
  (async done
    (setup-dom!)
    (install-fake-media!)
    (seed-services!)
    (let [mounted (mount-input!)]
      (do
        ;; First click: idle -> start-recording! -> :explain is bypassed
        ;; because :explained? is seeded true; the chain ends in :requesting
        ;; then :recording once getUserMedia resolves.
        (act!
         (fn []
           (.click (.querySelector (:container mounted)
                                   ".hc-speech-input button"))))
        (.then (wait-for-phase! :recording)
               (fn [reached?]
                 (is reached? "recording should reach :recording")
                 (is (= :recording (:phase @speech-ui/input-state)))
                 ;; The regression: the render that entered :recording must
                 ;; not call recorder.stop().  The label already reads
                 ;; 停止录音 here.
                 (is (= 0 @js/globalThis.__hcMediaStops)
                     "rendering :recording must not stop the recorder")
                 ;; Force one more render of the same phase to be sure no
                 ;; render path stops it.
                 (act!
                  (fn []
                    (.render (:root mounted)
                             (react/createElement speech-ui/input-element
                                                  #js {:sessionId "s1"
                                                       :draft #js {:text "草稿"
                                                                   :cursor 2}}))))
                 (is (= 0 @js/globalThis.__hcMediaStops)
                     "re-render while :recording must not stop the recorder")
                 ;; The real click handler stops it exactly once and moves
                 ;; on.  onstop fires on a later tick; poll for :uploading.
                 (act!
                  (fn []
                    (.click (.querySelector (:container mounted)
                                            ".hc-speech-input button"))))
                 (.then (wait-for-phase! :uploading)
                        (fn [uploaded?]
                          (is (= 1 @js/globalThis.__hcMediaStops)
                              "stop handler should stop the recorder once")
                          (is uploaded?
                              "click on 停止录音 should move to :uploading")
                          (cleanup-mounted! mounted done))))
               (fn [_err]
                 (cleanup-mounted! mounted done)))))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.speech.ui-test))

(set! *main-cli-fn* -main)
