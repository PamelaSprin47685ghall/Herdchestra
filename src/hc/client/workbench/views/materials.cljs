(ns hc.client.workbench.views.materials
  "Workspace materials, artifacts, memory, worldbooks and instruction cards.

  Files and artifacts show only server-reported paths and stamps.  Memory,
  worldbook and instruction-card sections mount the real materials component
  backed by `:materials/*` snapshots and commands.  The files, artifacts,
  browser, extensions and mini-apps sections mount their owning modules; the
  materials page supplies only the tab frame and never duplicates their
  state."
  (:require ["react" :as react]
            [com.fulcrologic.fulcro.application :as application]
            [hc.client.artifacts :as artifacts]
            [hc.client.artifacts.view :as artifacts-view]
            [com.fulcrologic.fulcro.components :as comp]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.extensions :as extensions]
            [hc.client.files.browser :as files]
            [hc.client.materials :as materials]
            [hc.client.miniapps.view :as miniapps]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.extensions-bridge :as extensions-bridge]
            [hc.client.workbench.materials-bridge :as materials-bridge]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.views.browser :as browser]
            [hc.client.workbench.views.common :as common]))

(defn- files-tab [this props]
  (files/files-pane this (:ui/files props)))

(defn- artifacts-tab [db]
  (artifacts-view/artifacts-list
   {:artifacts (artifacts/from-state db)
    :on-copy (fn [content-id]
               (common/copy-text! content-id nil))}))

(defn- extensions-tab [db]
  (let [state (or (:ui/extensions db) (extensions/initial-state))]
    (extensions/extensions-element state extensions-bridge/dispatch!)))

(defn- miniapps-tab []
  (react/createElement miniapps/miniapps-panel))

(defn- materials-section [db tab]
  (let [state (-> (or (:ui/materials db) (materials/initial-state))
                  (assoc :assistant-options (model/assistant-options db)))
        dispatch! (fn [action] (materials-bridge/dispatch! action))]
    (materials/materials-element state dispatch! tab)))

(defn materials-page
  [this props]
  (let [db (application/current-state this)
        tab (or (:ui/materials-tab props) :files)
        connection (:ui/connection props)
        state (or (:ui/materials db) (materials/initial-state))
        resync (get (model/availability {:connection connection}) :resync)]
    (dom/div {:className "wb-content"}
             (dom/div {:className "wb-content-inner"}
                      (dom/div {:className "wb-page-title"}
                               (dom/div {}
                                        (dom/h1 {} "材料与扩展")
                                        (dom/p {} "工作区文件、长期记忆、世界书、指令卡与产物。材料动作只在服务器回执后更新；文件只显示服务器报告的路径与 stamp。"))
                               (dom/div {:className "wb-row"}
                                        (common/badge (model/connection-label connection)
                                                      (:tone connection))
                                        (when (:ready? state)
                                          (common/badge (str "材料 revision "
                                                             (or (:revision state) "—"))
                                                        :neutral))
                                        (dom/button {:type "button"
                                                     :className "wb-button wb-button-small"
                                                     :disabled (not (:enabled? resync))
                                                     :title (when-not (:enabled? resync)
                                                              (model/availability-reason resync))
                                                     :onClick (fn [_]
                                                                (commands/request-resync! this :operator-materials))}
                                                    "请求重同步")))
                      (common/tabs [{:id :files :label "工作区文件"}
                                    {:id :artifacts :label "产物"}
                                    {:id :memory :label "长期记忆"}
                                    {:id :worldbook :label "世界书"}
                                    {:id :instructions :label "指令卡"}
                                    {:id :browser :label "浏览器"}
                                    {:id :extensions :label "扩展"}
                                    {:id :miniapps :label "小应用"}]
                                   tab
                                   (fn [next]
                                     (comp/transact! this
                                                     [(mutations/set-tab {:group :ui/materials-tab
                                                                          :tab next})])))
                      (case tab
                        :artifacts (artifacts-tab db)
                        :memory (materials-section db :memory)
                        :worldbook (materials-section db :worldbook)
                        :instructions (materials-section db :instructions)
                        :extensions (extensions-tab db)
                        :browser (browser/browser-pane db)
                        :miniapps (miniapps-tab)
                        (files-tab this props))
                      (common/facts
                       [["当前目录条目" (count (or (get-in props [:ui/files :entries]) []))]
                        ["产物条目" (count (model/artifacts db))]
                        ["记忆条目" (count (:memories state))]
                        ["世界书" (count (:books state))]
                        ["指令卡" (count (:cards state))]
                        ["MCP 服务器" (count (:mcp (:ui/extensions db)))]
                        ["技能" (count (:skills (:ui/extensions db)))]
                        ["同步序号" (:seq connection)]])))))
