(ns hc.server.backup.restore-projection-test
  "A restored connection stays visible to the target instance.

  The source settings document records which connections its launch file
  declared.  That provenance is instance-local; carrying it into another
  instance made `settings/merge-model-config` treat the restored connection
  as revoked, so the UI said \"connection removed\" while the store still had
  the connection enabled.  The restore now keeps the target's provenance and
  drops the source's."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.lifecycle-test-support :as lifecycle]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.app.system :as system]
            [hc.server.backup :as backup]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.storage-adapter :as adapter]
            [hc.server.backup.workspace :as backup-workspace]
            [hc.server.model-adapter :as model-adapter]
            [hc.server.sessions.ports :as session-ports]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(def ^:private password "correct horse battery staple")
(def ^:private fast {:kdf-iterations 10000})

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- migration
  [partition put]
  {:migration-id (str (UUID/randomUUID))
   :partition partition
   :reason :test-seed
   :put put})

(defn- seed-source!
  [store]
  (let [connection {:id "local-ollama"
                    :connection-id "local-ollama"
                    :name "Local Ollama"
                    :kind :ollama
                    :base-url "http://127.0.0.1:11434"
                    :enabled? true
                    :models [{:id "qwen"
                              :model-name "qwen"
                              :display-name "Qwen"
                              :enabled? true
                              :capabilities {:tools :unknown}}]}
        session {:session-id "s1"
                 :owner-id "o1"
                 :revision 1
                 :persisted-revision 1
                 :status :active
                 :title "Restored"
                 :title-source :manual
                 :language :en
                 :assistant-id nil
                 :origin-session-id nil
                 :created-at 1000
                 :updated-at 2000
                 :activity-at 2000
                 :pinned? false
                 :deleted-at nil
                 :model-binding {:connection-id "local-ollama"
                                 :model-entry-id "qwen"}
                 :drafts {}
                 :messages {}
                 :message-order []
                 :runs {}
                 :run-order []
                 :current-run-id nil
                 :calls {}
                 :attempts {}
                 :commands {}}]
    (storage/commit-migration!
     store
     (migration "config"
                [{:kind :app-config :id "config"
                  :doc {:revision 1
                        :connections [connection]
                        :connection-order ["local-ollama"]
                        :roles {:main {:connection "local-ollama"
                                       :model "qwen"}}
                        ;; Source-instance launch provenance: the target must
                        ;; not inherit it.
                        :launch-connection-ids #{"local-ollama"}}}]))
    (storage/commit-migration!
     store
     (migration "sessions"
                [{:kind :session-record :id "s1" :doc session}
                 {:kind :session-index :id "sessions"
                  :doc {:id "sessions" :ids ["s1"]}}]))))

(defn- app-config
  [root data]
  (let [static (str root "/static")
        index (java.io.File. (str static "/index.html"))]
    (.mkdirs (.getParentFile index))
    (spit index "<!doctype html><title>hc</title>")
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root (str root "/work")
      :static-root static
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :models {}})))

(deftest restored-connection-remains-projected
  (let [root (temp-dir "hc-restore-projection-")
        source-db (str root "/source/db")
        target-db (str root "/target/db")
        backups (str root "/backups")
        source (storage/open-store {:path source-db :create? true})]
    (seed-source! source)
    (let [target (file-target/store backups)
          created (backup/backup!
                   (adapter/domain source
                                   {:quiescent? true
                                    :workspace-store
                                    (backup-workspace/store
                                     (str root "/source-workspaces")
                                     {:workspaces []})})
                                  target
                                  (merge fast {:password password
                                               :categories [:connections
                                                            :appearance
                                                            :conversations]
                                               :now "2026-11-05T00:00:00Z"
                                               :app-version "test"
                                               :device "test"}))
          backup-id (get-in created [:backup :id])]
      (is (= :complete (:status created)))
      (storage/close-store! source)
      (let [target-store (storage/open-store {:path target-db :create? true})]
        (try
          (let [restored (backup/restore!
                          (adapter/domain
                           target-store
                           {:quiescent? true
                            :workspace-store
                            (backup-workspace/store
                             (str root "/target-workspaces")
                             {:workspaces []})})
                          target
                          (file-recovery/store (str root "/recovery"))
                          backup-id
                          (merge fast {:password password
                                       :mode :merge
                                       :categories [:connections :appearance
                                                    :conversations]
                                       :no-prior-app-commands? true}))]
            (is (= :complete (:status restored))))
          (finally (storage/close-store! target-store))))
      (let [started (system/start! (app-config root (str root "/target")))]
        (try
          (is (false? (:blocked? started)))
          (testing "the target health reports a configured model"
            (is (true? (get-in (system/health started)
                               [:models :configured?]))))
          (testing "the restored connection and binding stay usable"
            (let [persisted (:config (session-ports/read-config
                                      (:persistence started)))
                  merged (system/merge-persisted-config
                          {:models {}}
                          persisted)
                  connections (get-in merged [:models :connections])
                  models (into {} (map (fn [c] [(:id c) (:models c)]))
                               connections)
                  connection-map (into {} (map (fn [c] [(:id c) c]))
                                       connections)
                  session (first
                           (get-in (storage/read-session-view
                                    (:store started)
                                    {:partition "sessions" :session-id "s1"})
                                   [:records :session-record]))
                  binding (:model-binding session)]
              (is (= ["local-ollama"] (mapv :id connections)))
              (is (empty? (:launch-connection-ids persisted))
                  "the source instance's launch provenance is not inherited")
              (is (true? (model-adapter/model-configured? (:models merged))))
              (is (= "local-ollama" (:connection-id binding)))
              (is (= "qwen" (:model-entry-id binding)))
              (is (not= :connection-removed
                        (snapshot/binding-availability
                         connection-map models binding)))))
          (finally
            (lifecycle/stop-system! started)))))))
