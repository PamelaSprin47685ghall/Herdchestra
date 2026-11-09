(ns hc.client.workbench.shell-dom-test
  "Real DOM regression for the workbench drawer layer.

  E2E03 found the details panel's close button covered by
  `.wb-drawer-backdrop` in the wide layout.  The wide workbench keeps
  navigation and details as grid columns; only the <=1023px drawer layout may
  install a fixed backdrop.  These tests mount the real shell chrome in
  jsdom, so the layout predicate, the backdrop boundary, and the focus-return
  hand-off are exercised together.

  This is verification-only; it is not part of the production build."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.miniapps.view :as miniapps]
            [hc.client.settings :as settings]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.views.shell :as shell]))

(comp/defsc ShellRoot
  [_this _props]
  {:query [:ui/route]
   :ident (fn [] [:root/id :root])}
  nil)

(defn- base-db
  [overrides]
  (merge {:ui/route :assistants
          :ui/viewport-width 1440
          :ui/sidebar-open false
          :ui/aux-open true
          :ui/sidebar-return-id "wb-menu"
          :ui/aux-return-id "wb-aux-toggle"
          :ui/focus-target nil
          :ui/focus-seq 0
          :ui/connection (model/connection-view
                          {:phase :synced
                           :subscription 3
                           :epoch 7
                           :seq 40})
          :ui/assistants-tab :roles
          :ui/settings (settings/initial-state)
          :ui/notices []
          :ui/draft {}
          :ui/command {}
          :ui/stream {}
          :ui/ask {}
          :ui/run {}
          :ui/nudge {}
          :ui/search ""
          :ui/search-state {}
          :ui/selection-seq 0
          :ui/session-filter ""
          :ui/aux-tab :details
          :ui/materials {}
          :ui/theme :system
          :ui/font-scale 100}
         overrides))

(defn- act!
  [f]
  ((.-act react) f))

(defn- render!
  [root app]
  (act!
   (fn []
     (.render root
              (shell/workbench-content
               app
               (application/current-state app))))))

(defn- mount!
  [db]
  (let [app (application/headless-synchronous-app
             ShellRoot
             {:initial-db db})
        container (.createElement js/document "div")
        root (rdc/createRoot container)]
    (.appendChild js/document.body container)
    (render! root app)
    {:app app :container container :root root}))

(defn- unmount!
  [container root]
  (act! (fn [] (.unmount root)))
  (.remove container))

(defn- q
  [container selector]
  (.querySelector container selector))

(defn- click!
  [node]
  (when node
    (act!
     (fn []
       (.dispatchEvent node
                       (js/MouseEvent. "click"
                                       #js {:bubbles true
                                            :cancelable true}))))))

(deftest wide-details-column-has-no-modal-backdrop
  (let [{:keys [app container root]}
        (mount! (base-db {:ui/viewport-width 1440
                          :ui/aux-open true}))]
    (try
      (testing "the wide details column is visible without a modal layer"
        (is (some? (q container ".wb-aux")))
        (is (= "true" (.getAttribute (q container ".wb-aux") "data-open")))
        (is (= "true" (.getAttribute (q container ".wb-body") "data-aux-open")))
        (is (nil? (q container ".wb-drawer-backdrop"))
            "a fixed backdrop over a persistent column intercepts its controls"))
      (testing "the details close control remains clickable"
        (click! (q container ".wb-aux button[aria-label='关闭详情']"))
        (is (false? (:ui/aux-open (application/current-state app)))))
      (testing "closing the wide column reclaims its grid track"
        (render! root app)
        (is (= "false" (.getAttribute (q container ".wb-aux") "data-open")))
        (is (= "false" (.getAttribute (q container ".wb-body") "data-aux-open")))
        (is (nil? (q container ".wb-drawer-backdrop")))
        (is (= "wb-aux-toggle" (.-id (.-activeElement js/document)))
            "closing returns focus to the control that opened the column"))
      (finally
        (unmount! container root)))))

(deftest wide-navigation-drawer-state-has-no-modal-backdrop
  (let [{:keys [container root]}
        (mount! (base-db {:ui/viewport-width 1440
                          :ui/aux-open false
                          :ui/sidebar-open true}))]
    (try
      (is (nil? (q container ".wb-drawer-backdrop"))
          "the wide navigation column is not a drawer")
      (is (nil? (q container "#wb-nav-close"))
          "the wide column has no drawer-only close control")
      (finally
        (unmount! container root)))))

(deftest drawer-navigation-close-control-closes-and-returns-focus
  (let [{:keys [app container root]}
        (mount! (base-db {:ui/viewport-width 360
                          :ui/sidebar-open true
                          :ui/aux-open false
                          :ui/sidebar-return-id "wb-menu"}))]
    (try
      (is (some? (q container "#wb-nav-close"))
          "the open phone drawer exposes its own close control")
      (click! (q container "#wb-nav-close"))
      (render! root app)
      (is (false? (:ui/sidebar-open (application/current-state app))))
      (is (= "wb-menu" (.-id (.-activeElement js/document)))
          "closing the navigation drawer returns focus to its opener")
      (finally
        (unmount! container root)))))

(deftest drawer-layout-installs-and-removes-the-backdrop
  (let [{:keys [app container root]}
        (mount! (base-db {:ui/viewport-width 800
                          :ui/aux-open false}))]
    (try
      (is (nil? (q container ".wb-drawer-backdrop")))
      (click! (q container "#wb-aux-toggle"))
      (is (true? (:ui/aux-open (application/current-state app))))
      (render! root app)
      (is (= "wb-aux-close" (.-id (.-activeElement js/document)))
          "opening the drawer moves focus to its close control")
      (is (some? (q container ".wb-drawer-backdrop"))
          "the drawer layout owns the modal layer")
      (is (= "true" (.getAttribute (q container ".wb-aux") "data-open")))
      (is (= "none" (.. (q container "#wb-aux-toggle") -style -display))
          "the open drawer has exactly one close control; the topbar trigger is hidden")
      (click! (q container ".wb-aux button[aria-label='关闭详情']"))
      (is (false? (:ui/aux-open (application/current-state app))))
      (render! root app)
      (is (= "wb-aux-toggle" (.-id (.-activeElement js/document)))
          "closing returns focus to the topbar control that opened it")
      (is (nil? (q container ".wb-drawer-backdrop")))
      (is (= "false" (.getAttribute (q container ".wb-aux") "data-open")))
      (is (not= "none" (.. (q container "#wb-aux-toggle") -style -display))
          "the topbar opener returns when the drawer closes")
      (finally
        (unmount! container root)))))

(deftest drawer-close-returns-focus-to-the-recorded-opener
  (let [{:keys [app container root]}
        (mount! (base-db {:ui/viewport-width 800
                          :ui/aux-open true
                          :ui/aux-return-id "hc-header-action-open-aux"}))
        trigger (.createElement js/document "button")]
    (set! (.-id trigger) "hc-header-action-open-aux")
    (.appendChild js/document.body trigger)
    (try
      (is (some? (q container ".wb-drawer-backdrop")))
      (click! (q container ".wb-aux button[aria-label='关闭详情']"))
      (render! root app)
      (is (= "hc-header-action-open-aux" (.-id (.-activeElement js/document)))
          "a drawer opened from the conversation header returns there")
      (finally
        (.remove trigger)
        (unmount! container root)))))

(deftest drawer-layout-offers-session-switching
  (let [{:keys [container root]}
        (mount! (base-db {:ui/route :sessions
                          :ui/viewport-width 360
                          :ui/sidebar-open true
                          :ui/aux-open false
                          :ui/selected-session-id "s1"
                          :session/by-id {"s1" {:id "s1" :title "Alpha"}}
                          :message/by-id {}
                          :message/order []}))]
    (try
      (is (some? (q container "#wb-nav-session-search"))
          "the narrow drawer owns the session filter")
      (is (some? (q container "#wb-nav-new-session"))
          "a 360px window can still create a session")
      (is (= "Alpha"
             (.-textContent (q container ".wb-nav-session strong")))
          "a 360px window can still switch sessions")
      (finally
        (unmount! container root)))))

(deftest aux-panel-is-a-labeled-complementary-landmark
  (let [{:keys [container root]}
        (mount! (base-db {:ui/viewport-width 1440
                          :ui/aux-open true}))]
    (try
      (let [navs (vec (.querySelectorAll container "nav"))]
        (is (= 1 (count navs))
            "the shell exposes exactly one navigation landmark")
        (is (= "wb-nav-drawer" (.-id (first navs))))
        (let [aux (q container "#wb-aux")]
          (is (= "ASIDE" (.-tagName aux))
              "the details column is a complementary landmark, not a second nav")
          (is (= "上下文详情"
                 (.getAttribute aux "aria-label")))))
      (finally
        (unmount! container root)))))

(deftest default-session-title-displays-in-chinese
  (is (= "新会话" (shell/session-title-label "New session")))
  (is (= "Alpha" (shell/session-title-label "Alpha")))
  (let [{:keys [container root]}
        (mount! (base-db {:ui/route :sessions
                          :ui/viewport-width 800
                          :ui/sidebar-open true
                          :ui/aux-open false
                          :ui/selected-session-id "s1"
                          :ui/session-list [{:session-id "d1"
                                             :title "New session"
                                             :status :deleted
                                             :deleted-at "2026-11-01T00:00:00Z"}]
                          :session/by-id {"s1" {:id "s1"
                                                :title "New session"}}
                          :message/by-id {}
                          :message/order []}))]
    (try
      (let [row (q container ".wb-nav-session")]
        (is (= "新会话" (.-textContent (q row "strong")))
            "the navigation list shows the Chinese default title")
        (is (not (.includes (.-textContent row) "New session"))))
      (let [deleted (q container ".wb-nav-deleted-row")]
        (is (= "新会话" (.-textContent (q deleted "strong")))
            "the deleted list uses the same display label"))
      (finally
        (unmount! container root)))))

(deftest aux-facts-localize-the-default-session-title
  (let [{:keys [container root]}
        (mount! (base-db {:ui/route :sessions
                          :ui/viewport-width 1440
                          :ui/aux-open true
                          :ui/selected-session-id "s1"
                          :session/by-id {"s1" {:id "s1"
                                                :title "New session"}}
                          :message/by-id {}
                          :message/order []}))]
    (try
      (is (= "新会话"
             (.-textContent (q container ".wb-aux .wb-facts dd")))
          "the details facts show the Chinese default title")
      (finally
        (unmount! container root)))))

(deftest mini-app-boundaries-stay-chinese-and-guarded
  (testing "a 404 is a missing resource, a 503 is a server outage"
    (is (re-find #"找不到"
                 (miniapps/request-error-text
                  {:http-status 404
                   :error {:code :NOT_FOUND}})))
    (is (re-find #"服务器暂不可用"
                 (miniapps/request-error-text
                  {:http-status 503
                   :error {:code :SERVICE_UNAVAILABLE}})))
    (is (re-find #"打开设置"
                 (miniapps/request-error-text
                  {:http-status 503}))
        "a server outage points at the settings page")
    (is (not (re-find #"NOT_FOUND"
                      (miniapps/request-error-text
                       {:http-status 404
                        :error {:code :NOT_FOUND}})))
        "raw response codes never reach the user"))
  (testing "clear is disabled only when no local record exists"
    (is (true? (miniapps/clear-disabled? {})))
    (is (true? (miniapps/clear-disabled?
                {:data {:summary {:keys 0}}})))
    (is (false? (miniapps/clear-disabled?
                 {:data {:summary {:keys 2}}}))))
  (is (= "没有可清空的本地记录" miniapps/clear-disabled-reason)
      "the disabled clear control names its missing precondition")
  (testing "the settings entry uses the workbench hash route"
    (is (= "#/settings" miniapps/settings-route-hash))))

(deftest drawers-are-mutually-exclusive
  (let [{:keys [app container root]}
        (mount! (base-db {:ui/route :sessions
                          :ui/viewport-width 800
                          :ui/aux-open false
                          :ui/sidebar-open false}))]
    (try
      (click! (q container "#wb-aux-toggle"))
      (is (true? (:ui/aux-open (application/current-state app))))
      (render! root app)
      (click! (q container "#wb-menu"))
      (let [db (application/current-state app)]
        (is (true? (:ui/sidebar-open db)))
        (is (false? (:ui/aux-open db))
            "opening the navigation drawer closes the details drawer"))
      (click! (q container "#wb-aux-toggle"))
      (let [db (application/current-state app)]
        (is (true? (:ui/aux-open db)))
        (is (false? (:ui/sidebar-open db))
            "opening the details drawer closes the navigation drawer"))
      (finally
        (unmount! container root)))))

(deftest session-row-localizes-status-and-timestamp
  (let [{:keys [container root]}
        (mount! (base-db {:ui/route :sessions
                          :ui/viewport-width 1280
                          :ui/sidebar-open false
                          :ui/aux-open false
                          :ui/selected-session-id nil
                          :session/by-id
                          {"s1" {:id "s1"
                                 :title "Alpha"
                                 :status :streaming
                                 :updated-at "2026-11-05T08:00:00Z"}}
                          :message/by-id {}
                          :message/order []}))]
    (try
      (let [row (q container ".wb-session-item")
            text (.-textContent row)]
        (is (.includes text "进行中")
            "the internal :streaming status shows a Chinese label")
        (is (not (.includes text "streaming"))
            "the internal status keyword never reaches the list")
        (is (or (.includes text "2026/11/05")
                (.includes text "2026-11-05"))
            "the ISO timestamp renders as a localized date")
        (is (not (.includes text "2026-11-05T08:00:00Z"))
            "the raw ISO timestamp never reaches the list"))
      (finally
        (unmount! container root)))))

(deftest one-overlay-closes-per-request
  (let [{:keys [app container root]}
        (mount! (base-db {:ui/viewport-width 800
                          :ui/sidebar-open true
                          :ui/aux-open true}))]
    (try
      (act!
       (fn []
         (comp/transact! app [(mutations/close-overlay {:id :aux})])))
      (render! root app)
      (let [db (application/current-state app)]
        (is (false? (:ui/aux-open db)))
        (is (true? (:ui/sidebar-open db))
            "the navigation layer stays until its own close request")
        (is (= "wb-aux-toggle" (:ui/focus-target db))))
      (finally
        (unmount! container root)))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.shell-dom-test))

(set! *main-cli-fn* -main)
