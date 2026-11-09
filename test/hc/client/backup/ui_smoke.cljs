(ns hc.client.backup.ui-smoke
  "Node render smoke check for the real React backup surface.

  Compile with cljs.main using a :nodejs target and :node-modules-dirs
  [node_modules], then run the generated main.js.  It renders the same
  tree the browser mounts and checks labels, password fields, conflict
  choices and the offline-restore notice."
  (:require [clojure.string :as str]
            [hc.client.backup.model :as model]
            [hc.client.backup.react :as react-view]
            ["react-dom/server" :as react-dom-server]))

(defn- check [condition message]
  (when-not condition
    (throw (js/Error. (str "SMOKE FAIL: " message)))))

(defn- includes? [text needle]
  (str/includes? text needle))

(defn- status-result []
  {:data-dir "/data"
   :default-target-id :local
   :targets [{:id :local :kind :file :configured? true :available? true
              :root "/data/backups"}
             {:id :dav :kind :webdav :configured? true :available? false
              :base-url "https://dav.example.com" :directory "hc"
              :reason "无法连接 WebDAV"}
             {:id :s3 :kind :s3 :configured? false :available? false
              :endpoint "https://s3.example.com" :bucket "hc"}]
   :target {:id :local :kind :file :configured? true :available? true
            :root "/data/backups"}
   :restore {:online? false :offline? true :requires-quiescence? true}
   :backups [{:id "b1" :sampled-at "2026-11-03T00:00:00Z"
              :created-at "2026-11-03T00:00:01Z" :device "laptop"
              :app-version "0.0.1" :encrypted? true :format-version 1
              :size 1024 :integrity :verified}]
   :recovery-points [{:id "rp-1" :created-at "2026-11-03T00:00:02Z"
                      :reason :before-restore :scope [:conversations]}]
   :storage {:engine "datalevin" :path "/data"
             :used-bytes 2048 :free-bytes 4096}
   :errors []})

(defn- connected []
  (-> (model/initial-state)
      (assoc :sync {:phase :synced :command-epoch 7})
      (assoc-in [:ui :transport-available?] true)
      (assoc-in [:ui :target-id] :local)
      (assoc-in [:server :default-target-id] :local)))

(defn- act [state action]
  (:state (model/reduce-action state action)))

(defn- apply-result [state result]
  (let [id (-> state :outbox last :command-id)]
    (act state {:type :backup/apply-receipt
                :receipt {:command-id id :ok? true :receipt result}})))

(defn- preview-state []
  (-> (connected)
      (act {:type :backup/refresh})
      (apply-result (status-result))
      (act {:type :backup/select-backup :backup-id "b1"})
      (act {:type :backup/set-field :field :password
            :value "correct horse battery staple"})
      (act {:type :backup/preview})
      (apply-result {:mode :replace
                     :summary {:local 5 :imported 5 :added 1
                               :replaced 2 :removed 2 :skipped 0
                               :conflicts 1 :expired-skipped 0
                               :memory-pending 1}
                     :conflicts [{:category :assistants
                                  :id "a1" :imported-id "a1"
                                  :name "Reviewer"
                                  :reason :same-name-different-content
                                  :resolution :keep-both
                                  :choices [:keep-local :use-backup :keep-both]}]
                     :placeholders [{:id "assistant-x" :name "待配置的导入助手"}]
                     :missing-relations ["connection x"]
                     :relink-required [{:id "ws-1" :label "旧工作区"}]
                     :needs-review [{:id "tool-1" :label "工具"}]
                     :incomplete-attachments [{:id "att-1" :name "扫描件"}]
                     :memory-reactivation [{:id "m1" :name "旧记忆"}]
                     :expired-skipped []
                     :needs-reconfiguration [{:id "ws-1" :label "旧工作区"}]
                     :permissions {:schedules :paused
                                   :tools :disabled-needs-review
                                   :device-control :enabled}
                     :manifest {:format-version 1 :device "laptop"}
                     :recovery-point {:required? true :status :will-create}
                     :no-changes? false
                     :already-imported? false
                     :replace-all? true
                     :memory-reactivation-required? true
                     :warnings []})
      (act {:type :backup/select-section :section :restore})))

(defn- maintenance-failure-state
  [status extra]
  (-> (connected)
      (act {:type :backup/refresh})
      (apply-result (status-result))
      (assoc-in [:server :maintenance] {:available? true :mode :supervised})
      (act {:type :backup/apply-maintenance-event
            :event (merge {:maintenance-id "m-1"
                           :status status
                           :needs-attention? true
                           :restore-attempted? true}
                          extra)})
      (act {:type :backup/select-section :section :restore})))

(defn- render [state]
  (react-dom-server/renderToStaticMarkup
   (react-view/backup-element state (fn [_]))))

(defn -main []
  (let [create-html (render (act (connected) {:type :backup/select-section
                                               :section :backup}))]
    (check (includes? create-html "加密备份与恢复") "title is missing")
    (check (includes? create-html "至少 12 个字符") "password rule is missing")
    (check (includes? create-html "type=\"password\"") "password fields are not masked")
    (check (includes? create-html "包含模型密钥") "secrets option is missing")
    (check (includes? create-html "aria-label=\"加密备份与恢复\"")
           "region label is missing")
    (check (includes? create-html "生成加密备份") "create action is missing"))

  (let [plain-html (render (-> (connected)
                               (act {:type :backup/toggle-field
                                     :field :encrypt?})
                               (act {:type :backup/select-section
                                     :section :backup})))]
    (check (includes? plain-html "生成未加密备份")
           "plaintext create action is missing"))

  (let [restore-html (render (preview-state))]
    (check (includes? restore-html "替换选中类别") "replace mode is missing")
    (check (includes? restore-html "同名不同内容的助手与世界书默认保留两份")
           "conflict default is missing")
    (check (includes? restore-html "保留本地版本") "conflict choice is missing")
    (check (includes? restore-html "使用备份版本") "conflict choice is missing")
    (check (includes? restore-html "两份都保留") "conflict choice is missing")
    (check (includes? restore-html "工作台内恢复不可用") "offline notice is missing")
    (check (includes? restore-html "在应用停止后运行离线恢复")
           "offline instructions are missing")
    (check (includes? restore-html "未打包附件") "incomplete attachments are missing")
    (check (includes? restore-html "旧记忆") "memory reactivation is missing")
    (check (includes? restore-html "WebDAV") "configured WebDAV target is missing")
    (check (includes? restore-html "无法连接 WebDAV")
           "unavailable target reason is missing")
    (check (includes? restore-html "恢复后的权限默认值")
           "preview permission defaults are missing")
    (check (includes? restore-html "计划任务")
           "permission entry is missing")
    (check (not (includes? restore-html "设备控制"))
           "device control must not appear in restore permissions")
    (check (includes? restore-html "需要重新配置的项")
           "needs-reconfiguration list is missing")
    (check (includes? restore-html "旧工作区")
           "needs-reconfiguration item is missing")
    (check (not (includes? restore-html "执行恢复</button>"))
           "an enabled live restore button is rendered"))

  (let [html (render (-> (connected)
                         (assoc-in [:ui :last-restore]
                                   {:status :complete
                                    :permissions {:schedules :paused
                                                  :device-control :enabled}
                                    :warnings []})
                         (act {:type :backup/select-section :section :restore})))]
    (check (includes? html "外部权限没有自动恢复")
           "the restore result must state that external permissions stay unrestored")
    (check (not (includes? html "设备控制"))
           "device control must not appear in the restore result"))

  (let [html (render (-> (connected)
                         (act {:type :backup/refresh :origin :manual})
                         (apply-result (status-result))
                         (act {:type :backup/select-section :section :status})))]
    (check (includes? html "当前目标与状态")
           "the merged target and status heading is missing")
    (check (not (includes? html "已配置目标"))
           "the old configured-target heading must be gone")
    (check (not (includes? html "存储状态"))
           "the old storage heading must be gone")
    (check (includes? html "目标列表") "the target list heading is missing")
    (check (includes? html "未配置") "the unconfigured target state is missing")
    (check (includes? html "2 KiB") "the storage used amount is missing")
    (check (includes? html "4 KiB") "the storage free amount is missing"))

  (let [recovery-html (render (act (preview-state)
                                   {:type :backup/select-section
                                    :section :recovery}))]
    (check (includes? recovery-html "7+4+3") "retention policy is missing")
    (check (includes? recovery-html "rp-1") "recovery point is missing")
    (check (includes? recovery-html "在线回退不可用")
           "offline recovery notice is missing")
    (check (includes? recovery-html "hc.server.backup.cli restore-recovery-point")
           "offline recovery CLI is missing"))

  (let [html (render (maintenance-failure-state
                      :failed-needs-recovery
                      {:needs-recovery? true}))]
    (check (includes? html "已提交，需核对")
           "committed failure evidence is missing")
    (check (includes? html "inspect-and-accept-partial")
           "partial-restore acknowledgement code is missing")
    (check (includes? html "hc.server.backup.cli acknowledge")
           "acknowledgement CLI is missing")
    (check (includes? html "state-digest")
           "acknowledgement digest placeholder is missing")
    (check (includes? html "保持阻塞")
           "blocking start gate is missing"))

  (let [html (render (maintenance-failure-state
                      :failed-commit-unproven
                      {:commit-unproven? true}))]
    (check (includes? html "结果未知")
           "unknown commit evidence is missing")
    (check (includes? html "inspect-accept-unknown-commit")
           "unknown-commit acknowledgement code is missing"))

  (let [html (render (maintenance-failure-state
                      :failed-before-commit
                      {:store-unchanged? true}))]
    (check (includes? html "已确认未提交")
           "pre-commit evidence is missing")
    (check (includes? html "不需要操作员确认")
           "pre-commit acknowledgement note is missing")
    (check (not (includes? html "hc.server.backup.cli acknowledge"))
           "a pre-commit failure must not show an acknowledgement command"))

  (let [html (render (-> (connected)
                         (act {:type :backup/apply-maintenance-event
                               :event {:maintenance-id "m-1"
                                       :status :complete
                                       :no-changes? true}})
                         (act {:type :backup/select-section
                               :section :restore})))]
    (check (includes? html "没有写入")
           "a no-write maintenance outcome is missing")
    (check (not (includes? html "恢复完成"))
           "a no-write maintenance outcome must not claim a restore"))

  (println "BACKUP UI SMOKE OK"))
