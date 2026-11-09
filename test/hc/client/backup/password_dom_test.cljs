(ns hc.client.backup.password-dom-test
  "Real DOM regression for the backup password draft lifecycle.

  The production bridge writes `:ui/backup` through the Fulcro state atom and
  schedules a debounced render.  React therefore does not commit a new
  controlled value between two fast keystrokes.  This test mounts the real
  React adapter, types without rendering between characters, and proves the
  DOM draft and the caller's state keep every character.  It also covers IME
  composition, focus/selection during an unrelated server re-render, and the
  explicit clear reset.  The same harness mounts the real workbench
  `settings-page` to prove backup/diagnostics appear only in the data center,
  a section switch flushes an uncommitted DOM draft before unmounting, and a
  receipt applied while the panel is hidden still reaches the state.

  This is verification-only; it is not part of the production build.  The
  harness resolves `react-dom/client` through the real module injected by the
  Node runner, so the compiled Fulcro namespace and the direct mounts share
  one React instance."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            ["react-dom/server" :as rds]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.raw.application :as raw]
            [hc.client.backup.bridge :as backup-bridge]
            [hc.client.backup.model :as model]
            [hc.client.backup.react :as backup-react]
            [hc.client.diagnostics :as diagnostics]
            [hc.client.settings :as settings]
            [hc.client.workbench.transport :as transport]
            [hc.client.workbench.views.settings :as workbench-settings]))

(defn- setup-dom!
  []
  (if (and (exists? js/window) (exists? js/document))
    nil
    (let [jsdom (js/require "jsdom")
          dom (new (.-JSDOM jsdom) "<!doctype html><html><body></body></html>"
                   #js {:url "http://localhost/"})
          window (.-window dom)]
      (set! js/global.window window)
      (set! js/global.document (.-document window))
      (set! js/global.navigator (.-navigator window))
      (set! js/global.HTMLElement (.-HTMLElement window))
      (set! js/global.Node (.-Node window))
      (set! js/global.Event (.-Event window))
      (set! js/global.MouseEvent (.-MouseEvent window))
      (set! js/global.getComputedStyle (.-getComputedStyle window))
      (set! js/global.requestAnimationFrame (fn [f] (js/setTimeout f 0)))
      (set! js/global.cancelAnimationFrame (fn [id] (js/clearTimeout id)))
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/global) true)
      dom)))

(defn- connected []
  (-> (model/initial-state)
      (assoc :sync {:phase :synced :command-epoch 7})
      (assoc-in [:ui :transport-available?] true)
      (assoc-in [:ui :target-id] :local)
      (assoc-in [:server :default-target-id] :local)))

(defn- make-dispatch!
  [state-atom]
  (fn [action]
    (swap! state-atom
           (fn [state]
             (:state (model/reduce-action state action))))))

(defn- act!
  [f]
  ((.-act react) f))

(defn- render!
  [root state dispatch!]
  (act!
   (fn []
     (.render root (backup-react/backup-element state dispatch!)))))

(defn- input-by-aria
  [container label]
  (.querySelector container (str "input[aria-label='" label "']")))

(defn- button-by-text
  [container text]
  (first (filter #(= text (str/trim (.-textContent %)))
                 (array-seq (.querySelectorAll container "button")))))

(defn- type-char!
  [node ch]
  (act!
   (fn []
     (set! (.-value node) (str (.-value node) ch))
     (.dispatchEvent node (js/Event. "input"
                                    #js {:bubbles true :cancelable true})))))

(defn- type-text!
  [node text]
  (doseq [ch text]
    (type-char! node (str ch))))

(defn- mount!
  [state-atom dispatch!]
  (let [container (.createElement js/document "div")
        root (rdc/createRoot container)]
    (.appendChild js/document.body container)
    (render! root @state-atom dispatch!)
    [container root]))

(defn- unmount!
  [container root]
  (act! (fn [] (.unmount root)))
  (.remove container))

(defn- workbench-db
  [section]
  {:ui/settings (assoc-in (settings/initial-state
                           {:sync {:phase :synced :command-epoch 7}
                            :transport-available? true})
                          [:ui :section] section)
   :ui/connection {:phase :synced :command-epoch 7}
   :ui/theme :system
   :ui/font-scale 100
   :ui/contrast :normal
   :ui/reduced-motion false
   :ui/backup (connected)
   :ui/diagnostics (diagnostics/initial-state)})

(defn- mount-settings!
  [app]
  (let [container (.createElement js/document "div")
        root (rdc/createRoot container)]
    (.appendChild js/document.body container)
    (act! (fn [] (.render root (workbench-settings/settings-page app nil))))
    [container root]))

(defn- render-settings!
  [root app]
  (act! (fn [] (.render root (workbench-settings/settings-page app nil)))))

(deftest backup-panels-mount-only-in-data-center
  (doseq [section [:providers :models :appearance]]
    (let [markup (rds/renderToStaticMarkup
                  (workbench-settings/settings-page
                   {:com.fulcrologic.fulcro.application/state-atom
                    (atom (workbench-db section))}
                   nil))]
      (testing (str section " does not mount the data-center panels")
        (is (not (str/includes? markup "加密备份与恢复")))
        (is (not (str/includes? markup "统计与诊断"))))))
  (let [markup (rds/renderToStaticMarkup
                (workbench-settings/settings-page
                 {:com.fulcrologic.fulcro.application/state-atom
                  (atom (workbench-db :data))}
                 nil))]
    (testing "the data center mounts diagnostics and backup"
      (is (str/includes? markup "加密备份与恢复"))
      (is (str/includes? markup "统计与诊断")))))

(deftest section-switch-preserves-password-draft
  (let [app (raw/fulcro-app {:initial-db (workbench-db :data)})
        state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
    (reset! backup-bridge/app-ref app)
    (let [[container root] (mount-settings! app)]
      (try
        (let [password (input-by-aria container "备份密码")]
          (is (some? password))
          (type-text! password "abcdef")
          (is (= "abcdef" (get-in @state-atom [:ui/backup :ui :password])))
          (testing "an open IME draft is committed before the panel unmounts"
            ;; No input/compositionend event: the DOM is ahead of state, as it
            ;; can be while a composition is still open.
            (act! (fn [] (set! (.-value password) "abcdef中文")))
            (swap! state-atom assoc-in [:ui/settings :ui :section] :models)
            (render-settings! root app)
            (is (nil? (.querySelector container ".hc-backup")))
            (is (nil? (.querySelector container ".hc-diagnostics")))
            (is (= "abcdef中文"
                   (get-in @state-atom [:ui/backup :ui :password]))))
          (testing "returning to the data center seeds the saved draft"
            (swap! state-atom assoc-in [:ui/settings :ui :section] :data)
            (render-settings! root app)
            (let [remounted (input-by-aria container "备份密码")]
              (is (some? remounted))
              (is (= "abcdef中文" (.-value remounted))))))
        (finally
          (unmount! container root))))))

(deftest receipt-while-unmounted-survives-remount
  (let [db (-> (workbench-db :data)
               (assoc-in [:ui/backup :commands "cmd-1"]
                         {:command-id "cmd-1"
                          :command-epoch 7
                          :kind :backup/create
                          :key [:backup/create :global]
                          :payload {}
                          :status :submitting}))
        app (raw/fulcro-app {:initial-db db})
        state-atom (:com.fulcrologic.fulcro.application/state-atom app)]
    (reset! backup-bridge/app-ref app)
    (swap! transport/channel assoc :open? true)
    (let [[container root] (mount-settings! app)]
      (try
        (is (str/includes? (.-textContent container)
                           "已有命令等待回执；请先核对结果。"))
        (swap! state-atom assoc-in [:ui/settings :ui :section] :models)
        (render-settings! root app)
        (is (nil? (.querySelector container ".hc-backup")))
        (testing "a receipt applies through the same bridge path while hidden"
          ;; The Sente receipt listener calls this dispatch after the panel is
          ;; unmounted; the command must still complete and reach the state.
          (backup-bridge/dispatch!
           {:type :backup/apply-receipt
            :receipt {:command-id "cmd-1"
                      :ok? true
                      :receipt {:status :complete
                                :backup-id "backup-1"
                                :sampled-at "2026-11-04T00:00:00Z"
                                :target "local"}}})
          (is (= :applied
                 (get-in @state-atom
                         [:ui/backup :commands "cmd-1" :status]))))
        (swap! state-atom assoc-in [:ui/settings :ui :section] :data)
        (render-settings! root app)
        (is (not (str/includes? (.-textContent container)
                                "已有命令等待回执；请先核对结果。")))
        (is (str/includes? (.-textContent container) "最近一次备份"))
        (finally
          (swap! transport/channel assoc :open? false)
          (unmount! container root))))))

(deftest fast-typing-accumulates-without-a-render
  (let [state-atom (atom (connected))
        dispatch! (make-dispatch! state-atom)
        [container root] (mount! state-atom dispatch!)]
    (try
      (let [password (input-by-aria container "备份密码")
            confirm (input-by-aria container "再次输入备份密码")]
        (is (some? password))
        (is (some? confirm))
        (type-text! password "abcdef123456")
        (type-text! confirm "abcdef123456")
        (testing "the DOM keeps every character even though no render ran"
          (is (= "abcdef123456" (.-value password)))
          (is (= "abcdef123456" (.-value confirm))))
        (testing "the caller's state receives the complete drafts"
          (is (= "abcdef123456" (get-in @state-atom [:ui :password])))
          (is (= "abcdef123456" (get-in @state-atom [:ui :password-confirm]))))
        (testing "the committed render still shows the complete draft and count"
          (render! root @state-atom dispatch!)
          (is (= "abcdef123456" (.-value password)))
          (is (str/includes? (.-textContent container) "当前 12 个字符"))))
      (finally
        (unmount! container root)))))

(deftest focused-draft-survives-ime-and-server-render
  (let [state-atom (atom (connected))
        dispatch! (make-dispatch! state-atom)
        [container root] (mount! state-atom dispatch!)]
    (try
      (let [password (input-by-aria container "备份密码")
            text "中文密码 包含空格 123"]
        (is (some? password))
        (act! (fn [] (.focus password)))
        (type-text! password "中文密码 包含空格 ")
        (act!
         (fn []
           (set! (.-value password) text)
           (.dispatchEvent password
                          (js/Event. "compositionstart"
                                     #js {:bubbles true :cancelable true}))
           (.dispatchEvent password
                          (js/Event. "input"
                                     #js {:bubbles true :cancelable true}))
           (.dispatchEvent password
                          (js/Event. "compositionend"
                                     #js {:bubbles true :cancelable true}))))
        (is (= text (.-value password)))
        (is (= text (get-in @state-atom [:ui :password])))
        (testing "a focused re-render from a server push keeps text, focus, and selection"
          (act!
           (fn []
             (set! (.-selectionStart password) 2)
             (set! (.-selectionEnd password) 5)))
          (swap! state-atom assoc-in [:server :status] {:targets []})
          (render! root @state-atom dispatch!)
          (is (= text (.-value password)))
          (is (= password (.-activeElement js/document)))
          (is (= 2 (.-selectionStart password)))
          (is (= 5 (.-selectionEnd password)))))
      (finally
        (unmount! container root)))))

(deftest explicit-clear-writes-the-empty-draft
  (let [state-atom (atom (connected))
        dispatch! (make-dispatch! state-atom)
        [container root] (mount! state-atom dispatch!)]
    (try
      (let [password (input-by-aria container "备份密码")]
        (is (some? password))
        (type-text! password "abcdef123456")
        (render! root @state-atom dispatch!)
        (let [clear-button (button-by-text container "清除密码")]
          (is (some? clear-button))
          (act!
           (fn []
             (.dispatchEvent clear-button
                            (js/MouseEvent. "click"
                                            #js {:bubbles true
                                                 :cancelable true}))))
          (render! root @state-atom dispatch!)
          (is (= "" (.-value password)))
          (is (= "" (get-in @state-atom [:ui :password])))
          (is (= 1 (get-in @state-atom [:ui :password-reset])))))
      (finally
        (unmount! container root)))))

(deftest copy-password-feedback-stays-beside-the-button
  (let [state-atom (atom (connected))
        dispatch! (make-dispatch! state-atom)
        [container root] (mount! state-atom dispatch!)]
    (try
      (let [password (input-by-aria container "备份密码")]
        (type-text! password "abcdef123456")
        (render! root @state-atom dispatch!)
        (let [copy-button (button-by-text container "复制密码")]
          (is (some? copy-button))
          (act! (fn [] (.click copy-button)))
          (render! root @state-atom dispatch!)
          (let [feedback (.querySelector container ".hc-backup-copy-feedback")]
            (testing "progress appears inside the password action row"
              (is (some? feedback))
              (is (= "status" (.getAttribute feedback "role")))
              (is (str/includes? (.-textContent feedback) "正在复制密码"))
              (is (= :copying
                     (get-in @state-atom [:ui :copy-feedback :status]))))
            (testing "the page banner is not used"
              (is (nil? (.querySelector container ".hc-backup-message")))))
          (swap! state-atom
                 #(:state (model/reduce-action
                           % {:type :backup/copy-result :ok? true})))
          (render! root @state-atom dispatch!)
          (let [feedback (.querySelector container ".hc-backup-copy-feedback")]
            (is (str/includes? (.-textContent feedback) "已复制")))
          (swap! state-atom
                 #(:state (model/reduce-action
                           % {:type :backup/copy-result :ok? false})))
          (render! root @state-atom dispatch!)
          (let [feedback (.querySelector container ".hc-backup-copy-feedback")]
            (is (str/includes? (.-textContent feedback) "复制失败")))))
      (finally
        (unmount! container root)))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests]
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (setup-dom!)
  (run-tests 'hc.client.backup.password-dom-test))

(set! *main-cli-fn* -main)
