(ns hc.client.workbench.views.settings
  "Mount point for the settings-ui workspace.

  The settings tree, drafts, provider/role forms, and command receipts are
  owned by `hc.client.settings`; this namespace supplies only the workbench
  content frame.

  Backup and diagnostics are data-center panels.  Their state lives in the
  Fulcro database, not in this view: unmounting them while a backup runs does
  not cancel commands, and their password drafts remain in `:ui/backup`."
  (:require ["react" :as react]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.backup.ui :as backup-ui]
            [hc.client.diagnostics :as diagnostics]
            [hc.client.settings.view :as settings-view]
            [hc.client.speech.translation :as speech-translation]
            [hc.client.speech.ui :as speech-ui]
            [hc.client.workbench.model :as model]
            [hc.client.workbench.schedule-bridge :as schedule-bridge]
            [hc.client.workbench.settings-bridge :as settings-bridge]))

(defn data-section?
  "True when the operator is on the data-center section.

  Only the data center mounts diagnostics and the backup workspace.  The
  provider, model, and appearance sections must not show backup password
  fields merely because the settings workspace is open."
  [db]
  (= :data (get-in db [:ui/settings :ui :section])))

(defn- shortcuts-panel
  []
  (dom/div {}
           (dom/p {:className "wb-muted"}
                  "当前版本固定这些映射；自定义与冲突检查尚未提供。")
           (dom/table {:className "wb-shortcut-table"}
                      (dom/thead {}
                                 (dom/tr {}
                                         (dom/th {} "按键")
                                         (dom/th {} "动作")
                                         (dom/th {} "范围")))
                      (dom/tbody {}
                                 (for [{:keys [id keys-label action scope]}
                                       model/shortcuts]
                                   (dom/tr {:key (name id)}
                                           (dom/td {} (dom/kbd {} keys-label))
                                           (dom/td {} action)
                                           (dom/td {} scope)))))))

(defn- settings-workspace
  [js-props]
  ;; Advanced compilation renames `.-db`, but not the string key passed by
  ;; the creator's `#js {:db db}`.  Read custom props by string key.
  (let [db (aget js-props "db")
        data? (data-section? db)
        [tab set-tab!] (react/useState "settings")]
    (dom/div {:data-settings-section (name (or (get-in db [:ui/settings :ui :section]) :unknown))}
             (dom/div {:className "wb-page-title"}
                      (dom/h1 {} (settings-view/workspace-tab-title tab)))
             (dom/div {:className "wb-tabs" :role "tablist" :aria-label "设置、语音、翻译与定时任务"}
                      (dom/button {:type "button"
                                   :role "tab"
                                   :className "wb-tab"
                                   :aria-selected (if (= "settings" tab) "true" "false")
                                   :onClick (fn [_] (set-tab! "settings"))}
                                  "设置")
                      (dom/button {:type "button"
                                   :role "tab"
                                   :className "wb-tab"
                                   :aria-selected (if (= "schedule" tab) "true" "false")
                                   :onClick (fn [_] (set-tab! "schedule"))}
                                  "定时任务")
                      (dom/button {:type "button"
                                   :role "tab"
                                   :className "wb-tab"
                                   :aria-selected (if (= "speech" tab) "true" "false")
                                   :onClick (fn [_] (set-tab! "speech"))}
                                  "语音与朗读")
                      (dom/button {:type "button"
                                   :role "tab"
                                   :className "wb-tab"
                                   :aria-selected (if (= "translation" tab) "true" "false")
                                   :onClick (fn [_] (set-tab! "translation"))}
                                  "独立翻译")
                      (dom/button {:type "button"
                                   :role "tab"
                                   :className "wb-tab"
                                   :aria-selected (if (= "shortcuts" tab) "true" "false")
                                   :onClick (fn [_] (set-tab! "shortcuts"))}
                                  "快捷键"))
             (cond
               (= "schedule" tab)
               (schedule-bridge/schedule-element db)

               (= "speech" tab)
               (react/createElement speech-ui/settings-element)

               (= "translation" tab)
               (react/createElement speech-translation/page-element)

               (= "shortcuts" tab)
               (shortcuts-panel)

               :else
               (dom/div {}
                        (settings-bridge/settings-element db)
                        (when data?
                          (dom/div {:id settings-view/diagnostics-anchor-id}
                                   (diagnostics/diagnostics-element db)))
                        (when data?
                          (dom/div {:id settings-view/backup-anchor-id}
                                   (backup-ui/backup-element db))))))))

(defn settings-workspace-element
  "Create the settings workspace element for DB.

  The key is the operator's settings section, not the server revision.
  Server projections reduce against the current settings state; an operator
  section change remounts the section content.  Backup drafts live in
  `:ui/backup`, so remounting the data center seeds its password fields from
  that state instead of discarding them."
  [db]
  (react/createElement settings-workspace
                       #js {:key (str (get-in db [:ui/settings :ui :section]))
                            :db db}))

(defn settings-page
  [this _props]
  (let [db (application/current-state this)]
    (dom/div {:className "wb-content"}
             (dom/div {:className "wb-content-inner"}
                      ;; Do not key on server revision: the settings state is
                      ;; updated in place by settings-bridge, and remounting
                      ;; would discard unsaved forms, secrets, and focus.
                      ;; The section key only remounts for an operator tab
                      ;; change; server projections reduce against current
                      ;; settings state and preserve that section.
                      (settings-workspace-element db)))))
