(ns hc.client.speech.translation
  "Independent translation page.

  The page keeps the source text and every derived translation separate.  It
  never translates while the operator types unless the explicit auto-translate
  option is on.  Replacing a selection is allowed only when the source text
  and the exact selection captured at request time are unchanged."
  (:require ["react" :as react]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.speech.api :as api]
            [hc.client.speech.translation-model :as translation-model]))

(defn- style [value] (clj->js value))

(defn- button [label on-click & [opts]]
  (let [opts (or opts {})]
    (dom/button {:className "hc-button"
                 :type "button"
                 :disabled (boolean (:disabled? opts))
                 :aria-label (or (:aria-label opts) label)
                 :style (style (merge {:minHeight 36 :minWidth 36}
                                      (:style opts)))
                 :onClick (fn [event]
                            (.preventDefault event)
                            (on-click))}
                label)))

(def languages
  [["auto" "自动识别"]
   ["zh-CN" "简体中文"]
   ["en" "English"]
   ["ja" "日本語"]
   ["ko" "한국어"]
   ["fr" "Français"]
   ["de" "Deutsch"]
   ["es" "Español"]])

(defn- language-label [code]
  (or (second (some #(when (= code (first %)) %) languages)) code))

(defn- default-target []
  (if (and (exists? js/navigator)
           (str/starts-with? (str/lower-case (or (.-language js/navigator) ""))
                             "zh"))
    "zh-CN"
    "en"))

(defn page-element
  []
  (let [[services set-services!] (react/useState [])
        [max-chars set-max-chars!] (react/useState 20000)
        [service-id set-service-id!] (react/useState nil)
        [source set-source!] (react/useState "")
        [source-language set-source-language!] (react/useState "auto")
        [target set-target!] (react/useState (default-target))
        [results set-results!] (react/useState {})
        [status set-status!] (react/useState :idle)
        [error set-error!] (react/useState nil)
        [selection set-selection!] (react/useState {:start 0 :end 0})
        [auto? set-auto!] (react/useState false)
        [notice set-notice!] (react/useState nil)
        [undo set-undo!] (react/useState nil)]
    (letfn [(refresh! []
              (api/snapshot!
               (fn [result]
                 (when (:ok? result)
                   (let [translation (get-in result
                                             [:snapshot :speech :translation])
                         services (vec (filter :enabled? (:services translation)))
                         default-id (translation-model/select-service-id
                                     services
                                     (:default-service-id translation))]
                     (set-services! services)
                     (set-max-chars! (or (:max-text-chars translation) 20000))
                     (when-not service-id
                       (set-service-id! default-id)))))))
            (selected-fragment []
              (let [{:keys [start end]} selection]
                (when (< start end)
                  (subs source start end))))
            (translate! []
              (let [fragment (selected-fragment)
                    text (or fragment source)
                    start (:start selection)
                    end (:end selection)
                    source-at-request source]
                (cond
                  (str/blank? text)
                  (set-error! "请先输入要翻译的原文。")

                  (> (count text) max-chars)
                  (set-error! (str "文本超过 " max-chars
                                   " 字上限；原文与语言选择已保留。"))

                  (empty? services)
                  (set-error! "没有可用的翻译服务（未配置或无法读取）；请到设置→语音与朗读添加并测试。")

                  :else
                  (do
                    (set-status! :loading)
                    (set-error! nil)
                    (set-notice! nil)
                    (api/translate!
                     {:text text
                      :source-language source-language
                      :target-language target
                      :service-id service-id
                      :message-id "independent-translation"
                      :version-id (str (hash text))}
                     (fn [result]
                       (if (:ok? result)
                         (let [receipt (:receipt result)]
                           (set-results!
                            (assoc results target
                                   (assoc receipt
                                          :request-start (when fragment start)
                                          :request-end (when fragment end)
                                          :request-text (when fragment fragment)
                                          :source-at-request source-at-request
                                          :fragment? (boolean fragment))))
                           (set-status! :ready))
                         (do
                           (set-status! :error)
                           (set-error! (or (get-in result [:error :message])
                                           "翻译失败；原文与语言选择已保留。"))))))))))
            (replace-selection! [result]
              (let [plan (translation-model/replace-plan
                          {:source source
                           :selection selection
                           :result result})]
                (case (:action plan)
                  :replace
                  (do
                    (set-undo! {:source source :results results})
                    (set-source! (:text plan))
                    (set-selection! {:start (:cursor plan)
                                     :end (:cursor plan)})
                    (set-results! (assoc results target
                                         (assoc result :applied? true)))
                    (set-notice! "已替换当前选区。"))

                  :blocked
                  (set-notice! (:message plan)))))
            (insert-at-cursor! [result]
              (let [plan (translation-model/insert-plan
                          source selection (:translated-text result))]
                (set-undo! {:source source :results results})
                (set-source! (:text plan))
                (set-selection! {:start (:cursor plan)
                                 :end (:cursor plan)})
                (set-notice! "已插入到原文光标位置。")))
            (clear-source! []
              (set-undo! {:source source :results results})
              (set-source! "")
              (set-selection! {:start 0 :end 0})
              (set-error! nil)
              (set-status! :idle)
              (set-notice! "已清空原文；译文与语言选择保留。"))
            (clear-all! []
              (set-undo! {:source source :results results})
              (set-source! "")
              (set-selection! {:start 0 :end 0})
              (set-results! {})
              (set-error! nil)
              (set-status! :idle)
              (set-notice! "已清空原文与译文；可撤销。"))
            (undo-clear! []
              (when undo
                (set-source! (:source undo))
                (set-results! (:results undo))
                (set-undo! nil)
                (set-notice! "已撤销清空。")))
            (swap-languages! []
              (when (and (not= "auto" source-language)
                         (not (str/blank? target)))
                (let [old-source source-language
                      old-target target
                      old-result (get results target)]
                  (set-source-language! old-target)
                  (set-target! old-source)
                  (when old-result
                    (set-source! (:translated-text old-result)))
                  (set-results! {}))))]
      (react/useEffect
       (fn []
         (refresh!)
         (fn [] nil))
       #js [])
      (react/useEffect
       (fn []
         (when (and auto? (not (str/blank? source)) (seq services))
           (let [timer (js/setTimeout translate! 700)]
             (fn [] (js/clearTimeout timer))))
         js/undefined)
       #js [source auto? source-language target service-id])
      (let [result (get results target)
            selection? (< (:start selection) (:end selection))
            service-label (or (some (fn [service]
                                      (when (= (:id service) service-id)
                                        (or (:label service) (:id service))))
                                    services)
                              "未选择")]
        (dom/div {:style (style {:display "flex" :flexDirection "column"
                                 :gap "10px"})}
                 (dom/h2 {:style (style {:margin 0})} "独立翻译")
                 (dom/p {:className "hc-muted" :style (style {:margin 0})}
                        "原文与译文分开保留；输入时不自动调用远程服务。")
                 (when (empty? services)
                   (dom/div {:role "status"
                             :style (style {:padding "8px"
                                            :borderRadius "8px"
                                            :background "color-mix(in srgb, var(--wb-warn) 14%, transparent)"
                                            :color "var(--wb-warn)"})}
                            "没有可用的翻译服务（未配置或无法读取）；请到设置→“语音与朗读”添加并测试。"))
                 (dom/div {:style (style {:display "flex" :gap "8px"
                                          :flexWrap "wrap" :alignItems "end"})}
                          (dom/label {:style (style {:display "flex"
                                                     :flexDirection "column"
                                                     :gap "2px"})}
                                     "服务"
                                     (dom/select {:value (or service-id "")
                                                  :onChange (fn [event]
                                                              (set-service-id!
                                                               (.-value (.-target event))))
                                                  :style (style {:minHeight 34})}
                                                 (when (empty? services)
                                                   (dom/option {:value ""} "未配置"))
                                                 (for [service services]
                                                   (dom/option {:value (:id service)
                                                                :key (:id service)}
                                                               (or (:label service)
                                                                   (:id service))))))
                          (dom/label {:style (style {:display "flex"
                                                     :flexDirection "column"
                                                     :gap "2px"})}
                                     "源语言"
                                     (dom/select {:value source-language
                                                  :onChange (fn [event]
                                                              (set-source-language!
                                                               (.-value (.-target event))))
                                                  :style (style {:minHeight 34})}
                                                 (for [[code label] languages]
                                                   (dom/option {:value code :key code}
                                                               label))))
                          (dom/label {:style (style {:display "flex"
                                                     :flexDirection "column"
                                                     :gap "2px"})}
                                     "目标语言"
                                     (dom/select {:value target
                                                  :onChange (fn [event]
                                                              (set-target!
                                                               (.-value (.-target event))))
                                                  :style (style {:minHeight 34})}
                                                 (for [[code label] languages
                                                       :when (not= "auto" code)]
                                                   (dom/option {:value code :key code}
                                                               label))))
                          (button "交换语言" swap-languages!
                                  {:disabled? (= "auto" source-language)})
                          (dom/label {:style (style {:display "flex" :gap "6px"
                                                     :alignItems "center"
                                                     :minHeight "44px"})}
                                     (dom/input {:type "checkbox"
                                                 :checked auto?
                                                 :onChange (fn [event]
                                                             (set-auto!
                                                              (.-checked (.-target event))))})
                                     "自动翻译（默认关闭）"))
                 (dom/label {:style (style {:display "flex" :flexDirection "column"
                                            :gap "4px"})}
                            (str "原文 · " (count source) " / " max-chars " 字")
                            (dom/textarea {:value source
                                           :rows 8
                                           :placeholder "输入或粘贴原文；选中片段后翻译可安全替换选区。"
                                           :onChange (fn [event]
                                                       (let [target-node (.-target event)]
                                                         (set-source! (.-value target-node))
                                                         (set-selection!
                                                          {:start (.-selectionStart target-node)
                                                           :end (.-selectionEnd target-node)})))
                                           :onSelect (fn [event]
                                                       (let [target-node (.-target event)]
                                                         (set-selection!
                                                          {:start (.-selectionStart target-node)
                                                           :end (.-selectionEnd target-node)})))
                                           :style (style {:minHeight "160px"
                                                          :width "100%"
                                                          :boxSizing "border-box"
                                                          :font "inherit"})}))
                 (dom/div {:style (style {:display "flex" :gap "8px"
                                          :flexWrap "wrap" :alignItems "center"})}
                          (button (if (= status :loading) "正在翻译…" "翻译")
                                  translate!
                                  {:disabled? (or (= status :loading)
                                                  (empty? services)
                                                  (str/blank? source))})
                          (button "清空原文" clear-source!)
                          (button "清空全部" clear-all!)
                          (when undo
                            (button "撤销清空" undo-clear!))
                          (dom/span {:style (style {:fontSize ".82rem"
                                                    :color "var(--wb-ink-muted)"})}
                                    (str "服务：" service-label)))
                 (when error
                   (dom/div {:role "alert"
                             :style (style {:color "var(--wb-danger)"})}
                            error))
                 (when notice
                   (dom/div {:role "status"
                             :style (style {:color "var(--wb-ink-muted)"})}
                            notice))
                 (when result
                   (dom/div {:style (style {:borderTop "1px solid var(--wb-line)"
                                            :paddingTop "8px"
                                            :display "flex"
                                            :flexDirection "column"
                                            :gap "6px"})}
                            (dom/div {:style (style {:fontSize ".85rem"
                                                     :color "var(--wb-ink-muted)"})}
                                     (str "译文 · " (language-label (:source-language result))
                                          " → " (language-label (:target-language result))
                                          " · 服务 " (get-in result [:service :id])))
                            (dom/pre {:style (style {:whiteSpace "pre-wrap"
                                                     :margin 0
                                                     :minHeight "80px"})}
                                     (:translated-text result))
                            (dom/div {:style (style {:display "flex" :gap "8px"
                                                     :flexWrap "wrap"})}
                                     (button "复制译文"
                                             #(when (exists? js/navigator.clipboard)
                                                (.writeText (.-clipboard js/navigator)
                                                            (:translated-text result))))
                                     (button "替换选区"
                                             #(replace-selection! result)
                                             {:disabled? (not selection?)})
                                     (button "插入到光标"
                                             #(insert-at-cursor! result)))
                            (when (:applied? result)
                              (dom/div {:role "status"
                                        :style (style {:fontSize ".82rem"})}
                                       "已替换当前选区；原文已修改，不能再次替换旧位置。")))))))))
