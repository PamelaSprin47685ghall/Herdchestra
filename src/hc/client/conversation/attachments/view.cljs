(ns hc.client.conversation.attachments.view
  "Thin React surface for the attachment queue.

  The component renders cards from `hc.client.conversation.attachments` and
  reports gestures to the workbench.  It never uploads, constructs a command
  or marks an item ready.  A card is a real descriptor from the server; the
  status text is never inferred from a filename alone."
  (:require [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.conversation.attachments :as attachments]))

(def ^:private css
  ".hc-attachment-queue{display:flex;flex-direction:column;gap:8px;margin:0;padding:0;list-style:none}
.hc-attachment-card{display:flex;align-items:flex-start;gap:10px;padding:8px;border:1px solid var(--f7-list-item-border-color,#d8d8dc);border-radius:10px;background:var(--f7-card-bg-color,#fafafa);min-height:56px}
.hc-attachment-thumb{width:48px;height:48px;flex:0 0 48px;object-fit:cover;border-radius:8px;background:#e8e8ec;display:block}
.hc-attachment-icon{width:48px;height:48px;flex:0 0 48px;display:flex;align-items:center;justify-content:center;border-radius:8px;background:#e8e8ec;color:#444;font-size:.72rem;font-weight:600;text-align:center;padding:2px}
.hc-attachment-body{flex:1;min-width:0;display:flex;flex-direction:column;gap:2px}
.hc-attachment-name{font-size:.9rem;font-weight:600;overflow-wrap:anywhere}
.hc-attachment-meta{font-size:.78rem;color:var(--f7-label-color,#555)}
.hc-attachment-detail{font-size:.78rem;color:var(--f7-label-color,#555);overflow-wrap:anywhere}
.hc-attachment-actions{display:flex;gap:6px;flex-wrap:wrap;margin-top:4px}
.hc-attachment-actions .hc-button{min-height:44px;min-width:44px;padding:6px 10px}
.hc-attachment-status-error{color:#7f0000}
.hc-attachment-status-warn{color:#6d3b00}
.hc-attachment-status-ok{color:#1b5e20}
@media (max-width:599px){.hc-attachment-card{align-items:stretch}.hc-attachment-actions .hc-button{flex:1}}")

(defn- props-of
  [props]
  (if (map? props) props (.-props props)))

(defn- call!
  [callback card & [extra]]
  (when (fn? callback)
    (callback (merge {:attachment-id (:id card)
                      :card card}
                     extra))))

(defn- status-class
  [card]
  (case (:status card)
    :ready "hc-attachment-status-ok"
    :failed "hc-attachment-status-error"
    :unsupported "hc-attachment-status-error"
    :needs-choice "hc-attachment-status-warn"
    "hc-attachment-meta"))

(defn- kind-label
  [card]
  (case (:kind card)
    :image "图片"
    :pdf "PDF"
    :text "文本"
    :audio "音频"
    :video "视频"
    :archive "压缩包"
    "文件"))

(defn- card-view
  [{:keys [on-remove! on-retry! on-exclude! on-preview! on-extract!
           on-route!] :as _props} card]
  (dom/li {:className "hc-attachment-card" :key (:id card)}
          (if-let [url (:preview-url card)]
            (dom/img {:className "hc-attachment-thumb"
                      :src url
                      :alt ""
                      :loading "lazy"})
            (dom/div {:className "hc-attachment-icon"
                      :aria-hidden "true"}
                     (kind-label card)))
          (dom/div {:className "hc-attachment-body"}
                   (dom/span {:className "hc-attachment-name"}
                             (:name card))
                   (dom/span {:className "hc-attachment-meta"}
                             (str (kind-label card)
                                  (when-let [size (:size-label card)]
                                    (str " · " size))
                                  (when-let [route (:route-label card)]
                                    (str " · " route))))
                   (dom/span {:className (str "hc-attachment-detail "
                                              (status-class card))
                              :role "status"}
                             (str (:status-label card)
                                  (when-let [detail (:detail card)]
                                    (str " · " detail))))
                   (when-let [page-label (:page-label card)]
                     (dom/span {:className "hc-attachment-meta"}
                               page-label))
                   (when-let [copy-label (:copy-label card)]
                     (dom/span {:className "hc-attachment-meta"}
                               copy-label))
                   (when-let [unreadable-label (:unreadable-label card)]
                     (dom/span {:className "hc-attachment-detail hc-attachment-status-error"
                                :role "status"}
                               unreadable-label))
                   (when-let [workspace-label (:workspace-label card)]
                     (dom/span {:className "hc-attachment-detail hc-attachment-status-warn"}
                               workspace-label))
                   (dom/div {:className "hc-attachment-actions"}
                            (when (and (:preview-url card) (fn? on-preview!))
                              (dom/button {:className "hc-button"
                                           :type "button"
                                           :onClick #(call! on-preview! card)}
                                          "预览"))
                            (when (and (:can-extract? card) (fn? on-extract!))
                              (dom/button {:className "hc-button hc-button-primary"
                                           :type "button"
                                           :aria-label (:extract-label card)
                                           :onClick #(call! on-extract! card)}
                                          "提取文字"))
                            (when (and (:can-switch-text? card) (fn? on-route!))
                              (dom/button {:className "hc-button"
                                           :type "button"
                                           :aria-label (:switch-text-label card)
                                           :onClick #(call! on-route! card
                                                            {:preferred-route :text})}
                                          "改用提取文本"))
                            (when (and (:can-switch-native? card) (fn? on-route!))
                              (dom/button {:className "hc-button"
                                           :type "button"
                                           :aria-label (:switch-native-label card)
                                           :onClick #(call! on-route! card
                                                            {:preferred-route :native})}
                                          "发送原文件"))
                            (when (and (:can-retry? card) (fn? on-retry!))
                              (dom/button {:className "hc-button"
                                           :type "button"
                                           :aria-label (:retry-label card)
                                           :onClick #(call! on-retry! card)}
                                          "重试"))
                            (when (and (:can-exclude? card) (fn? on-exclude!))
                              (dom/button {:className "hc-button"
                                           :type "button"
                                           :aria-label (:exclude-label card)
                                           :onClick #(call! on-exclude! card)}
                                          "排除"))
                            (when (and (:can-remove? card) (fn? on-remove!))
                              (dom/button {:className "hc-button"
                                           :type "button"
                                           :aria-label (:remove-label card)
                                           :onClick #(call! on-remove! card)}
                                          "移除"))))))

(defn attachment-queue
  "Render the attachment queue.

  Props:
  - `:cards` vector from `attachments/cards`;
  - `:summary` optional `attachments/send-summary`;
  - `:on-remove!`, `:on-retry!`, `:on-exclude!`, `:on-preview!`,
    `:on-extract!`, `:on-route!` callbacks."
  [props]
  (let [{:keys [cards summary] :as props} (props-of props)
        cards (or cards [])]
    (when (seq cards)
      (dom/div {:className "hc-attachment-queue-root"}
               (dom/style {:dangerouslySetInnerHTML #js {:__html css}})
               (when-let [message (:message summary)]
                 (dom/div {:className "hc-attachment-meta"
                           :role "status"
                           :aria-live "polite"}
                          message))
               (dom/ul {:className "hc-attachment-queue"
                        :aria-label "待发送附件"}
                       (for [card cards]
                         (card-view props card)))))))
