(ns hc.server.backup.storage-adapter-test
  "End-to-end backup over a real Datalevin store and a real local target."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.scheduler.store :as sched-store]
            [hc.server.backup :as backup]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.cli :as backup-cli]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.maintenance :as maintenance]
            [hc.server.backup.model :as model]
            [hc.server.backup.port :as port]
            [hc.server.backup.offline :as offline]
            [hc.server.backup.service :as backup-service]
            [hc.server.backup.storage-adapter :as adapter]
            [hc.server.backup.workspace :as backup-workspace]
            [hc.server.blob-store :as blob-store]
            [hc.server.extensions.durable :as extensions-durable]
            [hc.server.extensions.skill.store :as skill-store]
            [hc.server.scheduler.durable-store :as scheduler-store]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(def ^:private password
  "correct horse battery staple")

(def ^:private fast
  {:kdf-iterations 10000})

(defn- delete-tree
  [root]
  (let [path (Path/of (str root) (make-array String 0))]
    (when (Files/exists path (make-array LinkOption 0))
      (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
        (doseq [entry (->> (iterator-seq (.iterator stream))
                           (sort-by #(.getNameCount ^Path %))
                           reverse)]
          (try (Files/deleteIfExists entry) (catch Exception _ nil)))))))

(defmacro with-temp-dir
  [[sym] & body]
  `(let [~sym (str (Files/createTempDirectory "hc-storage-backup-test-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree ~sym)))))

(defn- mid
  []
  (str (UUID/randomUUID)))

(defn- commit!
  [store partition put & [opts]]
  (storage/commit-migration!
   store
   (merge {:migration-id (mid)
           :partition partition
           :reason :test-seed
           :put put}
          opts)))

(defn- seed-source!
  [store root]
  (let [blob-root (str root "/blobs")
        attachment-bytes (.getBytes "attachment-data" "UTF-8")
        attachment (blob-store/put-bytes! blob-root attachment-bytes)
        prepared-bytes (.getBytes "prepared-image-copy" "UTF-8")
        prepared (blob-store/put-bytes! blob-root prepared-bytes)
        extension-root (str root "/extensions")
        installed (skill-store/install! extension-root "skill1"
                                        {"SKILL.md" (.getBytes "# Skill one\n" "UTF-8")})
        _ (when-not (:ok? installed)
            (throw (ex-info "test skill install failed" {:error (:error installed)})))
        skill {:id "skill1"
               :name "Skill one"
               :files (:files installed)
               :root (:root installed)
               :storage-root extension-root
               :enabled? true
               :bindings #{}}
        mcp-server {:id "mcp1"
                    :name "MCP one"
                    :enabled? true
                    :tools {}}
        catalog {:version 1
                 :skills {"skill1" skill}
                 :mcp {:servers {"mcp1" mcp-server}}}
        saved (extensions-durable/save! (extensions-durable/open extension-root)
                                        catalog 0)
        _ (when-not (:ok? saved)
            (throw (ex-info "test catalog save failed" {:error (:error saved)})))
        session {:session-id "s1"
                 :owner-id "o1"
                 :revision 1
                 :persisted-revision 1
                 :status :active
                 :title "Session one"
                 :title-source :manual
                 :language :en
                 :assistant-id "a1"
                 :origin-session-id nil
                 :created-at 1000
                 :updated-at 2000
                 :activity-at 2000
                 :pinned? false
                 :deleted-at nil
                 :model-binding {:connection-id "conn1"
                                 :model-entry-id "model1"}
                 :drafts {}
                 :messages {"m1" {:message-id "m1"
                                  :role :user
                                  :versions [{:version-id "m1-v1"
                                              :content "hello"
                                              :edited? false}]
                                  :current-version 0
                                  :attachments [{:attachment-id "att1"
                                                 :blob-id (:id attachment)
                                                 :size (alength ^bytes attachment-bytes)
                                                 :mime "text/plain"
                                                 :name "note.txt"
                                                 :local-copy? true
                                                 :prepared {:blob-id (:id prepared)
                                                            :size (alength ^bytes prepared-bytes)
                                                            :sha256 (:sha256 prepared)
                                                            :mime "image/png"}}]
                                  :citations []
                                  :created-at 1000
                                  :updated-at 1000
                                  :deleted? false
                                  :folded? false
                                  :manual-edit? false}}
                 :message-order ["m1"]
                 :runs {}
                 :run-order []
                 :current-run-id nil
                 :calls {}
                 :attempts {}
                 :commands {}}
        config {:revision 2
                :assistants {"a1" {:assistant-id "a1"
                                   :name "Helper"
                                   :default-model {:connection-id "conn1"
                                                   :model-entry-id "model1"}
                                   :created-at 1000
                                   :updated-at 2000}}
                :connections {"conn1" {:connection-id "conn1"
                                       :name "Provider"
                                       :kind :openai-compatible
                                       :base-url "http://example.invalid/v1"
                                       :enabled? true
                                       :created-at 1000
                                       :updated-at 2000}}
                :connection-order ["conn1"]
                :role-bindings {:main {:connection-id "conn1"
                                       :model-entry-id "model1"}
                                :task {:connection-id "conn1"
                                       :model-entry-id "model2"}}
                :created-at 1000
                :updated-at 2000}
        root {:v 1
              :revision 2
              :seq 4
              :settings {:memory-read? true :memory-suggest? false}}
        memory {:id "mem1"
                :status :active
                :text "remember this"
                :scope {:kind :global}
                :seq 1}
        book {:id "wb1" :name "Lore" :seq 2}
        entry {:id "e1" :book-id "wb1" :keywords ["lore"] :body "body"}
        worldbook-session {:turn 2 :runtime {"e1" {:last-trigger-turn 2}}
                           :last-turn nil}
        card {:id "card1" :name "Instruction" :body "be precise"}
        selection {:card-ids ["card1"]}
        owner {:session-id "s1"
               :conversation [{:role :user :content "hello"}]
               :board {:tasks []}
               :created-at 1000
               :updated-at 2000}
        diagnostic {:request-id "req1" :model "model1" :result :ok}]
    (commit! store "sessions"
             [{:kind :session-record :id "s1" :doc session}
              {:kind :session-index :id "sessions"
               :doc {:id "sessions" :ids ["s1"]}}])
    (commit! store "config"
             [{:kind :app-config :id "config" :doc config}])
    (commit! store "materials"
             [{:kind :materials-root :id "root" :doc root}
              {:kind :materials-memory :id "mem1" :doc memory}
              {:kind :materials-worldbook-book :id "wb1" :doc book}
              {:kind :materials-worldbook-entry :id "e1" :doc entry}
              {:kind :materials-worldbook-session :id "s1" :doc worldbook-session}
              {:kind :materials-instruction-card :id "card1" :doc card}
              {:kind :materials-instruction-session :id "s1" :doc selection}])
    (commit! store "session/s1"
             [{:kind :owner-state :id "s1" :doc owner}]
             {:session-id "s1"})
    (commit! store "scheduler"
             [{:kind :scheduler-root :id "root"
               :doc {:v 1 :revision 1 :seq 2 :device-zone "UTC"
                     :run-order ["run1"]}}
              {:kind :scheduler-schedule :id "sched1"
               :doc {:id "sched1" :name "Nightly" :state :enabled
                     :revision 1 :prompt "do it" :next-run 9999999999999
                     :active-run-id "run1"}}
              {:kind :scheduler-run :id "run1"
               :doc {:id "run1" :schedule-id "sched1" :status :running
                     :trigger :scheduled :started-at 1000}}])
    (commit! store "diagnostics"
             [{:kind :diagnostics-request :id "req1" :doc diagnostic}])))

(defn- seed-target-only!
  [store]
  (commit! store "diagnostics"
           [{:kind :diagnostics-request :id "target-only" :doc {:request-id "target-only"}}]))

(defn- open-store
  [path]
  (storage/open-store {:path path
                       :create? true
                       :durability :sync
                       :mapsize-mib 32}))

(defn- export
  [store]
  (storage/export-state store {}))

(defn- doc
  [export partition kind id]
  (first (filter #(and (= kind (:kind %)) (= id (:id %)))
                 (get-in export [:partitions partition :docs] []))))

(defn- category-ids
  [state category]
  (set (map :id (get-in state [:categories category :items]))))

(defn- write-workspace-file!
  [root relative text]
  (let [base (Path/of (str root) (make-array String 0))
        target (.resolve base relative)]
    (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
    (Files/write target (.getBytes (str text) "UTF-8")
                 (make-array java.nio.file.OpenOption 0))
    target))

(defn- support-thrown-code
  [f]
  (try
    (f)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:error (ex-data e)))))

(deftest real-store-to-encrypted-local-backup-and-restore
  (with-temp-dir [root]
    (let [source (open-store (str root "/source"))
          target-store (open-store (str root "/target"))
          target (file-target/store (str root "/backups"))
          recovery (file-recovery/store (str root "/recovery"))
          workspace-root (str root "/workspaces")
          target-workspace-root (str root "/target-workspaces")
          external-workspace (str root "/external-workspace")
          missing-external (str root "/missing-external")
          workspace-extras [{:id "ws1" :name "Managed workspace" :kind :managed}
                            {:id "ext1" :name "Linked workspace" :kind :external
                             :path external-workspace}
                            {:id "ext-missing" :name "Missing linked" :kind :external
                             :path missing-external}]
          _ (write-workspace-file! workspace-root "managed/ws1/notes.txt" "managed notes")
          _ (write-workspace-file! external-workspace "linked.txt" "linked content")
          source-domain (adapter/domain source
                                         {:quiescent? true
                                          :extensions-root (str root "/extensions")
                                          :blob-root (str root "/blobs")
                                          :workspace-store-root workspace-root
                                          :workspaces workspace-extras})]
      (try
        (seed-source! source root)
        (seed-target-only! target-store)
        (testing "the adapter exposes the real storage state"
          (let [snap (port/read-snapshot source-domain
                                         {:sampled-at "2026-11-03T00:00:00Z"
                                          :categories model/category-order
                                          :include-deleted? true
                                          :include-temp? true
                                          :include-cache? true
                                          :include-secrets? true
                                          :include-external-files? false
                                          :purpose :backup})]
            (is (= #{"s1"} (category-ids snap :conversations)))
            (is (= #{"a1"} (category-ids snap :assistants)))
            (is (= #{"conn1" "role-bindings"} (category-ids snap :connections)))
            (is (= #{"wb1" "s1"} (category-ids snap :worldbooks)))
            (is (= #{"mem1"} (category-ids snap :memories)))
            (is (= #{"materials-root" "card1" "s1"}
                   (category-ids snap :appearance)))
            (is (= #{"mcp1"} (category-ids snap :tools)))
            (is (= #{"ws1" "ext1" "ext-missing"}
                   (category-ids snap :workspaces)))
            (is (= #{"skill1"} (category-ids snap :skills)))
            (is (= #{"root" "sched1" "run1"} (category-ids snap :schedules)))
            (is (= #{"att1"} (set (keys (:attachments snap)))))
            (is (= 64 (count (get-in snap [:attachments "att1" :sha256]))))))
        (let [made (backup/backup! source-domain target
                                   (merge fast {:password password
                                                :now "2026-11-03T00:00:00Z"}))
              id (get-in made [:backup :id])
              target-domain (adapter/domain target-store
                                             {:quiescent? true
                                              :no-prior-app-commands? true
                                              :extensions-root (str root "/target-extensions")
                                              :blob-root (str root "/target-blobs")
                                              :workspace-store-root target-workspace-root
                                              :workspaces workspace-extras})]
          (is (= :complete (:status made)))
          (is (true? (get-in made [:backup :encrypted?])))
          (is (= 1 (get-in made [:manifest :categories :tools :count])))
          (is (= 1 (get-in made [:manifest :categories :skills :count])))
          (is (= 3 (get-in made [:manifest :categories :workspaces :count])))
          (is (= 3 (get-in made [:manifest :categories :schedules :count])))
          (is (= 1 (get-in made [:manifest :attachments :count])))
          (is (empty? (get-in made [:manifest :incomplete-attachments])))
          (testing "wrong password does not touch the target store"
            (is (= :BACKUP_WRONG_PASSWORD
                   (support-thrown-code
                    #(backup/restore! target-domain target recovery id
                                      (merge fast {:password "wrong horse battery staple"
                                                   :mode :replace
                                                   :quiescent? true}))))))
          (let [preview (backup/preview-restore target-domain target id
                                                (merge fast {:password password
                                                             :mode :replace
                                                             :quiescent? true}))]
            (is (true? (:ok? preview)))
            (is (= :replace (:mode preview))))
          (let [restored (backup/restore! target-domain target recovery id
                                          (merge fast {:password password
                                                       :mode :replace
                                                       :no-prior-app-commands? true
                                                       :quiescent? true}))
                after (export target-store)]
            (when (not= :complete (:status restored))
              (println :RESTORE-FAILED (:error restored)))
            (is (= :complete (:status restored))
                (pr-str (:error restored)))
            (is (= 1 (count (port/list-recovery-points recovery))))
            (testing "conversation, owner state, config, materials and imports restored"
              (is (some? (doc after "sessions" :session-record "s1")))
              (is (= ["s1"] (get-in (doc after "sessions" :session-index "sessions")
                                    [:doc :ids])))
              (is (some? (doc after "session/s1" :owner-state "s1")))
              (is (= "Helper"
                     (get-in (doc after "config" :app-config "config")
                             [:doc :assistants "a1" :name])))
              (let [connections (get-in (doc after "config" :app-config "config")
                                        [:doc :connections])
                    conn (first (filter #(= "conn1" (:id %)) connections))]
                (is (= "Provider" (:name conn)))
                (is (= :openai-compatible (:kind conn)))
                (is (true? (:enabled? conn)))
                (is (vector? connections)
                    "the settings document stores connections as a vector"))
              (is (= "model1"
                     (get-in (doc after "config" :app-config "config")
                             [:doc :assistants "a1" :default-model :model-entry-id])))
              (is (= "model2"
                     (get-in (doc after "config" :app-config "config")
                             [:doc :role-bindings :task :model-entry-id])))
              (is (= "remember this"
                     (get-in (doc after "materials" :materials-memory "mem1")
                             [:doc :text])))
              (is (= "body"
                     (get-in (doc after "materials" :materials-worldbook-entry "e1")
                             [:doc :body])))
              (is (= "be precise"
                     (get-in (doc after "materials" :materials-instruction-card "card1")
                             [:doc :body])))
              (is (some? (doc after "backup" :backup-import id)))
              (is (= :paused (get-in (doc after "scheduler" :scheduler-schedule "sched1")
                                     [:doc :state])))
              (is (= :interrupted (get-in (doc after "scheduler" :scheduler-run "run1")
                                          [:doc :status])))
              (is (empty? (get-in after [:partitions "scheduler" :obligations])))
              (let [scheduler (scheduler-store/durable-store target-store)
                    state (sched-store/load-state scheduler)]
                (is (= :paused (get-in state [:schedules "sched1" :state])))
                (is (= :interrupted (get-in state [:runs "run1" :status]))))
              (is (= "Skill one"
                     (get-in (extensions-durable/load!
                              (extensions-durable/open (str root "/target-extensions")))
                             [:catalog :skills "skill1" :name])))
              (is (false? (get-in (extensions-durable/load!
                                   (extensions-durable/open (str root "/target-extensions")))
                                  [:catalog :skills "skill1" :enabled?])))
              (is (false? (get-in (extensions-durable/load!
                                   (extensions-durable/open (str root "/target-extensions")))
                                  [:catalog :mcp :servers "mcp1" :enabled?])))
              (is (= "# Skill one\n"
                     (slurp (str root "/target-extensions/skills/skill1/SKILL.md"))))
              (is (= (str root "/target-extensions/skills/skill1")
                     (get-in (extensions-durable/load!
                              (extensions-durable/open (str root "/target-extensions")))
                             [:catalog :skills "skill1" :root])))
              (is (= "managed notes"
                     (slurp (str target-workspace-root "/managed/ws1/notes.txt"))))
              (is (not (Files/exists (Path/of missing-external (make-array String 0))
                                     (make-array LinkOption 0))))
              (let [workspaces (backup-workspace/read-workspaces
                                (backup-workspace/store target-workspace-root
                                                        {:workspaces workspace-extras})
                                {})
                    ext (first (filter #(= "ext1" (:id %)) workspaces))]
                (is (= external-workspace (:path ext)))
                (is (true? (:relink-required? ext)))
                (is (false? (:authorized? ext))))
              (let [target-session (doc after "sessions" :session-record "s1")
                    attachment (first (get-in target-session
                                              [:doc :messages "m1" :attachments]))
                    blob-id (:blob-id attachment)]
                (is (= "att1" (:attachment-id attachment)))
                (is (string? blob-id))
                (is (= "attachment-data"
                       (String. ^bytes (blob-store/read-bytes (str root "/target-blobs") blob-id)
                                "UTF-8")))
                (is (nil? (:prepared attachment))
                    "the derived image copy is not packaged and must not dangle")
                (is (= {:status :missing :reason :not-in-backup}
                       (:prepared-status attachment)))
                (let [refs (-> (storage/manage-blob-refs!
                                target-store {:partition "sessions" :op :list})
                               :refs)]
                  (is (some #(= blob-id (:id %)) refs)
                      "restored attachment bytes have a partition reference"))))
            (testing "unselected diagnostics survive the whole-store restore"
              (is (some? (doc after "diagnostics" :diagnostics-request "target-only"))))
            (testing "the offline CLI sees the same target and recovery points"
              (is (true? (:ok? (backup-cli/run :list-backups
                                               {:target-root (str root "/backups")}))))
              (is (true? (:ok? (backup-cli/run :list-recovery-points
                                               {:recovery-root (str root "/recovery")})))))
            (testing "the second restore is idempotent"
              (let [again (backup/restore! target-domain target recovery id
                                           (merge fast {:password password
                                                        :mode :merge
                                                        :no-prior-app-commands? true
                                                        :quiescent? true}))]
                (is (contains? #{:already-imported :no-changes} (:status again)))))))
        (finally
          (storage/close-store! source)
          (storage/close-store! target-store))))))


(deftest service-exposes-targets-and-refuses-live-restore
  (with-temp-dir [root]
    (let [store (open-store (str root "/store"))]
      (try
        (seed-source! store root)
        (let [svc (backup-service/service
                   {:store store
                    :data-dir (str root "/data")
                    :targets {:local {:kind :file}
                              :bad {:kind :webdav}}})
              status (backup-service/status svc {})
              created (backup-service/handle! svc
                                              (merge fast {:kind :backup/create
                                                           :password password
                                                           :now "2026-11-03T00:00:00Z"}))
              listed (backup-service/handle! svc {:kind :backup/list})
              bad (backup-service/handle! svc
                                          (merge fast {:kind :backup/create
                                                       :target-id :bad
                                                       :password password}))]
          (is (true? (:ok? status)))
          (is (= 2 (count (get-in status [:receipt :targets]))))
          (is (= :local (get-in status [:receipt :default-target-id])))
          (is (false? (get-in status [:receipt :restore :online?])))
          (is (true? (get-in status [:receipt :restore :offline?])))
          (is (true? (:ok? created)))
          (is (= 1 (count (get-in listed [:receipt :backups]))))
          (is (false? (:ok? bad)))
          (is (= :BACKUP_TARGET_UNAVAILABLE (get-in bad [:error :code])))
          (is (= :BACKUP_RESTORE_REQUIRES_QUIESCENCE
                 (get-in (backup-service/handle! svc {:kind :backup/restore
                                                      :backup-id "x"})
                         [:error :code])))
          (let [custom (backup-service/service
                        {:store store
                         :data-dir (str root "/data-custom")
                         :target-root (str root "/custom-backups")})
                custom-status (backup-service/status custom {})]
            (is (= (str root "/custom-backups")
                   (get-in custom-status [:receipt :target :root]))))
          (let [configured (backup-service/service
                            {:store store
                             :data-dir (str root "/data-configured")
                             :config {:backup {:target {:kind :file
                                                        :root (str root "/config-backups")}}}})
                configured-status (backup-service/status configured {})]
            (is (= (str root "/config-backups")
                   (get-in configured-status [:receipt :target :root])))))
        (finally
          (storage/close-store! store))))))


(deftest offline-entry-point-roundtrips-real-store
  (with-temp-dir [root]
    (let [source-path (str root "/offline-source")
          target-path (str root "/offline-target")
          data-dir (str root "/offline-data")
          workspace-root (str root "/workspaces")
          target-workspace-root (str root "/offline-target-workspaces")
          external-workspace (str root "/external-workspace")
          missing-external (str root "/missing-external")
          workspace-extras [{:id "ws1" :name "Managed workspace" :kind :managed}
                            {:id "ext1" :name "Linked workspace" :kind :external
                             :path external-workspace}
                            {:id "ext-missing" :name "Missing linked" :kind :external
                             :path missing-external}]
          _ (write-workspace-file! workspace-root "managed/ws1/notes.txt" "managed notes")
          _ (write-workspace-file! external-workspace "linked.txt" "linked content")]
      (let [source (open-store source-path)]
        (try
          (seed-source! source root)
          (finally (storage/close-store! source))))
      (let [target (open-store target-path)]
        (try
          (seed-target-only! target)
          (finally (storage/close-store! target))))
      (let [made (offline/backup! {:db-path source-path
                                   :data-dir data-dir
                                   :extensions-root (str root "/extensions")
                                   :blob-root (str root "/blobs")
                                   :workspace-store-root (str root "/workspaces")
                                   :workspaces workspace-extras
                                   :password password
                                   :now "2026-11-03T00:00:00Z"})
            id (get-in made [:backup :id])
            preview (offline/preview! {:db-path target-path
                                       :data-dir data-dir
                                       :backup-id id
                                       :password password
                                       :mode :replace})
            lifecycle (atom [])
            restored (maintenance/restore!
                      {:kind :backup
                       :stop! #(do (swap! lifecycle conj :stop) {:stopped? true})
                       :start! #(do (swap! lifecycle conj :start) {:ok? true})
                       :offline-opts {:db-path target-path
                                      :data-dir data-dir
                                      :extensions-root (str root "/offline-target-extensions")
                                      :blob-root (str root "/offline-target-blobs")
                                      :workspace-store-root target-workspace-root
                                      :workspaces workspace-extras
                                      :no-prior-app-commands? true
                                      :backup-id id
                                      :password password
                                      :mode :replace}})]
        (is (= :complete (:status made)))
        (is (true? (:ok? preview)))
        (is (= :complete (:status restored))
            (pr-str (:error restored) (:restore restored)))
        (is (= [:stop :start] @lifecycle))
        (is (= :complete (get-in restored [:restore :value :status])))
        (let [target (open-store target-path)]
          (try
            (let [after (export target)]
              (is (some? (doc after "sessions" :session-record "s1")))
              (is (= "Helper"
                     (get-in (doc after "config" :app-config "config")
                             [:doc :assistants "a1" :name])))
              (is (= "remember this"
                     (get-in (doc after "materials" :materials-memory "mem1")
                             [:doc :text])))
              (is (some? (doc after "diagnostics" :diagnostics-request "target-only")))
              (is (= :paused (get-in (doc after "scheduler" :scheduler-schedule "sched1")
                                     [:doc :state])))
              (is (some? (get-in (extensions-durable/load!
                                  (extensions-durable/open
                                   (str root "/offline-target-extensions")))
                                 [:catalog :skills "skill1"])))
              (is (= "managed notes"
                     (slurp (str target-workspace-root "/managed/ws1/notes.txt"))))
              (is (not (Files/exists (Path/of missing-external (make-array String 0))
                                     (make-array LinkOption 0)))))
            (finally (storage/close-store! target))))))))
