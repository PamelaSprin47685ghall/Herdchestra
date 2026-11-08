(ns hc.client.conversation.selection-dom-test
  "Real React DOM regression for a reader's selection during streaming.

  Blink resets a Range's offsets when character data is replaced through
  `nodeValue`; React updates a growing streamed paragraph that way.  This
  harness mounts the real conversation surface, selects a paragraph, appends
  streamed text, and simulates that browser reset.  The surface must put the
  same selection back without dropping the arriving text, changing the
  composer draft, or resurrecting a selection the operator deliberately
  cleared.

  This is verification-only; it is not part of the production build."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.view :as conversation]
            [hc.client.speech.playback :as speech-playback]
            [hc.client.speech.ui :as speech-ui]))

(defn- act!
  [f]
  ((.-act react) f))

(defn- base-props
  [content]
  {:session {:id "s1" :title "选择回归" :status :running}
   :messages [{:message/id "m1"
               :message/role :user
               :message/content "E2E03 长回复"
               :message/status :completed
               :message/created-at "2026-11-05T00:00:00Z"}
              {:message/id "m2"
               :message/role :assistant
               :message/content content
               :message/status :streaming
               :message/created-at "2026-11-05T00:00:01Z"}]
   :draft (assoc draft/empty-draft :text "E2E03 下一条草稿")
   :stream (assoc (stream/initial)
                  :streaming? true
                  :active-message-id "m2"
                  :content-version (count content))
   :connection {:phase :synced}
   :layout :wide
   :platform :desktop
   :callbacks {:send! (fn [_] nil)
               :stop! (fn [_] nil)
               :edit-draft! (fn [_] nil)
               :scroll! (fn [_] nil)}})

(defn- render!
  [root props]
  (.render root
           (react/createElement conversation/conversation-page
                                #js {:props props})))

(defn- mount!
  [content]
  (let [container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (act! #(render! root (base-props content)))
    {:container container :root root}))

(defn- unmount!
  [{:keys [container root]}]
  (act! #(.unmount root))
  (.remove container))

(defn- messages-node
  [container]
  (.querySelector container ".hc-conversation-messages"))

(defn- paragraph
  "The assistant message is the last message, so its streamed text is the last
  `.hc-text` paragraph."
  [container]
  (last (array-seq (.querySelectorAll container ".hc-text"))))

(defn- selection-text
  []
  (str (.getSelection js/window)))

(defn- clear-selection!
  []
  (when-let [selection (.getSelection js/window)]
    (.removeAllRanges selection)))

(defn- select-paragraph!
  "Select one paragraph and let the surface capture it through its mouseup
  handler, the same gesture a reader uses."
  [container]
  (let [range (.createRange js/document)]
    (.selectNodeContents range (paragraph container))
    (let [selection (.getSelection js/window)]
      (.removeAllRanges selection)
      (.addRange selection range))
    (act! (fn []
            (.dispatchEvent (messages-node container)
                            (js/MouseEvent. "mouseup" #js {:bubbles true}))))
    (selection-text)))

(defn- with-content
  [props content]
  (-> props
      (assoc-in [:messages 1 :message/content] content)
      (assoc-in [:messages 1 :message/content-version] (count content))
      (assoc-in [:stream :content-version] (count content))))

(defn- stream!
  [root props content]
  (act! #(render! root (with-content props content))))

(deftest streamed-growth-keeps-the-readers-selection
  (clear-selection!)
  (let [{:keys [container root]} (mount! "第一段：可选择的文字。\n\n第二段：继续阅读。")
        props (base-props "第一段：可选择的文字。\n\n第二段：继续阅读。")]
    (try
      (let [selected (select-paragraph! container)]
        (is (str/includes? selected "第一段")
            "the fixture selects real conversation text")
        (testing "ordinary growth keeps the live selection"
          (stream! root props
                   "第一段：可选择的文字。\n\n第二段：继续阅读。\n\n第三段：流式到达。")
          (is (str/includes? (selection-text) "第一段"))
          (is (str/includes? (.-textContent (paragraph container)) "流式到达"))
          (is (= "E2E03 下一条草稿"
                 (.-value (.querySelector container
                                          "textarea[aria-label='消息输入']")))
              "streaming never rewrites the composer draft"))
        (testing "a browser character-data reset is repaired after the commit"
          ;; Blink collapses offsets when React replaces `nodeValue`.  The
          ;; surface's layout effect must restore the same range.
          (clear-selection!)
          (stream! root (with-content props
                                       "第一段：可选择的文字。\n\n第二段：继续阅读。\n\n第三段：流式到达。")
                   "第一段：可选择的文字。\n\n第二段：继续阅读。\n\n第三段：流式到达。\n\n第四段：仍在接收。")
          (is (str/includes? (selection-text) "第一段")
              "the selection is restored from its recorded character range")
          (is (= selected (selection-text))
              "the restored range keeps exactly the text the reader selected")
          (is (str/includes? (.-textContent (paragraph container)) "仍在接收")
              "the stream continued while the selection was protected")))
      (finally
        (clear-selection!)
        (unmount! {:container container :root root})))))

(deftest deliberately-cleared-selection-is-not-resurrected
  (clear-selection!)
  (let [{:keys [container root]} (mount! "第一段：可选择的文字。\n\n第二段：继续阅读。")
        props (base-props "第一段：可选择的文字。\n\n第二段：继续阅读。")]
    (try
      (is (str/includes? (select-paragraph! container) "第一段"))
      (let [textarea (.querySelector container "textarea[aria-label='消息输入']")]
        (act! (fn []
                (.dispatchEvent textarea
                                (js/Event. "pointerdown" #js {:bubbles true}))))
        (clear-selection!)
        (stream! root props
                 "第一段：可选择的文字。\n\n第二段：继续阅读。\n\n第三段：流式到达。")
        (is (= "" (selection-text))
            "a pointer gesture that starts a new interaction ends the old range")
        (is (str/includes? (.-textContent (paragraph container)) "流式到达")))
      (finally
        (clear-selection!)
        (unmount! {:container container :root root})))))

(deftest replaced-text-does-not-revive-a-stale-range
  (clear-selection!)
  (let [{:keys [container root]} (mount! "第一段：可选择的文字。\n\n第二段：继续阅读。")
        props (base-props "第一段：可选择的文字。\n\n第二段：继续阅读。")]
    (try
      (is (str/includes? (select-paragraph! container) "第一段"))
      (clear-selection!)
      (stream! root props "完全不同的正文，不再包含原来的段落。")
      (is (= "" (selection-text))
          "a version/edit replacement never selects unrelated text")
      (is (str/includes? (.-textContent (paragraph container)) "完全不同的正文"))
      (finally
        (clear-selection!)
        (unmount! {:container container :root root})))))

(deftest ask-card-appears-after-mount-without-changing-parent-hooks
  (clear-selection!)
  (let [{:keys [container root]} (mount! "第一段")
        props (base-props "第一段")
        submitted (atom nil)
        skipped (atom nil)
        ask {:ask-id "ask-1"
             :name "ask-target"
             :question "继续？"
             :options [{:value "yes" :label "是"}]
             :input? false
             :multiple? false
             :status :pending
             :created-at "t"
             :draft {:text "" :selected #{}}
             :answers []
             :submit {:label "提交回答并恢复"
                      :aria-label "提交回答并恢复挂起的程序"
                      :target-size 44}
             :aria {:label "提问：继续？"
                    :describedby "hc-ask-ask-1-status"}}]
    (try
      ;; The first render has no ask.  Adding one must not append hooks to the
      ;; parent; the card is a real component with its own state slots.
      (act! #(render! root (assoc props
                                  :ask {"ask-1" ask}
                                  :callbacks (assoc (:callbacks props)
                                                    :submit-ask! (fn [value]
                                                                   (reset! submitted value))
                                                    :skip-ask! (fn [value]
                                                                 (reset! skipped value))))))
      (is (some? (.querySelector container ".hc-ask")))
      (let [option (.querySelector container ".hc-ask-option input")]
        (act! #(.click option)))
      (let [button (.querySelector container ".hc-ask button")]
        (is (false? (.-disabled button)))
        (act! #(.click button)))
      (is (= "ask-1" (:ask-id @submitted)))
      (is (= "yes" (:answer @submitted)))
      (let [skip-button (last (array-seq (.querySelectorAll container ".hc-ask button")))]
        (is (false? (.-disabled skip-button)))
        (act! #(.click skip-button)))
      (is (= "ask-1" (:ask-id @skipped))
          "skip is an explicit operator action with the card identity")
      (testing "an answered card freezes and shows the option label"
        (let [answered (assoc ask
                              :ask-id "ask-2"
                              :status :answered
                              :answers [{:record-id "ask-2-a1"
                                         :command-id "c1"
                                         :value "yes"
                                         :label "是"
                                         :status :confirmed}]
                              :aria {:label "提问：继续？"
                                     :describedby "hc-ask-ask-2-status"})]
          (act! #(render! root (assoc props
                                      :ask {"ask-2" answered}
                                      :callbacks (assoc (:callbacks props)
                                                        :submit-ask! (fn [_] nil)
                                                        :skip-ask! (fn [_] nil)))))
          (is (str/includes? (.-textContent (.querySelector container ".hc-ask-status"))
                             "已回答"))
          (is (str/includes? (.-textContent (.querySelector container ".hc-ask-answers"))
                             "是")
              "the record shows the option label, not the internal value")
          (let [buttons (array-seq (.querySelectorAll container ".hc-ask button"))]
            (is (true? (.-disabled (first buttons))))
            (is (true? (.-disabled (last buttons)))
                "an answered card cannot be skipped again"))))
      (finally
        (clear-selection!)
        (unmount! {:container container :root root})))))

(deftest translation-controls-receive-selection-and-safe-replace
  (clear-selection!)
  (let [captured (atom nil)
        replaced (atom nil)
        inserted (atom nil)
        base (base-props "answer")
        draft-state (assoc draft/empty-draft
                           :text "E2E03 下一条草稿"
                           :cursor 5
                           :selection {:start 0 :end 5}
                           :version 7)
        props (assoc base
                     :draft draft-state
                     :callbacks (assoc (:callbacks base)
                                       :insert-transcript! (fn [value]
                                                             (reset! inserted value))
                                       :replace-transcript! (fn [value]
                                                              (reset! replaced value))))
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)
        probe (fn [js-props]
                (reset! captured
                        {:selection (when-let [selection (.-selection js-props)]
                                      (js->clj selection :keywordize-keys true))
                         :draft-text (.-draftText js-props)
                         :on-replace (.-onReplace js-props)})
                (react/createElement "span" nil "translation-probe"))]
    (try
      (with-redefs [speech-ui/translation-controls probe]
        (act! #(render! root props))
        (is (= {:start 0 :end 5 :text "E2E03" :draft-version 7}
               (:selection @captured))
            "the panel receives the request-time draft selection")
        (is (= "E2E03 下一条草稿" (:draft-text @captured)))
        (is (fn? (:on-replace @captured)))
        ((:on-replace @captured) {:start 0 :end 5
                                  :expected-text "E2E03"
                                  :text "译文"})
        (is (= {:session-id "s1" :start 0 :end 5
                :expected-text "E2E03" :text "译文"}
               @replaced)
            "a matching selection replaces through the workbench callback")
        (is (nil? @inserted))
        (act! #(render! root
                        (assoc props
                               :draft (assoc draft-state
                                             :cursor 10
                                             :selection {:start 6 :end 10}))))
        (is (= {:start 6 :end 10 :text "下一条草" :draft-version 7}
               (:selection @captured)))
        ((:on-replace @captured) {:start 0 :end 5
                                  :expected-text "E2E03"
                                  :text "译文"})
        (is (= {:session-id "s1" :insert-at 6 :text "译文"}
               @inserted)
            "a changed selection falls back to inserting at the current caret")
        (is (= {:session-id "s1" :start 0 :end 5
                :expected-text "E2E03" :text "译文"}
               @replaced)
            "the stale request never reaches the replace mutation"))
      (finally
        (clear-selection!)
        (act! #(.unmount root))
        (.remove container)))))

(deftest translation-replacement-uses-the-existing-draft-edit
  (clear-selection!)
  (let [captured (atom nil)
        edited (atom nil)
        base (base-props "answer")
        draft-state (assoc draft/empty-draft
                           :text "E2E03 下一条草稿"
                           :cursor 5
                           :selection {:start 0 :end 5}
                           :version 7)
        props (assoc base
                     :draft draft-state
                     :callbacks (assoc (:callbacks base)
                                       :edit-draft! (fn [value]
                                                      (reset! edited value))))
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)
        probe (fn [js-props]
                (reset! captured {:selection (when-let [selection (.-selection js-props)]
                                               (js->clj selection :keywordize-keys true))
                                  :on-replace (.-onReplace js-props)})
                (react/createElement "span" nil "translation-probe"))]
    (try
      (with-redefs [speech-ui/translation-controls probe]
        (act! #(render! root props))
        (is (= {:start 0 :end 5 :text "E2E03" :draft-version 7}
               (:selection @captured))
            "the existing draft edit is enough to offer replacement")
        ((:on-replace @captured) {:start 0 :end 5
                                  :expected-text "E2E03"
                                  :text "译文"})
        (is (= {:session-id "s1"
                :text "译文 下一条草稿"
                :cursor 2
                :selection {:start 2 :end 2}
                :composing? false}
               @edited)
            "the range is replaced exactly and the caret follows the text"))
      (finally
        (clear-selection!)
        (act! #(.unmount root))
        (.remove container)))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(deftest per-session-scroll-position-is-restored
  (let [saved (atom nil)
        props-for
        (fn [session-id content]
          (-> (base-props content)
              (assoc :session {:id session-id :title session-id :status :running})
              (assoc-in [:callbacks :scroll!]
                        (fn [{:keys [scroll-top]}]
                          (reset! saved scroll-top)))))
        s0 (props-for "s0" "s0 的长消息")
        s1 (props-for "s1" "s1 的另一条消息")
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (try
      (act! #(render! root s0))
      (let [node (messages-node container)]
        (set! (.-scrollTop node) 12345)
        (act! #(.dispatchEvent node (js/Event. "scroll" #js {:bubbles true})))
        (is (= 12345 @saved) "the surface reports the real scroll offset")
        ;; Switch away and clear the reused DOM node to make the restore
        ;; observable, then switch back to the session with the saved offset.
        (act! #(render! root s1))
        (set! (.-scrollTop (messages-node container)) 0)
        (act! #(render! root (assoc-in s0 [:stream :scroll-top] @saved)))
        (is (= 12345 (.-scrollTop (messages-node container)))
            "returning to the session restores the reading position"))
      (finally
        (act! #(.unmount root))
        (.remove container)))))

(deftest in-page-find-reports-counts-and-closes
  (let [messages [{:message/id "m1"
                   :message/role :user
                   :message/content "alpha first"
                   :message/status :completed}
                  {:message/id "m2"
                   :message/role :assistant
                   :message/content "alpha second alpha"
                   :message/process [{:label "alpha folded"}]
                   :message/status :completed}]
        props (-> (base-props "alpha first")
                  (assoc :messages messages)
                  (assoc :find-seq 0))
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (try
      (act! #(render! root props))
      (act! #(render! root (assoc props :find-seq 1)))
      (let [bar (.querySelector container ".hc-find")
            input (.querySelector container ".hc-find-input")]
        (is (some? bar) "Ctrl/Cmd+F opens the in-page find bar")
        ;; React tracks the input value; use the prototype setter so the
        ;; dispatched input event is not swallowed as an unchanged value.
        (let [setter (.-set (js/Object.getOwnPropertyDescriptor
                             (.-prototype js/window.HTMLInputElement)
                             "value"))]
          (.call setter input "alpha"))
        (act! #(.dispatchEvent input (js/Event. "input" #js {:bubbles true})))
        (is (= "3 个命中"
               (.-textContent (.querySelector container ".hc-find-count"))))
        (let [node (messages-node container)
              before (.-scrollTop node)]
          (act! #(.click (.querySelector container
                                         "button[aria-label='下一个命中']")))
          (is (= before (.-scrollTop node))
              "find navigation does not reset the reading position")
          (is (some (fn [details] (.-open details))
                    (array-seq (.querySelectorAll container
                                                  "details.hc-process")))
              "a hit inside a folded process block opens it")
          (act! #(.click (.querySelector container
                                         "button[aria-label='关闭页内查找']")))
          (is (nil? (.querySelector container ".hc-find")))
          (is (= before (.-scrollTop node))
              "closing find keeps the reader's position")))
      (finally
        (act! #(.unmount root))
        (.remove container)))))



(deftest stream-stop-status-and-owner-blocked-are-visible
  (let [props (base-props "普通内容")
        props (assoc props :stream (assoc (stream/initial)
                                          :stop-state :stopping
                                          :streaming? true))
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (try
      (act! #(render! root props))
      (is (= "正在停止"
             (.-textContent (.querySelector container ".hc-stream-status"))))
      (act! #(render! root (assoc props :stream (assoc (stream/initial)
                                                       :stop-state :stopped))))
      (is (= "已停止"
             (.-textContent (.querySelector container ".hc-stream-status"))))
      (act! #(render! root (assoc props
                                  :blocked {:code :TOOL_ADMISSION_REFUSED_LIMIT
                                            :member "worker-1"
                                            :count 3
                                            :last-code :tool-failed
                                            :remedy "检查工具准入配置"})))
      (let [blocked (.querySelector container ".hc-blocked")]
        (is (some? blocked))
        (is (str/includes? (.-textContent blocked) "权限被关闭，或执行结果无法确认")
            "the reason category renders before the concrete blocked detail")
        (is (str/includes? (.-textContent blocked) "worker-1"))
        (is (str/includes? (.-textContent blocked) "3"))
        (is (str/includes? (.-textContent blocked) "tool-failed"))
        (is (str/includes? (.-textContent blocked) "检查工具准入配置")))
      (finally
        (act! #(.unmount root))
        (.remove container)))))

(deftest s1-ask-card-submits-an-unpark-answer
  (let [submitted (atom nil)
        props (-> (base-props "普通内容")
                  (assoc :ask {:ask-id "a1" :name "plan"
                               :question "继续吗？"
                               :input? true
                               :status :pending
                               :draft {:text "" :selected #{} :version 0}
                               :answers []
                               :options [{:value "yes" :label "继续"}]})
                  (assoc-in [:callbacks :submit-ask!]
                            (fn [value] (reset! submitted value))))
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (try
      (act! #(render! root props))
      (is (some? (.querySelector container ".hc-ask")))
      (is (str/includes? (.-textContent container) "需要你回答"))
      (let [radio (.querySelector container "input[type=radio]")]
        (act! #(.click radio)))
      (let [button (first (filter (fn [b] (= "提交回答并恢复" (str/trim (.-textContent b)))) (array-seq (.querySelectorAll container "button"))))]
        (act! #(.click button)))
      (is (= "a1" (:ask-id @submitted)))
      (is (= ["yes"] (:selected @submitted)))
      (finally
        (act! #(.unmount root))
        (.remove container)))))



(deftest round-context-panel-shows-sections-and-unknowns
  (let [props (assoc (base-props "普通内容")
                     :session
                     {:id "s1" :title "上下文"
                      :context
                      {:outbound
                       {:source :last-run
                        :system {:prompt-bytes :unknown :modules ["pair-hint"]}
                        :assistant {:id "a1" :name "Steward" :revision 3}
                        :instructions {:session-override? false :text-preview "简短指令"}
                        :memory {:enabled? true
                                 :reason :ok
                                 :items [{:id "m1" :summary "偏好中文"
                                          :category :expression-preference
                                          :scope {:kind :global}
                                          :version 2}]
                                 :omitted [{:id "m2" :summary "长偏好"
                                            :reason :budget :chars 2400}]
                                 :archived-excluded :unknown}
                        :worldbook {:enabled-books ["wb1"]
                                    :provided [{:entry "e1" :reason :always}]
                                    :hit-not-provided [{:entry "e2" :reason :budget}]}
                        :instruction-cards [{:name "格式卡" :scope :turn :version 1
                                             :body-preview "先列假设"}]
                        :tools [{:name "files.read" :read-write :read :enabled? true
                                 :description "读取工作区文件"}]
                        :history {:range {:message-ids ["m1" "m2"]
                                          :summary-ids []
                                          :rounds 1}
                                  :visible-count 5 :excluded-count 3}
                        :attachments [{:attachment-id "a1" :name "scan.pdf"
                                       :kind :pdf :pages [2 3] :page-count 3}]
                        :estimates {:tokens :unknown :kind :unknown}}}})
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (try
      (act! #(render! root props))
      (let [text (.-textContent container)]
        (is (str/includes? text "本轮上下文 · 上次发送"))
        (is (str/includes? text "身份：a1"))
        (is (str/includes? text "偏好中文"))
        (is (str/includes? text "命中但未提供 e2"))
        (is (str/includes? text "命中但未提供 长偏好"))
        (is (str/includes? text "超出本轮预算"))
        (is (str/includes? text "格式卡 · turn"))
        (is (str/includes? text "files.read · read"))
        (is (str/includes? text "读取工作区文件"))
        (is (str/includes? text "页 2,3"))
        (is (str/includes? text "未知")))
      (finally
        (act! #(.unmount root))
        (.remove container)))))



(deftest branch-session-keeps-messages-and-export
  (let [props (-> (base-props "分支会话内容")
                  (assoc :session {:id "branch-1" :title "分支"
                                   :context {:outbound {}}})
                  (assoc :messages [{:message/id "m1"
                                     :message/role :user
                                     :message/content "分支起点"
                                     :message/status :completed}
                                    {:message/id "m2"
                                     :message/role :assistant
                                     :message/content "分支回答"
                                     :message/status :completed}]))
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (try
      (act! #(render! root props))
      (is (= 2 (count (array-seq (.querySelectorAll container ".hc-message")))))
      (is (some (fn [b] (= "导出当前分支" (str/trim (.-textContent b))))
                (array-seq (.querySelectorAll container "button"))))
      (finally
        (act! #(.unmount root))
        (.remove container)))))

(deftest version-switch-stops-read-aloud
  ;; A version switch changes the visible transcript.  Read-aloud belongs to
  ;; the old version, so the selector must stop playback in the same gesture;
  ;; leaving it running makes the player claim to read text that is no longer
  ;; displayed.
  (let [stopped (atom 0)
        props (-> (base-props "版本正文")
                  (assoc :messages [{:message/id "m1"
                                     :message/role :assistant
                                     :message/content "版本二正文"
                                     :message/status :completed
                                     :versions [{:version-id "v1" :content "版本一正文"}
                                                {:version-id "v2" :content "版本二正文"}]
                                     :current-version 1}])
                  (assoc :callbacks {:history/select-version! (fn [_] nil)}))
        container (.createElement js/document "div")
        _ (.appendChild js/document.body container)
        root (rdc/createRoot container)]
    (try
      (with-redefs [speech-playback/stop! (fn [] (swap! stopped inc))]
        (act! #(render! root props))
        (let [buttons (array-seq (.querySelectorAll container ".hc-version-button"))]
          (is (= 2 (count buttons)))
          (act! #(.click (first buttons))))
        (is (= 1 @stopped)
            "switching versions stops the previous read-aloud exactly once"))
      (finally
        (act! #(.unmount root))
        (.remove container)))))

(defn -main
  [& _]
  (run-tests 'hc.client.conversation.selection-dom-test))

(set! *main-cli-fn* -main)
