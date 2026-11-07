(ns hc.client.artifacts.view
  "Framework7-compatible artifact cards.

  The renderer consumes `hc.client.artifacts/card` values.  It shows only
  actions the card model produced: a missing artifact has no open/download
  link, and a card without a content ID never gets a fabricated URL."
  (:require [com.fulcrologic.fulcro.dom :as dom]))

(defn- action
  [on-copy {:keys [action label url target rel filename value]}]
  (case action
    :open
    (dom/a {:key "open"
            :className "wb-button wb-button-small"
            :href url
            :target (if (= target :new-tab) "_blank" nil)
            :rel rel}
           label)

    :download
    (dom/a {:key "download"
            :className "wb-button wb-button-small"
            :href url
            :download filename}
           label)

    :copy-content-id
    (dom/button {:key "copy-content-id"
                 :type "button"
                 :className "wb-button wb-button-small"
                 :onClick (fn [_] (when on-copy (on-copy value)))}
                label)

    nil))

(defn- provenance-row
  [[label value code?]]
  (when (and value (not= "" value))
    (dom/div {:key (str label) :className "wb-row wb-muted"}
             (dom/span {} (str label "："))
             (dom/span {:className (when code? "wb-code")} (str value)))))

(defn artifact-card
  "Render one artifact card.

  Props: `{:artifact <card> :on-copy (fn [content-id])
           :on-retry (fn [card]) :on-relocate (fn [card])
           :on-retract (fn [card])}`.  Retry/relocate/retract appear only when
  the host supplies a real callback; otherwise the card states the limitation
  instead of showing a dead button."
  [{:keys [artifact on-copy on-retry on-relocate on-retract]}]
  (let [{:keys [id kind-label type-label size-label status-label
                status-tone environment-label device project-id
                workspace-root path relative-path
                modified-label source-task-id content-id content-version
                storage-version download-url available?
                openable? actions provenance missing-reason
                retractable? truncated? truncated-reason]} artifact
        artifact-name (:name artifact)]
    (dom/li {:key id}
            (dom/div {:className "wb-list-button wb-artifact-card"}
                     (dom/div {:className "wb-list-main"}
                              (dom/strong {} artifact-name)
                              (dom/small {}
                                         (str kind-label " · " type-label
                                              " · " (or size-label "大小未知")
                                              " · " status-label
                                              " · " environment-label))
                              (when project-id
                                (dom/small {:className "wb-muted"}
                                           (str "项目 " project-id)))
                              (when workspace-root
                                (dom/small {:className "wb-muted"}
                                           (str "工作区 " workspace-root)))
                              (when device
                                (dom/small {:className "wb-muted"}
                                           (str "设备 " device)))
                              (when path
                                (dom/small {:className "wb-code"} path))
                              (when (and relative-path (not= relative-path path))
                                (dom/small {:className "wb-muted"} relative-path))
                              (when modified-label
                                (dom/small {:className "wb-muted"}
                                           (str "修改时间 " modified-label)))
                              (when truncated?
                                (dom/small {:className "wb-muted"}
                                           (str "已截断"
                                                (when truncated-reason
                                                  (str "：" truncated-reason)))))
                              (when source-task-id
                                (dom/small {:className "wb-muted"}
                                           (str "来源任务 " source-task-id)))
                              (when missing-reason
                                (dom/small {:className "wb-muted"}
                                           (str "不可访问：" missing-reason)))
                              (when (or content-version storage-version)
                                (dom/small {:className "wb-muted"}
                                           (str "版本 "
                                                (or content-version "—")
                                                " · 存储 "
                                                (or storage-version "—"))))
                              (when (seq actions)
                                (dom/div {:className "wb-row"}
                                         (for [a actions]
                                           (action on-copy a))))
                              (when (and (not available?)
                                         (or on-retry on-relocate))
                                (dom/div {:className "wb-row"}
                                         (when on-relocate
                                           (dom/button {:type "button"
                                                        :className "wb-button wb-button-small"
                                                        :onClick (fn [_] (on-relocate artifact))}
                                                       "重新定位"))
                                         (when on-retry
                                           (dom/button {:type "button"
                                                        :className "wb-button wb-button-small"
                                                        :onClick (fn [_] (on-retry artifact))}
                                                       "重新生成"))))
                              (when (and (not available?)
                                         (not (or on-retry on-relocate)))
                                (dom/small {:className "wb-muted"}
                                           "重新定位或重新生成需要服务器命令面支持。"))
                              (when (and retractable? on-retract)
                                (dom/button {:type "button"
                                             :className "wb-button wb-button-small"
                                             :onClick (fn [_] (on-retract artifact))}
                                            "撤回"))
                              (when (seq provenance)
                                (dom/details {:className "wb-artifact-provenance"}
                                             (dom/summary {} "来源与检查")
                                             (for [[label value code?] provenance]
                                               (provenance-row [label value code?]))))
                              (when (and (not openable?) download-url)
                                (dom/small {:className "wb-muted"}
                                           "此类型只提供下载，不在浏览器内预览。"))
                              (when content-id
                                (dom/small {:className "wb-code wb-muted"}
                                           (str "内容 ID " content-id))))
                     (dom/span {:className "wb-badge"
                                :data-tone (name (if (= :error status-tone)
                                                   :danger
                                                   (or status-tone :warn)))}
                               status-label)))))

(defn artifacts-list
  "Render a list of artifact cards.

  Props: `{:artifacts [card] :on-copy (fn [content-id])
           :on-retry (fn [card]) :on-relocate (fn [card])
           :on-retract (fn [card])}`."
  [{:keys [artifacts on-copy on-retry on-relocate on-retract]}]
  (if (seq artifacts)
    (dom/ul {:className "wb-list wb-artifact-list"}
            (for [artifact artifacts]
              (artifact-card {:artifact artifact
                              :on-copy on-copy
                              :on-retry on-retry
                              :on-relocate on-relocate
                              :on-retract on-retract})))
    (dom/div {:className "wb-empty"}
             (dom/strong {} "没有产物")
             (dom/p {} "服务器尚未提供 artifact 元数据。临时路径不会冒充已交付产物。"))))
