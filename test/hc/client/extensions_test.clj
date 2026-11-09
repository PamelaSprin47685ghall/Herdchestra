(ns hc.client.extensions-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.extensions.model :as model]
            [hc.client.extensions.view :as view]))

(deftest initial-state-is-empty-and-not-ready
  (let [state (model/initial-state)]
    (is (false? (:ready? state)))
    (is (= [] (:skills state)))
    (is (= [] (:mcp state)))
    (is (empty? (:commands state)))))

(deftest mcp-draft-builds-separate-program-and-arguments
  (let [draft {:name "local"
               :type :stdio
               :command "python3"
               :args "-m\nserver.py"
               :env "TOKEN=secret\nPLAIN=visible"}
        built (model/mcp-draft->server draft)]
    (is (:ok? built))
    (is (= {:name "local"
            :type :stdio
            :command "python3"
            :args ["-m" "server.py"]
            :env {"TOKEN" "secret" "PLAIN" "visible"}}
           (:server built)))
    (is (= (:server built)
           (:server (model/mcp-draft->server (assoc draft :type "stdio"))))
        "a select value arrives as a string and must normalize to a keyword")))

(deftest skill-source-builds-text-url-and-archive
  (is (= {:kind :text :text "body"}
         (:source (model/skill-draft->source
                   {:source :text :text "body"}))))
  (is (= {:kind :url :url "https://example.test/SKILL.md"}
         (:source (model/skill-draft->source
                   {:source "url" :url "https://example.test/SKILL.md"}))))
  (is (= {:kind :archive-base64
          :base64 "UEsDBA=="
          :filename "skill.zip"}
         (:source (model/skill-draft->source
                   {:source :archive
                    :archive-base64 "UEsDBA=="
                    :archive-name "skill.zip"}))))
  (is (false? (:ok? (model/skill-draft->source
                     {:source :url :url " "}))))
  (is (false? (:ok? (model/skill-draft->source
                     {:source :archive :archive-base64 ""})))))

(deftest upstream-update-source-supports-url-and-archive
  (let [state (assoc (model/initial-state)
                     :draft {:skill-updates {}
                             :skill-update-sources
                             {"s1" {:kind "url"
                                    :url "https://example.test/SKILL.md"}}})]
    (is (= {:kind :url :url "https://example.test/SKILL.md"}
           (:source (model/skill-update-source state "s1")))))
  (let [state (assoc (model/initial-state)
                     :draft {:skill-updates {}
                             :skill-update-sources
                             {"s1" {:kind :archive
                                    :archive-base64 "UEsDBA=="
                                    :archive-name "up.zip"}}})]
    (is (= {:kind :archive-base64
            :base64 "UEsDBA=="
            :filename "up.zip"}
           (:source (model/skill-update-source state "s1"))))))

(deftest invalid-drafts-do-not-enqueue
  (let [state (model/initial-state)
        result (model/reduce-action state
                                   {:type :extensions/mcp-add})]
    (is (nil? (get-in result [:state :outbox 0]))
        "invalid draft must not create a command")
    (is (string? (get-in result [:error :message])))))

(deftest valid-add-enqueues-a-real-command
  (let [state (-> (model/initial-state)
                  (model/reduce-action {:type :extensions/mcp-draft
                                        :field :name
                                        :value "remote"})
                  :state
                  (model/reduce-action {:type :extensions/mcp-draft
                                        :field :type
                                        :value :http})
                  :state
                  (model/reduce-action {:type :extensions/mcp-draft
                                        :field :url
                                        :value "https://example.test/mcp"})
                  :state
                  (model/reduce-action {:type :extensions/mcp-add})
                  :state)]
    (is (= 1 (count (:outbox state))))
    (is (= (keyword "extensions" "mcp/add")
           (get-in state [:outbox 0 :payload :kind])))))

(deftest outbox-is-not-success-when-transport-rejects
  (let [state (-> (model/initial-state)
                  (model/reduce-action {:type :extensions/refresh})
                  :state)
        flushed (model/flush-outbox! state (fn [_] false))]
    (is (= :failed (get-in flushed [:commands (get-in state [:outbox 0 :command-id])
                                   :status])))
    (is (empty? (:outbox flushed)))
    (is (string? (get-in flushed [:last-error :message])))))

(deftest accepted-view-receipt-updates-server-copy
  (let [state (model/initial-state)
        event {:type "CUSTOM"
               :name "hc/command-receipt"
               :value {:ok? true
                       :command-id "c1"
                       :receipt {:status :accepted
                                 :result {:version 4
                                          :skills [{:id "s1" :name "demo" :enabled? false}]
                                          :mcp [{:id "m1" :name "server" :enabled? false}]
                                          :connections []}}}}
        next (model/apply-receipt state event)]
    (is (true? (:ready? next)))
    (is (= 4 (:version next)))
    (is (= "demo" (get-in next [:skills 0 :name])))
    (is (false? (get-in next [:skills 0 :enabled?])))))

(deftest older-view-does-not-overwrite-newer-state
  (let [state (assoc (model/initial-state) :ready? true :version 9)
        event {:value {:ok? true
                       :command-id "c1"
                       :receipt {:status :accepted
                                 :result {:version 3 :skills [] :mcp []}}}}
        next (model/apply-receipt state event)]
    (is (= 9 (:version next)))))

(deftest accepted-mutation-queues-one-view-refresh
  (let [state (-> (model/initial-state)
                  (model/reduce-action {:type :extensions/mcp-enable
                                        :id "m1" :enabled? true})
                  :state)
        id (get-in state [:outbox 0 :command-id])
        event {:value {:ok? true
                       :command-id id
                       :receipt {:status :accepted :result {}}}}
        next (model/apply-receipt state event)]
    (is (= :accepted (get-in next [:commands id :status])))
    (is (= 1 (count (filter #(= model/view-kind (get-in % [:payload :kind]))
                            (:outbox next)))))))

(deftest tool-review-sends-explicit-review-flag
  (let [state (-> (model/initial-state)
                  (model/reduce-action {:type :extensions/mcp-tool-enable
                                        :id "m1" :tool "write_file"
                                        :enabled? true
                                        :approve-review? true})
                  :state)]
    (is (= {:approve-review? true}
           (get-in state [:outbox 0 :payload :opts])))))

(deftest skill-compare-enqueues-source-and-keeps-diff
  (let [state (-> (model/initial-state)
                  (model/reduce-action {:type :extensions/skill-update-draft
                                        :id "s1"
                                        :value "---\nname: demo\n---\n# upstream"})
                  :state
                  (model/reduce-action {:type :extensions/skill-compare
                                        :id "s1"})
                  :state)
        id (get-in state [:outbox 0 :command-id])
        diff {:paths [{:path "SKILL.md"
                       :status :conflict
                       :base "sha256-a"
                       :local "sha256-b"
                       :upstream "sha256-c"}]
              :conflicts #{"SKILL.md"}
              :summary {:conflict 1}}
        next (model/apply-receipt
              state
              {:value {:ok? true
                       :command-id id
                       :receipt {:status :accepted
                                 :result {:ok? true :diff diff}}}})]
    (is (= (keyword "extensions" "skill/compare")
           (get-in state [:outbox 0 :payload :kind])))
    (is (= :conflict (get-in next [:skill-updates "s1" :diff
                                   :paths 0 :status])))
    (is (= #{"SKILL.md"}
           (get-in next [:skill-updates "s1" :diff :conflicts])))))

(deftest skill-update-carries-conflict-strategy
  (let [state (-> (model/initial-state)
                  (model/reduce-action {:type :extensions/skill-update-draft
                                        :id "s1"
                                        :value "upstream text"})
                  :state
                  (model/reduce-action {:type :extensions/skill-apply-update
                                        :id "s1"
                                        :strategy :take-upstream})
                  :state)]
    (is (= (keyword "extensions" "skill/update")
           (get-in state [:outbox 0 :payload :kind])))
    (is (= :take-upstream
           (get-in state [:outbox 0 :payload :strategy])))
    (is (= "upstream text"
           (get-in state [:outbox 0 :payload :source :text])))))

(deftest mcp-import-preview-and-apply
  (let [state (-> (model/initial-state)
                  (model/reduce-action {:type :extensions/mcp-import-draft
                                        :value "{\"mcpServers\":{}}"})
                  :state
                  (model/reduce-action {:type :extensions/mcp-import-preview})
                  :state)
        id (get-in state [:outbox 0 :command-id])
        next (model/apply-receipt
              state
              {:value {:ok? true
                       :command-id id
                       :receipt {:status :accepted
                                 :result {:ok? true
                                          :candidates [{:name "remote"
                                                        :transport-type :http
                                                        :target "https://example.test/mcp"
                                                        :has-secrets? true}]
                                          :failed []}}}})
        applied (-> next
                    (model/reduce-action {:type :extensions/mcp-import-apply})
                    :state)
        import-command (some #(when (= (keyword "extensions" "mcp/import")
                                       (get-in % [:payload :kind]))
                                %)
                             (:outbox applied))]
    (is (= (keyword "extensions" "mcp/import-preview")
           (get-in state [:outbox 0 :payload :kind])))
    (is (= "remote" (get-in next [:mcp-import :preview :candidates 0 :name])))
    (is (= (keyword "extensions" "mcp/import")
           (get-in import-command [:payload :kind])))
    (is (= {:on-duplicate :rename}
           (get-in import-command [:payload :opts])))))

(deftest session-availability-is-never-assumed
  (is (= :available (:state (model/session-availability {:session-available? true}))))
  (is (= :unavailable (:state (model/session-availability {:session-available? false}))))
  (is (= :unknown (:state (model/session-availability {}))))
  (is (= "当前会话可用性未报告"
         (:label (model/session-availability {})))))

(deftest skill-source-selection-has-a-specific-message
  (is (re-find #"多个技能目录"
               (model/skill-source-error-label
                {:code :SKILL_SOURCE_NEEDS_SELECTION})))
  (is (re-find #"私有地址"
               (model/skill-source-error-label
                {:code ":SKILL_SOURCE_PRIVATE_ADDRESS"}))))

(deftest skill-install-payload-is-disabled-and-unbound
  (let [state (-> (model/initial-state)
                  (assoc-in [:draft :skill :text] "---\nname: demo\n---\n")
                  (model/reduce-action {:type :extensions/skill-install})
                  :state)
        payload (get-in state [:outbox 0 :payload])]
    (is (= (keyword "extensions" "skill/install") (:kind payload)))
    (is (not (contains? (:source payload) :enabled?)))
    (is (not (contains? payload :assistant-id)))))

(defn- view-nodes
  [node]
  (cond
    (map? node) (cons node (mapcat view-nodes (:children node)))
    (sequential? node) (mapcat view-nodes node)
    :else []))

(deftest extension-view-disables-enable-switches-while-pending
  (let [state (assoc (model/initial-state)
                     :ready? true
                     :version 1
                     :commands {"pending" {:status :submitting}}
                     :mcp [{:id "m1" :name "remote" :enabled? false
                            :state :connected-not-enabled
                            :transport {:type :http
                                        :url "https://example.test/mcp"}
                            :connection {:status :connected}
                            :tools []}]
                     :skills [{:id "s1" :name "demo" :enabled? false
                               :version "1" :bindings #{}
                               :description "demo"
                               :source {:kind :url
                                        :uri "https://example.test/SKILL.md"}
                               :files {}}])
        nodes (view-nodes (view/panel-tree state))
        disabled-checkboxes (filter (fn [node]
                                      (and (= :input (:tag node))
                                           (= "checkbox"
                                              (get-in node [:attrs :type]))
                                           (true? (get-in node [:attrs :disabled]))))
                                    nodes)]
    (is (>= (count disabled-checkboxes) 2))))

(deftest extension-view-shows-session-and-source
  (let [state (assoc (model/initial-state)
                     :ready? true
                     :version 1
                     :mcp [{:id "m1" :name "remote" :enabled? false
                            :state :connected-not-enabled
                            :session-available? false
                            :session-scope {:session-id "s1"
                                            :assistant-id "a1"}
                            :transport {:type :http
                                        :url "https://example.test/mcp"}
                            :connection {:status :connected}
                            :tools []}]
                     :skills [{:id "s1" :name "demo" :enabled? false
                               :version "1" :bindings #{}
                               :description "demo"
                               :source {:kind :url
                                        :uri "https://example.test/SKILL.md"}
                               :files {}}])
        rendered (pr-str (view/panel-tree state))]
    (is (re-find #"当前会话不可用" rendered))
    (is (re-find #"会话范围：a1 / s1" rendered))
    (is (re-find #"新增配置 → 测试连通 → 启用工具 → 会话可用" rendered))
    (is (re-find #"来源：链接 https://example.test/SKILL.md" rendered))))

(deftest extension-view-shows-skill-selection-message
  (let [state (assoc (model/initial-state)
                     :last-error {:code :SKILL_SOURCE_NEEDS_SELECTION})]
    (is (re-find #"多个技能目录"
                 (pr-str (view/panel-tree state))))))

(defn- skill-source-select
  "The source-kind select in the install form.  It is the only select whose
  options contain the archive wire value."
  [tree]
  (first (filter (fn [node]
                   (and (= :select (:tag node))
                        (some (fn [option]
                                (and (= :option (:tag option))
                                     (= "archive"
                                        (get-in option [:attrs :value]))))
                              (view-nodes (:children node)))))
                 (view-nodes tree))))

(defn- button-labeled
  [tree label]
  (first (filter #(and (= :button (:tag %))
                       (= [label] (:children %)))
                 (view-nodes tree))))

(defn- select-for-action
  [tree type]
  (first (filter (fn [node]
                   (and (= :select (:tag node))
                        (= type (get-in node [:attrs :events :on-change
                                              :action :type]))))
                 (view-nodes tree))))

(deftest skill-source-select-sends-archive-kind-and-shows-picker
  (let [state (assoc (model/initial-state) :ready? true)
        tree (view/panel-tree state)
        source-select (skill-source-select tree)
        options (filter #(= :option (:tag %))
                        (view-nodes (:children source-select)))]
    (is (some? source-select)
        "the install form offers a source selector")
    (is (= ["text" "url" "archive"]
           (mapv #(get-in % [:attrs :value]) options))
        "option values are wire keywords, never the visible labels")
    (is (= [["文本"] ["链接"] ["压缩包"]]
           (mapv :children options)))
    (is (= {:on-change {:action {:type :extensions/skill-source
                                 :field :source}
                        :value :target-value}}
           (get-in source-select [:attrs :events]))
        "the change event carries the selected option value into the draft")
    (is (not-any? #(and (= :option (:tag %))
                        (= "压缩包" (get-in % [:attrs :value])))
                  (view-nodes tree))
        "the visible label is never sent as the source kind")
    (is (some #(and (= :textarea (:tag %))
                    (re-find #"weekly-report"
                             (str (get-in % [:attrs :placeholder]))))
              (view-nodes tree))
        "the text editor is shown for the text source")
    (let [selected (-> state
                       (model/reduce-action {:type :extensions/skill-source
                                             :field :source
                                             :value "archive"})
                       :state)
          selected-nodes (view-nodes (view/panel-tree selected))]
      (is (= :archive
             (model/source-kind
              (get-in selected [:draft :skill :source])))
          "the selected wire value normalizes to :archive")
      (is (some #(and (= :input (:tag %))
                      (= "file" (get-in % [:attrs :type]))
                      (= ".zip,application/zip"
                         (get-in % [:attrs :accept])))
                selected-nodes)
          "choosing 压缩包 reveals the archive file picker")
      (is (not-any? #(and (= :textarea (:tag %))
                          (re-find #"weekly-report"
                                   (str (get-in % [:attrs :placeholder]))))
                    selected-nodes)
          "the archive picker replaces the text editor"))))

(deftest skill-update-source-select-sends-archive-kind
  (let [skill {:id "s1" :name "demo" :enabled? false
               :version "1" :bindings #{}
               :description "demo"
               :source {:kind :url :uri "https://example.test/SKILL.md"}
               :files {}}
        state (assoc (model/initial-state)
                     :ready? true :version 1 :skills [skill])
        update-select (select-for-action (view/panel-tree state)
                                         :extensions/skill-update-source)
        archive-option (first (filter #(and (= :option (:tag %))
                                            (= "archive"
                                               (get-in % [:attrs :value])))
                                      (view-nodes (:children update-select))))
        selected (-> state
                     (model/reduce-action
                      {:type :extensions/skill-update-source
                       :id "s1"
                       :value "archive"})
                     :state)
        selected-nodes (view-nodes (view/panel-tree selected))
        url-state (-> selected
                      (model/reduce-action
                       {:type :extensions/skill-update-source
                        :id "s1"
                        :value "url"})
                      :state)
        url-nodes (view-nodes (view/panel-tree url-state))]
    (is (some? update-select)
        "the installed skill exposes an upstream source selector")
    (is (= "压缩包" (first (:children archive-option))))
    (is (= "archive"
           (get-in selected [:draft :skill-update-sources "s1" :kind]))
        "the selected option value is stored as the update source kind")
    (is (= :archive
           (model/source-kind
            (get-in selected [:draft :skill-update-sources "s1" :kind])))
        "the stored update source value normalizes to :archive")
    (is (some #(and (= :input (:tag %))
                    (= "file" (get-in % [:attrs :type]))
                    (= ".zip,application/zip"
                       (get-in % [:attrs :accept])))
              selected-nodes)
        "choosing 压缩包 reveals the update archive picker")
    (is (= "url"
           (get-in url-state [:draft :skill-update-sources "s1" :kind])))
    (is (some #(and (= :input (:tag %))
                    (= "https://example.test/SKILL.md 或 .zip"
                       (get-in % [:attrs :placeholder])))
              url-nodes)
        "choosing 链接 reveals the upstream URL field")))

(deftest auto-refresh-preserves-compared-diff-and-apply-stays-enabled
  (let [skill {:id "s1" :name "demo" :enabled? false
               :version "1" :bindings #{}
               :description "demo"
               :source {:kind :url :uri "https://example.test/SKILL.md"}
               :files {}}
        state (-> (model/initial-state)
                  (assoc :ready? true :version 1 :skills [skill])
                  (assoc-in [:draft :skill-updates "s1"] "upstream text")
                  (model/reduce-action {:type :extensions/skill-compare
                                        :id "s1"})
                  :state)
        compare-id (get-in state [:outbox 0 :command-id])
        diff {:paths [{:path "SKILL.md"
                       :status :conflict
                       :base "sha256-a"
                       :local "sha256-b"
                       :upstream "sha256-c"}]
              :conflicts #{"SKILL.md"}
              :summary {:conflict 1}}
        compared (model/apply-receipt
                  state
                  {:value {:ok? true
                           :command-id compare-id
                           :receipt {:status :accepted
                                     :result {:ok? true :diff diff}}}})
        refresh-id (some (fn [command]
                           (when (= model/view-kind
                                    (get-in command [:payload :kind]))
                             (:command-id command)))
                         (:outbox compared))
        refreshed (model/apply-receipt
                   compared
                   {:value {:ok? true
                            :command-id refresh-id
                            :receipt {:status :accepted
                                      :result {:version 2
                                               :skills [(assoc skill
                                                               :description
                                                               "refreshed")]
                                               :mcp []
                                               :connections []}}}})
        applied (-> refreshed
                    (model/reduce-action
                     {:type :extensions/skill-apply-update
                      :id "s1"
                      :strategy :keep-local})
                    :state)
        update-command (first (filter #(= (keyword "extensions" "skill/update")
                                          (get-in % [:payload :kind]))
                                      (:outbox applied)))]
    (is (true? (get-in (button-labeled (view/panel-tree state)
                                       "应用并保留本地")
                       [:attrs :disabled]))
        "apply is disabled before a diff exists")
    (is (= diff (get-in compared [:skill-updates "s1" :diff])))
    (is (some? refresh-id)
        "an accepted compare queues one automatic snapshot refresh")
    (is (false? (model/pending? compared))
        "the automatic refresh does not count as a pending write")
    (is (some? (button-labeled (view/panel-tree compared)
                               "应用并保留本地")))
    (is (false? (get-in (button-labeled (view/panel-tree compared)
                                        "应用并保留本地")
                        [:attrs :disabled]))
        "apply is enabled as soon as the diff exists")
    (is (= 2 (:version refreshed)))
    (is (= diff (get-in refreshed [:skill-updates "s1" :diff]))
        "the automatic snapshot keeps the compared diff")
    (is (false? (get-in (button-labeled (view/panel-tree refreshed)
                                        "应用并保留本地")
                        [:attrs :disabled]))
        "apply stays enabled after the automatic snapshot")
    (is (= :keep-local (get-in update-command [:payload :strategy])))
    (is (= "upstream text"
           (get-in update-command [:payload :source :text])))
    (is (= 1 (count (filter #(= (keyword "extensions" "skill/update")
                                (get-in % [:payload :kind]))
                            (:outbox applied)))))))

(deftest installed-skill-card-shows-the-file-preview
  (let [state (assoc (model/initial-state)
                     :ready? true
                     :version 1
                     :skills [{:id "s1" :name "demo" :enabled? false
                               :version "1" :bindings #{}
                               :description "demo"
                               :source {:kind :archive :filename "demo.zip"}
                               :files {"SKILL.md" {:size 42 :text? true}
                                       "assets/logo.png" {:size 9
                                                          :text? false}}}])
        nodes (view-nodes (view/panel-tree state))
        file-rows (filter #(= "hc-ext-file" (get-in % [:attrs :class]))
                          nodes)
        rendered (pr-str (view/panel-tree state))]
    (is (= 2 (count file-rows))
        "the installed card lists each skill file as the preview surface")
    (is (re-find #"来源：压缩包 demo.zip" rendered))
    (is (re-find #"SKILL\.md" rendered))
    (is (re-find #"42 B · 文本" rendered))
    (is (re-find #"9 B · 二进制" rendered))))

(deftest session-skill-toggle-enqueues-a-session-command
  (let [state (-> (model/initial-state)
                  (assoc :selected-session-id "s1"
                         :session-scope {:mode :custom :skills #{"a"}})
                  (model/reduce-action
                   {:type :extensions/session-skill-toggle
                    :id "b" :selected? true})
                  :state)
        payload (get-in state [:outbox 0 :payload])]
    (is (= (keyword "session" "select-skills") (:kind payload)))
    (is (= "s1" (:session-id payload)))
    (is (= :custom (:mode payload)))
    (is (= ["a" "b"] (:skills payload))
        "toggling one skill keeps the rest of the custom set")))

(deftest session-skill-follow-command-drops-the-custom-set
  (let [state (-> (model/initial-state)
                  (assoc :selected-session-id "s1"
                         :session-scope {:mode :custom :skills #{"a"}})
                  (model/reduce-action
                   {:type :extensions/session-skills-follow})
                  :state)
        payload (get-in state [:outbox 0 :payload])]
    (is (= (keyword "session" "select-skills") (:kind payload)))
    (is (= "s1" (:session-id payload)))
    (is (= :follow (:mode payload)))
    (is (not (contains? payload :skills)))))

(deftest session-skill-selection-needs-a-session
  (let [{:keys [state error]}
        (model/reduce-action (model/initial-state)
                             {:type :extensions/session-skill-toggle
                              :id "a" :selected? true})]
    (is (= "先选择一个会话" (:message error)))
    (is (empty? (:outbox state)))))

(deftest session-skill-view-shows-an-empty-selection
  (let [rendered (pr-str (view/panel-tree
                          (assoc (model/initial-state) :ready? true)))]
    (is (re-find #"会话技能" rendered))
    (is (re-find #"未选择会话" rendered))))

(deftest session-skill-view-shows-custom-selection-and-active-set
  (let [state (assoc (model/initial-state)
                     :ready? true
                     :selected-session-id "s1"
                     :session-scope {:mode :custom :skills #{"s1"}}
                     :resolved-skills {:active [{:id "s1" :name "demo"}]
                                       :blocked [] :excluded []}
                     :skills [{:id "s1" :name "demo" :enabled? true
                               :version "1" :bindings #{}
                               :description "demo"
                               :source {:kind :text} :files {}}])
        nodes (view-nodes (view/panel-tree state))
        checked (filter (fn [node]
                          (and (= :input (:tag node))
                               (= "checkbox" (get-in node [:attrs :type]))
                               (= :extensions/session-skill-toggle
                                  (get-in node [:attrs :events :on-change
                                                :action :type]))
                               (true? (get-in node [:attrs :checked]))))
                        nodes)
        rendered (pr-str (view/panel-tree state))]
    (is (= 1 (count checked))
        "the selected skill is checked")
    (is (re-find #"(本轮实际可用|当前会话实际可用)：demo" rendered))
    (is (re-find #"当前：自定义" rendered))))

(deftest session-skill-view-explains-a-global-disable
  (let [state (assoc (model/initial-state)
                     :ready? true
                     :selected-session-id "s1"
                     :session-scope {:mode :custom :skills #{"s1"}}
                     :resolved-skills {:active []
                                       :blocked []
                                       :excluded [{:id "s1" :name "demo"
                                                   :reason :globally-disabled}]}
                     :skills [{:id "s1" :name "demo" :enabled? false
                               :version "1" :bindings #{}
                               :description "demo"
                               :source {:kind :text} :files {}}])
        nodes (view-nodes (view/panel-tree state))
        disabled (filter (fn [node]
                           (and (= :input (:tag node))
                                (= "checkbox" (get-in node [:attrs :type]))
                                (true? (get-in node [:attrs :disabled]))))
                         nodes)
        rendered (pr-str (view/panel-tree state))]
    (is (= 1 (count disabled)))
    (is (re-find #"技能已全局停用" rendered))))

(deftest apply-view-merges-session-scope-and-resolved-skills
  (let [state (model/apply-view
               (model/initial-state)
               {:version 2
                :skills []
                :mcp []
                :connections []
                :session-id "s1"
                :session-scope {:mode :custom :skills ["a" "b"]}
                :resolved-skills {:active [{:id "a" :name "A"}]
                                  :blocked [{:id "b" :name "B"}]
                                  :excluded []}})]
    (is (= "s1" (:selected-session-id state)))
    (is (= {:mode :custom :skills #{"a" "b"}} (:session-scope state)))
    (is (= ["a"] (mapv :id (get-in state [:resolved-skills :active]))))
    (is (= ["b"] (mapv :id (get-in state [:resolved-skills :blocked]))))))

(deftest command-history-is-bounded-and-keeps-pending
  (let [completed (reduce (fn [state i]
                            (assoc-in state [:commands (str "c" i)]
                                      {:command-id (str "c" i)
                                       :status :accepted
                                       :created-at i}))
                          (model/initial-state)
                          (range 150))
        state (assoc-in completed [:commands "pending"]
                        {:command-id "pending" :status :queued :created-at 999})
        pruned (#'model/prune-commands state)]
    (is (= 100 (count (:commands pruned))))
    (is (contains? (:commands pruned) "pending")
        "a queued command is never pruned")
    (is (contains? (:commands pruned) "c149")
        "the newest completed receipt survives")
    (is (not (contains? (:commands pruned) "c0"))
        "the oldest completed receipt is dropped")))

(deftest accepted-session-skill-receipt-updates-the-local-scope
  (let [state (-> (model/initial-state)
                  (assoc :selected-session-id "s1"
                         :session-scope {:mode :follow :skills #{}})
                  (model/reduce-action {:type :extensions/session-skills-custom})
                  :state)
        command-id (get-in state [:outbox 0 :command-id])
        applied (model/apply-receipt
                 state
                 {:value {:ok? true
                          :command-id command-id
                          :receipt {:status :accepted
                                    :session-scope {:mode :custom
                                                    :skills ["a"]}}}})]
    (is (= :custom (get-in applied [:session-scope :mode])))
    (is (= #{"a"} (get-in applied [:session-scope :skills]))
        "the accepted receipt is authoritative without waiting for a view")))

(deftest accepted-session-skill-receipt-projects-active-skills
  (let [state (-> (model/initial-state)
                  (assoc :selected-session-id "s1"
                         :skills [{:id "a" :name "A" :enabled? true}
                                  {:id "b" :name "B" :enabled? true}]
                         :session-scope {:mode :follow :skills #{}})
                  (model/reduce-action {:type :extensions/session-skill-toggle
                                        :id "a" :selected? true})
                  :state)
        command-id (get-in state [:outbox 0 :command-id])
        applied (model/apply-receipt
                 state
                 {:value {:ok? true
                          :command-id command-id
                          :receipt {:status :accepted
                                    :session-scope {:mode :custom
                                                    :skills ["a" "b"]}}}})]
    (is (= ["a" "b"] (mapv :id (get-in applied [:resolved-skills :active]))))
    (let [blocked (assoc-in state [:resolved-skills :blocked] [{:id "b"}])
          applied (model/apply-receipt
                   blocked
                   {:value {:ok? true
                            :command-id command-id
                            :receipt {:status :accepted
                                      :session-scope {:mode :custom
                                                      :skills ["a" "b"]}}}})]
      (is (= ["a"] (mapv :id (get-in applied [:resolved-skills :active])))
          "a server-blocked skill is not shown as active"))))

(deftest switching-session-clears-the-previous-session-scope
  (let [a (assoc (model/initial-state)
                 :selected-session-id "a"
                 :session-scope {:mode :custom :skills #{"s1"}}
                 :resolved-skills {:active [{:id "s1" :name "demo"}]
                                   :blocked [] :excluded []})
        b (model/switch-session a "b")]
    (is (= "b" (:selected-session-id b)))
    (is (= {:mode :follow :skills #{}} (:session-scope b)))
    (is (= [] (get-in b [:resolved-skills :active]))
        "the previous session's active skills are not reused")
    (is (= a (model/switch-session a "a"))
        "selecting the same session keeps its state")))

(defn- button-actions
  "On-click action types present in TREE."
  [tree]
  (set (keep #(get-in % [:attrs :events :on-click :action :type])
             (view-nodes tree))))

(defn- delete-view-state
  []
  (assoc (model/initial-state)
         :ready? true
         :version 1
         :mcp [{:id "m1" :name "remote" :enabled? false
                :state :connected-not-enabled
                :transport {:type :http :url "https://example.test/mcp"}
                :connection {:status :connected}
                :tools []}]
         :skills [{:id "s1" :name "demo" :enabled? false
                   :version "1" :bindings #{}
                   :description "demo"
                   :source {:kind :text}
                   :files {}}]))

(deftest mcp-delete-needs-an-explicit-confirmation
  (let [state (assoc (model/initial-state) :ready? true)
        requested (:state (model/reduce-action
                           state
                           {:type :extensions/mcp-request-delete :id "m1"}))]
    (is (= "m1" (get-in requested [:confirm-delete :mcp])))
    (is (empty? (:outbox requested))
        "the request action only records the confirmation")
    (let [unconfirmed (model/reduce-action
                       state
                       {:type :extensions/mcp-remove :id "m1"})]
      (is (empty? (get-in unconfirmed [:state :outbox]))
          "a delete without confirmation never reaches the server")
      (is (string? (get-in unconfirmed [:error :message]))))
    (let [cancelled (:state (model/reduce-action
                             requested
                             {:type :extensions/mcp-cancel-delete}))]
      (is (nil? (get-in cancelled [:confirm-delete :mcp])))
      (is (empty? (:outbox cancelled))))
    (let [removed (model/reduce-action requested
                                       {:type :extensions/mcp-remove :id "m1"})]
      (is (= (keyword "extensions" "mcp/remove")
             (get-in removed [:state :outbox 0 :payload :kind])))
      (is (= "m1" (get-in removed [:state :outbox 0 :payload :id])))
      (is (nil? (get-in removed [:state :confirm-delete :mcp]))
          "the confirmation is cleared as soon as the command is queued")
      (is (nil? (:error removed))))))

(deftest skill-delete-needs-its-own-confirmation
  (let [state (assoc (model/initial-state)
                     :confirm-delete {:mcp "s1" :skill nil})
        requested (:state (model/reduce-action
                           state
                           {:type :extensions/skill-request-delete :id "s1"}))]
    (is (= "s1" (get-in requested [:confirm-delete :skill])))
    (is (= "s1" (get-in requested [:confirm-delete :mcp]))
        "an MCP confirmation with the same id is untouched")
    (is (empty? (:outbox requested)))
    (let [wrong-family (model/reduce-action
                        state
                        {:type :extensions/skill-delete :id "s1"})]
      (is (empty? (get-in wrong-family [:state :outbox]))
          "an MCP confirmation does not confirm a skill delete"))
    (let [removed (model/reduce-action requested
                                       {:type :extensions/skill-delete :id "s1"})]
      (is (= (keyword "extensions" "skill/delete")
             (get-in removed [:state :outbox 0 :payload :kind])))
      (is (nil? (get-in removed [:state :confirm-delete :skill])))
      (is (nil? (:error removed))))
    (let [cancelled (:state (model/reduce-action
                             requested
                             {:type :extensions/skill-cancel-delete}))]
      (is (nil? (get-in cancelled [:confirm-delete :skill])))
      (is (empty? (:outbox cancelled))))))

(deftest extension-view-asks-before-deleting
  (let [state (delete-view-state)
        tree (view/panel-tree state)
        request (button-labeled tree "删除")
        actions (button-actions (view/panel-tree state))]
    (is (= :extensions/mcp-request-delete
           (get-in request [:attrs :events :on-click :action :type])))
    (is (= "m1" (get-in request [:attrs :events :on-click :action :id])))
    (is (contains? actions :extensions/mcp-request-delete))
    (is (contains? actions :extensions/skill-request-delete))
    (is (not (contains? actions :extensions/mcp-remove))
        "the real MCP delete action is absent before confirmation")
    (is (not (contains? actions :extensions/skill-delete))
        "the real skill delete action is absent before confirmation")
    (is (nil? (button-labeled (view/panel-tree state) "确认删除服务器")))
    (is (nil? (button-labeled (view/panel-tree state) "确认删除技能")))
    (let [confirmed (assoc state :confirm-delete {:mcp "m1" :skill "s1"})
          tree (view/panel-tree confirmed)
          actions (button-actions tree)
          mcp-confirm (button-labeled tree "确认删除服务器")
          skill-confirm (button-labeled tree "确认删除技能")]
      (is (some? mcp-confirm))
      (is (some? skill-confirm))
      (is (= :extensions/mcp-remove
             (get-in mcp-confirm [:attrs :events :on-click :action :type])))
      (is (= :extensions/skill-delete
             (get-in skill-confirm [:attrs :events :on-click :action :type])))
      (is (= "m1" (get-in mcp-confirm [:attrs :events :on-click :action :id])))
      (is (= "s1" (get-in skill-confirm [:attrs :events :on-click :action :id])))
      (is (contains? actions :extensions/mcp-cancel-delete))
      (is (contains? actions :extensions/skill-cancel-delete))
      (is (not (contains? actions :extensions/mcp-request-delete)))
      (is (not (contains? actions :extensions/skill-request-delete))))))

(deftest delete-request-is-disabled-while-a-command-is-pending
  (let [state (assoc (delete-view-state)
                     :commands {"pending" {:status :submitting}})
        requests (filter #(contains? #{:extensions/mcp-request-delete
                                        :extensions/skill-request-delete}
                                      (get-in % [:attrs :events :on-click
                                                 :action :type]))
                         (view-nodes (view/panel-tree state)))]
    (is (= 2 (count requests)))
    (is (every? #(true? (get-in % [:attrs :disabled])) requests))))

(deftest skill-update-section-folds-into-details
  (let [skill {:id "s1" :name "demo" :enabled? false
               :version "1" :bindings #{}
               :description "demo"
               :source {:kind :text}
               :files {}}
        state (assoc (model/initial-state)
                     :ready? true :version 1 :skills [skill])
        nodes (view-nodes (view/panel-tree state))
        details (first (filter #(= :details (:tag %)) nodes))
        summary (first (filter #(= :summary (:tag %)) nodes))]
    (is (some? details)
        "the upstream comparison is a disclosure element")
    (is (= "hc-ext-update" (get-in details [:attrs :class])))
    (is (not (contains? (:attrs details) :open))
        "the comparison starts collapsed")
    (is (= ["上游更新与三方对比"] (:children summary)))
    (is (not-any? #(and (= :h3 (:tag %))
                        (= ["上游更新与三方对比"] (:children %)))
                  nodes)
        "the summary replaces the duplicate h3 heading")
    (is (seq (filter #(= :textarea (:tag %))
                     (view-nodes (:children details))))
        "the collapsed section keeps its full content tree")))

(deftest session-skill-view-names-blocked-and-excluded-skills
  (let [rendered (pr-str (view/panel-tree
                          (assoc (model/initial-state)
                                 :ready? true
                                 :selected-session-id "s1"
                                 :session-scope {:mode :custom :skills #{}}
                                 :resolved-skills
                                 {:active []
                                  :blocked [{:id "b1" :name "乙技能"}
                                            {:id "b2"}]
                                  :excluded [{:id "e1" :name "丙技能"}]})))]
    (is (re-find #"条件未满足（2）：乙技能、b2" rendered))
    (is (re-find #"未生效（1）：丙技能" rendered)))
  (let [rendered (pr-str (view/panel-tree
                          (assoc (model/initial-state)
                                 :ready? true
                                 :selected-session-id "s1"
                                 :session-scope {:mode :custom :skills #{}}
                                 :resolved-skills
                                 {:active [] :blocked [] :excluded []})))]
    (is (not (re-find #"条件未满足" rendered))
        "an empty blocked set renders no line")
    (is (not (re-find #"未生效" rendered))
        "an empty excluded set renders no line")))

(deftest skill-card-shows-binding-assistant-names
  (let [state (assoc (model/initial-state)
                     :ready? true
                     :version 1
                     :assistants [{:id "a1" :name "助手甲"}
                                  {:id "a2" :name "助手乙"}]
                     :skills [{:id "s1" :name "demo" :enabled? false
                               :version "1"
                               :bindings ["a1" {:id "a2"}]
                               :description "demo"
                               :source {:kind :text}
                               :files {}}])
        rendered (pr-str (view/panel-tree state))]
    (is (re-find #"绑定：助手甲、助手乙" rendered))
    (is (not (re-find #"绑定：a1" rendered)))))

(deftest skill-card-falls-back-to-binding-id-and-says-unbound
  (let [base (assoc (model/initial-state)
                    :ready? true
                    :version 1
                    :assistants [{:id "a9" :name "查表名"}])
        bound (assoc base
                     :skills [{:id "s1" :name "demo" :enabled? false
                               :version "1"
                               :bindings [{:assistant-id "a9" :name "显式名"}
                                          "a9"
                                          "missing"]
                               :description "demo"
                               :source {:kind :text}
                               :files {}}])
        unbound (assoc base
                       :skills [{:id "s2" :name "plain" :enabled? false
                                 :version "1"
                                 :bindings []
                                 :description "plain"
                                 :source {:kind :text}
                                 :files {}}])]
    (is (re-find #"绑定：显式名、查表名、missing"
                 (pr-str (view/panel-tree bound)))
        "a map name wins, then the assistants lookup, then the raw id")
    (is (re-find #"未绑定" (pr-str (view/panel-tree unbound))))))
