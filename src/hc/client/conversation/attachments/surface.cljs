(ns hc.client.conversation.attachments.surface
  "Composition surface: attachment queue, file entry and conversation page.

  Workbench mounts this one component instead of teaching the conversation
  view about pre-draft queue state.  The queue owns upload/readiness cards;
  the conversation page still owns text, IME, send gating and the draft.

  Props:
  - `:conversation-props` the exact map normally passed to
    `hc.client.conversation.view/conversation-page`;
  - `:queue` optional controlled queue state; without it the surface keeps
    its own queue for the mounted session;
  - `:on-queue-change!` receives the next queue after add/remove/retry/exclude;
  - `:on-retry!` optional upload restart hook (the File handle is browser-local
    and cannot be serialized into the queue);
  - `:on-preview!` optional preview hook;
  - `:on-extract!` optional notification hook; extraction itself updates the
    queue through `:on-queue-change!` after the real HTTP response;
  - `:client-id` optional stable client identity; defaults to a per-tab
    `sessionStorage` identity so two tabs never share one pre-send queue;
  - `:base-url` optional absolute origin for uploads in tests/non-browser use.

  The component merges `(attachments/snapshot queue)` into the draft before
  rendering the conversation page, so `draft/sendable?` sees the same items
  that are visible above it.  It never writes a command, clears the text draft,
  or marks an item ready without a real server descriptor."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.conversation.attachments :as attachments]
            [hc.client.conversation.attachments.http :as attachments-http]
            [hc.client.conversation.attachments.storage :as attachments-storage]
            [hc.client.conversation.attachments.view :as attachments-view]
            [hc.client.conversation.view :as conversation]))

(defn- props-of
  [props]
  (if (map? props) props (.-props props)))

(defn- session-id
  [conversation-props]
  (or (get-in conversation-props [:session :id])
      (get-in conversation-props [:session :session-id])
      (:session-id conversation-props)))

(defn- merged-conversation-props
  [conversation-props queue]
  (let [draft (:draft conversation-props)]
    (if draft
      ;; The queue is authoritative for this mount.  It was initialized from
      ;; the per-session store or the draft, so an empty snapshot means the
      ;; attachments were sent/removed; stale draft attachments must not be
      ;; resurrected and accidentally sent twice.
      (assoc conversation-props :draft (assoc draft :attachments
                                              (attachments/snapshot queue)))
      conversation-props)))

(defn- queue-cards
  [queue]
  (attachments/cards queue))

(defn- notice-class
  [tone]
  (case tone
    :danger "hc-banner hc-banner-error"
    :warn "hc-banner hc-banner-warning"
    "hc-banner hc-banner-info"))

(defn- file-input
  [input-ref on-files!]
  (dom/input {:type "file"
              :multiple true
              :ref input-ref
              :style #js {:display "none"}
              :aria-hidden "true"
              :onChange (fn [event]
                          (let [files (attachments-http/file-list->vector
                                       (.. event -target -files))]
                            (set! (.-value (.-target event)) "")
                            (when (seq files)
                              (on-files! files))))}))

(defn conversation-with-attachments
  "Render attachment entry and queue above the conversation page."
  [props]
  (let [{:keys [conversation-props queue model on-queue-change! on-retry!
                on-preview! on-extract! client-id base-url]
         :as props} (props-of props)
        conversation-props (or conversation-props {})
        model (or model (get-in conversation-props [:session :model]))
        sid (session-id conversation-props)
        client-id (or (when (and (string? client-id)
                                 (not (str/blank? client-id)))
                        client-id)
                      (attachments-storage/client-id))
        controlled? (some? queue)
        initial-ref (react/useRef nil)
        _ (when (nil? (.-current initial-ref))
            (let [stored (attachments-storage/load-queue sid {:client-id client-id})]
              (set! (.-current initial-ref)
                    {:stored stored
                     :queue (attachments/apply-model
                             (if (:found? stored)
                               (attachments/merge-draft
                                (:queue stored)
                                (:draft conversation-props))
                               (attachments/from-draft (:draft conversation-props)))
                             model)})))
        [local-queue set-local-queue!]
        (react/useState (fn []
                          (if controlled?
                            queue
                            (:queue (.-current initial-ref)))))
        [notice set-notice!] (react/useState nil)
        [uploading? set-uploading!] (react/useState false)
        mounted? (react/useRef true)
        uploading-ref (react/useRef (atom false))
        send-state (react/useRef (atom {:pending? false :ids #{} :commands #{}}))
        input-ref (react/useRef nil)
        queue (if controlled? queue local-queue)
        latest-queue-ref (react/useRef queue)
        _ (set! (.-current latest-queue-ref) queue)
        update-queue! (fn [next-queue]
                        (set! (.-current latest-queue-ref) next-queue)
                        (if controlled?
                          (when (fn? on-queue-change!)
                            (on-queue-change! next-queue))
                          (do
                            (when (.-current mounted?)
                              (set-local-queue! next-queue))
                            (let [saved (attachments-storage/save-queue!
                                         sid next-queue {:client-id client-id})]
                              (when-not (:ok? saved)
                                (set-notice!
                                 {:tone :warn
                                  :text "附件队列暂未保存到本地；切换会话前请保留此页。"})))))
                        next-queue)
        merge-upload-queue! (fn [pipeline-queue]
                              (update-queue!
                               (attachments/merge-items
                                (.-current latest-queue-ref)
                                pipeline-queue)))
        _ (react/useEffect
           (fn []
             (set! (.-current mounted?) true)
             (when-let [error (get-in (.-current initial-ref) [:stored :error])]
               (set-notice!
                {:tone :warn
                 :text (if (= :invalid-stored-queue error)
                         "附件队列本地记录不可读，已回退到草稿附件。"
                         "附件队列本地存储不可用；切换会话仍会保留在当前页面。" )}))
             (let [initial (:queue (.-current initial-ref))
                   pending (:pending-send initial)
                   draft (:draft conversation-props)
                   draft-ids (set (map #(or (:id %) (:attachment-id %))
                                       (:attachments draft)))
                   pending-commands (set (get-in initial [:pending-send :commands]))
                   accepted? (some (fn [[command-id command]]
                                     (and (not (contains? pending-commands command-id))
                                          (= :send (keyword (:kind command)))
                                          (= :accepted (keyword (:status command)))
                                          (or (nil? sid)
                                              (= sid (:session-id command)))))
                                   (:command conversation-props))]
               (if (and (map? pending) (seq (:ids pending)))
                 (update-queue!
                  (cond
                    accepted?
                    (-> initial
                        (attachments/clear-sent (:ids pending))
                        (dissoc :pending-send))

                    (and (map? draft) (some draft-ids (:ids pending)))
                    (dissoc initial :pending-send)

                    (map? draft)
                    (-> initial
                        (attachments/clear-sent (:ids pending))
                        (dissoc :pending-send))

                    :else initial))
                 (when-not controlled?
                   (attachments-storage/save-queue!
                    sid initial {:client-id client-id}))))
             (fn []
               (set! (.-current mounted?) false)
               nil))
           #js [])
        model-key (pr-str (select-keys model [:id :model-id :capabilities
                                              :attachment-limits]))
        _ (react/useEffect
           (fn []
             (when-not controlled?
               (update-queue!
                (attachments/apply-model (.-current latest-queue-ref) model)))
             (fn [] nil))
           #js [model-key])
        original-callbacks (:callbacks conversation-props)
        upload-files! (fn [files]
                        (when (and (seq files)
                                   (not @(.-current uploading-ref)))
                          (reset! (.-current uploading-ref) true)
                          (set-uploading! true)
                          (-> (attachments-http/add-files-and-upload!
                               queue files
                               {:base-url base-url
                                :on-queue-change! merge-upload-queue!
                                :on-notice! (fn [{:keys [tone text]}]
                                              (set-notice! {:tone tone :text text}))})
                              (.then (fn [{:keys [refused added]}]
                                       (reset! (.-current uploading-ref) false)
                                       (set-uploading! false)
                                       (when (seq refused)
                                         (set-notice! {:tone :warn
                                                       :text (str "已加入 " added " 个，"
                                                                  (count refused)
                                                                  " 个需要处理")}))))
                              (.catch (fn [error]
                                        (reset! (.-current uploading-ref) false)
                                        (set-uploading! false)
                                        (set-notice! {:tone :danger
                                                      :text (or (.-message error)
                                                                "附件加入失败")}))))))
        adjusting-blocked! (fn []
                             (set-notice!
                              {:tone :warn
                               :text "附件正在加入，完成后再调整或移除。"}))
        remove! (fn [{:keys [attachment-id]}]
                  (if (or @(.-current uploading-ref) uploading?)
                    (adjusting-blocked!)
                    (do
                      (update-queue! (attachments/remove-item queue attachment-id))
                      (when (fn? (:remove-attachment! original-callbacks))
                        ((:remove-attachment! original-callbacks)
                         {:session-id (session-id conversation-props)
                          :attachment-id attachment-id})))))
        retry! (fn [{:keys [attachment-id card]}]
                 (if (or @(.-current uploading-ref) uploading?)
                   (adjusting-blocked!)
                   (do
                     (update-queue! (attachments/retry queue attachment-id))
                     (when (fn? on-retry!)
                       (on-retry! {:attachment-id attachment-id :card card})))))
        exclude! (fn [{:keys [attachment-id]}]
                   (if (or @(.-current uploading-ref) uploading?)
                     (adjusting-blocked!)
                     (update-queue! (attachments/exclude queue attachment-id))))
        preview! (fn [event]
                   (when (fn? on-preview!)
                     (on-preview! event)))
        extract! (fn [{:keys [card]}]
                   (let [pdf? (= :pdf (:kind card))
                         pages (when pdf?
                                 (js/prompt "输入页码（如 2-5，留空表示全部）" ""))]
                     (if (and pdf? (nil? pages))
                       (js/Promise.resolve queue)
                       (let [route (or (:extract-route card) :ocr)
                             selection (cond-> {:route route}
                                         (seq (str/trim (str pages)))
                                         (assoc :pages (str/trim (str pages))))]
                         (-> (attachments-http/extract!
                              queue card selection {:base-url base-url})
                             (.then (fn [result]
                                      (let [latest (.-current latest-queue-ref)
                                            extraction (:extraction result)
                                            next-queue (-> latest
                                                           (attachments/mark-extracted
                                                            (:id card) extraction)
                                                           (cond-> (:ok? result)
                                                             (attachments/update-item
                                                              (:id card)
                                                              {:preferred-route :text}))
                                                           (attachments/apply-model
                                                            (:model latest)))]
                                        (update-queue! next-queue))
                                      (when (fn? on-extract!)
                                        (on-extract!
                                         {:attachment-id (:id card)
                                          :card card
                                          :selection selection
                                          :result result}))
                                      (when-not (:ok? result)
                                        (set-notice!
                                         {:tone :warn
                                          :text (or (get-in result [:error :message])
                                                    "文字提取失败")}))
                                      result)))))))
        route! (fn [{:keys [attachment-id preferred-route]}]
                 (let [latest (.-current latest-queue-ref)
                       next-queue (-> latest
                                      (attachments/update-item
                                       attachment-id
                                       {:preferred-route preferred-route})
                                      (attachments/apply-model (:model latest)))]
                   (update-queue! next-queue)))
        callbacks (assoc (or original-callbacks {})
                         :send!
                         (fn [event]
                           (when (fn? (:send! original-callbacks))
                             (let [ids (set (map :id (attachments/active-items queue)))
                                   sent (assoc queue :pending-send
                                                 {:ids ids
                                                  :commands (set (keys (or (:command conversation-props) {})))})]
                               (reset! (.-current send-state)
                                       {:pending? true
                                        :ids ids
                                        :commands (set (keys (or (:command conversation-props) {})))})
                               (update-queue! sent)
                               (let [result ((:send! original-callbacks) event)]
                                 (when (false? (:ok? result))
                                   (update-queue! (dissoc sent :pending-send))
                                   (reset! (.-current send-state)
                                           {:pending? false :ids #{} :commands #{}}))
                                 result))))
                         :remove-attachment!
                         (fn [{:keys [attachment-id] :as event}]
                           (if (some #(= attachment-id (:id %))
                                     (attachments/items queue))
                             (remove! event)
                             (when (fn? (:remove-attachment! original-callbacks))
                               ((:remove-attachment! original-callbacks) event)))))
        conversation-props (assoc conversation-props :callbacks callbacks)
        _ (react/useEffect
           (fn []
             (let [{:keys [pending? ids commands]} @(.-current send-state)
                   current-commands (:command conversation-props)
                   new-command (when pending?
                                 (some (fn [[id command]]
                                         (when (and (not (contains? commands id))
                                                    (= :send (keyword (:kind command)))
                                                    (or (nil? (session-id conversation-props))
                                                        (= (session-id conversation-props)
                                                           (:session-id command))))
                                           command))
                                       current-commands))
                   status (keyword (:status new-command))]
               (when (and pending? new-command)
                 (cond
                   (= :accepted status)
                   (do (update-queue! (-> queue
                                          (attachments/clear-sent ids)
                                          (dissoc :pending-send)))
                       (reset! (.-current send-state)
                               {:pending? false :ids #{} :commands #{}}))

                   (contains? #{:rejected :failed} status)
                   (do (update-queue! (dissoc queue :pending-send))
                       (reset! (.-current send-state)
                               {:pending? false :ids #{} :commands #{}}))

                   :else nil))
               (fn [] nil))))
        conversation-props (merged-conversation-props conversation-props queue)]
    (dom/div {:className "hc-conversation-with-attachments"
              :onDragOver (fn [event] (.preventDefault event))
              :onDrop (fn [event]
                        (.preventDefault event)
                        (when-not uploading?
                          (upload-files!
                           (attachments-http/file-list->vector
                            (.. event -dataTransfer -files)))))}
             (dom/div {:className "hc-attachment-entry"}
                      (dom/button {:type "button"
                                   :className "hc-button"
                                   :aria-label "添加附件"
                                   :disabled uploading?
                                   :onClick (fn [_]
                                              (when-let [node (.-current input-ref)]
                                                (.click node)))}
                                  "添加附件")
                      (dom/button {:type "button"
                                   :className "hc-button"
                                   :aria-label "关联工作区文件"
                                   :disabled uploading?
                                   :onClick
                                   (fn [_]
                                     (let [path (js/prompt
                                                 "输入工作区相对路径（如 docs/scan.pdf）"
                                                 "")]
                                       (when (and (string? path)
                                                  (not (str/blank? path)))
                                         (-> (attachments-http/associate-workspace!
                                              queue
                                              (str/trim path)
                                              {:base-url base-url
                                               :on-queue-change! merge-upload-queue!})
                                             (.then
                                              (fn [{:keys [ok? error]}]
                                                (when-not ok?
                                                  (set-notice!
                                                   {:tone :warn
                                                    :text (or (:message error)
                                                              "工作区关联失败")}))))))))}
                                  "关联工作区文件")
                      (file-input input-ref upload-files!)
                      (when-let [{:keys [tone text]} notice]
                        (dom/div {:className (notice-class tone)
                                  :role "status"
                                  :aria-live "polite"}
                                 text)))
             (attachments-view/attachment-queue
              {:cards (queue-cards queue)
               :summary (attachments/send-summary queue)
               :on-remove! remove!
               :on-retry! retry!
               :on-exclude! exclude!
               :on-preview! preview!
               :on-extract! extract!
               :on-route! route!})
             (conversation/conversation-page conversation-props))))
