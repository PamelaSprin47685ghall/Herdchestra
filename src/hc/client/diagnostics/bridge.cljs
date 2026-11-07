(ns hc.client.diagnostics.bridge
  "Transport and receipt bridge for the diagnostics workspace.

  The bridge owns only command envelopes and receipt routing.  The server is
  the authority for statistics, prices, log policy, and bundle preparation.
  A receipt, not a click, changes the displayed domain data.

  The bridge never uploads.  The only export path is a user-clicked command
  whose receipt contains a local artifact; the browser then offers a local
  download.  A preview is never sent anywhere."
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.diagnostics.model :as model]
            [hc.client.diagnostics.view :as view]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.sync-bridge :as sync-bridge]
            [hc.client.workbench.transport :as transport]
            [taoensso.timbre :as log]))

(defonce app-ref
  (atom nil))

(defonce installed?
  (atom false))

(defn set-app!
  [app]
  (reset! app-ref app))

(defn- current-db
  []
  (when-let [app @app-ref]
    (application/current-state app)))

(defn state
  []
  (or (:ui/diagnostics (current-db)) (model/initial-state)))

(defn- transact-state!
  [next-state]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/set-diagnostics-state
                      {:new-state next-state})])))

(defn- browser-zone
  []
  (try
    (let [formatter (js/Intl.DateTimeFormat.)]
      (or (.. formatter resolvedOptions -timeZone) "UTC"))
    (catch :default _ "UTC")))

(defn- zone-for
  [state]
  (or (get-in state [:query :zone])
      (browser-zone)))

(defn- today-for
  [state]
  (model/today-in-zone (zone-for state)))

(defn- synced?
  []
  (sync-bridge/command-allowed?))

(defn- now-label
  []
  (str (js/Date.)))

(defn- notify!
  [tone text]
  (when-let [app @app-ref]
    (comp/transact! app
                    [(mutations/notify
                      {:notice {:id (str "diagnostics-" (random-uuid))
                                :tone tone
                                :text text
                                :at (now-label)}})])))

(defn- mark-pending!
  [state command-id payload extra]
  (let [latest (or (:ui/diagnostics (current-db)) state)]
    (transact-state!
     (assoc-in latest [:pending command-id]
               (merge {:kind (:kind payload)
                       :sent-at (now-label)}
                      extra)))))

(defn- command-not-sent!
  [state payload extra message notify?]
  (transact-state!
   (-> state
       (model/restore-failed-send (:kind payload) message
                                  (:previous-removed-lines extra))
       (assoc :can-submit? false
              :message message)))
  (when notify?
    (notify! :warn "诊断命令未发送：实时通道未打开。"))
  nil)

(defn- submit!
  "Send one diagnostics command.  Returns the command ID or nil."
  [state payload & [extra]]
  (cond
    (not (synced?))
    (command-not-sent! state payload extra
                       "实时通道未就绪；命令未发送。" false)

    (not (and (integer? (sync-bridge/command-epoch))
              (pos? (sync-bridge/command-epoch))))
    (command-not-sent! state payload extra
                       "服务器命令纪元尚未到达；命令未发送。" false)

    :else
    (let [command-id (str (random-uuid))
          command-epoch (sync-bridge/command-epoch)
          command {:command-id command-id
                   :command-epoch command-epoch
                   :kind (:kind payload)
                   :status :submitting
                   :created-at (now-label)
                   :payload payload}]
      (when-let [app @app-ref]
        (comp/transact! app
                        [(mutations/track-command {:command command})]))
      (mark-pending! state command-id payload extra)
      (if (transport/command! {:command-id command-id
                               :command-epoch command-epoch
                               :payload payload})
        command-id
        (do
          (when-let [app @app-ref]
            (comp/transact! app
                            [(mutations/update-command
                              {:command-id command-id
                               :changes {:status :failed
                                         :message "Sente 通道未接受该命令"
                                         :updated-at (now-label)}})]))
          (let [latest (or (:ui/diagnostics (current-db)) state)]
            (transact-state!
             (-> latest
                 (update :pending dissoc command-id)
                 (model/restore-failed-send
                  (:kind payload)
                  "命令未发送：实时通道未打开或已中断。"
                  (:previous-removed-lines extra))
                 (assoc :message "命令未发送：实时通道未打开或已中断。"))))
          (notify! :warn "诊断命令未发送：实时通道未打开。")
          nil)))))

(defn- copy-text!
  [text]
  (try
    (if (and (exists? js/navigator.clipboard)
             (fn? (.-writeText js/navigator.clipboard)))
      (-> (.writeText js/navigator.clipboard (str text))
          (.then (fn [] (notify! :info "反馈草稿已复制。")))
          (.catch (fn [_] (notify! :warn "剪贴板不可用，未复制。"))))
      (do
        (let [element (.createElement js/document "textarea")]
          (set! (.-value element) (str text))
          (set! (.-position (.-style element)) "fixed")
          (set! (.-opacity (.-style element)) "0")
          (.appendChild js/document.body element)
          (.select element)
          (let [ok? (try (.execCommand js/document "copy")
                         (catch :default _ false))]
            (.removeChild js/document.body element)
            (notify! (if ok? :info :warn)
                     (if ok? "反馈草稿已复制。" "剪贴板不可用，未复制。"))))))
    (catch :default _
      (notify! :warn "剪贴板不可用，未复制。"))))

(defn- download!
  "Offer CONTENT as a local file.  This is called only after the user clicks
  export and the server returns a local artifact."
  [{:keys [filename content]}]
  (try
    (let [blob (js/Blob. #js [(str content)]
                         #js {:type "application/edn;charset=utf-8"})
          url (.createObjectURL js/URL blob)
          anchor (.createElement js/document "a")]
      (set! (.-href anchor) url)
      (set! (.-download anchor)
            (or filename "herdchestra-diagnostics.edn"))
      (set! (.-display (.-style anchor)) "none")
      (.appendChild js/document.body anchor)
      (.click anchor)
      (.removeChild js/document.body anchor)
      (js/setTimeout #(.revokeObjectURL js/URL url) 1000)
      true)
    (catch :default error
      (log/warn error "Diagnostics download failed")
      (notify! :warn "浏览器未能保存诊断文件；预览仍保留。")
      false)))

(defn- open-mailto!
  [url]
  (try
    (set! (.-location js/window) url)
    true
    (catch :default error
      (log/warn error "Unable to open feedback draft")
      (notify! :warn "无法打开邮件客户端；可复制草稿后自行发送。")
      false)))

(defn- handle-effect!
  [effect state]
  (case (:effect effect)
    :request-statistics
    (let [result (model/request-query state (today-for state))]
      (if (:ok? result)
        (submit! state {:kind :diagnostics/statistics
                        :query (:query result)})
        (transact-state!
         (-> state
             (assoc :status :error
                    :error (:error result)
                    :message (or (get-in result [:error :message])
                                 "统计条件无效。"))))))

    :diagnostics-command
    (submit! state (:payload effect))

    :bundle-preview
    (let [request (cond-> (model/bundle-request state (today-for state))
                    (:rename effect) (assoc :rename (:rename effect)))]
      (submit! state {:kind :diagnostics/bundle-preview
                      :bundle request}))

    :bundle-edit
    (submit! state
             (cond-> {:kind (:kind effect)
                      :bundle (:bundle effect)}
               (:line-ids effect) (assoc :line-ids (:line-ids effect))
               (:rename effect) (assoc :rename (:rename effect)))
             (select-keys effect [:previous-removed-lines]))

    :bundle-export
    (submit! state {:kind :diagnostics/bundle-export
                    :bundle (model/bundle-for-export state)})

    :save-price
    (submit! state {:kind :diagnostics/save-price
                    :service-id (:service-id effect)
                    :model (:model effect)
                    :price (:price effect)})

    :copy-text
    (copy-text! (:text effect))

    :open-mailto
    (open-mailto! (:url effect))

    :download-text
    (download! {:filename (:filename effect)
                :content (:content effect)})

    :notify
    (notify! (:tone effect) (:text effect))

    (log/debug "Ignoring diagnostics effect" (:effect effect))))

(defn dispatch!
  "Apply one diagnostics UI action, then run its effects."
  [action]
  (let [current (assoc (state) :can-submit? (synced?))
        {:keys [state effects]} (model/reduce-action current action)
        next-state (assoc state :can-submit? (synced?))]
    (transact-state! next-state)
    (doseq [effect effects]
      (try
        (handle-effect! effect next-state)
        (catch :default error
          (log/error error "Diagnostics effect failed")
          (let [latest (or (:ui/diagnostics (current-db)) next-state)]
            (transact-state!
             (-> latest
                 (assoc :status (if (= :loading (:status latest))
                                  :error
                                  (:status latest))
                        :error {:message (str "诊断操作未完成："
                                              (or (ex-message error) "未知原因"))}
                        :message (str "诊断操作未完成："
                                      (or (ex-message error) "未知原因")))))))))))

(defn- event-key
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else nil))

(defn- receipt-event?
  [event]
  (let [type (event-key (or (:type event) (:event event)))
        name (event-key (or (:name event) (:event event)))]
    (or (contains? #{:hc/receipt :hc/command-receipt :diagnostics/receipt}
                   type)
        (contains? #{:hc/receipt :hc/command-receipt :diagnostics/receipt}
                   name))))

(defn- receipt-status
  [value]
  (or (model/field value :status)
      (model/field (model/field value :receipt) :status)))

(defn- apply-receipt!
  [value]
  (let [command-id (model/field value :command-id)
        current (state)
        pending (get (:pending current) command-id)]
    (when pending
      (if (= :not-found (model/keyword-value (receipt-status value)))
        ;; A lost receipt does not prove that the command failed.  Keep the
        ;; pending entry and mark the command unknown so a later receipt can
        ;; still be routed; never apply a domain result or download anything.
        (do
          (transact-state!
           (-> current
               (model/restore-unknown-send (:kind pending))
               (assoc :can-submit? (synced?))
               (assoc :message "未找到回执，状态未知。")
               (update-in [:pending command-id]
                          assoc :status :unknown
                                :updated-at (now-label))))
          (when-let [app @app-ref]
            (comp/transact! app
                            [(mutations/update-command
                              {:command-id command-id
                               :changes {:status :unknown
                                         :message "未找到回执，状态未知。"
                                         :updated-at (now-label)}})]))
          (notify! :warn "未找到回执，状态未知。"))
        (let [kind (:kind pending)
              error (or (model/field value :error)
                        (model/field (model/field value :receipt) :error)
                        {:message "诊断命令被拒绝。"})
              next-state (cond-> (-> current
                                     (model/apply-receipt value)
                                     (update :pending dissoc command-id)
                                     (assoc :can-submit? (synced?)))
                           (and (false? (model/field value :ok?))
                                (not= kind :diagnostics/statistics))
                           (assoc :status (:status current)
                                  :error (:error current))

                           (and (false? (model/field value :ok?))
                                (keyword? kind)
                                (str/starts-with? (name kind) "bundle-"))
                           (assoc-in [:bundle :error] error)

                           (and (false? (model/field value :ok?))
                                (keyword? kind)
                                (str/starts-with? (name kind) "bundle-"))
                           (assoc-in [:bundle :status]
                                     (if (model/bundle-value current)
                                       :preview
                                       :error))

                           (and (false? (model/field value :ok?))
                                (= kind :diagnostics/bundle-remove-lines))
                           (assoc-in [:bundle :removed-lines]
                                     (if (contains? pending
                                                    :previous-removed-lines)
                                       (:previous-removed-lines pending)
                                       (get-in current [:bundle :removed-lines])))

                           (and (false? (model/field value :ok?))
                                (= kind :diagnostics/save-price))
                           (assoc-in [:price :error] error))
              artifact (get-in next-state [:bundle :export :artifact])
              ok? (model/field value :ok?)]
          (transact-state! next-state)
          (when-let [app @app-ref]
            (comp/transact! app
                            [(mutations/update-command
                              {:command-id command-id
                               :changes {:status (if (false? ok?) :rejected :applied)
                                         :message (or (model/field error :message)
                                                      (get-in value [:receipt :status]))
                                         :updated-at (now-label)}})]))
          (when (and artifact (:content artifact))
            (download! artifact))
          (when (false? ok?)
            (notify! :warn
                     (or (model/field error :message)
                         "诊断命令被拒绝。"))))))))

(defn handle-event!
  "Feed one Sente event to the diagnostics bridge.  Non-receipt events are
  ignored."
  [event]
  (when (receipt-event? event)
    (let [value (or (:value event) (:receipt event) event)]
      (apply-receipt! value))))

(defn install!
  "Install the bridge for APP.  Idempotent for the process lifetime."
  [app]
  (set-app! app)
  (when (compare-and-set! installed? false true)
    (sync-bridge/add-event-listener! handle-event!))
  app)

(defn sync!
  "Project the latest server display copy into diagnostics state.

  Reads `:diagnostics {:log ...}`, the five-phase connection gate, and the
  settings data section.  Does not invent a statistics result."
  [db]
  (let [current (or (:ui/diagnostics db) (model/initial-state))
        connection (:ui/connection db)
        server-diagnostics (model/field db :diagnostics)
        server-log (model/field server-diagnostics :log)
        can-submit? (boolean (:synced? connection))
        next-state (cond-> current
                     (some? server-log)
                     (assoc :log server-log :log-loaded? true)

                     true
                     (assoc :can-submit? can-submit?))]
    (when (not= (select-keys current [:log :can-submit?])
                (select-keys next-state [:log :can-submit?]))
      (transact-state! next-state))
    next-state))

(defn ensure-stats!
  "Load statistics once when the data section is open and the channel is
  synced.  A failed read is not retried automatically."
  [db]
  (let [current (or (:ui/diagnostics db) (model/initial-state))
        synced? (boolean (get-in db [:ui/connection :synced?]))
        data-section? (= :data (get-in db [:ui/settings :ui :section]))]
    (when (and synced?
               data-section?
               (not (:loaded? current))
               (not= :loading (:status current))
               (not (contains? #{:error :read-failed :invalid-query}
                               (:status current))))
      (dispatch! {:type :diagnostics/refresh}))))

(defn diagnostics-element
  "Return the React element for the diagnostics workspace."
  [db]
  (view/diagnostics-element (or (:ui/diagnostics db) (model/initial-state))
                            dispatch!))
