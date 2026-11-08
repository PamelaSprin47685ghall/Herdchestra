(ns hc.client.appearance.view
  "Pure appearance/accessibility settings tree.

  The tree uses the same hiccup-like node and event-descriptor shape as
  `hc.client.settings.view`, so the settings React adapter can render it
  without a second component system.  Controls are native buttons, a range
  input, and checkboxes; keyboard behavior comes from the platform."
  (:require [hc.client.appearance.model :as model]))

(defn- flatten-children
  [children]
  (vec
   (mapcat (fn [child]
             (cond
               (nil? child) []
               (and (sequential? child) (not (string? child)))
               (flatten-children child)
               :else [child]))
           children)))

(defn- el
  ([tag] {:tag tag :attrs {} :children []})
  ([tag attrs] {:tag tag :attrs (or attrs {}) :children []})
  ([tag attrs & children]
   {:tag tag
    :attrs (or attrs {})
    :children (flatten-children children)}))

(defn- act
  [type & kvs]
  (assoc (apply hash-map kvs) :type type))

(defn- ev
  [action]
  {:action action})

(defn- ev-value
  [action]
  {:action action :value :target-value})

(defn- ev-checked
  [action]
  {:action action :value :target-checked})

(defn- button
  ([label action]
   (button label action {}))
  ([label action attrs]
   (el :button
       (merge {:type :button
               :class "hc-button hc-appearance-button"}
              attrs
              {:on-click (ev action)})
       label)))

(defn- theme-label
  [theme]
  (case theme
    :system "跟随系统"
    :light "浅色"
    :dark "深色"
    (name theme)))

(defn- reduced-motion-label
  [value]
  (case value
    :system "跟随系统"
    :reduce "减少动态"
    :no-preference "标准动态"
    "跟随系统"))

(defn- language-label
  [value]
  (case value
    :en "English"
    "简体中文"))

(defn- background-label
  [value]
  (case value
    :light "浅色背景"
    :dark "深色背景"
    "跟随主题"))

(defn- bubble-label
  [value]
  (case value
    :compact "紧凑气泡"
    :rounded "圆角气泡"
    "标准气泡"))

(defn- choice-row
  [for-id label options value set-action]
  (el :label {:class "hc-field" :for for-id}
      (el :span {:class "hc-field__label"} label)
      (el :select {:id for-id
                   :class "hc-select"
                   :value (name value)
                   :on-change (ev-value set-action)}
          (mapv (fn [[option label]]
                  (el :option {:value (name option) :key (name option)} label))
                options))))

(defn- extra-rows
  [state]
  (el :div {:class "hc-appearance-toggles"}
      (choice-row "hc-appearance-language" "界面语言"
                  [[:zh-CN "简体中文"] [:en "English"]]
                  (:language state)
                  (act :appearance/set-language))
      (choice-row "hc-appearance-background" "背景"
                  [[:system "跟随主题"] [:light "浅色背景"] [:dark "深色背景"]]
                  (:background state)
                  (act :appearance/set-background))
      (choice-row "hc-appearance-bubble" "消息气泡"
                  [[:standard "标准气泡"] [:compact "紧凑气泡"] [:rounded "圆角气泡"]]
                  (:bubble state)
                  (act :appearance/set-bubble))))

(defn- theme-row
  [state]
  (el :div {:class "hc-appearance-row"
            :role "group"
            :aria-label "主题"
            :style {:display "flex" :flexWrap "wrap" :gap "8px"}}
      (mapv (fn [theme]
              (button (theme-label theme)
                      (act :appearance/set-theme :theme theme)
                      {:key (name theme)
                       :aria-pressed (if (= theme (:theme state)) "true" "false")
                       :class (str "hc-button hc-appearance-button"
                                   (when (= theme (:theme state))
                                     " hc-button--primary"))}))
            model/themes)))

(defn- font-row
  [state]
  (el :div {:class "hc-appearance-font"}
      (el :label {:class "hc-field"
                  :for "hc-appearance-font-scale"}
          (el :span {:class "hc-field__label"} "字号")
          (el :input {:id "hc-appearance-font-scale"
                      :class "hc-input hc-appearance-range"
                      :type "range"
                      :min model/min-font-scale
                      :max model/max-font-scale
                      :step model/font-step
                      :value (:font-scale state)
                      :aria-valuemin model/min-font-scale
                      :aria-valuemax model/max-font-scale
                      :aria-valuenow (:font-scale state)
                      :aria-valuetext (str (:font-scale state) "%")
                      :on-input (ev-value (act :appearance/set-font-scale))})
          (el :output {:class "hc-appearance-value"
                       :for "hc-appearance-font-scale"
                       :aria-live "polite"}
              (str (:font-scale state) "%")))
      (el :div {:class "hc-appearance-row"
                :style {:display "flex" :flexWrap "wrap" :gap "8px"}}
          (button "减小字号"
                  (act :appearance/decrease-font)
                  {:aria-label "减小字号" :disabled (= (:font-scale state)
                                                       model/min-font-scale)})
          (button "增大字号"
                  (act :appearance/increase-font)
                  {:aria-label "增大字号" :disabled (= (:font-scale state)
                                                       model/max-font-scale)})
          (button "字号恢复 100%"
                  (act :appearance/reset-font)
                  {:class "hc-button hc-appearance-button hc-button--quiet"})
          (button "外观恢复默认"
                  (act :appearance/reset)
                  {:class "hc-button hc-appearance-button hc-button--quiet"}))))

(defn- toggle-row
  [state]
  (el :div {:class "hc-appearance-toggles"}
      (el :label {:class "hc-checkbox"
                  :for "hc-appearance-contrast"}
          (el :input {:id "hc-appearance-contrast"
                      :type "checkbox"
                      :checked (= :high (:contrast state))
                      :on-change (ev-checked (act :appearance/set-contrast))})
          (el :span {} "高对比度"))
      (el :label {:class "hc-field"
                  :for "hc-appearance-reduced-motion"}
          (el :span {:class "hc-field__label"} "减少动态效果")
          (el :select {:id "hc-appearance-reduced-motion"
                       :class "hc-select"
                       :value (name (:reduced-motion state))
                       :on-change (ev-value
                                   (act :appearance/set-reduced-motion))}
              (mapv (fn [[value label]]
                      (el :option {:value (name value) :key (name value)}
                          label))
                    [[:system "跟随系统"]
                     [:reduce "减少动态"]
                     [:no-preference "标准动态"]])))))

(defn settings-tree
  "Return the appearance section tree for a partial or complete STATE."
  [state]
  (let [state (model/normalize state)]
    (el :section {:class "hc-panel hc-appearance"
                  :aria-labelledby "hc-appearance-title"}
        (el :h2 {:id "hc-appearance-title"} "外观与无障碍")
        (el :p {:class "hc-muted"}
            "主题、字号、对比度、语言、背景、气泡与动态效果只影响本浏览器；不改变服务器状态或会话内容。")
        (el :fieldset {:class "hc-appearance-fieldset"
                       :style {:border "0" :padding "0" :margin "0"}}
            (el :legend {:class "hc-field__label"} "主题")
            (theme-row state))
        (font-row state)
        (extra-rows state)
        (toggle-row state)
        (el :p {:class "hc-muted hc-appearance-status"
                :role "status"
                :aria-live "polite"
                :aria-atomic "true"}
            (str "当前：" (theme-label (:theme state))
                 " · " (:font-scale state) "%"
                 " · " (if (= :high (:contrast state)) "高对比度" "标准对比度")
                 " · " (reduced-motion-label (:reduced-motion state))
                 " · " (language-label (:language state))
                 " · " (background-label (:background state))
                 " · " (bubble-label (:bubble state))))
        (el :p {:class "hc-muted"}
            "键盘：Tab 聚焦，方向键调整字号，空格切换复选选项。"))))
