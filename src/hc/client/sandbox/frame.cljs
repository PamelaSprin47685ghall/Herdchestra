(ns hc.client.sandbox.frame
  "React iframe host for one sandboxed HTML document.

  The iframe carries only `allow-scripts`; it never receives
  `allow-same-origin`, `allow-forms`, `allow-popups`, `allow-downloads`,
  `allow-modals` or `allow-top-navigation`.  The document's CSP and bridge are
  built by `hc.client.sandbox.model`.

  Every message is checked against the iframe's `contentWindow` and the
  per-instance token before it reaches the caller.  Requests are answered with
  `reply!`; unsolicited events carry no reply."
  (:require ["react" :as react]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.sandbox.model :as model]))

(defn- js-data
  [event]
  (try
    (js->clj (.-data event) :keywordize-keys true)
    (catch :default _ nil)))

(defn- reply-message
  [source token request-id result]
  (let [ok? (boolean (:ok? result))
        value (:value result)
        error (:error result)]
    (try
      (.postMessage source
                    (clj->js (cond-> {:token token
                                      :type "hc/reply"
                                      :requestId request-id
                                      :ok ok?}
                               ok? (assoc :value value)
                               (not ok?) (assoc :error (or error "sandbox request failed"))))
                    "*")
      (catch :default _ nil))))

(defn sandbox-frame
  "Render a sandboxed iframe.

  Props:

    :html              original HTML; the component injects policy and bridge
    :token             per-instance message token
    :mode              :preview or :app
    :config            bridge configuration map
    :allowed-origins   user-granted network origins
    :title             accessible iframe title
    :streaming?        true while the source is still being generated
    :className         extra wrapper class
    :on-event          (fn [{:keys [type payload reply!]}])
    :on-ready          (fn [])
    :on-input          (fn [])

  `on-event` receives keyword `:type` values from `model/message-kind`.  It
  must call `reply!` exactly once for request types."
  [{:keys [html token mode config allowed-origins title streaming? class-name revision
           on-event on-ready on-input]}]
  (let [iframe-ref (react/useRef nil)
        event-ref (react/useRef on-event)
        ready-ref (react/useRef on-ready)
        input-ref (react/useRef on-input)
        [loaded? set-loaded!] (react/useState false)
        [timed-out? set-timed-out!] (react/useState false)
        _ (set! (.-current event-ref) on-event)
        _ (set! (.-current ready-ref) on-ready)
        _ (set! (.-current input-ref) on-input)
        allowed-key (pr-str (vec (or allowed-origins [])))
        document-html (react/useMemo
                       #(model/document-html {:html html
                                              :token token
                                              :mode (or mode :preview)
                                              :config (or config {})
                                              :allowed-origins allowed-origins})
                       #js [html token mode allowed-key revision])]
    (react/useEffect
     (fn []
       (let [handler
             (fn [event]
               (let [source (.-source event)
                     data (js-data event)
                     frame (some-> (.-current iframe-ref) (.-contentWindow))]
                 (when (and source frame (identical? source frame)
                            (model/valid-message? token data))
                   (let [kind (model/message-kind data)
                         payload (model/message-payload data)
                         request-id (or (:requestId payload) (:request-id payload))
                         reply! (fn [result]
                                  (when request-id
                                    (reply-message source token request-id result)))]
                     (case kind
                       :ready (do (set-loaded! true)
                                  (when-let [f (.-current ready-ref)] (f)))
                       :input (when-let [f (.-current input-ref)] (f))
                       (when-let [f (.-current event-ref)]
                         (f {:type kind
                             :payload payload
                             :reply! reply!})))))))]
         (.addEventListener js/window "message" handler)
         #(.removeEventListener js/window "message" handler)))
     #js [token html])
    (react/useEffect
     (fn []
       (set-loaded! false)
       (set-timed-out! false)
       (let [timer (js/setTimeout #(set-timed-out! true) 8000)]
         #(js/clearTimeout timer)))
     #js [document-html])
    (dom/div {:className (str "hc-sandbox-frame-wrap "
                              (when streaming? "hc-sandbox-streaming ")
                              (or class-name ""))
              :data-hc-sandbox-mode (name (or mode :preview))}
             (dom/iframe {:ref iframe-ref
                          :className "hc-sandbox-frame"
                          :title (or title "沙箱预览")
                          :sandbox "allow-scripts"
                          :allow "camera 'none'; microphone 'none'; geolocation 'none'; clipboard-read 'none'; clipboard-write 'none'; payment 'none'; usb 'none'; serial 'none'; midi 'none'; display-capture 'none'"
                          :referrerPolicy "no-referrer"
                          :srcDoc document-html
                          :aria-busy (if streaming? "true" "false")})
             (when-not loaded?
               (dom/div {:className "hc-sandbox-loading" :role "status"}
                        (if timed-out?
                          "尚未收到预览加载确认；内容可能仍在运行，或脚本已被策略阻止。"
                          "正在准备沙箱预览…"))))))

(defn- frame-component
  [js-props]
  (sandbox-frame (.-props js-props)))

(defn sandbox-frame-element
  "Create a stable React element for `sandbox-frame`.

  Callers must use this instead of calling `sandbox-frame` directly.  Direct
  calls put the iframe hooks into the caller's hook list; mounting or
  unmounting the preview would then change the caller's hook count."
  [props]
  (react/createElement frame-component #js {:props props}))
