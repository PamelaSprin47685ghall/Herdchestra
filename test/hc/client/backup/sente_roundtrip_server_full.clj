(ns hc.client.backup.sente-roundtrip-server-full
  "Full integrated application for the backup Sente roundtrip.

  Uses hc.server.app.system so the production command router, gateway,
  backup service, settings and static client all run together."
  (:require [hc.server.app.config :as config]
            [hc.server.app.system :as system])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- ensure-dir! [path]
  (.mkdirs (File. ^String path))
  path)

(defn- test-config []
  (let [root (temp-dir "hc-backup-full-root-")
        data (temp-dir "hc-backup-full-data-")
        backup-root (ensure-dir! (str data "/backups"))
        recovery-root (ensure-dir! (str data "/recovery"))]
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root root
      :static-root (.getAbsolutePath (File. "resources/public"))
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :models {}
      :backup {:targets {:local {:kind :file :root backup-root}}
               :default-target-id :local}})))

(defn -main
  [& _]
  (let [config (test-config)
        started (system/start! config)]
    (when (:blocked? started)
      (binding [*out* *err*] (println "SERVER_BLOCKED" (pr-str started)))
      (System/exit 2))
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. ^Runnable (fn [] (try (system/stop! started) (catch Throwable _ nil)))))
    (println (str "SERVER_URL=" (:url started)))
    (println (str "SERVER_DATA_DIR=" (:data-dir config)))
    (println (str "SERVER_BACKUP_ROOT="
                  (get-in config [:backup :targets :local :root])))
    (println (str "SERVER_RECOVERY_ROOT=" (str (:data-dir config) "/recovery")))
    (flush)
    (Thread/sleep Long/MAX_VALUE)))
