(ns hc.client.sessions.controls.ui-test
  "Real DOM interaction tests for the session metadata controls.

  The pure model tests prove payloads and gating.  These tests mount the
  actual `SessionControls` component in jsdom and click the rendered buttons,
  so a boundary change between an action map and the action keyword cannot
  regress silently.  They also prove that delete and purge require a second
  click, that a pending receipt blocks an already-open form or confirmation,
  and that the purge command carries no workspace target.

  This is verification-only; it is not part of the production build.  The
  harness resolves `react-dom/client` through the real module injected by the
  Node runner, so the compiled component and the direct mount share one React
  instance."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [clojure.string :as str]
            [hc.client.sessions.controls.ui :as ui]))

(def synced
  {:synced? true :phase :synced})

(def active
  {:id "s1" :title "Alpha" :status :active :pinned? false})

(def deleted
  {:id "s2" :title "Beta" :status :deleted
   :deleted-at "2026-11-04T11:00:00Z"})

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

(defn- act!
  [f]
  ((.-act react) f))

(defn- controls-props
  [session connection command on-command on-notice]
  #js {:session session
       :connection connection
       :command command
       :on-command on-command
       :on-notice on-notice})

(defn- consumer
  [session connection command calls notices result]
  (controls-props
   session connection command
   (fn [request]
     (swap! calls conj request)
     (let [result @result]
       (if (fn? result) (result request) result)))
   (fn [tone message]
     (swap! notices conj [tone message]))))

(defn- render!
  [root props]
  (act!
   (fn []
     (.render root (react/createElement ui/SessionControls props)))))

(defn- mount!
  [props]
  (let [container (.createElement js/document "div")
        root (rdc/createRoot container)]
    (.appendChild js/document.body container)
    (render! root props)
    [container root]))

(defn- unmount!
  [container root]
  (act! (fn [] (.unmount root)))
  (.remove container))

(defn- button-by-text
  [container text]
  (first (filter #(= text (str/trim (.-textContent %)))
                 (array-seq (.querySelectorAll container "button")))))

(defn- input-by-aria
  [container label]
  (.querySelector container (str "input[aria-label='" label "']")))

(defn- dialog
  [container]
  (.querySelector container "[role='alertdialog']"))

(defn- simulate-change!
  [node value]
  (let [simulate (.-Simulate (js/require "react-dom/test-utils"))]
    (act!
     (fn []
       (.change simulate node #js {:target #js {:value value}})))))

(defn- simulate-submit!
  [node]
  (let [simulate (.-Simulate (js/require "react-dom/test-utils"))]
    (act!
     (fn []
       (.submit simulate node #js {})))))

(defn- click!
  [node]
  (when node
    (act!
     (fn []
       (.dispatchEvent node
                       (js/MouseEvent. "click"
                                       #js {:bubbles true
                                            :cancelable true}))))))

(deftest active-controls-send-real-keyword-actions
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        [container root]
        (mount! (consumer active synced nil calls notices result))]
    (try
      (testing "pin sends the canonical command with the toggled flag"
        (click! (button-by-text container "置顶"))
        (is (= [{:session-id "s1"
                 :kind :session/pin
                 :action :pin
                 :payload {:pinned? true}}]
               @calls)))
      (testing "archive sends the canonical command with an empty payload"
        (click! (button-by-text container "归档"))
        (is (= {:session-id "s1"
                :kind :session/archive
                :action :archive
                :payload {}}
               (last @calls))))
      (testing "rename opens a form and sends the trimmed title"
        (click! (button-by-text container "重命名"))
        (is (= 2 (count @calls)) "opening the form must not send a command")
        (let [input (input-by-aria container "新会话标题")
              form (.querySelector container "form.hc-session-rename")]
          (is (some? input))
          (is (some? form))
          (simulate-change! input "  Renamed  ")
          (simulate-submit! form))
        (is (= {:session-id "s1"
                :kind :session/rename
                :action :rename
                :payload {:title "Renamed"}}
               (last @calls)))
        (is (nil? (.querySelector container "form.hc-session-rename"))))
      (testing "a successful send adds no local notice"
        (is (empty? @notices)))
      (finally
        (unmount! container root)))))

(deftest invalid-rename-keeps-the-form-and-notifies
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        [container root]
        (mount! (consumer active synced nil calls notices result))]
    (try
      (click! (button-by-text container "重命名"))
      (let [form (.querySelector container "form.hc-session-rename")]
        (simulate-change! (input-by-aria container "新会话标题") "   ")
        (simulate-submit! form))
      (is (empty? @calls))
      (is (= [[:warn "标题不能为空，且不超过 200 个字符"]]
             @notices))
      (is (some? (.querySelector container "form.hc-session-rename")))
      (finally
        (unmount! container root)))))

(deftest delete-requires-an-explicit-second-click
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        [container root]
        (mount! (consumer active synced nil calls notices result))]
    (try
      (testing "the first click only opens the confirmation"
        (click! (button-by-text container "删除"))
        (is (empty? @calls))
        (let [confirm (dialog container)]
          (is (some? confirm))
          (is (str/includes? (.-textContent confirm) "30 天"))
          (is (str/includes? (.-textContent confirm) "确认删除"))))
      (testing "cancel closes the confirmation without a command"
        (click! (button-by-text container "取消"))
        (is (empty? @calls))
        (is (nil? (dialog container))))
      (testing "the second explicit click sends the delete command"
        (click! (button-by-text container "删除"))
        (click! (button-by-text container "确认删除"))
        (is (= [{:session-id "s1"
                 :kind :session/delete
                 :action :delete
                 :payload {}}]
               @calls))
        (is (nil? (dialog container))))
      (finally
        (unmount! container root)))))

(deftest purge-requires-a-second-click-and-carries-no-workspace-target
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        [container root]
        (mount! (consumer deleted synced nil calls notices result))]
    (try
      (testing "the first click only opens the permanent-delete confirmation"
        (click! (button-by-text container "永久删除"))
        (is (empty? @calls))
        (let [confirm (dialog container)]
          (is (some? confirm))
          (is (str/includes? (.-textContent confirm) "不可恢复"))
          (is (str/includes? (.-textContent confirm) "不会删除工作区文件"))))
      (testing "cancel closes the confirmation without a command"
        (click! (button-by-text container "取消"))
        (is (empty? @calls))
        (is (nil? (dialog container))))
      (testing "the second explicit click sends the confirmed purge"
        (click! (button-by-text container "永久删除"))
        (click! (button-by-text container "确认永久删除"))
        (is (= [{:session-id "s2"
                 :kind :session/purge
                 :action :purge
                 :payload {:confirm? true}}]
               @calls))
        (is (nil? (dialog container)))
        (is (not-any? (fn [request]
                        (some #{:workspace :workspace-root :workspace-id
                                :path :files}
                              (keys (:payload request))))
                      @calls)))
      (finally
        (unmount! container root)))))

(deftest deleted-session-restores-without-a-confirmation
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        [container root]
        (mount! (consumer deleted synced nil calls notices result))]
    (try
      (click! (button-by-text container "恢复"))
      (is (= [{:session-id "s2"
               :kind :session/restore
               :action :restore
               :payload {}}]
             @calls))
      (is (nil? (dialog container))
          "restore is a single explicit action, not a destructive confirmation")
      (finally
        (unmount! container root)))))

(deftest a-failed-delete-keeps-the-confirmation-open
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? false :reason :transport-not-open})
        [container root]
        (mount! (consumer active synced nil calls notices result))]
    (try
      (click! (button-by-text container "删除"))
      (click! (button-by-text container "确认删除"))
      (is (= 1 (count @calls)))
      (is (some? (dialog container))
          "a failed send must keep the confirmation for a retry")
      (reset! result {:ok? true})
      (click! (button-by-text container "确认删除"))
      (is (= 2 (count @calls)))
      (is (nil? (dialog container)))
      (finally
        (unmount! container root)))))

(deftest pending-command-disables-the-action-buttons
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        command {:kind :session/pin :status :sent}
        [container root]
        (mount! (consumer active synced command calls notices result))]
    (try
      (doseq [label ["重命名" "置顶" "归档" "删除"]]
        (is (true? (.-disabled (button-by-text container label)))
            (str label " must be disabled while a receipt is pending")))
      (is (empty? @calls))
      (is (str/includes? (.-textContent container)
                         "已发送，等待服务器回执"))
      (finally
        (unmount! container root)))))

(deftest pending-command-disables-an-open-confirmation
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        [container root]
        (mount! (consumer active synced nil calls notices result))]
    (try
      (click! (button-by-text container "删除"))
      (is (some? (dialog container)))
      (render! root
               (consumer active synced
                         {:kind :session/pin :status :sent}
                         calls notices result))
      (let [confirm (button-by-text container "确认删除")]
        (is (true? (.-disabled confirm)))
        (click! confirm))
      (is (empty? @calls))
      (is (some? (dialog container)))
      (finally
        (unmount! container root)))))

(deftest pending-command-blocks-an-open-rename-form
  (let [calls (atom [])
        notices (atom [])
        result (atom {:ok? true})
        [container root]
        (mount! (consumer active synced nil calls notices result))]
    (try
      (click! (button-by-text container "重命名"))
      (simulate-change! (input-by-aria container "新会话标题") "Renamed")
      (render! root
               (consumer active synced
                         {:kind :session/rename :status :submitting}
                         calls notices result))
      (let [save (button-by-text container "保存")
            form (.querySelector container "form.hc-session-rename")]
        (is (true? (.-disabled save)))
        (simulate-submit! form))
      (is (empty? @calls))
      (is (some (fn [[_ message]] (str/includes? message "回执"))
                @notices))
      (is (some? (.querySelector container "form.hc-session-rename")))
      (render! root (consumer active synced nil calls notices result))
      (let [save (button-by-text container "保存")
            form (.querySelector container "form.hc-session-rename")]
        (is (false? (.-disabled save)))
        (simulate-submit! form))
      (is (= [{:session-id "s1"
               :kind :session/rename
               :action :rename
               :payload {:title "Renamed"}}]
             @calls))
      (is (nil? (.querySelector container "form.hc-session-rename")))
      (finally
        (unmount! container root)))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (setup-dom!)
  (run-tests 'hc.client.sessions.controls.ui-test))

(set! *main-cli-fn* -main)
