(ns hc.client.files.ui
  "Read-only workspace file browser.

  The renderer receives a server-reported state map and callbacks.  It never
  fetches, scans or invents data.  Directories navigate; regular text files
  open a preview; every row shows the relative path, type, size, modification
  time and — for regular files — the exact `(mtime, size)` stamp.

  The preview shows a line diff against the previous text the user actually
  saw.  Added and removed lines carry visible `+`/`-` text markers and an
  accessible label, not color alone.  No delete, rename, create, download or
  fake recovery action is offered: this view is read-only."
  (:require [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.files.model :as model]
            [hc.client.files.diff :as diff]))

(def css
  ".hc-files{display:flex;flex-direction:column;gap:12px}
.hc-files h2{margin:0;font-size:1.08em}
.hc-files h3{margin:0 0 6px;font-size:.98em}
.hc-files-toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
.hc-files-crumbs{display:flex;flex-wrap:wrap;align-items:center;gap:4px;flex:1 1 260px;min-width:0}
.hc-files-crumb{min-height:32px;padding:4px 6px;border:0;background:transparent;color:var(--wb-accent);font:inherit;cursor:pointer;border-radius:6px}
.hc-files-crumb:hover,.hc-files-crumb:focus-visible{background:var(--wb-surface-2);outline:2px solid var(--wb-accent);outline-offset:-2px}
@media(max-width:599px){.hc-files-crumb{min-height:44px}}
.hc-files-crumb[aria-current=\"true\"]{color:var(--wb-ink);font-weight:600}
.hc-files-sep{color:var(--wb-ink-muted)}
.hc-files-layout{display:grid;grid-template-columns:minmax(0,1fr) minmax(300px,44%);gap:12px;align-items:start}
@media(max-width:900px){.hc-files-layout{grid-template-columns:1fr}}
.hc-files-panel{background:var(--wb-surface);border:1px solid var(--wb-line);border-radius:var(--wb-radius);padding:10px;min-width:0}
.hc-files-table-wrap{max-width:100%;min-width:0;overflow-x:auto}
.hc-files-table{width:100%;border-collapse:collapse;font-size:.9em}
@media(max-width:599px){.hc-files-table{min-width:600px}}
.hc-files-table th{text-align:left;color:var(--wb-ink-muted);font-weight:600;padding:6px 8px;border-bottom:1px solid var(--wb-line);white-space:nowrap}
.hc-files-table td{padding:4px 8px;border-bottom:1px solid var(--wb-line);vertical-align:top}
.hc-files-table tr:last-child td{border-bottom:0}
.hc-files-table tr[aria-current=\"true\"]{background:color-mix(in srgb,var(--wb-accent) 12%,transparent)}
.hc-files-name{display:flex;align-items:center;gap:6px;min-height:44px;width:100%;padding:4px 2px;border:0;background:transparent;color:var(--wb-ink);font:inherit;text-align:left;cursor:pointer;border-radius:6px}
.hc-files-name:hover,.hc-files-name:focus-visible{background:var(--wb-surface-2);outline:2px solid var(--wb-accent);outline-offset:-2px}
.hc-files-name:disabled{cursor:default;color:var(--wb-ink-muted)}
.hc-files-glyph{width:1.2em;text-align:center;color:var(--wb-ink-muted)}
.hc-files-path{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.86em;word-break:break-all}
.hc-files-muted{color:var(--wb-ink-muted)}
.hc-files-code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82em;word-break:break-all}
.hc-files-preview{display:flex;flex-direction:column;gap:8px;min-width:0}
.hc-files-meta{display:flex;flex-wrap:wrap;gap:6px 12px;font-size:.86em;color:var(--wb-ink-muted)}
.hc-files-pre{max-height:44vh;overflow:auto;margin:0;padding:10px;background:var(--wb-surface-2);border-radius:8px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82em;white-space:pre;overscroll-behavior:contain}
.hc-files-diff-table{width:100%;border-collapse:collapse;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.8em}
.hc-files-diff-table td{padding:1px 6px;vertical-align:top}
.hc-files-diff-marker{width:2.2em;text-align:center;font-weight:700;user-select:none}
.hc-files-diff-line--add .hc-files-diff-marker,.hc-files-diff-line--add .hc-files-diff-text{background:color-mix(in srgb,var(--wb-ok) 18%,transparent)}
.hc-files-diff-line--remove .hc-files-diff-marker,.hc-files-diff-line--remove .hc-files-diff-text{background:color-mix(in srgb,var(--wb-danger) 18%,transparent)}
.hc-files-diff-line--equal .hc-files-diff-marker{color:var(--wb-ink-muted)}
.hc-files-line-no{width:3.5em;text-align:right;color:var(--wb-ink-muted);user-select:none}
.hc-files-diff-text{white-space:pre-wrap;word-break:break-word}
.hc-files-hunk{margin:6px 0 10px}
.hc-files-hunk-header{color:var(--wb-ink-muted);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.8em;padding:2px 0}
.hc-files-alert{border:1px solid var(--wb-danger);border-radius:8px;padding:8px 10px;color:var(--wb-danger)}
.hc-files-note{font-size:.86em;color:var(--wb-ink-muted)}
.hc-files-empty{border:1px dashed var(--wb-line);border-radius:8px;padding:14px;color:var(--wb-ink-muted)}
.hc-files-sr{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
.hc-files-badge{display:inline-flex;align-items:center;gap:4px;padding:2px 8px;border-radius:999px;border:1px solid var(--wb-line);font-size:.8em}
.hc-files-badge[data-tone=\"ok\"]{color:var(--wb-ok);border-color:var(--wb-ok)}
.hc-files-badge[data-tone=\"warn\"]{color:var(--wb-warn);border-color:var(--wb-warn)}
.hc-files-badge[data-tone=\"danger\"]{color:var(--wb-danger);border-color:var(--wb-danger)}
.hc-files-search{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.hc-files-search input{min-height:36px;padding:4px 8px;border:1px solid var(--wb-line);border-radius:8px;background:var(--wb-surface);color:var(--wb-ink);font:inherit;flex:1 1 180px}
.hc-files-mode-switch{display:flex;gap:4px;align-items:center}
.hc-files-mode-button{min-height:36px;padding:4px 10px;border:1px solid var(--wb-line);border-radius:8px;background:var(--wb-surface);color:var(--wb-ink);font:inherit;cursor:pointer}
.hc-files-mode-button[aria-pressed=\"true\"]{background:var(--wb-accent);border-color:var(--wb-accent);color:var(--wb-accent-ink)}
.hc-files-mode-button:focus-visible{outline:2px solid var(--wb-accent);outline-offset:2px}
.hc-files-search-note{flex-basis:100%;font-size:.82em;color:var(--wb-ink-muted)}
.hc-files-snippet{margin:4px 0 0;padding:4px 6px;background:var(--wb-surface-2);border-radius:6px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82em;white-space:pre-wrap;word-break:break-word}")

(defn- status-view
  [state]
  (case (:status state)
    :loading {:label "正在读取目录…" :tone :warn}
    :error {:label "目录读取失败" :tone :danger}
    :loaded {:label (str (or (:total-entries state) 0) " 项") :tone :ok}
    {:label "尚未读取" :tone :neutral}))

(defn- error-text
  [error]
  (let [code (get-in error [:error :code])
        message (get-in error [:error :message])]
    (str (when code (str (name code) " · ")) (or message "请求失败"))))

(defn- stamp-text
  [stamp]
  (or (model/stamp-label stamp) "无 stamp"))

(defn- entry-clickable?
  [entry]
  (case (:kind entry)
    :directory true
    :file (:previewable? entry)
    false))

(defn- entry-glyph
  [entry]
  (cond
    (:outside? entry) "↗"
    (:symlink? entry) "↪"
    (= :directory (:kind entry)) "▸"
    (= :file (:kind entry)) "·"
    :else "?"))

(defn- entry-reason
  [entry]
  (or (:reason-label entry)
      (:reason entry)
      (:error-code entry)
      (when (:too-large? entry) :FILE_TOO_LARGE)
      (when (:binary? entry) :BINARY_FILE)
      (when (and (= :file (:kind entry)) (not (:previewable? entry)))
        :PREVIEW_UNAVAILABLE)
      (when (= :missing (:kind entry)) :NOT_FOUND)))

(defn- reason-text
  [reason]
  (cond
    (keyword? reason) (name reason)
    (string? reason) reason
    (some? reason) (str reason)
    :else "UNKNOWN"))

(defn- entry-row
  [state entry callbacks]
  (let [on-navigate (:on-navigate callbacks)
        on-open (:on-open callbacks)
        clickable? (entry-clickable? entry)
        selected? (= (:path entry) (:selected-path state))
        activate (fn [_]
                   (case (:kind entry)
                     :directory (when on-navigate (on-navigate (:path entry)))
                     :file (when on-open (on-open (:path entry)))
                     nil))]
    (dom/tr {:key (str (:path entry))
             :aria-current (if selected? "true" nil)}
            (dom/td {}
                    (dom/button {:type "button"
                                 :className "hc-files-name"
                                 :disabled (not clickable?)
                                 :title (cond
                                          (:outside? entry)
                                          "符号链接指向工作区外，不能打开"
                                          (not clickable?)
                                          (str "不可打开："
                                               (reason-text (or (entry-reason entry)
                                                                                     :UNKNOWN)))
                                          :else (str "打开 " (:path entry)))
                                 :onClick (when clickable? activate)}
                                (dom/span {:className "hc-files-glyph"
                                           :aria-hidden "true"}
                                          (entry-glyph entry))
                                (dom/span {:className "hc-files-path"}
                                          (or (:name entry) (:path entry)))))
            (dom/td {}
                    (model/entry-type-label entry)
                    (when-let [reason (entry-reason entry)]
                      (dom/div {:className "hc-files-code hc-files-muted"}
                               (reason-text reason))))
            (dom/td {:className "hc-files-muted"}
                    (if (= :directory (:kind entry))
                      "—"
                      (model/format-size (:size entry))))
            (dom/td {:className "hc-files-muted"}
                    (or (model/format-time (:mtime-sec entry)
                                           (:mtime-nsec entry))
                        "—"))
            (dom/td {:className "hc-files-code hc-files-muted"}
                    (if (:stamp entry)
                      (stamp-text (:stamp entry))
                      (if (= :directory (:kind entry))
                        "目录无内容 stamp"
                        "—"))))))

(defn- listing-view
  [state callbacks]
  (let [entries (:entries state)]
    (dom/section {:className "hc-files-panel"
                  :aria-label "目录条目"}
                 (if (seq entries)
                   (dom/div {:className "hc-files-table-wrap"}
                            (dom/table {:className "hc-files-table"}
                                       (dom/thead {}
                                                  (dom/tr {}
                                                          (dom/th {:scope "col"} "名称")
                                                          (dom/th {:scope "col"} "类型")
                                                          (dom/th {:scope "col"} "大小")
                                                          (dom/th {:scope "col"} "修改时间")
                                                          (dom/th {:scope "col"} "stamp")))
                                       (dom/tbody {}
                                                  (mapv #(entry-row state % callbacks)
                                                        entries))))
                   (dom/div {:className "hc-files-empty"}
                            (if (= :loading (:status state))
                              "正在读取服务器目录…"
                              "此目录为空，或服务器尚未返回条目。")))
                 (when (:truncated? state)
                   (dom/p {:className "hc-files-note"}
                          (str "条目超过显示上限，当前只显示前 "
                               (count entries) " 项；总数 "
                               (:total-entries state)
                               "。"))))))

(defn- conflict-view
  [error retry]
  (let [details (get-in error [:error :details])
        expected (:expected details)
        current (:current details)]
    (dom/div {:className "hc-files-alert" :role "alert"}
             (dom/strong {} "文件在读取期间发生变化")
             (dom/p {} "本次内容未作为稳定快照，未显示文本。请重新读取。")
             (when expected
               (dom/div {:className "hc-files-code"}
                        (str "原 stamp：" (stamp-text expected))))
             (when current
               (dom/div {:className "hc-files-code"}
                        (str "当前 stamp：" (stamp-text current))))
             (dom/button {:type "button"
                          :className "wb-button wb-button-small"
                          :onClick (fn [_] (when retry (retry)))}
                         "重新读取"))))

(defn- diff-line
  [line]
  (let [op (:op line)
        marker (case op :add "+" :remove "-" " ")
        label (case op :add "新增行" :remove "删除行" "未改动行")]
    (dom/tr {:key (str (or (:old-line line) "x") "-"
                       (or (:new-line line) "x") "-" op)
             :className (str "hc-files-diff-line hc-files-diff-line--"
                             (name op))}
            (dom/td {:className "hc-files-diff-marker"
                     :title label
                     :aria-label label}
                    marker)
            (dom/td {:className "hc-files-line-no"}
                    (or (:old-line line) ""))
            (dom/td {:className "hc-files-line-no"}
                    (or (:new-line line) ""))
            (dom/td {:className "hc-files-diff-text"}
                    (or (:text line) "")))))

(defn- hunk-view
  [hunk]
  (let [header (:header hunk)]
    (dom/div {:key (str "hunk-" (:old-start header) "-" (:new-start header))
              :className "hc-files-hunk"}
             (dom/div {:className "hc-files-hunk-header"}
                      (str "@@ -" (:old-start header) ","
                           (:old-count header) " +" (:new-start header) ","
                           (:new-count header) " @@"))
             (dom/table {:className "hc-files-diff-table"}
                        (dom/tbody {}
                                   (mapv diff-line (:lines hunk)))))))

(defn- diff-view
  [state]
  (let [diff (:diff state)]
    (when diff
      (dom/section {:className "hc-files-panel hc-files-preview"
                    :aria-label "文件差异"}
                   (dom/h3 {} (if (:changed? diff)
                                "与上次读取的差异"
                                "读取 stamp 对照"))
                   (dom/div {:className "hc-files-meta"}
                            (dom/span {} (str "旧 " (stamp-text (:base-stamp diff))))
                            (dom/span {} (str "新 " (stamp-text (:current-stamp diff)))))
                   (if (:changed? diff)
                     (dom/div {}
                              (dom/p {:className "hc-files-note"}
                                     (str (model/diff-summary diff)
                                          "；+ 为新增，− 为删除，空白标记为未改动。"))
                              (when (:coarse? diff)
                                (dom/p {:className "hc-files-note"}
                                       "变化范围较大，本视图按整段替换显示，不声称是逐行最小差异。"))
                              (when (:too-large? diff)
                                (dom/p {:className "hc-files-alert"}
                                       "差异超过面板显示上限，未生成完整差异。请缩小文件或另行导出查看。"))
                              (mapv hunk-view (:hunks diff)))
                     (dom/p {:className "hc-files-note"}
                            "文本未变化；文件 stamp 已变化。"))))))

(defn- preview-body
  [state callbacks preview]
  (dom/div {}
           (dom/div {:className "hc-files-meta"}
                    (dom/span {:className "hc-files-path"}
                              (or (:path preview) ""))
                    (dom/span {} (stamp-text (:stamp preview)))
                    (dom/span {} (model/format-size (:size preview))))
           (dom/div {:className "hc-files-toolbar"}
                    (dom/button {:type "button"
                                 :className "wb-button wb-button-small"
                                 :onClick (fn [_]
                                            (when-let [copy (:on-copy callbacks)]
                                              (copy (:text preview))))}
                                "复制文本")
                    (dom/button {:type "button"
                                 :className "wb-button wb-button-small"
                                 :onClick (fn [_]
                                            (when-let [open (:on-open callbacks)]
                                              (open (:path preview))))}
                                "重新读取"))
           (diff-view state)
           (if (and (:diff state) (:changed? (:diff state)))
             (dom/details {:className "hc-files-current"}
                          (dom/summary {} "当前完整文本")
                          (dom/pre {:className "hc-files-pre"}
                                   (or (:text preview) "")))
             (dom/pre {:className "hc-files-pre"}
                      (or (:text preview) "")))))

(defn- preview-view
  [state callbacks]
  (let [preview (:preview state)
        error (:preview-error state)
        retry #(when-let [path (:selected-path state)]
                 (when-let [open (:on-open callbacks)]
                   (open path)))]
    (dom/section {:className "hc-files-panel hc-files-preview"
                  :aria-label "文件预览"}
                 (cond
                   (= :loading (:preview-status state))
                   (dom/div {:className "hc-files-empty"} "正在读取服务器文本…")

                   (and (= :error (:preview-status state)) error)
                   (dom/div {}
                            (when (= :FILE_CONFLICT (get-in error [:error :code]))
                              (conflict-view error retry))
                            (when-not (= :FILE_CONFLICT (get-in error [:error :code]))
                              (dom/div {:className "hc-files-alert" :role "alert"}
                                       (dom/strong {} "文件未能读取")
                                       (dom/p {} (error-text error))
                                       (dom/button {:type "button"
                                                    :className "wb-button wb-button-small"
                                                    :onClick (fn [_] (retry))}
                                                   "重试")))
                            (when preview
                              (dom/div {:className "hc-files-previous"}
                                       (dom/h4 {} "上次成功读取的内容")
                                       (dom/p {:className "hc-files-note"}
                                              "本次失败未覆盖以下内容。")
                                       (preview-body state callbacks preview))))

                   preview
                   (preview-body state callbacks preview)

                   :else
                   (dom/div {:className "hc-files-empty"}
                            "选择一个文本文件。预览只显示服务器读取的内容，不会扫描本机路径。")))))

(defn- search-view
  [state callbacks]
  (let [search (:search state)
        mode (model/normalize-search-mode (:search-mode state))
        view-state (model/search-view-state search)
        on-open (:on-open callbacks)
        on-navigate (:on-navigate callbacks)
        change-mode (:on-search-mode-change callbacks)
        switch! (fn [next-mode]
                  (when change-mode
                    (change-mode next-mode)))
        submit! (fn [event]
                  (.preventDefault event)
                  (when-let [search-fn (:on-search callbacks)]
                    (search-fn (:search-query state))))
        shortcut! (fn [event]
                    (when (.-altKey event)
                      (let [key (.-key event)]
                        (cond
                          (contains? #{"n" "N"} key)
                          (do (.preventDefault event) (switch! :name))
                          (contains? #{"c" "C"} key)
                          (do (.preventDefault event) (switch! :content))
                          :else nil))))]
    (dom/section {:className "hc-files-panel"
                  :aria-label "工作区搜索"}
                 (dom/form {:className "hc-files-search"
                            :onSubmit submit!
                            :onKeyDown shortcut!}
                           (dom/input {:type "search"
                                       :name "workspace-file-search"
                                       :placeholder (str (model/search-mode-label mode)
                                                         "搜索工作区")
                                       :aria-label (str (model/search-mode-label mode)
                                                        "搜索工作区")
                                       :value (or (:search-query state) "")
                                       :onChange (fn [event]
                                                   (when-let [change (:on-search-change callbacks)]
                                                     (change (.. event -target -value))))})
                           (dom/div {:className "hc-files-mode-switch"
                                     :role "group"
                                     :aria-label "搜索模式"}
                                    (for [candidate model/search-modes]
                                      (dom/button
                                       {:type "button"
                                        :key (name candidate)
                                        :className "hc-files-mode-button"
                                        :aria-pressed (if (= candidate mode)
                                                        "true"
                                                        "false")
                                        :onClick (fn [_] (switch! candidate))}
                                       (model/search-mode-label candidate))))
                           (dom/button {:type "submit"
                                        :className "wb-button wb-button-small"
                                        :disabled (= :loading (:status search))}
                                       "搜索")
                           (when (or (seq (:results search))
                                     (= :loaded (:status search)))
                             (dom/button {:type "button"
                                          :className "wb-button wb-button-small"
                                          :onClick (fn [_]
                                                     (when-let [clear (:on-clear-search callbacks)]
                                                       (clear)))}
                                         "清除结果"))
                           (dom/span {:className "hc-files-search-note"}
                                     "快捷键：Alt+N 按名称，Alt+C 按内容。"))
                 (cond
                   (= :loading view-state)
                   (dom/p {:className "hc-files-note"}
                          (str "正在" (model/search-mode-label mode) "搜索…"))

                   (= :error view-state)
                   (dom/p {:className "hc-files-alert"}
                          (error-text (:error search)))

                   (= :results view-state)
                   (dom/div {}
                            (dom/p {:className "hc-files-note"}
                                   (str (model/search-mode-label (:mode search))
                                        "匹配 " (count (:results search))
                                        " 项"
                                        (when (:truncated? search)
                                          "；已达到搜索上限，结果不完整")))
                            (dom/ul {:className "wb-list"}
                                    (for [entry (:results search)]
                                      (dom/li {:key (:path entry)}
                                              (dom/button {:type "button"
                                                           :className "wb-list-button"
                                                           :onClick (fn [_]
                                                                      (if (= :directory (:kind entry))
                                                                        (when on-navigate
                                                                          (on-navigate (:path entry)))
                                                                        (when on-open
                                                                          (on-open (:path entry)))))}
                                                          (dom/div {:className "wb-list-main"}
                                                                   (dom/strong {} (:path entry))
                                                                   (dom/small {}
                                                                              (str (model/entry-type-label entry)
                                                                                   " · "
                                                                                   (if (:stamp entry)
                                                                                     (stamp-text (:stamp entry))
                                                                                     "无内容 stamp")))
                                                                   (when (:line entry)
                                                                     (dom/div {:className "hc-files-code hc-files-muted"}
                                                                              (str "第 " (:line entry)
                                                                                   " 行，第 "
                                                                                   (or (:column entry) 1)
                                                                                   " 列"
                                                                                   (when (:match-count entry)
                                                                                     (str " · 匹配 "
                                                                                          (:match-count entry)
                                                                                          " 次")))))
                                                                   (when (seq (:snippet entry))
                                                                     (dom/pre {:className "hc-files-snippet"}
                                                                              (:snippet entry)))))))))

                   (= :empty view-state)
                   (dom/p {:className "hc-files-note"}
                          (str (model/search-mode-label (:mode search))
                               "未匹配到任何项。"))

                   (= :idle view-state)
                   (dom/p {:className "hc-files-note"}
                          (str "尚未搜索。输入关键词后按“搜索”；当前为"
                               (model/search-mode-label mode) "模式。"))

                   :else
                   (dom/p {:className "hc-files-note"}
                          "搜索状态未知；请重新搜索。")))))

(defn file-browser-element
  "Render the workspace file browser.

  STATE is the `:ui/files` map.  CALLBACKS may contain `:on-navigate`,
  `:on-open`, `:on-refresh`, `:on-search`, `:on-search-change`,
  `:on-search-mode-change`, `:on-clear-search` and `:on-copy`."
  [state callbacks]
  (let [state (or state (model/initial))
        callbacks (or callbacks {})
        status (status-view state)
        on-refresh (:on-refresh callbacks)
        on-navigate (:on-navigate callbacks)
        path (or (:path state) "")
        crumbs (model/breadcrumbs path
                                  (or (get-in state [:workspace :name])
                                      "工作区"))
        parent (model/parent-path path)]
    (dom/section {:className "hc-files"
                  :aria-label "工作区文件浏览器"}
                 (dom/style #js {:key "hc-files-style"} css)
                 (dom/div {:className "wb-spread"}
                          (dom/h2 {} "工作区文件")
                          (dom/div {:className "wb-row"}
                                   (dom/span {:className "hc-files-badge"} "实例工作区")
                                   (dom/span {:className "hc-files-badge"
                                              :data-tone (name (:tone status))}
                                             (:label status))))
                 (dom/div {:className "hc-files-toolbar"}
                          (dom/div {:className "hc-files-crumbs"
                                    :role "group"
                                    :aria-label "工作区路径"}
                                   (map-indexed
                                    (fn [index crumb]
                                      (dom/span {:key (str "crumb-" (:path crumb))}
                                                (when (pos? index)
                                                  (dom/span {:className "hc-files-sep"
                                                             :aria-hidden "true"} "/"))
                                                (dom/button {:type "button"
                                                             :className "hc-files-crumb"
                                                             :aria-current (if (= (:path crumb) path)
                                                                             "true" nil)
                                                             :onClick (fn [_]
                                                                        (when on-navigate
                                                                          (on-navigate (:path crumb))))}
                                                            (:label crumb))))
                                    crumbs))
                          (dom/button {:type "button"
                                       :className "wb-button wb-button-small"
                                       :disabled (nil? parent)
                                       :title (if parent
                                                "返回上级目录"
                                                "已在工作区根目录")
                                       :onClick (fn [_]
                                                  (when (and parent on-navigate)
                                                    (on-navigate parent)))}
                                      "返回上级")
                          (dom/button {:type "button"
                                       :className "wb-button wb-button-small"
                                       :disabled (= :loading (:status state))
                                       :onClick (fn [_] (when on-refresh (on-refresh)))}
                                      "刷新")
                          (dom/span {:className "hc-files-code hc-files-muted"}
                                    (or (get-in state [:workspace :root]) "")))
                 (when (:error state)
                   (dom/div {:className "hc-files-alert" :role "alert"}
                            (dom/strong {} "目录读取失败")
                            (dom/p {} (error-text (:error state)))))
                 (search-view state callbacks)
                 (dom/div {:className "hc-files-layout"}
                          (listing-view state callbacks)
                          (preview-view state callbacks))
                 (dom/p {:className "hc-files-note"}
                        "stamp 是服务器读取时的 (mtime, size) 依据；目录不携带内容 stamp。本视图只读，不提供删除、重命名或新建入口。"))))
