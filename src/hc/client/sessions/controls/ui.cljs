(ns hc.client.sessions.controls.ui
  "React controls for session rename, pin, archive, restore, and delete.

  The component is deliberately small and local: it renders real
  `:session/*` actions, keeps only the inline rename/confirm draft, and waits
  for the workbench receipt table before claiming success.  It never mutates
  the session title, pinned flag, or status locally."
  (:require ["react" :as react]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.sessions.controls :as model]))

(defn- prop
  [props k]
  (aget props k))

(defn- reason-text
  [result]
  (case (:reason result)
    :title-required "标题不能为空，且不超过 200 个字符"
    :connection-not-ready "尚未与服务器同步，命令不可提交"
    :command-epoch-unknown "缺少该会话的命令纪元；请等待服务器基线"
    :transport-not-open "实时通道未打开，命令未发送"
    "会话命令未发送"))

(defn- action-button
  [action on-click]
  (dom/button #js {:type "button"
                   :key (name (:action action))
                   :className (str "wb-button wb-button-small"
                                   (when (:danger? action) " wb-button-danger"))
                   :disabled (boolean (:disabled? action))
                   :title (or (:reason action) (:description action))
                   :aria-label (str (:label action) "：" (:description action)
                                    (when (:reason action)
                                      (str "（不可用：" (:reason action) "）")))
                   :data-session-action (name (:action action))
                   ;; `click!` and `send!` dispatch on the action keyword;
                   ;; passing the whole action map silently falls through to
                   ;; the title validator and sends nothing.
                   :onClick (fn [_] (on-click (:action action)))}
                  (:label action)))

(defn- rename-form
  [title set-title! on-save on-cancel disabled?]
  (dom/form #js {:className "hc-session-rename"
                 :style #js {:display "flex" :flexWrap "wrap" :gap "4px"
                             :alignItems "center" :marginTop "4px"}
                 :onSubmit (fn [event]
                             (.preventDefault event)
                             (on-save))}
            (dom/input #js {:type "text"
                            :value title
                            :autoFocus true
                            :maxLength 200
                            :aria-label "新会话标题"
                            :placeholder "会话标题"
                            :onChange (fn [event]
                                        (set-title! (.. event -target -value)))
                            :style #js {:minWidth "8rem" :flex "1 1 8rem"}})
            (dom/button #js {:type "submit"
                             :className "wb-button wb-button-small"
                             :disabled (boolean disabled?)
                             :title (when disabled?
                                      "上一条会话命令仍在等待服务器回执")}
                        "保存")
            (dom/button #js {:type "button"
                             :className "wb-button wb-button-small"
                             :onClick (fn [_] (on-cancel))}
                        "取消")))

(defn- delete-confirm
  [on-confirm on-cancel disabled?]
  (dom/div #js {:className "hc-session-delete-confirm"
                :role "alertdialog"
                :aria-label "确认删除会话"
                :style #js {:marginTop "4px"}}
           (dom/p #js {:className "wb-muted"}
                  "删除后会话进入已删除区，30 天内可恢复；临时会话不进入已删除区。")
           (dom/div #js {:className "wb-row"}
                    (dom/button #js {:type "button"
                                     :autoFocus true
                                     :className "wb-button wb-button-small wb-button-danger"
                                     :disabled (boolean disabled?)
                                     :title (when disabled?
                                              "上一条会话命令仍在等待服务器回执")
                                     :onClick (fn [_] (on-confirm))}
                                "确认删除")
                    (dom/button #js {:type "button"
                                     :className "wb-button wb-button-small"
                                     :onClick (fn [_] (on-cancel))}
                                "取消"))))

(defn- purge-confirm
  [on-confirm on-cancel disabled?]
  (dom/div #js {:className "hc-session-purge-confirm"
                :role "alertdialog"
                :aria-label "确认永久删除会话"
                :style #js {:marginTop "4px"}}
           (dom/p #js {:className "wb-muted"}
                  "永久删除后会话记录不可恢复。此操作不会删除工作区文件。")
           (dom/div #js {:className "wb-row"}
                    (dom/button #js {:type "button"
                                     :autoFocus true
                                     :className "wb-button wb-button-small wb-button-danger"
                                     :disabled (boolean disabled?)
                                     :title (when disabled?
                                              "上一条会话命令仍在等待服务器回执")
                                     :onClick (fn [_] (on-confirm))}
                                "确认永久删除")
                    (dom/button #js {:type "button"
                                     :className "wb-button wb-button-small"
                                     :onClick (fn [_] (on-cancel))}
                                "取消"))))

(defn controls-element
  "Render controls for one session.

  Expected map:

    {:session     normalized session summary
     :connection  workbench connection view
     :command     newest tracked command for the session, or nil
     :on-command  (fn [{:keys [kind action payload]}]) -> {:ok? bool :reason ...}
     :on-notice   (fn [tone text])}"
  [{:keys [session connection command on-command on-notice]}]
  (let [session (or session {})
        command (model/metadata-command command)
        rename-state (react/useState false)
        rename? (aget rename-state 0)
        set-rename! (aget rename-state 1)
        title-state (react/useState (or (:title session) ""))
        title (aget title-state 0)
        set-title! (aget title-state 1)
        confirm-state (react/useState nil)
        confirm-action (aget confirm-state 0)
        set-confirm! (aget confirm-state 1)
        actions (model/actions session connection command)
        pending? (model/pending? command)
        status-text (model/command-status-text command)
        notice! (fn [tone text]
                  (when (fn? on-notice) (on-notice tone text)))
        send! (fn [action]
                (if pending?
                  ;; The action buttons are disabled, but an open rename form
                  ;; or confirmation dialog is local state and can still be
                  ;; submitted with Enter.  Recheck the pending gate so one
                  ;; metadata command remains in flight at a time.
                  (do
                    (notice! :warn
                             (or (model/action-reason action session connection command)
                                 "上一条会话命令仍在等待服务器回执"))
                    {:ok? false :reason :pending})
                  (let [request (model/command-request action session {:title title})
                        result (if request
                                 (try
                                   (or (on-command request)
                                       {:ok? false :reason :not-sent})
                                   (catch :default _
                                     {:ok? false :reason :not-sent}))
                                 {:ok? false :reason :title-required})]
                    (if (:ok? result)
                      (case action
                        :rename (set-rename! false)
                        (:delete :purge) (set-confirm! nil)
                        nil)
                      ;; The command helper already notifies for connection,
                      ;; epoch, and transport failures.  Only the local title
                      ;; validation needs a second notice.
                      (when (= :title-required (:reason result))
                        (notice! :warn (reason-text result))))
                    result)))
        click! (fn [action]
                 (case action
                   :rename (do (set-title! (or (:title session) ""))
                               (set-rename! true))
                   :delete (set-confirm! :delete)
                   :purge (set-confirm! :purge)
                   (send! action)))]
    (react/useEffect
     (fn []
       (set-title! (or (:title session) ""))
       js/undefined)
     #js [(:id session) (:title session)])
    (dom/div #js {:className "hc-session-controls"
                  :data-session-id (:id session)
                  :data-command-status (when-let [status (:status command)] (name status))
                  :aria-busy (if pending? "true" "false")
                  :style #js {:display "flex" :flexWrap "wrap" :gap "4px"
                              :alignItems "center" :marginTop "4px"}}
             (mapv #(action-button % click!) actions)
             (when rename?
               (rename-form title set-title!
                            #(send! :rename)
                            #(set-rename! false)
                            pending?))
             (when (= :delete confirm-action)
               (delete-confirm #(send! :delete)
                               #(set-confirm! nil)
                               pending?))
             (when (= :purge confirm-action)
               (purge-confirm #(send! :purge)
                              #(set-confirm! nil)
                              pending?))
             (when status-text
               (dom/p #js {:className "wb-muted"
                           :role "status"
                           :aria-live "polite"
                           :data-status (when-let [status (:status command)] (name status))
                           :style #js {:flexBasis "100%" :margin "2px 0 0"}}
                      status-text)))))

(defn SessionControls
  "Plain React entry.  Props are a JS object from `react/createElement`."
  [props]
  (controls-element {:session (prop props "session")
                     :connection (prop props "connection")
                     :command (prop props "command")
                     :on-command (prop props "on-command")
                     :on-notice (prop props "on-notice")}))
