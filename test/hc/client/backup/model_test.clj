(ns hc.client.backup.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.backup.commands :as commands]
            [hc.client.backup.model :as model]
            [hc.client.backup.view :as view]))

(defn- connected
  []
  (-> (model/initial-state)
      (assoc :sync {:phase :synced :command-epoch 7})
      (assoc-in [:ui :transport-available?] true)
      (assoc-in [:ui :target-id] :local)
      (assoc-in [:server :default-target-id] :local)
      (assoc-in [:server :targets]
                [{:id :local :kind :file :configured? true :available? true
                  :root "/data/backups"}])
      (assoc-in [:server :status]
                {:targets [{:id :local :kind :file :configured? true
                            :available? true :root "/data/backups"}]
                 :default-target-id :local
                 :target {:id :local :kind :file :configured? true
                          :available? true :root "/data/backups"}
                 :restore {:online? false :offline? true
                           :requires-quiescence? true}})))

(defn- act
  [state action]
  (:state (model/reduce-action state action)))

(defn- last-command
  [state]
  (let [envelope (last (:outbox state))
        id (:command-id envelope)]
    (assoc (get-in state [:commands id])
           :payload (:payload envelope))))

(defn- apply-result
  [state result]
  (let [id (-> state :outbox last :command-id)]
    (act state {:type :backup/apply-receipt
                :receipt {:command-id id :ok? true :receipt result}})))

(defn- apply-error
  [state error]
  (let [id (-> state :outbox last :command-id)]
    (act state {:type :backup/apply-receipt
                :receipt {:command-id id :ok? false :error error}})))

(defn- find-node
  [tree pred]
  (cond
    (pred tree) tree
    (map? tree) (some #(find-node % pred) (:children tree))
    (sequential? tree) (some #(find-node % pred) tree)
    :else nil))

(defn- button-by-text
  [tree text]
  (find-node tree
             (fn [node]
               (and (= :button (get-in node [:tag]))
                    (= text (first (:children node)))))))

(defn- with-command
  [state kind payload]
  (let [id "manual-command"]
    (-> state
        (assoc-in [:commands id]
                  {:command-id id
                   :command-epoch 7
                   :kind kind
                   :key [kind :manual :manual]
                   :payload payload
                   :status :submitting})
        (assoc :outbox []))))

(defn- apply-manual-result
  [state id result]
  (act state {:type :backup/apply-receipt
              :receipt {:command-id id :ok? true :receipt result}}))

(defn- status-result
  []
  {:data-dir "/data"
   :default-target-id :local
   :targets [{:id :local :kind :file :configured? true :available? true
              :root "/data/backups"}
             {:id :dav :kind :webdav :configured? true :available? false
              :base-url "https://dav.example.com" :directory "hc"
              :reason "连接失败"}
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
                      :bytes 2048
                      :reason :before-restore :scope [:conversations]}]
   :storage {:engine "datalevin"}
   :errors []})

(deftest password-contract-test
  (testing "Chinese and spaces are accepted and never trimmed"
    (let [password "中文密码 包含空格 123"
          check (model/password-check password password)]
      (is (:ok? check))
      (is (= 13 (:length check)))
      (is (= password password))))
  (testing "leading and trailing spaces are meaningful"
    (let [password "  twelve chars "
          check (model/password-check password password)]
      (is (:ok? check))
      (is (= 15 (:length check)))))
  (testing "fewer than twelve code points is refused"
    (let [check (model/password-check "中文密码 123" "中文密码 123")]
      (is (false? (:ok? check)))
      (is (= :too-short (:reason check)))))
  (testing "a surrogate pair counts as one character"
    (is (= 12 (model/code-point-count "😀😀😀😀😀😀😀😀😀😀😀😀")))
    (is (:ok? (model/password-check "😀😀😀😀😀😀😀😀😀😀😀😀"
                                    "😀😀😀😀😀😀😀😀😀😀😀😀"))))
  (testing "whitespace-only and mismatch are refused"
    (is (= :blank (:reason (model/password-check "            " "            "))))
    (is (= :mismatch (:reason (model/password-check "correct horse" "correct horsE"))))))

(deftest category-selection-test
  (let [state (connected)]
    (is (= (set model/category-order)
           (set (model/selected-categories (get-in state [:ui :categories])))))
    (let [state (act state {:type :backup/set-categories
                            :scope :create :value false})
          state (-> state
                    (act {:type :backup/toggle-category
                          :scope :create :category :assistants})
                    (act {:type :backup/toggle-category
                          :scope :create :category :worldbooks}))]
      (is (= [:assistants :worldbooks]
             (model/selected-categories (get-in state [:ui :categories])))))
    (testing "create and restore selections are independent"
      (is (= (set model/category-order)
             (set (model/selected-categories
                   (get-in state [:ui :restore-categories]))))))))

(deftest create-payload-test
  (let [state (-> (connected)
                  (act {:type :backup/set-field
                        :field :password
                        :value "correct horse battery staple"})
                  (act {:type :backup/set-field
                        :field :password-confirm
                        :value "correct horse battery staple"})
                  (act {:type :backup/set-categories
                        :scope :create :value false})
                  (act {:type :backup/toggle-category
                        :scope :create :category :assistants})
                  (act {:type :backup/toggle-category
                        :scope :create :category :worldbooks}))
        queued (act state {:type :backup/create})
        payload (:payload (last-command queued))]
    (is (= :backup/create (:kind payload)))
    (is (= [:assistants :worldbooks] (:categories payload)))
    (is (true? (:encrypt? payload)))
    (is (false? (:include-secrets? payload)))
    (is (= "correct horse battery staple" (:password payload)))
    (is (= "correct horse battery staple" (:password-confirm payload)))
    (is (= :local (:target-id payload)))
    (testing "a short password refuses to queue anything"
      (let [state (act state {:type :backup/set-field
                              :field :password :value "short"})
            before (count (:outbox state))
            state (act state {:type :backup/create})]
        (is (= before (count (:outbox state))))
        (is (= :warning (get-in state [:ui :message :kind])))))
    (testing "unencrypted backups omit the password"
      (let [state (-> state
                      (act {:type :backup/toggle-field :field :encrypt?})
                      (act {:type :backup/create}))
            payload (:payload (last-command state))]
        (is (false? (:encrypt? payload)))
        (is (not (contains? payload :password)))))
    (testing "the create receipt clears the password and redacts the command"
      (let [command-id (-> queued :outbox last :command-id)
            done (apply-result queued
                               {:status :complete
                                :backup {:id "b-new"
                                         :sampled-at "2026-11-03T00:00:00Z"
                                         :encrypted? true}
                                :categories {}
                                :attachments {:count 0 :bytes 0}})
            payload (get-in done [:commands command-id :payload])]
        (is (= "" (get-in done [:ui :password])))
        (is (= "" (get-in done [:ui :password-confirm])))
        (is (not (contains? payload :password)))
        (is (not (contains? payload :password-confirm)))
        (is (true? (get-in done [:commands command-id :password-redacted?])))))))

(deftest verify-receipt-clears-password-and-redacts-command-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/select-backup :backup-id "b1"})
                  (act {:type :backup/set-field :field :password
                        :value "correct horse battery staple"})
                  (act {:type :backup/verify}))
        command-id (-> state :outbox last :command-id)
        done (apply-result state
                           {:backup {:id "b1"
                                     :sampled-at "2026-11-03T00:00:00Z"}})
        payload (get-in done [:commands command-id :payload])]
    (is (= :backup/verify (get-in done [:commands command-id :kind])))
    (is (some? (get-in done [:ui :verify-result])))
    (is (= "" (get-in done [:ui :password])))
    (is (not (contains? payload :password)))
    (is (true? (get-in done [:commands command-id :password-redacted?])))))

(deftest status-refresh-preserves-create-message-test
  (let [state (-> (connected)
                  (act {:type :backup/set-field :field :password
                        :value "correct horse battery staple"})
                  (act {:type :backup/set-field :field :password-confirm
                        :value "correct horse battery staple"})
                  (act {:type :backup/create})
                  (apply-result {:status :complete
                                 :backup {:id "b-new"
                                          :sampled-at "2026-11-03T00:00:00Z"
                                          :encrypted? true}
                                 :categories {}
                                 :attachments {:count 0 :bytes 0}}))]
    (is (= :BACKUP_CREATED (get-in state [:ui :message :code])))
    (testing "the queued status refresh does not replace the success message"
      (let [status-id (-> state :outbox last :command-id)
            state (act state {:type :backup/apply-receipt
                              :receipt {:command-id status-id
                                        :ok? true
                                        :receipt (assoc (status-result)
                                                        :backups
                                                        [{:id "b-new"
                                                          :sampled-at "2026-11-03T00:00:00Z"
                                                          :created-at "2026-11-03T00:00:01Z"
                                                          :device "laptop"
                                                          :encrypted? true}])}})]
        (is (= :BACKUP_CREATED (get-in state [:ui :message :code])))))))

(deftest plaintext-secrets-confirmation-test
  (let [state (-> (connected)
                  (act {:type :backup/toggle-field :field :encrypt?})
                  (act {:type :backup/toggle-field :field :include-secrets?})
                  (act {:type :backup/set-categories
                        :scope :create :value false})
                  (act {:type :backup/toggle-category
                        :scope :create :category :assistants}))
        refused (act state {:type :backup/create})]
    (is (empty? (:outbox refused)))
    (is (= :BACKUP_PLAINTEXT_SECRETS_UNCONFIRMED
           (get-in refused [:ui :message :code])))
    (let [allowed (-> refused
                      (act {:type :backup/toggle-field
                            :field :confirm-plaintext-secrets?})
                      (act {:type :backup/create}))]
      (is (= :backup/create (:kind (last-command allowed))))
      (is (true? (:confirm-plaintext-secrets? (:payload (last-command allowed))))))))

(deftest command-gate-test
  (testing "disconnected state refuses to queue"
    (let [state (act (model/initial-state) {:type :backup/refresh})]
      (is (empty? (:outbox state)))
      (is (= :backup/not-synced (get-in state [:ui :message :code])))))
  (testing "a feed epoch alone does not authorize a command"
    (let [state (-> (model/initial-state)
                    (assoc :sync {:phase :synced :epoch 7})
                    (assoc-in [:ui :transport-available?] true))
          state (act state {:type :backup/refresh})]
      (is (empty? (:outbox state)))
      (is (= :backup/not-synced (get-in state [:ui :message :code])))))
  (testing "the same command is not queued twice"
    (let [state (act (connected) {:type :backup/refresh})
          state (act state {:type :backup/refresh})]
      (is (= 1 (count (:outbox state))))
      (is (= :warning (get-in state [:ui :message :kind])))))
  (testing "a transport failure becomes outcome-unknown, not success"
    (let [queued (act (connected) {:type :backup/refresh})
          state (act queued {:type :backup/mark-outcome-unknown
                             :command-id (-> queued :outbox first :command-id)
                             :error {:message "socket closed"}})
          command (first (vals (:commands state)))]
      (is (= :outcome-unknown (:status command)))
      (is (= :backup/outcome-unknown (get-in state [:ui :message :code]))))))

(deftest status-targets-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result)))
        target (model/target-view state)]
    (is (= :local (:selected-id target)))
    (is (= 3 (count (:targets target))))
    (is (false? (:online-restore? target)))
    (is (= "/data/backups" (:local-root target)))
    (is (= "b1" (-> state model/backup-rows first :id)))
    (testing "choosing a target clears target-specific results and queues status"
      (let [state (-> state
                      (act {:type :backup/select-backup :backup-id "b1"})
                      (act {:type :backup/select-target :target-id :dav}))
            command (last-command state)]
        (is (= :dav (get-in state [:ui :target-id])))
        (is (nil? (get-in state [:ui :selected-backup-id])))
        (is (empty? (get-in state [:server :backups])))
        (is (= :backup/status (:kind command)))
        (is (= :dav (:target-id (:payload command))))
        (is (some #(= :BACKUP_TARGET_UNAVAILABLE (:code %))
                  (model/create-validation state)))))))

(deftest status-section-merges-target-and-storage-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh :origin :manual})
                  (apply-result (assoc (status-result)
                                       :storage {:path "/data"
                                                 :used-bytes 2048
                                                 :free-bytes 4096}))
                  (assoc-in [:ui :section] :status))
        text (pr-str (view/backup-tree state))]
    (testing "one heading owns the target facts, target list, and storage"
      (is (re-find #"当前目标与状态" text))
      (is (not (re-find #"已配置目标" text)))
      (is (not (re-find #"存储状态" text)))
      (is (re-find #"目标列表" text))
      (is (re-find #"2 KiB" text))
      (is (re-find #"4 KiB" text))
      (is (re-find #"/data" text)))))

(deftest status-listing-errors-are-not-empty-lists-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result {:data-dir "/data"
                                 :targets [{:id :local :kind :file
                                            :configured? true :available? true}]
                                 :default-target-id :local
                                 :target {:id :local :kind :file
                                          :configured? true :available? true}
                                 :backups []
                                 :recovery-points []
                                 :errors [{:target "permission denied"}]}))]
    (is (= "permission denied" (get-in state [:server :listing-error])))
    (is (= :warning (get-in state [:ui :message :kind])))
    (is (re-find #"部分读取失败" (get-in state [:ui :message :text])))))

(deftest status-loads-maintenance-failure-attention-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result
                   (assoc (status-result)
                          :maintenance
                          {:available? true
                           :mode :supervised
                           :last {:maintenance-id "m-1"
                                  :status :failed-needs-recovery
                                  :needs-recovery? true}})))]
    (is (= :BACKUP_MAINTENANCE_ATTENTION
           (get-in state [:ui :message :code])))
    (is (= :warning (get-in state [:ui :message :kind])))
    (is (re-find #"恢复与预览" (get-in state [:ui :message :text])))))

(deftest preview-and-conflict-test
  (let [preview {:mode :merge
                 :summary {:local 10 :imported 4 :added 2 :replaced 0
                           :removed 0 :skipped 1 :conflicts 1
                           :expired-skipped 0 :memory-pending 0}
                 :conflicts [{:category :assistants
                              :id "a1" :imported-id "a1"
                              :name "Reviewer"
                              :reason :same-name-different-content
                              :resolution :keep-both
                              :choices [:keep-local :use-backup :keep-both]
                              :kept-id "a1@backup-123"}]
                 :placeholders [{:id "missing-assistant"
                                 :name "待配置的导入助手"}]
                 :missing-relations ["connection x"]
                 :relink-required [{:id "ws-1" :label "旧工作区"}]
                 :needs-review [{:id "tool-1" :label "工具"}]
                 :incomplete-attachments [{:id "att-1"}]
                 :memory-reactivation []
                 :expired-skipped []
                 :permissions {}
                 :recovery-point {:required? true :status :will-create}
                 :no-changes? false
                 :already-imported? false
                 :replace-all? false
                 :memory-reactivation-required? false
                 :warnings []}
        state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/select-backup :backup-id "b1"})
                  (act {:type :backup/set-field :field :password
                        :value "correct horse battery staple"})
                  (act {:type :backup/preview})
                  (apply-result preview))
        shown (model/preview-view state)]
    (is (= "合并选中类别" (:mode-label shown)))
    (is (= 1 (:conflict-count shown)))
    (is (= "同名、内容不同" (-> shown :conflicts first :reason-label)))
    (is (= :keep-both (-> shown :conflicts first :resolution)))
    (is (= 1 (count (:placeholders shown))))
    (testing "changing a resolution marks the preview stale and blocks restore"
      (let [state (act state {:type :backup/resolve-conflict
                              :category :assistants
                              :id "a1"
                              :resolution :keep-local})
            shown (model/preview-view state)
            errors (model/restore-validation state)]
        (is (true? (:stale? shown)))
        (is (= :keep-local (-> shown :conflicts first :resolution)))
        (is (some #(= :BACKUP_PREVIEW_STALE (:code %)) errors))))))

(deftest preview-no-change-and-idempotence-test
  (doseq [[result expected]
          [[{:no-changes? true :already-imported? false
             :summary {} :conflicts []}
            :BACKUP_NO_CHANGES]
           [{:no-changes? false :already-imported? true
             :summary {} :conflicts []}
            :BACKUP_ALREADY_IMPORTED]]]
    (let [state (-> (connected)
                    (act {:type :backup/refresh})
                    (apply-result (status-result))
                    (act {:type :backup/select-backup :backup-id "b1"})
                    (act {:type :backup/set-field :field :password
                          :value "correct horse battery staple"})
                    (act {:type :backup/preview})
                    (apply-result result))]
      (is (= expected (get-in state [:ui :message :code])))
      (is (some #(= expected (:code %))
                (model/restore-validation state))))))

(deftest category-missing-receipt-selects-available-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/select-backup :backup-id "b1"})
                  (act {:type :backup/set-field :field :password
                        :value "correct horse battery staple"})
                  (act {:type :backup/preview})
                  (apply-error {:code :BACKUP_CATEGORY_MISSING
                                :message "backup does not contain every selected category"
                                :data {:available [:assistants :worldbooks]}}))]
    (is (= [:assistants :worldbooks]
           (model/selected-categories (get-in state [:ui :restore-categories]))))
    (is (true? (get-in state [:ui :preview-stale?])))
    (is (some #(= :BACKUP_PREVIEW_STALE (:code %))
              (model/restore-validation state)))
    (is (= :warning (get-in state [:ui :message :kind])))
    (is (re-find #"只包含以下类别" (get-in state [:ui :message :text])))
    (is (empty? (model/preview-validation state)))))

(deftest restore-category-change-marks-preview-stale-test
  (let [preview-categories
        (model/selected-categories
         (get-in (connected) [:ui :restore-categories]))
        preview {:mode :merge
                 :summary {}
                 :conflicts []
                 :categories preview-categories
                 :no-changes? false
                 :already-imported? false
                 :replace-all? false
                 :memory-reactivation-required? false}
        base (-> (connected)
                 (act {:type :backup/refresh})
                 (apply-result (status-result))
                 (act {:type :backup/select-backup :backup-id "b1"})
                 (act {:type :backup/set-field :field :password
                       :value "correct horse battery staple"})
                 (act {:type :backup/preview})
                 (apply-result preview))]
    (testing "toggling one restore category marks the preview stale"
      (let [state (act base {:type :backup/toggle-category
                             :scope :restore
                             :category :worldbooks})]
        (is (true? (get-in state [:ui :preview-stale?])))
        (is (some #(= :BACKUP_PREVIEW_STALE (:code %))
                  (model/restore-validation state)))))
    (testing "select-all in the restore scope marks the preview stale"
      (let [state (act base {:type :backup/set-categories
                             :scope :restore :value false})]
        (is (true? (get-in state [:ui :preview-stale?])))
        (is (some #(= :BACKUP_PREVIEW_STALE (:code %))
                  (model/restore-validation state)))))
    (testing "create-scope category changes leave the restore preview alone"
      (let [state (act base {:type :backup/toggle-category
                             :scope :create
                             :category :worldbooks})]
        (is (false? (get-in state [:ui :preview-stale?])))))
    (testing "preview categories that differ from the selection are stale"
      (let [state (-> base
                      (assoc-in [:ui :preview-stale?] false)
                      (assoc-in [:ui :preview :categories] [:assistants]))]
        (is (some #(= :BACKUP_PREVIEW_STALE (:code %))
                  (model/restore-validation state)))))
    (testing "the server's category-summary map is compared by its keys"
      (let [state (-> base
                      (assoc-in [:ui :preview-stale?] false)
                      (assoc-in [:ui :preview :categories]
                                (zipmap preview-categories
                                        (repeat {:count 0}))))]
        (is (not (some #(= :BACKUP_PREVIEW_STALE (:code %))
                       (model/restore-validation state))))))))

(deftest offline-restore-is-honest-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/select-backup :backup-id "b1"})
                  (act {:type :backup/set-field :field :password
                        :value "correct horse battery staple"})
                  (act {:type :backup/preview})
                  (apply-result {:mode :merge :summary {} :conflicts []
                                 :recovery-point {:required? true :status :will-create}
                                 :no-changes? false :already-imported? false
                                 :replace-all? false
                                 :memory-reactivation-required? false})
                  (act {:type :backup/toggle-restore-confirm})
                  (act {:type :backup/request-restore}))
        errors (model/restore-validation state)]
    (testing "the preview is still available but no restore command is sent"
      (is (some? (get-in state [:ui :preview])))
      (is (empty? (:outbox state)))
      (is (some #(= :BACKUP_RESTORE_REQUIRES_QUIESCENCE (:code %)) errors))
      (is (= "需要离线维护窗口"
             (get-in state [:ui :maintenance :title]))))
    (testing "no UI path sets quiescent from the browser"
      (is (not (contains? (:payload (or (last-command state) {}))
                          :quiescent?))))))

(deftest recovery-point-restore-is-offline-only-test
  (let [state (-> (connected)
                  (act {:type :backup/request-recovery-restore
                        :recovery-point-id "rp-1"})
                  (act {:type :backup/confirm-recovery-restore}))]
    (is (empty? (:outbox state)))
    (is (= :BACKUP_RESTORE_REQUIRES_QUIESCENCE
           (get-in state [:ui :message :code])))
    (is (= "需要离线维护窗口"
           (get-in state [:ui :maintenance :title])))
    (is (nil? (get-in state [:ui :recovery-confirm-id])))))

(deftest restore-receipt-test
  (let [preview {:mode :replace :summary {:local 5 :imported 5 :added 1
                                          :replaced 2 :removed 2 :skipped 0
                                          :conflicts 0 :expired-skipped 0
                                          :memory-pending 0}
                 :conflicts [] :no-changes? false :already-imported? false
                 :replace-all? true :memory-reactivation-required? false
                 :recovery-point {:required? true :status :will-create}}
        base (-> (connected)
                 (assoc-in [:server :status] (assoc (status-result)
                                                    :restore {:online? true}))
                 (assoc-in [:server :backups]
                           (:backups (status-result)))
                 (assoc-in [:ui :selected-backup-id] "b1")
                 (assoc-in [:ui :preview] preview)
                 (assoc-in [:ui :preview-stale?] false)
                 (assoc-in [:ui :restore-confirm?] true)
                 (assoc-in [:ui :password] "correct horse battery staple"))
        state (act base {:type :backup/request-restore})]
    (testing "a server snapshot that claims online restore never queues one"
      (is (empty? (:outbox state)))
      (is (false? (model/online-restore? state)))
      (is (some #(= :BACKUP_RESTORE_REQUIRES_QUIESCENCE (:code %))
                (model/restore-validation state)))
      (is (= "需要离线维护窗口"
             (get-in state [:ui :maintenance :title]))))
    (testing "a completed maintenance restore records the result and clears the password"
      (let [done (act base
                      {:type :backup/apply-maintenance-event
                       :event {:maintenance-id "m-restore"
                               :status :complete
                               :result {:status :complete
                                        :mode :replace
                                        :summary (:summary preview)
                                        :recovery-point {:id "rp-2"}
                                        :needs-reconfiguration [{:id "conn-1"}]}}})]
        (is (= :success (get-in done [:ui :message :kind])))
        (is (= "" (get-in done [:ui :password])))
        (is (false? (get-in done [:ui :restore-confirm?])))
        (is (some? (get-in done [:ui :last-restore])))))
    (testing "a failed restore keeps the failure facts"
      (let [manual (with-command base :backup/restore {})
            failed (apply-manual-result manual "manual-command"
                                        {:status :failed
                                         :rolled-back? :unknown
                                         :needs-recovery? true
                                         :summary (:summary preview)})]
        (is (= :error (get-in failed [:ui :message :kind])))
        (is (= :unknown (get-in failed [:ui :last-restore :rolled-back?])))
        (is (true? (get-in failed [:ui :last-restore :needs-recovery?])))))))

(deftest restore-result-evidence-label-test
  (doseq [[result expected]
          [[{:status :failed :needs-recovery? true}
            "恢复失败，存储可能已提交，需要核对"]
           [{:status :failed :commit-unproven? true}
            "恢复失败，提交结果未知"]
           [{:status :failed :store-unchanged? true}
            "恢复失败，已确认未提交"]
           [{:status :failed}
            "恢复未完成"]]]
    (let [state (assoc-in (connected) [:ui :last-restore] result)]
      (is (= expected (:status-label (model/restore-result-view state)))))))

(deftest error-contract-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/select-backup :backup-id "b1"})
                  (act {:type :backup/set-field :field :password
                        :value "wrong password"})
                  (act {:type :backup/preview})
                  (apply-error {:code :BACKUP_WRONG_PASSWORD
                                :message "key check failed"}))]
    (is (= :error (get-in state [:ui :message :kind])))
    (is (re-find #"无法解锁" (get-in state [:ui :message :text])))
    (is (nil? (get-in state [:ui :preview])))
    (is (true? (get-in state [:ui :preview-stale?]))))
  (testing "format, version, integrity and quiescence have distinct text"
    (doseq [[code needle]
            [[:BACKUP_FORMAT_INVALID "无法识别"]
             [:BACKUP_VERSION_UNSUPPORTED "较新的格式"]
             [:BACKUP_INTEGRITY "完整性"]
             [:BACKUP_RESTORE_REQUIRES_QUIESCENCE "离线维护窗口"]
             [:BACKUP_STORAGE_FULL "空间不足"]]]
      (let [view (model/error-view {:code code :message "server detail"})]
        (is (= code (:code view)))
        (is (re-find (re-pattern needle) (:text view))))))
  (testing "an unknown code keeps the server message and does not claim success"
    (let [view (model/error-view {:code :SOMETHING_NEW :message "unexpected"})]
      (is (= :SOMETHING_NEW (:code view)))
      (is (re-find #"unexpected" (:text view)))
      (is (re-find #"不要自动重试" (:text view))))))

(deftest preferences-never-include-password-test
  (let [state (-> (connected)
                  (act {:type :backup/restore-prefs
                        :prefs {:encrypt? false
                                :include-secrets? true
                                :categories {:assistants true :worldbooks true}
                                :password "must not survive"
                                :password-confirm "must not survive"
                                :confirm-plaintext-secrets? true
                                :section :restore}}))]
    (is (false? (get-in state [:ui :encrypt?])))
    (is (true? (get-in state [:ui :include-secrets?])))
    (is (= [:assistants :worldbooks]
           (model/selected-categories (get-in state [:ui :categories]))))
    (is (= "" (get-in state [:ui :password])))
    (is (= "" (get-in state [:ui :password-confirm])))
    (is (false? (get-in state [:ui :confirm-plaintext-secrets?])))
    (is (= :restore (get-in state [:ui :section])))))

(deftest maintenance-unavailable-and-failure-test
  (let [state (-> (connected)
                  (assoc-in [:server :maintenance]
                            {:available? false
                             :mode :offline-only
                             :reason :NO_IN_PROCESS_QUIESCE
                             :command "pixi run backup"}))
        maintenance (model/maintenance-view state)
        errors (model/maintenance-validation state)]
    (is (false? (:available? maintenance)))
    (is (= :offline-only (:mode maintenance)))
    (is (some #(= :BACKUP_MAINTENANCE_UNAVAILABLE (:code %)) errors))
    (is (re-find #"工作台内恢复不可用"
                 (pr-str (view/backup-tree
                          (-> state
                              (assoc-in [:ui :section] :restore)
                              (assoc-in [:ui :preview]
                                        {:summary {} :conflicts []})))))))
  (let [state (act (connected)
                   {:type :backup/apply-maintenance-event
                    :event {:status :complete :no-changes? true}})]
    (is (= :info (get-in state [:ui :message :kind])))
    (is (= :BACKUP_NO_CHANGES (get-in state [:ui :message :code])))
    (is (not= :success (get-in state [:ui :message :kind]))))
  (let [state (act (connected)
                   {:type :backup/apply-maintenance-event
                    :event {:status :restart-failed
                            :needs-attention? true
                            :message "restart failed"}})]
    (is (= :restart-failed (get-in state [:ui :maintenance-request :status])))
    (is (true? (get-in state [:ui :maintenance-request :needs-attention?])))
    (is (= :error (get-in state [:ui :message :kind])))
    (is (not= :success (get-in state [:ui :message :kind])))))

(deftest maintenance-failure-evidence-test
  (testing "each new terminal failure names exactly what is known"
    (doseq [[status event expected-evidence expected-ack? expected-code]
            [[:failed-needs-recovery
              {:needs-recovery? true}
              :committed true "inspect-and-accept-partial"]
             [:failed-commit-unproven
              {:commit-unproven? true}
              :unknown true "inspect-accept-unknown-commit"]
             [:failed-before-commit
              {:store-unchanged? true}
              :not-committed false nil]
             [:failed-needs-attention
              {}
              :unknown true "inspect-accept-unknown-commit"]
             [:failed
              {:restore-attempted? true}
              :no-evidence true "inspect-accept-unknown-commit"]]]
      (let [state (act (connected)
                       {:type :backup/apply-maintenance-event
                        :event (merge {:maintenance-id "m-1"
                                       :status status
                                       :needs-attention? true}
                                      event)})
            maintenance (model/maintenance-view state)]
        (is (= status (:status maintenance)))
        (is (true? (:terminal? maintenance)))
        (is (false? (:reconnect? maintenance)))
        (is (= expected-evidence (:evidence maintenance)))
        (is (= expected-ack? (:acknowledge-required? maintenance)))
        (is (= expected-code (:acknowledge-code maintenance)))
        (is (not= :success (get-in state [:ui :message :kind])))))))

(deftest maintenance-acknowledgement-is-not-success-test
  (testing "a proven partial restore blocks until an operator acknowledges it"
    (let [state (act (connected)
                     {:type :backup/apply-maintenance-event
                      :event {:maintenance-id "m-1"
                              :status :failed-needs-recovery
                              :needs-recovery? true
                              :needs-attention? true
                              :restore-attempted? true}})
          maintenance (model/maintenance-view state)]
      (is (true? (:blocking? maintenance)))
      (is (re-find #"保持阻塞" (:start-gate-label maintenance)))
      (is (some #(= :BACKUP_MAINTENANCE_ACK_REQUIRED (:code %))
                (model/maintenance-validation
                 (assoc-in state [:server :maintenance]
                           {:available? true :mode :supervised}))))
      (is (some #(= :BACKUP_MAINTENANCE_ACK_REQUIRED (:code %))
                (model/maintenance-recovery-point-validation state "rp-1")))))
  (testing "an acknowledged failure keeps its status and stops blocking"
    (let [state (act (connected)
                     {:type :backup/apply-maintenance-event
                      :event {:maintenance-id "m-1"
                              :status :failed-needs-recovery
                              :needs-recovery? true
                              :needs-attention? true
                              :acknowledged? true}})
          maintenance (model/maintenance-view state)]
      (is (= :failed-needs-recovery (:status maintenance)))
      (is (true? (:acknowledged? maintenance)))
      (is (false? (:blocking? maintenance)))
      (is (false? (:acknowledge-required? maintenance)))
      (is (= :warning (get-in state [:ui :message :kind])))
      (is (re-find #"不表示恢复成功"
                   (get-in state [:ui :message :text])))))
  (testing "a pre-restore :failed record is history, not a blocking commit"
    (let [state (act (connected)
                     {:type :backup/apply-maintenance-event
                      :event {:maintenance-id "m-2"
                              :status :failed
                              :restore-attempted? false
                              :needs-attention? false}})
          maintenance (model/maintenance-view state)]
      (is (false? (:blocking? maintenance)))
      (is (false? (:acknowledge-required? maintenance)))
      (is (re-find #"不阻塞" (:start-gate-label maintenance)))
      (is (= :warning (get-in state [:ui :message :kind]))))))

(deftest maintenance-active-record-does-not-inherit-acknowledgement-test
  (let [state (-> (connected)
                  (assoc-in [:server :maintenance]
                            {:available? true
                             :mode :supervised
                             :last {:maintenance-id "old"
                                    :status :failed-needs-recovery
                                    :needs-recovery? true
                                    :acknowledged? true}
                             :active {:maintenance-id "new"
                                      :status :accepted
                                      :phase :validating}})
                  (assoc-in [:ui :maintenance-request]
                            {:id "new" :maintenance-id "new"
                             :status :accepted :phase :validating}))
        maintenance (model/maintenance-view state)]
    (is (= :accepted (:status maintenance)))
    (is (false? (:acknowledged? maintenance)))
    (is (false? (:acknowledge-required? maintenance)))
    (is (= "维护进行中" (:start-gate-label maintenance)))))

(deftest maintenance-status-refresh-uses-last-record-test
  (let [state (-> (connected)
                  (assoc-in [:server :maintenance]
                            {:available? true
                             :mode :supervised
                             :last {:maintenance-id "m-1"
                                    :status :failed-needs-recovery
                                    :needs-recovery? true}})
                  (act {:type :backup/refresh-maintenance}))
        payload (:payload (last-command state))]
    (is (= :backup/maintenance-status (:kind payload)))
    (is (= "m-1" (:maintenance-id payload)))))

(deftest maintenance-receipt-error-preserves-evidence-test
  (let [queued (act (connected) {:type :backup/refresh})
        id (-> queued :outbox last :command-id)
        state (act queued
                   {:type :backup/apply-receipt
                    :receipt
                    {:command-id id
                     :ok? false
                     :error
                     {:code :BACKUP_MAINTENANCE_RESTORE_FAILED
                      :message "the previous restore failed"
                      :details {:maintenance-id "m-1"
                                :status :failed-commit-unproven
                                :commit-unproven? true
                                :needs-recovery? false}}}})
        maintenance (model/maintenance-view state)]
    (is (= :failed-commit-unproven (:status maintenance)))
    (is (true? (:commit-unproven? maintenance)))
    (is (true? (:acknowledge-required? maintenance)))
    (is (= "inspect-accept-unknown-commit" (:acknowledge-code maintenance)))
    (is (= :error (get-in state [:ui :message :kind])))))

(deftest maintenance-acknowledge-guidance-test
  (let [state (-> (connected)
                  (assoc-in [:server :maintenance]
                            {:available? true :mode :supervised})
                  (act {:type :backup/apply-maintenance-event
                        :event {:maintenance-id "m-1"
                                :status :failed-needs-recovery
                                :needs-recovery? true
                                :needs-attention? true
                                :restore-attempted? true}})
                  (act {:type :backup/select-section :section :restore}))
        text (pr-str (view/backup-tree state))]
    (is (re-find #"已提交，需核对" text))
    (is (re-find #"inspect-and-accept-partial" text))
    (is (re-find #"hc\.server\.backup\.cli acknowledge" text))
    (is (re-find #"state-digest" text))
    (is (re-find #"--store-changed true" text))
    (is (re-find #"保持阻塞" text))
    (is (re-find #"不会把失败改成成功" text)))
  (let [state (-> (connected)
                  (act {:type :backup/apply-maintenance-event
                        :event {:maintenance-id "m-2"
                                :status :failed-before-commit
                                :store-unchanged? true
                                :needs-attention? true}})
                  (act {:type :backup/select-section :section :restore}))
        text (pr-str (view/backup-tree state))]
    (is (re-find #"已确认未提交" text))
    (is (re-find #"不需要操作员确认" text))
    (is (not (re-find #"hc\.server\.backup\.cli acknowledge" text)))))

(deftest maintenance-payload-includes-preview-fingerprint-test
  (let [state (-> (connected)
                  (assoc-in [:server :maintenance]
                            {:available? true :mode :supervised})
                  (assoc-in [:server :status]
                            (assoc (status-result)
                                   :maintenance {:available? true
                                                 :mode :supervised}))
                  (assoc-in [:server :backups] (:backups (status-result)))
                  (assoc-in [:ui :selected-backup-id] "b1")
                  (assoc-in [:ui :password] "correct horse battery staple")
                  (assoc-in [:ui :preview]
                            {:fingerprint "preview-fingerprint-1"
                             :summary {}
                             :conflicts []
                             :no-changes? false
                             :already-imported? false
                             :memory-reactivation-required? false})
                  (assoc-in [:ui :restore-confirm?] true))
        queued (act state {:type :backup/request-maintenance-restore})
        payload (:payload (last-command queued))]
    (is (= :backup/maintenance-restore (:kind payload)))
    (is (= "preview-fingerprint-1" (:preview-fingerprint payload)))
    (is (true? (:confirm? payload)))
    (is (not (contains? payload :quiescent?)))))

(deftest maintenance-recovery-point-payload-test
  (let [state (-> (connected)
                  (assoc-in [:server :maintenance]
                            {:available? true :mode :supervised})
                  (act {:type :backup/request-maintenance-restore-point
                        :recovery-point-id "rp-1"}))
        payload (:payload (last-command state))]
    (is (= :backup/maintenance-restore-recovery-point (:kind payload)))
    (is (= "rp-1" (:recovery-point-id payload)))
    (is (true? (:confirm? payload)))
    (is (not (contains? payload :quiescent?)))
    (is (= :accepted (get-in state [:ui :maintenance-request :status])))))

(deftest delete-confirmation-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/request-delete :backup-id "b1"}))]
    (is (= "b1" (get-in state [:ui :delete-confirm-id])))
    (is (empty? (:outbox state)))
    (let [queued (act state {:type :backup/confirm-delete :backup-id "b1"})
          payload (:payload (last-command queued))]
      (is (true? (:confirm? payload)))
      (is (= :local (:target-id payload))))
    (let [queued (act state {:type :backup/confirm-delete :backup-id "b1"})
          state (apply-result queued {:backup-id "b1"})]
      (is (nil? (get-in state [:ui :delete-confirm-id])))
      (is (empty? (get-in state [:server :backups]))))))

(deftest tree-renders-safe-labels-test
  (let [state (-> (connected)
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
                                           :memory-pending 0}
                                 :conflicts [{:category :assistants
                                              :id "a1" :imported-id "a1"
                                              :name "Reviewer"
                                              :reason :same-name-different-content
                                              :resolution :keep-both
                                              :choices [:keep-local :use-backup :keep-both]}]
                                 :memory-reactivation [{:id "m1" :name "旧记忆"}]
                                 :memory-reactivation-required? true
                                 :replace-all? true
                                 :no-changes? false :already-imported? false
                                 :recovery-point {:required? true :status :will-create}
                                 :placeholders [] :missing-relations []
                                 :relink-required [] :needs-review []
                                 :incomplete-attachments [] :warnings []})
                  (act {:type :backup/select-section :section :restore}))
        tree (view/backup-tree state)
        text (pr-str tree)]
    (is (re-find #"加密备份与恢复" text))
    (is (re-find #"替换选中类别" text))
    (is (re-find #"同名不同内容的助手与世界书默认保留两份" text))
    (is (re-find #"工作台内恢复不可用" text))
    (is (re-find #"WebDAV" text))
    (is (re-find #"aria-label \"加密备份与恢复\"" text))))

(deftest maintenance-no-write-test
  (doseq [event [{:status :complete :no-changes? true}
                 {:status :complete :already-imported? true}
                 {:status :complete :no-changes? true
                  :result {:status :complete :no-changes? true}}
                 {:status :complete :result {:no-changes? true}}]]
    (let [state (-> (connected)
                    (act {:type :backup/apply-maintenance-event
                          :event (merge {:maintenance-id "m-1"} event)})
                    (act {:type :backup/select-section :section :restore}))
          maintenance (model/maintenance-view state)
          text (pr-str (view/backup-tree state))]
      (is (true? (:no-write? maintenance)))
      (is (re-find #"没有写入" text))
      (is (not (re-find #"恢复完成" text))))))

(deftest maintenance-requires-supervised-mode-test
  (let [state (-> (connected)
                  (assoc-in [:server :maintenance]
                            {:available? true :mode :offline-only}))
        maintenance (model/maintenance-view state)]
    (is (false? (:available? maintenance)))
    (is (false? (model/maintenance-available? state)))
    (is (some #(= :BACKUP_MAINTENANCE_UNAVAILABLE (:code %))
              (model/maintenance-validation state))))
  (let [state (assoc-in (connected) [:server :maintenance]
                        {:available? true :mode :supervised})]
    (is (true? (model/maintenance-available? state)))))

(deftest selection-clears-verify-result-test
  (let [verified (assoc-in (connected) [:ui :verify-result]
                           {:backup {:id "b1"}})]
    (is (nil? (get-in (act verified {:type :backup/select-backup
                                     :backup-id "b1"})
                      [:ui :verify-result])))
    (is (nil? (get-in (act verified {:type :backup/select-target
                                     :target-id :local})
                      [:ui :verify-result])))))

(deftest create-button-label-test
  (let [encrypted (view/backup-tree (assoc-in (connected)
                                              [:ui :section] :backup))
        plain (view/backup-tree (-> (connected)
                                    (assoc-in [:ui :section] :backup)
                                    (assoc-in [:ui :encrypt?] false)))]
    (is (some? (button-by-text encrypted "生成加密备份")))
    (is (some? (button-by-text plain "生成未加密备份")))
    (is (nil? (button-by-text plain "生成加密备份")))))

(deftest recovery-point-size-and-prune-gate-test
  (let [base (-> (connected)
                 (act {:type :backup/refresh})
                 (apply-result (status-result)))
        row (first (model/recovery-point-rows base))
        idle-tree (view/backup-tree (assoc-in base [:ui :section] :recovery))
        idle (button-by-text idle-tree "按 7+4+3 清理")]
    (is (= 2048 (:bytes row)))
    (is (re-find #"2 KiB" (pr-str idle-tree)))
    (is (some? idle))
    (is (not (true? (get-in idle [:attrs :disabled]))))
    (let [active (-> base
                     (assoc-in [:server :maintenance]
                               {:available? true :mode :supervised
                                :active {:maintenance-id "m-1"
                                         :status :restoring}})
                     (assoc-in [:ui :maintenance-request]
                               {:id "m-1" :maintenance-id "m-1"
                                :status :restoring :phase :restoring}))
          button (button-by-text
                  (view/backup-tree (assoc-in active [:ui :section] :recovery))
                  "按 7+4+3 清理")]
      (is (true? (model/restore-in-progress? active)))
      (is (true? (get-in button [:attrs :disabled]))))))

(deftest preview-projection-and-restore-result-lists-test
  (let [preview {:mode :merge
                 :summary {}
                 :conflicts []
                 :categories (model/selected-categories
                              (get-in (connected) [:ui :restore-categories]))
                 :permissions {:schedules :paused
                               :tools :disabled-needs-review
                               :source {:x 1}}
                 :manifest {:format-version 1 :device "laptop"}
                 :needs-reconfiguration [{:id "ws-1" :label "旧工作区"}]
                 :warnings [{:reason :restore-failed}]
                 :no-changes? false
                 :already-imported? false
                 :replace-all? false
                 :memory-reactivation-required? false}
        state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/select-backup :backup-id "b1"})
                  (act {:type :backup/set-field :field :password
                        :value "correct horse battery staple"})
                  (act {:type :backup/preview})
                  (apply-result preview)
                  (act {:type :backup/select-section :section :restore}))
        shown (model/preview-view state)
        text (pr-str (view/backup-tree state))]
    (is (= (:permissions preview) (:permissions shown)))
    (is (= (:manifest preview) (:manifest shown)))
    (is (= ["旧工作区"] (:needs-reconfiguration shown)))
    (is (re-find #"恢复后的权限默认值" text))
    (is (re-find #"计划任务" text))
    (is (re-find #"暂停" text))
    (is (re-find #"需要重新配置的项" text))
    (is (re-find #"旧工作区" text)))
  (let [state (-> (connected)
                  (assoc-in [:ui :last-restore]
                            {:status :failed
                             :warnings [{:reason :restore-failed}]
                             :permissions {:schedules :paused
                                           :tools :disabled-needs-review}})
                  (assoc-in [:ui :section] :restore))
        text (pr-str (view/backup-tree state))]
    (is (re-find #"恢复警告" text))
    (is (re-find #"restore-failed" text))
    (is (re-find #"恢复后的权限状态" text))
    (is (re-find #"已停用，待审核" text))))

(deftest create-result-shows-location-size-and-verification-error-test
  (let [state (-> (connected)
                  (assoc-in [:ui :last-create]
                            {:status :needs-verification
                             :backup {:id "b1"
                                      :path "/data/backups/b1.hcb"
                                      :size 2048
                                      :created-at "2026-11-03T00:00:01Z"
                                      :sampled-at "2026-11-03T00:00:00Z"
                                      :encrypted? true}
                             :verification {:error {:message "checksum mismatch"}}
                             :categories {}
                             :attachments {:count 0 :bytes 0}})
                  (assoc-in [:ui :section] :backup))
        shown (model/create-result-view state)
        text (pr-str (view/backup-tree state))]
    (is (= "/data/backups/b1.hcb" (:path shown)))
    (is (= 2048 (:size shown)))
    (is (= "2026-11-03T00:00:01Z" (:created-at shown)))
    (is (re-find #"2 KiB" text))
    (is (re-find #"/data/backups/b1\.hcb" text))
    (is (re-find #"备份校验错误" text))
    (is (re-find #"checksum mismatch" text))))

(deftest command-key-includes-target-test
  (let [state (connected)
        first-target (commands/queue state :backup/status {:target-id :a})
        second-target (commands/queue (:state first-target)
                                      :backup/status {:target-id :b})
        duplicate (commands/queue (:state second-target)
                                  :backup/status {:target-id :a})]
    (is (:ok? first-target))
    (is (:ok? second-target))
    (is (false? (:ok? duplicate)))
    (is (= 2 (count (:outbox (:state second-target)))))
    (is (= [:backup/status :a :global]
           (commands/command-key :backup/status {:target-id :a})))))

(deftest copy-password-action-test
  (let [state (assoc-in (connected) [:ui :password]
                        "correct horse battery staple")
        copied (model/reduce-action state {:type :backup/copy-password})]
    (is (= [{:type :backup/copy-text
             :text "correct horse battery staple"}]
           (:effects copied)))
    (is (= :copying
           (get-in (:state copied) [:ui :copy-feedback :status])))
    (is (= :BACKUP_PASSWORD_COPYING
           (get-in (:state copied) [:ui :copy-feedback :code])))
    (is (re-find #"正在复制密码"
                 (get-in (:state copied) [:ui :copy-feedback :message])))
    (is (nil? (get-in (:state copied) [:ui :message])))
    (is (= "correct horse battery staple"
           (get-in (:state copied) [:ui :password])))
    (let [done (:state (model/reduce-action (:state copied)
                                            {:type :backup/copy-result
                                             :ok? true}))]
      (is (= :copied (get-in done [:ui :copy-feedback :status])))
      (is (= :BACKUP_PASSWORD_COPIED
             (get-in done [:ui :copy-feedback :code])))
      (is (re-find #"已复制" (get-in done [:ui :copy-feedback :message])))
      (is (not (re-find #"correct horse"
                        (pr-str (get-in done [:ui :copy-feedback])))))
      (is (nil? (get-in done [:ui :message]))))
    (let [failed (:state (model/reduce-action state
                                              {:type :backup/copy-result
                                               :ok? false}))]
      (is (= :failed (get-in failed [:ui :copy-feedback :status])))
      (is (= :BACKUP_PASSWORD_COPY_FAILED
             (get-in failed [:ui :copy-feedback :code])))
      (is (re-find #"剪贴板"
                   (get-in failed [:ui :copy-feedback :message])))
      (is (nil? (get-in failed [:ui :message])))))
  (let [result (model/reduce-action (connected) {:type :backup/copy-password})]
    (is (empty? (:effects result)))
    (is (= :failed (get-in (:state result) [:ui :copy-feedback :status])))
    (is (= :BACKUP_PASSWORD_EMPTY
           (get-in (:state result) [:ui :copy-feedback :code])))
    (is (re-find #"没有可复制的密码"
                 (get-in (:state result) [:ui :copy-feedback :message])))
    (is (nil? (get-in (:state result) [:ui :message])))))

(deftest automatic-status-refresh-is-silent-test
  (testing "mount and post-mutation refreshes leave the page banner alone"
    (let [queued (act (connected) {:type :backup/refresh})
          done (apply-result queued (status-result))]
      (is (nil? (get-in queued [:ui :message])))
      (is (nil? (:origin (:payload (last-command queued)))))
      (is (nil? (get-in done [:ui :message])))))
  (testing "an explicit operator refresh still reports completion"
    (let [queued (act (connected) {:type :backup/refresh :origin :manual})
          done (apply-result queued (status-result))]
      (is (= :manual (:origin (:payload (last-command queued)))))
      (is (= :backup/status-pending (get-in queued [:ui :message :code])))
      (is (= :backup/status-loaded (get-in done [:ui :message :code])))
      (is (= :info (get-in done [:ui :message :kind]))))))

(deftest target-editor-new-edit-and-fields-test
  (let [state (connected)]
    ;; A fresh editor starts closed; toggle opens a blank WebDAV form.
    (let [opened (act state {:type :backup/toggle-target-editor})]
      (is (true? (get-in opened [:ui :target-editor :open?])))
      (is (nil? (get-in opened [:ui :target-editor :target-id])))
      (is (= :webdav (get-in opened [:ui :target-editor :kind]))))
    ;; :backup/new-target always opens a blank form.
    (let [fresh (act state {:type :backup/new-target})]
      (is (true? (get-in fresh [:ui :target-editor :open?])))
      (is (= "" (get-in fresh [:ui :target-editor :remote-url]))))
    ;; Editing the WebDAV row backfills non-secret fields and clears secrets.
    (let [state (act (connected)
                     {:type :backup/refresh})
          state (apply-result state (status-result))
          edited (act state {:type :backup/edit-target :target-id :dav})
          editor (get-in edited [:ui :target-editor])]
      (is (true? (:open? editor)))
      (is (= :dav (:target-id editor)))
      (is (= :webdav (:kind editor)))
      (is (= "https://dav.example.com" (:remote-url editor)))
      (is (= "hc" (:directory editor)))
      (is (= "" (:password editor)))
      (is (= "" (:secret editor)))
      ;; Editing an unknown id warns and keeps the editor closed.
      (let [missing (act state {:type :backup/edit-target :target-id :nope})]
        (is (= :BACKUP_TARGET_UNKNOWN (get-in missing [:ui :message :code])))
        (is (false? (get-in missing [:ui :target-editor :open?]))))
      ;; set-default? compares against :server :default-target-id.
      (is (false? (:set-default? editor)))
      (let [local (act state {:type :backup/edit-target :target-id :local})]
        (is (true? (get-in local [:ui :target-editor :set-default?])))))))

(deftest target-set-field-test
  (let [state (act (connected) {:type :backup/new-target})
        state (act state {:type :backup/set-target-field
                          :field :kind :value "s3"})
        state (act state {:type :backup/set-target-field
                          :field :remote-url :value "https://s3.example.com"})
        state (act state {:type :backup/set-target-field
                          :field :bucket :value "hc-bucket"})
        editor (get-in state [:ui :target-editor])]
    (is (= :s3 (:kind editor)))
    (is (= "https://s3.example.com" (:remote-url editor)))
    (is (= "hc-bucket" (:bucket editor)))
    (testing "unknown fields are ignored"
      (let [state (act state {:type :backup/set-target-field
                              :field :not-a-field :value "x"})]
        (is (not (contains? (get-in state [:ui :target-editor])
                            :not-a-field)))))))

(deftest target-save-payload-and-gate-test
  (let [state (-> (connected)
                  (act {:type :backup/new-target})
                  (act {:type :backup/set-target-field
                        :field :remote-url :value "https://dav.example.com/hc"})
                  (act {:type :backup/set-target-field
                        :field :username :value "alice"})
                  (act {:type :backup/set-target-field
                        :field :password :value "s3cret"})
                  (act {:type :backup/set-target-field
                        :field :directory :value "backups"})
                  (act {:type :backup/set-target-field
                        :field :set-default? :value true}))
        view (model/target-config-view state)]
    (is (true? (:can-save? view)))
    (is (true? (:can-test? view)))
    (is (= 11 (count (:fields view))))
    (let [queued (act state {:type :backup/save-target})
          command (last-command queued)
          payload (:payload command)]
      (is (= :backup/save-target (:kind command)))
      (is (nil? (:target-id payload)))
      (is (true? (:default? payload)))
      (is (= {:kind :webdav
              :remote-url "https://dav.example.com/hc"
              :username "alice"
              :password "s3cret"
              :directory "backups"}
             (:config payload)))
      (is (= :backup/save-target-pending (get-in queued [:ui :message :code]))))
    (testing "an unsynced client refuses and keeps the form"
      (let [offline (-> (model/initial-state)
                        (act {:type :backup/new-target})
                        (act {:type :backup/set-target-field
                              :field :remote-url
                              :value "https://dav.example.com"}))
            refused (act offline {:type :backup/save-target})]
        (is (empty? (:outbox refused)))
        (is (= :backup/not-synced (get-in refused [:ui :message :code])))
        (is (= "https://dav.example.com"
               (get-in refused [:ui :target-editor :remote-url])))))
    (testing "missing required fields refuse to queue"
      (let [state (act (connected) {:type :backup/new-target})
            refused (act state {:type :backup/save-target})]
        (is (empty? (:outbox refused)))
        (is (= :BACKUP_TARGET_INVALID (get-in refused [:ui :message :code])))))
    (testing "s3 payload maps secret and path-style"
      (let [state (-> (connected)
                      (act {:type :backup/new-target})
                      (act {:type :backup/set-target-field
                            :field :kind :value :s3})
                      (act {:type :backup/set-target-field
                            :field :endpoint :value "https://s3.example.com"})
                      (act {:type :backup/set-target-field
                            :field :bucket :value "b"})
                      (act {:type :backup/set-target-field
                            :field :region :value "us-east-1"})
                      (act {:type :backup/set-target-field
                            :field :access-key :value "AK"})
                      (act {:type :backup/set-target-field
                            :field :secret :value "SK"})
                      (act {:type :backup/save-target}))
            config (:config (:payload (last-command state)))]
        (is (= :s3 (:kind config)))
        (is (= "https://s3.example.com" (:endpoint config)))
        (is (= "b" (:bucket config)))
        (is (= "us-east-1" (:region config)))
        (is (= "AK" (:access-key config)))
        (is (= "SK" (:secret config)))
        (is (true? (:path-style? config)))))))

(deftest target-save-receipt-test
  (let [state (-> (connected)
                  (act {:type :backup/new-target})
                  (act {:type :backup/set-target-field
                        :field :remote-url :value "https://dav.example.com"})
                  (act {:type :backup/set-target-field
                        :field :directory :value "hc"})
                  (act {:type :backup/save-target}))
        done (apply-result state
                           {:status :accepted :target-id :dav
                            :kind :webdav :persisted? true
                            :targets [{:id :dav :kind :webdav
                                       :configured? true :available? true
                                       :base-url "https://dav.example.com"
                                       :directory "hc"}]
                            :default-target-id :local})]
    (is (false? (get-in done [:ui :target-editor :open?])))
    (is (= :BACKUP_TARGET_SAVED (get-in done [:ui :message :code])))
    (is (some #(= :dav (:id %))
              (get-in done [:server :targets])))
    ;; A status refresh was queued after the save receipt.
    (is (some #(= :backup/status (:kind (:payload %)))
              (:outbox done)))
    (testing "a failed save keeps the editor and form"
      (let [state (-> (connected)
                      (act {:type :backup/new-target})
                      (act {:type :backup/set-target-field
                            :field :remote-url
                            :value "https://dav.example.com"})
                      (act {:type :backup/save-target}))
            failed (apply-error state
                                {:code :BACKUP_TARGET_INVALID
                                 :message "bad target"})]
        (is (true? (get-in failed [:ui :target-editor :open?])))
        (is (= "https://dav.example.com"
               (get-in failed [:ui :target-editor :remote-url])))
        (is (= :warning (get-in failed [:ui :message :kind])))))))

(deftest target-test-receipt-test
  (let [state (-> (connected)
                  (act {:type :backup/new-target})
                  (act {:type :backup/set-target-field
                        :field :remote-url :value "https://dav.example.com"})
                  (act {:type :backup/test-target}))
        command (last-command state)
        payload (:payload command)]
    (is (= :backup/test-target (:kind command)))
    (is (= {:kind :webdav
            :remote-url "https://dav.example.com"
            :username "" :password ""}
           (:config payload)))
    (is (string? (:command-key payload)))
    (testing "probe ok marks the test state and shows info"
      (let [done (apply-result state
                               {:status :complete :command-id "x"
                                :probe {:ok? true :kind :webdav
                                        :target-id nil
                                        :detail "reachable"}})]
        (is (= :ok (get-in done [:ui :target-test :status])))
        (is (= "reachable" (get-in done [:ui :target-test :detail])))
        (is (= :info (get-in done [:ui :message :kind])))))
    (testing "a failed probe is still a successful command"
      (let [done (apply-result state
                               {:status :complete
                                :probe {:ok? false :kind :webdav
                                        :target-id nil
                                        :detail "401 unauthorized"}})]
        (is (= :failed (get-in done [:ui :target-test :status])))
        (is (= "401 unauthorized"
               (get-in done [:ui :target-test :detail])))
        (is (= :warning (get-in done [:ui :message :kind])))))
    (testing "a flat ok?/detail receipt shape is also accepted"
      ;; The receipt may carry :ok?/:detail directly instead of :probe.
      (let [done (apply-result state {:ok? true :detail "fine"})]
        (is (= :ok (get-in done [:ui :target-test :status])))))
    (testing "a command-level error marks the test failed"
      (let [failed (apply-error state
                                {:code :BACKUP_TARGET_UNAVAILABLE
                                 :message "unreachable"})]
        (is (= :failed (get-in failed [:ui :target-test :status])))
        (is (string? (get-in failed [:ui :target-test :detail])))))
    (testing "different drafts do not block each other"
      (let [state (act state {:type :backup/set-target-field
                              :field :kind :value :s3})
            state (act state {:type :backup/set-target-field
                              :field :endpoint
                              :value "https://s3.example.com"})
            state (act state {:type :backup/set-target-field
                              :field :bucket :value "b"})
            state (act state {:type :backup/set-target-field
                              :field :region :value "r"})
            state (act state {:type :backup/set-target-field
                              :field :access-key :value "ak"})
            state (act state {:type :backup/set-target-field
                              :field :secret :value "sk"})
            second (act state {:type :backup/test-target})]
        ;; The first test-target is still pending (:queued), yet the second
        ;; draft queues because its :command-key differs.
        (is (= 2 (count (:outbox second))))
        (is (some #(= "test-s3-new" (:command-key (:payload %)))
                  (:outbox second)))))))

(deftest target-delete-two-step-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result)))
        ;; Step one only records the pending id.
        requested (act state {:type :backup/request-delete-target
                              :target-id :dav})
        _ (is (= :dav (get-in requested [:ui :target-delete-id])))
        _ (is (empty? (:outbox requested)))
        ;; Cancelling clears it without a command.
        cancelled (act requested {:type :backup/cancel-delete-target})
        _ (is (nil? (get-in cancelled [:ui :target-delete-id])))
        ;; Step two queues the delete and clears the confirmation.
        confirmed (act requested {:type :backup/delete-target})
        command (last-command confirmed)]
    (is (= :backup/delete-target (:kind command)))
    (is (= {:target-id :dav :kind :backup/delete-target}
           (select-keys (:payload command) [:target-id :kind])))
    (is (nil? (get-in confirmed [:ui :target-delete-id])))
    (is (= :backup/delete-target-pending
           (get-in confirmed [:ui :message :code])))
    (testing "delete without a pending id warns and queues nothing"
      (let [state (act state {:type :backup/delete-target})]
        (is (empty? (:outbox state)))
        (is (= :BACKUP_TARGET_UNKNOWN (get-in state [:ui :message :code])))))
    (testing "the success receipt clears state and refreshes"
      (let [done (apply-result confirmed
                                {:status :accepted :target-id :dav
                                 :deleted? true
                                 :targets [{:id :local :kind :file
                                            :configured? true
                                            :available? true
                                            :root "/data/backups"}]
                                 :default-target-id :local})]
        (is (nil? (get-in done [:ui :target-delete-id])))
        (is (= :BACKUP_TARGET_DELETED (get-in done [:ui :message :code])))
        (is (not (some #(= :dav (:id %))
                       (get-in done [:server :targets]))))
        (is (some #(= :backup/status (:kind (:payload %)))
                  (:outbox done))))
    (testing "a failed delete keeps the warning and the pending id"
      (let [requested (act state {:type :backup/request-delete-target
                                  :target-id :dav})
            queued (act requested {:type :backup/delete-target})
            failed (apply-error queued
                                {:code :BACKUP_TARGET_UNKNOWN
                                 :message "missing"})]
        (is (= :warning (get-in failed [:ui :message :kind]))))))))

(deftest target-config-view-complete-test
  (let [state (-> (connected)
                  (act {:type :backup/refresh})
                  (apply-result (status-result))
                  (act {:type :backup/new-target})
                  (act {:type :backup/set-target-field
                        :field :kind :value :s3})
                  (act {:type :backup/set-target-field
                        :field :endpoint :value "https://s3.example.com"})
                  (act {:type :backup/set-target-field
                        :field :bucket :value "b"})
                  (act {:type :backup/set-target-field
                        :field :region :value "us-east-1"})
                  (act {:type :backup/set-target-field
                        :field :access-key :value "ak"})
                  (act {:type :backup/set-target-field
                        :field :secret :value "sk"}))
        view (model/target-config-view state)]
    (is (= :s3 (:kind view)))
    (is (= "S3" (:kind-label view)))
    (is (= (apply dissoc model/default-target-editor
                  [:open? :kind :endpoint :bucket :region
                   :access-key :secret])
           (dissoc (:editor view) :open? :kind :endpoint :bucket :region
                   :access-key :secret)))
    (is (= [[:webdav "WebDAV"] [:s3 "S3"] [:file "本地文件"]]
           model/target-kind-options))
    (is (= 3 (count (:targets view))))
    (is (false? (:busy? view)))
    (is (true? (:can-save? view)))
    (is (true? (:can-test? view)))
    (is (= :idle (get-in view [:test :status])))
    (testing "fields carry labels, inputs, required flags and kinds"
      (let [by-key (into {} (map (juxt :key identity) (:fields view)))]
        (is (= :text (get-in by-key [:remote-url :input])))
        (is (= "服务地址" (get-in by-key [:remote-url :label])))
        (is (= #{:webdav} (get-in by-key [:remote-url :kinds])))
        (is (= :password (get-in by-key [:secret :input])))
        (is (= #{:s3} (get-in by-key [:secret :kinds])))
        (is (true? (get-in by-key [:bucket :required?])))
        (is (= :checkbox (get-in by-key [:set-default? :input])))
        (is (= #{:webdav :s3 :file}
               (get-in by-key [:set-default? :kinds])))))))

(deftest target-test-message-is-total
  (doseq [[label test expected]
          [["pending" {:status :pending} "正在测试连接"]
           ["ok-detail" {:status :ok :detail "reachable"} "reachable"]
           ["ok-default" {:status :ok} "连接测试通过"]
           ["failed-detail" {:status :failed :detail "401"} "401"]
           ["failed-default" {:status :failed} "连接测试失败"]
           ["future-kind" {:status :future-kind :detail "ignored"} "未知"]
           ["nil-status" {} "未知"]]]
    (let [message (#'view/target-test-message test)]
      (is (string? message) label)
      (is (not (str/blank? message)) label)
      (is (str/includes? message expected) label))))
