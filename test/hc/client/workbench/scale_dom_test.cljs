(ns hc.client.workbench.scale-dom-test
  "§8.5 client scale probe: 1,000 sessions and 2,000 visible activities.

  jsdom has no layout engine, so scroll numbers are event-handler costs, not
  paint costs.  The probe records that limitation explicitly.  The real
  browser's 20k draft/undo/crash-recovery path is covered by
  `test/js/scale_draft_probe.cjs`."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.conversation.draft :as draft]
            [hc.client.settings :as settings]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.views.shell :as shell]))

(comp/defsc ShellRoot
  [_this _props]
  {:query [:ui/route]
   :ident (fn [] [:root/id :root])}
  nil)

(defn- now-ms []
  (js/performance.now))

(defn- session-row
  [i]
  {:session-id (str "s" i)
   :title (str "会话 " i " 配置改造与发布计划")
   :status (if (even? i) :idle :finished)
   :updated-at (str "2026-11-05T08:" (mod i 60) ":00Z")
   :model-label (if (even? i) "model-a" "model-b")
   :message-ids []})

(defn- message-row
  [i]
  (let [kind (mod i 5)
        content (case kind
                  0 (str "普通消息 " i " 中文与 English 混排。")
                  1 (str "```clojure\n(defn f" i " [x] (+ x " i "))\n```")
                  2 "| 列 A | 列 B |\n|---|---|\n| 1 | 2 |"
                  3 (str "工具结果 " i)
                  4 (str "引用与附件消息 " i))]
    {:message-id (str "m" i)
     :session-id "s0"
     :role (if (even? i) :user :assistant)
     :content content
     :content-version (mod i 3)
     :citations (when (= kind 4)
                  [{:title (str "来源 " i)
                    :url (str "https://example.test/" i)}])
     :attachments (when (= kind 4)
                    [{:id (str "a" i) :name "note.txt" :status :ready}])
     :tool-calls (when (= kind 3)
                   [{:tool-call-id (str "t" i) :status :succeeded}])}))

(defn- build-db
  [session-count message-count]
  (let [sessions (into {} (map (fn [i] [(str "s" i) (session-row i)]))
                       (range session-count))
        messages (into {} (map (fn [i] [(str "m" i) (message-row i)]))
                       (range message-count))
        order (mapv #(str "m" %) (range message-count))]
    {:ui/route :sessions
     :ui/selected-session-id "s0"
     :ui/selection-seq 0
     :ui/viewport-width 1280
     :ui/sidebar-open false
     :ui/aux-open true
     :ui/aux-tab :details
     :ui/session-filter ""
     :ui/sidebar-return-id "wb-menu"
     :ui/aux-return-id "wb-aux-toggle"
     :ui/focus-target nil
     :ui/focus-seq 0
     :ui/connection (model/connection-view
                     {:phase :synced :subscription 3 :epoch 7 :seq 40})
     :ui/settings (settings/initial-state)
     :ui/materials {}
     :ui/extensions {}
     :ui/notices []
     :ui/draft {"s0" (assoc draft/empty-draft :text "")}
     :ui/stream {}
     :ui/command {}
     :ui/ask {}
     :ui/run {}
     :ui/nudge {}
     :ui/search ""
     :ui/search-state {}
     :ui/theme :system
     :ui/font-scale 100
     :session/by-id (assoc sessions "s0"
                           (assoc (session-row 0) :message-ids order))
     :message/by-id messages
     :message/order order}))

(defn- mount!
  [db]
  (let [app (application/headless-synchronous-app ShellRoot {:initial-db db})
        container (.createElement js/document "div")
        root (rdc/createRoot container)]
    (.appendChild js/document.body container)
    {:app app :container container :root root}))

(defn- act!
  [f]
  ((.-act react) f))

(defn- render!
  [{:keys [app root]}]
  (act!
   (fn []
     (.render root
              (shell/workbench-content
               app
               (application/current-state app))))))

(defn- q
  [container selector]
  (.querySelector container selector))

(defn- qa
  [container selector]
  (vec (.querySelectorAll container selector)))

(defn- timed
  [f]
  (let [start (now-ms)
        value (f)]
    {:ms (- (now-ms) start) :value value}))

(defn -main
  [& _]
  (let [sessions (js/parseInt (or (aget js/process.env "HC_SCALE_SESSIONS") "1000")
                              10)
        messages (js/parseInt (or (aget js/process.env "HC_SCALE_MESSAGES") "2000")
                              10)
        db (build-db sessions messages)
        {:keys [app container root]} (mount! db)]
    (try
      (let [render (timed #(render! {:app app :root root}))
            rows (count (qa container ".wb-session-item"))
            list (q container ".wb-session-list")
            list-scroll (timed (fn []
                                 (when list
                                   (set! (.-scrollTop list) 500)
                                   (.dispatchEvent list (js/Event. "scroll")))
                                 (when list (.-scrollTop list))))
            messages-node (q container ".hc-conversation-messages")
            message-nodes (count (qa container "[data-message-id]"))
            message-scroll (timed (fn []
                                    (when messages-node
                                      (set! (.-scrollTop messages-node) 12345)
                                      (.dispatchEvent messages-node
                                                      (js/Event. "scroll")))
                                    (when messages-node
                                      (.-scrollTop messages-node))))
            ;; Reading-position restore: leave s0, come back, read scrollTop.
            _ (act! (fn []
                      (comp/transact! app
                                      [(mutations/select-session
                                        {:session-id "s1"})])))
            _ (render! {:app app :root root})
            _ (act! (fn []
                      (comp/transact! app
                                      [(mutations/select-session
                                        {:session-id "s0"})])))
            restore (timed #(render! {:app app :root root}))
            restored-scroll (some-> (q container ".hc-conversation-messages")
                                    (.-scrollTop))
            _ (act! (fn []
                      (comp/transact! app
                                      [(mutations/request-find {:seq 1})])))
            _ (render! {:app app :root root})
            find-controls (count (qa container
                                     "input[aria-label='查找消息内容'],button[aria-label*='查找']"))
            ta (q container ".hc-composer textarea")
            big-text (apply str (repeat 10000 "中a"))
            edit (timed (fn []
                          (when ta
                            (let [setter (.-set
                                          (js/Object.getOwnPropertyDescriptor
                                           (.-prototype js/window.HTMLTextAreaElement)
                                           "value"))]
                              (.call setter ta big-text)
                              (.dispatchEvent ta (js/Event. "input" #js {:bubbles true}))))
                          (when ta (count (.-value ta)))))
            selection (timed (fn []
                               (when ta
                                 (.focus ta)
                                 (.setSelectionRange ta 100 200))
                               (when ta [(.-selectionStart ta)
                                         (.-selectionEnd ta)])))
            undo (timed (fn []
                          (if (fn? (.-execCommand js/document))
                            (.execCommand js/document "undo")
                            :unsupported)
                          (when ta (count (.-value ta)))))
            report {:env {:sessions sessions
                          :messages messages
                          :cpus (.-hardwareConcurrency js/navigator)
                          :userAgent (.-userAgent js/navigator)}
                    :render-ms (:ms render)
                    :session-rows rows
                    :session-list-scroll {:ms (:ms list-scroll)
                                          :scrollTop (:value list-scroll)}
                    :message-nodes message-nodes
                    :message-scroll {:ms (:ms message-scroll)
                                     :scrollTop (:value message-scroll)}
                    :restore {:ms (:ms restore)
                              :scrollTop restored-scroll
                              :expected 12345}
                    :find-controls find-controls
                    :draft-edit {:ms (:ms edit)
                                 :length (:value edit)}
                    :draft-selection {:ms (:ms selection)
                                      :range (:value selection)}
                    :draft-undo {:ms (:ms undo)
                                 :length (:value undo)}
                    :memory (when (exists? js/process.memoryUsage)
                              (js/process.memoryUsage))}]
        (js/console.log (js/JSON.stringify (clj->js report) nil 2)))
      (finally
        (act! (fn [] (.unmount root)))
        (.remove container)))))

(set! *main-cli-fn* -main)
