(ns hc.client.backup.sente-roundtrip-server
  "Minimal integrated server for the backup Sente roundtrip test.

  This starts the real gateway, the real backup service, the real storage
  handle and the real static client bundle.  It deliberately does not start
  the full application shell, so an unrelated in-progress server namespace
  cannot block the backup transport audit.  The command path is the same
  production path: Sente command -> OwnerPort -> hc.server.backup.service.

  Prints handshake lines prefixed with SERVER_, then stays alive until the
  test process terminates it."
  (:require [hc.server.app.http :as app-http]
            [hc.server.app.static :as static]
            [hc.server.backup.service :as backup-service]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.ports :as ports]
            [hc.server.storage :as storage]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- ensure-dir! [path]
  (.mkdirs (File. ^String path))
  path)

(defn- owner
  [service]
  (let [receipts (atom {})]
    (ports/owner-port
     (fn [request reply-fn]
       (binding [*out* *err*]
         (println "OWNER_COMMAND" (pr-str (:payload request))))
       (let [result (backup-service/handle! service (:payload request))
             result (assoc result
                           :command-id (:command-id request)
                           :command-epoch (:command-epoch request))]
         (swap! receipts assoc (:command-id request) (:receipt result))
         (when reply-fn (reply-fn result))
         (binding [*out* *err*]
           (println "OWNER_REPLIED" (:command-id request) (pr-str result)))
         result))
     (fn [command-id] (get @receipts command-id)))))

(defn -main
  [& _]
  (let [root (temp-dir "hc-backup-roundtrip-root-")
        data (temp-dir "hc-backup-roundtrip-data-")
        db-path (str data "/db")
        backup-root (ensure-dir! (str data "/backups"))
        recovery-root (ensure-dir! (str data "/recovery"))
        store (storage/open-store {:path db-path
                                   :create? true
                                   :durability :sync})
        service (backup-service/service
                 {:store store
                  :data-dir data
                  :target-root backup-root
                  :recovery-root recovery-root
                  :targets {:local {:kind :file :root backup-root}}
                  :default-target-id :local})
        snapshot {:sync {:phase :synced
                         :epoch 1
                         :seq 0
                         :command-epoch 1}}
        gw (gateway/start!
            {:sch-adapter (sente-http-kit/get-sch-adapter)
             :owner (owner service)
             :store (ports/memory-blob-port (atom {}))
             :fallback (static/handler (.getAbsolutePath (File. "resources/public")))
             :snapshot snapshot})
        listener (app-http/start! {:handler (:handler gw) :port 0})]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. ^Runnable
              (fn []
                (try ((:stop! listener)) (catch Throwable _ nil))
                (try (gateway/stop! gw) (catch Throwable _ nil))
                (try (storage/close-store! store) (catch Throwable _ nil)))))
    (println (str "SERVER_URL=" (app-http/url listener)))
    (println (str "SERVER_DATA_DIR=" data))
    (println (str "SERVER_BACKUP_ROOT=" backup-root))
    (println (str "SERVER_RECOVERY_ROOT=" recovery-root))
    (flush)
    (Thread/sleep Long/MAX_VALUE)))
