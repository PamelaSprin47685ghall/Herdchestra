(ns hc.visual.fixture
  "Temporary visual-audit harness.  It mounts the production components with
  fixture data so states that need a live mission can be inspected in a real
  browser.  It is not part of the shipped client."
  (:require ["react" :as react]
            ["react-dom/client" :as rdom]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]
            [hc.client.swarm.ui :as swarm-ui]
            [hc.client.files.ui :as files-ui]
            [hc.client.files.model :as files-model]
            [hc.client.browser.react :as browser-react]
            [hc.client.browser.model :as browser-model]
            [hc.client.artifacts :as artifacts]
            [hc.client.artifacts.view :as artifacts-view]
            [hc.client.search.view :as search-view]
            [hc.client.search.model :as search-model]))

(defonce root-atom (atom nil))

(defn- params []
  (let [search (or (.. js/window -location -search) "")
        entries (->> (str/split (subs search 1) #"&")
                     (remove str/blank?)
                     (map (fn [pair]
                            (let [[k v] (str/split pair #"=" 2)]
                              [(js/decodeURIComponent k)
                               (js/decodeURIComponent (or v ""))])))
                     (into {}))]
    entries))

(defn- scenario []
  (or (get (params) "scenario") "rich"))

(defn- page-title [title description badges]
  (dom/div {:className "wb-page-title"}
           (dom/div {}
                    (dom/h1 {} title)
                    (dom/p {} description))
           (dom/div {:className "wb-row"} badges)))

(defn- content [& children]
  (dom/div {:className "wb-content"}
           (apply dom/div {:className "wb-content-inner"} children)))

(defn- badge [label tone]
  (dom/span {:className "wb-badge" :data-tone (name (or tone :neutral))} label))

;; ---------------------------------------------------------------------------
;; Board fixtures

(def long-report
  (str "Joined after rebase onto master 12.\n\n"
       "Implemented the config parser, the compatibility adapter, and the\n"
       "empty-string normalization.  Verified with the full parser suite.\n\n"
       "Files touched:\n"
       "  src/config/parse.clj\n"
       "  src/config/compat.clj\n"
       "  test/config/parse_test.clj\n\n"
       "Notes: the old format still loads; empty string is treated as an empty\n"
       "config.  No migration is required for existing files.\n\n"
       "Tail marker: REPORT-END-9f3c\n"))

(defn- task [id & kvs]
  (merge {:task-id id} (apply hash-map kvs)))

(defn- agent [id & kvs]
  (merge {:agent-id id} (apply hash-map kvs)))

(defn- action [id & kvs]
  (merge {:action-id id} (apply hash-map kvs)))

(defn- board-db [scenario]
  (let [long? (= scenario "long")
        slice (if long?
                "config-parse-with-a-very-long-slice-name-that-keeps-going"
                "config-parse")
        goal (if long?
               (str "Integrate the new configuration parser while preserving the "
                    "legacy loader, empty-file defaults, and empty-string handling "
                    "without breaking any existing startup path")
               "Integrate authentication core")
        base
        {:fleet/master-version 12
         :board/version 41
         :swarm/by-id
         {"config-parse"
          {:id "config-parse" :slice-id slice
           :worktree (str "/worktrees/config-migration/" slice)
           :status :running
           :root-ready 1 :root-waiting 1 :root-done 1
           :queued-count 1 :last-event :result-queued
           :master-version 12 :created-seq 1}
          "config-compat"
          {:id "config-compat" :slice-id "config-compat"
           :worktree "/worktrees/config-migration/config-compat"
           :status :joined
           :root-ready 0 :root-waiting 0 :root-done 1
           :queued-count 0 :last-event :joined
           :master-version 12 :created-seq 2
           :report long-report
           :ff-commit "a1b2c3d4e5f6"
           :rebase-rounds 2}
          "startup-integration"
          {:id "startup-integration" :slice-id "startup-integration"
           :worktree "/worktrees/config-migration/startup-integration"
           :status :queued
           :root-ready 0 :root-waiting 0 :root-done 0
           :queued-count 0 :last-event :admitted
           :master-version 12 :created-seq 3}
          "startup-verification"
          {:id "startup-verification" :slice-id "startup-verification"
           :worktree "/worktrees/config-migration/startup-verification"
           :status :failed
           :root-ready 0 :root-waiting 0 :root-done 0
           :queued-count 0 :last-event :failed
           :master-version 12 :created-seq 4
           :error {:code :SWARM_LAUNCH_FAILED
                   :message (str "The worktree could not be prepared: "
                                 "fatal: invalid reference: master")}}}
         :task/by-id
         {"t1" (task "t1" :swarm-id "config-parse" :goal goal
                     :remaining-work (str "Join the parser, compatibility adapter, "
                                          "and empty-string handling; run the startup "
                                          "verification and record the result.")
                     :done-when "startup verification passes on master"
                     :state :waiting :ledger-key :review-config
                     :child-ids ["e1" "e2" "e3"] :wait-for ["e3"])
          "e1" (task "e1" :swarm-id "config-parse" :parent-id "t1"
                     :goal "Write the new format parser"
                     :remaining-work "none" :done-when "parser unit test passes"
                     :state :done)
          "e2" (task "e2" :swarm-id "config-parse" :parent-id "t1"
                     :goal "Keep the legacy format loader"
                     :remaining-work "Add the compatibility branch and one regression test."
                     :done-when "old-format fixture still loads"
                     :state :ready :owner-id "agent-holder")
          "e3" (task "e3" :swarm-id "config-parse" :parent-id "t1"
                     :goal "Normalize empty strings to defaults"
                     :remaining-work "Treat \"\" as an empty config; do not raise."
                     :done-when "empty-string test passes"
                     :state :waiting :wait-for ["e2"])
          "t2" (task "t2" :swarm-id "config-parse" :goal "Run the integration suite"
                     :remaining-work "Run the startup suite against the joined parser."
                     :done-when "startup suite exits 0"
                     :state :ready :ledger-key :review-token :needs-token true)
          "t3" (task "t3" :swarm-id "config-parse" :goal "Publish the migration note"
                     :remaining-work "none" :done-when "note merged"
                     :state :done :ledger-key :review-note)
          "c1" (task "c1" :swarm-id "config-compat" :goal "Adapt the old format loader"
                     :remaining-work "none" :done-when "compat test passes"
                     :state :done :ledger-key :review-compat)}
         :action/by-id
         {"a-shell" (action "a-shell" :swarm-id "config-parse" :task-id "t2"
                            :agent-id "agent-holder" :kind :shell :phase :running)
          "a-edit" (action "a-edit" :swarm-id "config-parse" :task-id "e2"
                           :agent-id "agent-worker" :kind :edit :phase :waiting-gate)
          "a-apply" (action "a-apply" :swarm-id "config-parse" :task-id "t3"
                            :agent-id "agent-worker" :kind :edit :phase :applying)}
         :agent/by-id
         {"agent-holder" (agent "agent-holder" :swarm-id "config-parse"
                                :slot-id "slot-a" :role :holder
                                :life-state :alive :generation 2)
          "agent-worker" (agent "agent-worker" :swarm-id "config-parse"
                                :slot-id "slot-b" :role :normal
                                :life-state :dead :generation 1
                                :death-reason :attempt-lost
                                :handoff-note "Remaining ordinary work returned to the board.")
          "agent-failed" (agent "agent-failed" :swarm-id "config-parse"
                                :slot-id "slot-c" :role :normal
                                :life-state :terminated :generation 1
                                :death-reason :failed
                                :handoff-note "The shell tool crashed; inspect the workspace.")}
         :slot/by-id
         {"slot-a" {:slot-id "slot-a" :swarm-id "config-parse"}
          "slot-b" {:slot-id "slot-b" :swarm-id "config-parse"}
          "slot-c" {:slot-id "slot-c" :swarm-id "config-parse"}
          "slot-d" {:slot-id "slot-d" :swarm-id "config-parse"}}
         :token-slot/by-id
         {"config-parse" {:holder-id "agent-holder" :term-id 7 :state :held}}
         :write-gate/by-id
         {"config-parse" {:held? true :owner "agent-holder"
                          :current-action "a-shell"
                          :queue ["a-edit" "a-apply"]}}
         :session/active-id "s1"
         :session/by-id
         {"s1" {:session-id "s1"
                :unresolved-calls
                [{:call-id "call-1" :kind :tool :role :worker :lane :execution
                  :tenure-id "tenure-3" :agent-id "agent-2" :member "clj-worker"
                  :task-id "t2" :phase :outcome-unknown
                  :last-known-stage :outcome-unknown :outcome-unknown? true
                  :created-at 1000}
                 {:call-id "call-2" :kind :tool :role :plan :phase :succeeded
                  :last-known-stage :succeeded :created-at 1100
                  :ack {:outcome :succeeded :by :operator :at 2000
                        :note "checked the target file"}}]
                :worker-programs
                [{:job-id "job-conflict" :task-id "e2" :claim-id "claim-9"
                  :agent-id "agent-worker" :program-status :FILE_CONFLICT
                  :file-changed? "true" :file-path "src/config/compat.clj"
                  :conflicts [{:path "src/config/compat.clj"
                               :reason :stamp-mismatch
                               :expected {:mtime-sec 1710000000 :mtime-nsec 123456789 :size 412}
                               :actual {:mtime-sec 1710000042 :mtime-nsec 987654321 :size 433}}]
                  :created-at 1200}
                 {:job-id "job-applied" :task-id "t3" :claim-id "claim-10"
                  :agent-id "agent-worker" :program-status :APPLIED
                  :file-path "plans/config-migration.md" :created-at 1300}
                 {:job-id "job-rejected" :task-id "e2" :claim-id "claim-11"
                  :agent-id "agent-worker" :program-status :PREPARE_REJECTED
                  :file-path "src/config/parse.clj"
                  :error {:code :STALE_READ_SET :message "read-set changed before apply"}
                  :created-at 1400}]}}}
        recovery
        {:recovery-blocked? true
         :recovery-reasons [:uncertain-actions :write-gate-held :process-restart]
         :recovery-actions ["call-1" "a-apply"]
         :recovery-write-gate {:held? true :owner "agent-holder"
                               :queue 2 :outcome-unknown? true
                               :queue-abandoned? true
                               :reason :uncertain-actions
                               :recovered-at 1710000200000}
         :recovery-acknowledgement {:acknowledged? true
                                    :actor-id "operator"
                                    :evidence "Checked the target file and the W owner."
                                    :write-gate-held? true
                                    :at-version 41}}
        base (if (= scenario "recovery")
               (assoc-in base [:write-gate/by-id "config-parse"]
                         (merge (get-in base [:write-gate/by-id "config-parse"])
                                recovery))
               base)]
    (case scenario
      "empty" {}
      "board-only" {:task/by-id {"only" (task "only" :goal "Board-only task"
                                              :remaining-work "Read the server snapshot"
                                              :done-when "snapshot arrives"
                                              :state :ready)}
                    :token-slot/by-id {"local" {:holder-id "agent-1" :term-id 3
                                                :state :held :swarm-id "local"}}
                    :board/version 9}
      base)))

(defn- board-page []
  (content
   (page-title "Swarm 看板"
               "fleet 总览、任务树、等待链与固定运行槽。状态来自服务器当前态；此视图不提供认领或写文件操作。"
               [(badge "已同步" :ok)
                (dom/button {:type "button" :className "wb-button wb-button-small"} "请求重同步")])
   (react/createElement swarm-ui/SwarmDashboardFromDb
                        #js {:db (board-db (scenario))})))

;; ---------------------------------------------------------------------------
;; Browser fixtures

(defn- browser-state [scenario]
  (let [base (browser-model/initial-state)]
    (case scenario
      "offline"
      (assoc base :offline? true :phase :idle
             :permission {:enabled? false :scope :current-tab :sites []
                          :private-read-approved? false :persisted? false})

      "permission"
      (assoc base
             :phase :ready :session-id "s1" :offline? false
             :sync {:phase :synced :command-epoch 4}
             :permission {:enabled? true :scope :session-tabs
                          :sites ["example.com" "docs.example.org"]
                          :private-read-approved? true
                          :persisted? true
                          :model-provider :openai-compatible}
             :permission-form {:scope :session-tabs :site-text "example.com"}
             :tabs {"tab-1" {:tab-id "tab-1" :title "Example documentation"
                             :url "https://example.com/docs/config"
                             :site "example.com" :status :open
                             :agent-authorized? true :loading? false
                             :takeover :agent-active :created-at 1}
                    "tab-2" {:tab-id "tab-2" :title "Login — Example"
                             :url "https://example.com/login"
                             :site "example.com" :status :open
                             :agent-authorized? false :loading? true
                             :takeover :read-only :created-at 2}
                    "tab-3" {:tab-id "tab-3" :title "Old draft"
                             :url "https://example.com/old"
                             :site "example.com" :status :closed
                             :agent-authorized? false :loading? false
                             :created-at 3}}
             :tab-order ["tab-1" "tab-2" "tab-3"]
             :active-tab-id "tab-1"
             :address "https://example.com/docs/config"
             :observation {:observation-id "obs-9" :tab-id "tab-1"
                           :title "Configuration — Example"
                           :url "https://example.com/docs/config"
                           :text (str "Configuration\n\n"
                                      "Set the parser mode and the legacy compatibility flag.\n"
                                      "Empty values fall back to defaults.\n")
                           :generation 3 :iframes 1 :private? false
                           :nodes [{:ref "n1" :tag :input :type :text
                                    :label "Service name" :field-name "service"
                                    :placeholder "config-service"}
                                   {:ref "n2" :tag :button :type :submit
                                    :label "Save changes"}
                                   {:ref "n3" :tag :input :type :password
                                    :label "API token" :field-name "token"
                                    :sensitive? true}]}
             :selected-ref "n1"
             :type-text "config-service"
             :cards [{:card-id "card-1" :seq 1 :action :observe
                      :at "2026-11-06T10:01:00Z" :site "example.com"
                      :target-summary "读取页面配置区域"
                      :result {:status :ok :page-status :loaded}
                      :tab-id "tab-1"
                      :screenshot {:id "sha256-demo" :reason nil}}
                     {:card-id "card-2" :seq 2 :action :click
                      :at "2026-11-06T10:02:00Z" :site "example.com"
                      :target-summary "点击 Save changes"
                      :result {:status :ok :page-status :loaded}
                      :tab-id "tab-1"}
                     {:card-id "card-3" :seq 3 :action :navigate
                      :at "2026-11-06T10:03:00Z" :site "example.com"
                      :target-summary "打开 /login"
                      :result {:status :failed :page-status :blocked
                               :page-error :needs-login
                               :error {:code :PAGE_BLOCKED :message "需要登录后继续"}}
                      :tab-id "tab-2"}]
             :cards-meta {:dropped 7 :truncated? true}
             :limits {:sensitive-detection true :screenshot true
                      :observation true :iframes 1 :coordinates true
                      :external-browser false :permission :session-tabs
                      :outcome-unknown true :page-content "text only"}
             :commands {"c1" {:command-id "c1" :kind :observe :status :accepted
                              :queued-at 100}
                        "c2" {:command-id "c2" :kind :click :status :refused
                              :queued-at 101
                              :message "目标元素已被页面替换，请重新观察。"}}
             :stopped? false)

      "takeover"
      (-> (browser-state "permission")
          (assoc-in [:tabs "tab-1" :takeover] :sensitive-paused)
          (assoc-in [:tabs "tab-1" :takeover-reason] :password-field-focused)
          (assoc :last-error {:message "页面需要操作员完成登录；代理操作已暂停。"})
          (assoc-in [:commands "c3"] {:command-id "c3" :kind :type
                                      :status :failed :queued-at 102
                                      :message "敏感字段不能在聊天卡中输入。"}))

      base)))

(defn- browser-page []
  (content
   (react/createElement browser-react/BrowserPanel
                        #js {:state (browser-state (scenario))
                             :dispatch! (fn [action]
                                          (js/console.log "browser dispatch" (pr-str action)))})))

;; ---------------------------------------------------------------------------
;; Files fixtures

(defn- entry [name path kind & kvs]
  (merge {:name name :path path :kind kind :exists? true}
         (apply hash-map kvs)))

(defn- file-state [scenario]
  (let [base (files-model/initial)
        entries [(entry "src" "src" :directory :stamp-applicable? false)
                 (entry "resources" "resources" :directory :stamp-applicable? false)
                 (entry "plans" "plans" :directory :stamp-applicable? false)
                 (entry "README.md" "README.md" :file :previewable? true
                        :size 4210 :mtime-sec 1762300000 :mtime-nsec 123000000
                        :stamp {:mtime-sec 1762300000 :mtime-nsec 123000000 :size 4210})
                 (entry "deps.edn" "deps.edn" :file :previewable? true
                        :size 18744 :mtime-sec 1762301000 :mtime-nsec 0
                        :stamp {:mtime-sec 1762301000 :mtime-nsec 0 :size 18744})
                 (entry "logo.png" "logo.png" :file :previewable? false :binary? true
                        :size 98304 :mtime-sec 1762200000 :mtime-nsec 0
                        :stamp {:mtime-sec 1762200000 :mtime-nsec 0 :size 98304})
                 (entry "huge.log" "huge.log" :file :previewable? false :too-large? true
                        :size 734003200 :mtime-sec 1762100000 :mtime-nsec 0
                        :stamp {:mtime-sec 1762100000 :mtime-nsec 0 :size 734003200})
                 (entry "outside-link" "outside-link" :symlink-outside
                        :symlink? true :outside? true :exists? true
                        :reason-label "符号链接指向工作区外")
                 (entry "a-very-long-file-name-that-keeps-going-and-going-until-it-wraps.md"
                        "a-very-long-file-name-that-keeps-going-and-going-until-it-wraps.md"
                        :file :previewable? true :size 1200
                        :mtime-sec 1762000000 :mtime-nsec 0
                        :stamp {:mtime-sec 1762000000 :mtime-nsec 0 :size 1200})]
        preview {:path "README.md"
                 :text (str "# Herdchestra\n\n"
                            "A workbench for many agents.\n\n"
                            "## Workspace\n\n"
                            "Files are read through the server.\n\n"
                            "The preview shows the text the server actually returned.\n")
                 :stamp {:mtime-sec 1762300000 :mtime-nsec 123000000 :size 4210}
                 :size 4210 :kind :file}
        diff {:changed? true :coarse? false :too-large? false
              :base-stamp {:mtime-sec 1762200000 :mtime-nsec 0 :size 4100}
              :current-stamp {:mtime-sec 1762300000 :mtime-nsec 123000000 :size 4210}
              :summary "新增 3 行，删除 1 行"
              :hunks [{:header {:old-start 1 :old-count 3 :new-start 1 :new-count 3}
                       :lines [{:op :equal :old-line 1 :new-line 1 :text "# Herdchestra"}
                               {:op :add :new-line 2 :text ""}
                               {:op :add :new-line 3 :text "A workbench for many agents."}
                               {:op :remove :old-line 2 :text "Old tagline."}
                               {:op :equal :old-line 3 :new-line 4 :text ""}]}]}
        base (assoc base :status :loaded :path "src/hc/client"
                    :workspace {:name "herdchestra" :root "/home/operator/herdchestra"}
                    :entries entries :total-entries 9 :truncated? false
                    :selected-path "README.md"
                    :preview preview :preview-status :loaded :diff diff
                    :baseline {:path "README.md" :text (:text preview) :stamp (:stamp preview)})]
    (case scenario
      "empty" (assoc base :entries [] :total-entries 0 :preview nil :diff nil
                     :selected-path nil :preview-status :idle)
      "error" (assoc base :status :error
                     :error {:ok? false :error {:code :WORKSPACE_UNREADABLE
                                                :message "工作区根目录不可读或已被移除。"}}
                     :preview-status :error
                     :preview-error {:ok? false :error {:code :FILE_UNREADABLE
                                                        :message "权限不足，无法读取 README.md。"}})
      "conflict" (assoc base :preview-status :error
                        :preview-error {:ok? false
                                        :error {:code :FILE_CONFLICT
                                                :message "文件在读取期间发生变化。"
                                                :details {:expected {:mtime-sec 1762300000 :mtime-nsec 123000000 :size 4210}
                                                          :current {:mtime-sec 1762300042 :mtime-nsec 999000000 :size 4233}}}})
      "search" (assoc base :search {:status :loaded :query "config"
                                    :mode :name :truncated? true
                                    :results (mapv (fn [i]
                                                     (entry (str "config-" i ".clj")
                                                            (str "src/config/config-" i ".clj")
                                                            :file :previewable? true :size (* 100 i)
                                                            :stamp {:mtime-sec 1762300000 :mtime-nsec 0 :size (* 100 i)}))
                                                   (range 1 9))})
      base)))

(defn- files-page []
  (content
   (files-ui/file-browser-element
    (file-state (scenario))
    {:on-navigate (fn [path] (js/console.log "navigate" path))
     :on-open (fn [path] (js/console.log "open" path))
     :on-refresh (fn [] (js/console.log "refresh"))
     :on-search (fn [q] (js/console.log "search" q))
     :on-search-change (fn [q] (js/console.log "search-change" q))
     :on-clear-search (fn [] (js/console.log "clear-search"))
     :on-copy (fn [text] (js/console.log "copy" (count text)))})))

;; ---------------------------------------------------------------------------
;; Artifact fixtures

(def content-a (str "sha256-" (apply str (repeat 64 "a"))))
(def content-b (str "sha256-" (apply str (repeat 64 "b"))))

(defn- artifact-rows []
  [{:id "art-1" :name "config-migration-report.md" :kind :report
    :media-type "text/markdown" :size 18422 :status :available
    :blob-present? true :content-id content-a :version 3 :storage/version 2
    :environment :instance :project-id "herdchestra"
    :workspace-root "/home/operator/herdchestra"
    :source-path "/home/operator/herdchestra/plans/config-migration.md"
    :relative-path "plans/config-migration.md"
    :source-stamp {:mtime-sec 1762300000 :mtime-nsec 0 :size 18422}
    :source-task-id "t3" :source-call-id "call-9" :retractable? true
    :origin {:workspace-root "/home/operator/herdchestra"
             :path "/home/operator/herdchestra/plans/config-migration.md"
             :relative-path "plans/config-migration.md"
             :stamp {:mtime-sec 1762300000 :mtime-nsec 0 :size 18422}}
    :checks [{:label "size" :ok? true} {:label "hash" :ok? true}]}
   {:id "art-2" :name "startup.log" :kind :log :media-type "text/plain"
    :size 120034 :status :available :blob-present? true
    :content-id content-b :environment :instance-browser
    :truncated? true :truncated-reason "只保留了前 120 KiB"
    :workspace-root "/home/operator/herdchestra"
    :relative-path "artifacts/startup.log"
    :source-stamp {:mtime-sec 1762200000 :mtime-nsec 0 :size 120034}
    :source-task-id "t2"}
   {:id "art-3" :name "screenshot.png" :kind :binary :media-type "image/png"
    :size 483920 :status :missing :blob-present? false
    :content-id content-a :environment :host-browser
    :missing-reason "受管 Blob 已被清理"
    :source-task-id "t1"}
   {:id "art-4" :name "no-content-id.txt" :kind :file :media-type "text/plain"
    :size 42 :status :available :blob-present? true
    :environment :instance :source-task-id "t1"}
   {:id "art-5" :name "diagnostics.zip" :kind :diagnostic
    :media-type "application/zip" :size 92103 :status :available
    :blob-present? true :content-id content-b :environment :instance
    :retractable? true :workspace-root "/home/operator/herdchestra"
    :relative-path "diagnostics.zip"
    :source-stamp {:mtime-sec 1762100000 :mtime-nsec 0 :size 92103}}])

(defn- artifacts-page []
  (content
   (page-title "产物"
               "已保存到受管 Blob 区的交付物。缺失内容不会伪装成可打开的文件。"
               [(badge "5 项" :neutral)])
   (artifacts-view/artifacts-list
    {:artifacts (mapv artifacts/card (artifact-rows))
     :on-copy (fn [id] (js/console.log "copy" id))
     :on-retry (fn [card] (js/console.log "retry" (:id card)))
     :on-relocate (fn [card] (js/console.log "relocate" (:id card)))
     :on-retract (fn [card] (js/console.log "retract" (:id card)))})))

;; ---------------------------------------------------------------------------
;; Search fixtures

(defn- search-services []
  [{:id "local-stub" :label "Local Stub Search · searxng"
    :selectable? true :last-test {:label "最近测试成功" :tested-at "2026-11-06 09:40"}}
   {:id "vendor-search" :label "Vendor Search · 需要凭据"
    :selectable? false :last-test {:label "最近测试失败"
                                   :tested-at "2026-11-06 09:12"
                                   :error "缺少可用凭据"}}
   {:id "model-native" :label "模型自带搜索 · 当前模型不支持"
    :selectable? false}])

(defn- search-state [scenario]
  (let [base (merge (search-model/initial-state)
                    {:mode :required :service-id "local-stub" :open? true})]
    (case scenario
      "closed" (assoc base :mode :off :open? false)
      "empty" (assoc base :last-run {:status :empty :mode :auto
                                     :service "local-stub" :sources []})
      "error" (assoc base :last-run {:status :failed :mode :required
                                     :service "local-stub"
                                     :must-search-failed? true
                                     :error {:code :SEARCH_TIMEOUT
                                             :message "搜索服务在 10 秒内没有响应。"}})
      "sources"
      (assoc base
             :last-run {:status :ok :mode :required :service "local-stub"
                        :source-cards
                        [{:citation-number 1 :title "Configuration reference"
                          :url "https://example.com/docs/config"
                          :site "example.com" :published-at "2026-10-28"
                          :fetched-at "2026-11-06 10:01"
                          :snippet (str "The parser accepts the new format. Empty values "
                                        "fall back to the documented defaults.")
                          :content-status :full
                          :queries [{:query-id "q1" :text "config parser"}]}
                         {:citation-number 2 :title "Migration guide"
                          :url "https://example.com/docs/migrate"
                          :site "example.com" :published-at-unknown? true
                          :fetched-at "2026-11-06 10:01"
                          :snippet "Old files can still be loaded through the compatibility adapter."
                          :content-status :partial
                          :queries [{:query-id "q1" :text "config parser"}
                                    {:query-id "q2" :text "legacy loader"}]}
                         {:citation-number 3
                          :title "https://example.org/very/long/path/to/a/page/without/a/title"
                          :url "https://example.org/very/long/path/to/a/page/without/a/title"
                          :site "example.org" :published-at-unknown? true
                          :fetched-at "2026-11-06 10:02"
                          :content-status :blocked
                          :snippet "Access denied by the site."}]
                        :omitted-source-count 4})
      base)))

(defn- search-page []
  (let [state (search-state (scenario))]
    (content
     (page-title "联网搜索"
                 "搜索模式对当前会话生效；来源卡只显示服务器返回的事实。"
                 [(badge "来源：此对话覆盖" :warn)])
     (search-view/search-controls
      {:state state
       :services (search-services)
       :error (when (= (scenario) "error") nil)
       :disabled? false
       :on-change! (fn [change] (js/console.log "search change" (pr-str change)))}))))

;; ---------------------------------------------------------------------------
;; App

(defn- page []
  (case (or (.. js/window -location -hash) "#/board")
    "#/browser" (browser-page)
    "#/files" (files-page)
    "#/artifacts" (artifacts-page)
    "#/search" (search-page)
    (board-page)))

(defn- App []
  (let [[route set-route] (react/useState (or (.. js/window -location -hash) "#/board"))]
    (react/useEffect
     (fn []
       (let [on-hash (fn [] (set-route (or (.. js/window -location -hash) "#/board")))]
         (.addEventListener js/window "hashchange" on-hash)
         (fn [] (.removeEventListener js/window "hashchange" on-hash))))
     #js [])
    (dom/div {:className "wb-shell" :data-route route}
             (dom/header {:className "wb-topbar"}
                         (dom/div {:className "wb-brand"}
                                  (dom/strong {} "Herdchestra")
                                  (dom/span {} "视觉审查夹具"))
                         (dom/div {:className "wb-topbar-spacer"})
                         (dom/nav {:className "wb-row"}
                                  (for [r ["#/board" "#/browser" "#/files" "#/artifacts" "#/search"]]
                                    (dom/a {:key r :href r :className "wb-button wb-button-small"} r))))
             (dom/div {:className "wb-main" :style {:flex "1 1 auto"}}
                      (page)))))

(defn init []
  (let [el (js/document.getElementById "app")
        root (rdom/createRoot el)]
    (reset! root-atom root)
    (.render root (react/createElement App))))
