(ns e2e17-seed
  "Seed driver for E2E-17 offline merge restore.

  Writes one source store (local modified state) and one destination store
  (older state) through `hc.server.storage/restore-state!`, plus the managed
  blob objects the sessions reference.  Run with:

      clojure -M:server -i test/e2e/e2e17_seed.clj --eval '(e2e17-seed/run ...)'

  `ws-ext-path` is the absolute path of the linked external workspace; the
  driver creates the directory and registers it in
  `<data-dir>/workspaces/workspaces.edn`, which is where the backup domain
  reads its workspace registry (`workspace-store-root` defaults to
  `<data-dir>/workspaces`)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files Path StandardOpenOption)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defn- write-file!
  [^Path path ^bytes bytes]
  (Files/createDirectories (.getParent path)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (Files/write path bytes
               (into-array java.nio.file.OpenOption
                           [StandardOpenOption/CREATE
                            StandardOpenOption/TRUNCATE_EXISTING
                            StandardOpenOption/WRITE])))

(defn- write-text!
  [^Path path ^String text]
  (write-file! path (.getBytes text "UTF-8")))

(defn- blob-id
  "The canonical blob content id (`sha256-<hex>`), same as
  `hc.server.blob-store.content/id-of-bytes`."
  [^bytes bytes]
  (str "sha256-" (codec/sha256-hex bytes)))

(defn- write-blob!
  [blob-root bytes]
  (let [id (blob-id bytes)
        ;; hc.server.blob-store.layout: <root>/sha256/<first-2-hex>/<rest>
        rel (str "sha256/" (subs id 7 9) "/" (subs id 9))
        path (.resolve (Path/of (str blob-root) (make-array String 0)) rel)]
    (write-file! path bytes)
    {:id id
     :size (alength bytes)
     :sha256 (str "sha256-" (codec/sha256-hex bytes))}))

(defn- attachment-ref
  "One message-level attachment descriptor.  `:blob-id` may be nil for the
  intentionally missing attachment; `attachment-refs` only needs
  `:attachment-id`."
  [{:keys [attachment-id name blob-id size mime-type]}]
  (cond-> {:attachment-id attachment-id
           :name name
           :mime-type mime-type}
    blob-id (assoc :blob-id blob-id)
    size (assoc :size size)))

(defn- message
  "One stored message document in the current versioned shape: a map of
  versions plus `:current-version` index and `:message-order` position."
  [message-id role text {:keys [attachments now] :as _opts}]
  (let [version {:version-id (str message-id "-v0")
                 :content (or text "")
                 :created-at now
                 :edited? false
                 :source :model
                 :attachments (vec (or attachments []))
                 :citations []
                 :quotes []
                 :tool-records []}]
    {:message-id message-id
     :role role
     :versions [version]
     :current-version 0
     :attachments (vec (or attachments []))
     :citations []
     :quotes []
     :created-at now
     :updated-at now
     :deleted? false
     :folded? false
     :deleted-at nil
     :folded-at nil
     :manual-edit? false}))

(defn- session-doc
  "One stored session record (`:session-record` doc).  `:messages` is a map
  keyed by message-id and `:message-order` lists them in display order."
  [{:keys [session-id title status assistant-id workspace-id language
           attachments messages drafts deleted-at now]}]
  {:session-id session-id
   :title title
   :status (or status :active)
   :assistant-id assistant-id
   :workspace-id workspace-id
   :language (or language "zh-CN")
   :pinned? false
   :temporary? false
   :revision 1
   :owner-id "local"
   :message-order (mapv :message-id messages)
   :messages (into {} (map (fn [m] [(:message-id m) m])) messages)
   :drafts (or drafts {})
   :runs {}
   :run-order []
   :calls {}
   :attempts {}
   :context {}
   :model-binding nil
   :created-at now
   :updated-at now
   :activity-at now
   :deleted-at deleted-at})

(defn- mk-partition
  "One storage export partition.  `docs` entries are
  `{:kind :id :session-id? :doc}`; `blob-refs` may be nil."
  [_p docs]
  {:version 1
   :meta nil
   :last-commit nil
   :docs (vec docs)
   :obligations []
   :blob-refs nil})

(defn- doc-entry
  [kind id doc & [{:keys [session-id]}]]
  (cond-> {:kind kind :id id :doc doc}
    session-id (assoc :session-id session-id)))

(defn- config-doc
  []
  {:revision 1
   :created-at "2026-01-01T00:00:00Z"
   :updated-at "2026-01-01T00:00:00Z"
   :assistants {"a1" {:name "助手甲" :content "本地版本"}
                "a2" {:name "助手乙·本地" :content "助手乙内容"}}
   :connections []
   :connection-order []
   :role-bindings {}})

(defn- schedule-doc
  [id]
  ;; A paused-or-enabled schedule in the scheduler domain shape.  The
  ;; restore sanitizes imported schedules to `:state :paused`.
  {:id id
   :name "每日摘要"
   :prompt "总结今天的会话"
   :state :enabled
   :enabled? true
   :paused? false
   :recurrence {:kind :daily :time "09:00"}
   :timezone "UTC"
   :snapshot {}
   :execution {}
   :attention nil
   :active-run-id nil
   :last-run-id nil
   :revision 1
   :created-at 1767225600000
   :updated-at 1767225600000})

(defn- scheduler-root-doc
  []
  {:v 1
   :revision 1
   :seq 1
   :device-zone "UTC"
   ;; `run-order` must only name scheduler-run docs; there are none.
   :run-order []})

(defn- write-state!
  [data-dir export]
  (let [db-path (str data-dir "/db")
        store (storage/open-store {:path db-path
                                   :create? true
                                   :durability :sync})]
    (try
      (storage/restore-state! store export
                              {:quiescent? true
                               :migration-id (str "e2e17-seed-" (System/currentTimeMillis))})
      (finally
        (storage/close-store! store)))))

(defn- register-workspace!
  "Write `<data-dir>/workspaces/workspaces.edn` and create the external
  workspace directory so the backup domain sees `ws-ext`."
  [data-dir ws-ext-path]
  (let [ws-dir (java.nio.file.Paths/get ws-ext-path (make-array String 0))
        _ (Files/createDirectories ws-dir
                                  (make-array java.nio.file.attribute.FileAttribute 0))
        registry-dir (java.nio.file.Paths/get (str data-dir "/workspaces")
                                             (make-array String 0))
        registry-file (.resolve registry-dir "workspaces.edn")
        registry {:format-version 1
                  :workspaces {"ws-ext" {:id "ws-ext"
                                         :name "外部工作区"
                                         :label "外部工作区"
                                         :kind :external
                                         :path (str ws-ext-path)}}}]
    (Files/createDirectories registry-dir
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (write-text! registry-file (pr-str registry))
    ws-ext-path))

(defn- build-export
  "Assemble the storage export map for one store."
  [config sessions schedule-id]
  (let [session-entries
        (into []
              (concat
               (mapv (fn [s]
                       (doc-entry :session-record
                                  (:session-id s) s
                                  {:session-id (:session-id s)}))
                     sessions)
               [{:kind :session-index
                 :id "sessions"
                 :doc {:id "sessions"
                       :ids (mapv :session-id sessions)}}]))
        scheduler-entries
        [{:kind :scheduler-root :id "root"
          :doc (scheduler-root-doc)}
         {:kind :scheduler-schedule :id schedule-id
          :doc (schedule-doc schedule-id)}]
        partitions
        (sorted-map
         "config" (mk-partition nil [(doc-entry :app-config "config" config)])
         "sessions" (mk-partition nil session-entries)
         "scheduler" (mk-partition nil scheduler-entries))]
    {:format-version 1
     :complete? true
     :store-id "e2e17-seed"
     :exported-at "2026-01-01T00:00:00Z"
     :partitions partitions}))

(defn run
  "Seed one store.  Args: data-dir, ws-ext-path, blob-root, variant
  (:backup or :destination)."
  [data-dir ws-ext-path blob-root variant]
  (let [variant (keyword variant)
        _ (register-workspace! data-dir ws-ext-path)
        ;; Blob objects are shared: write them under blob-root once.
        att1-bytes (.getBytes "attachment one" "UTF-8")
        att2-bytes (.getBytes "attachment two" "UTF-8")
        b1 (write-blob! blob-root att1-bytes)
        b2 (write-blob! blob-root att2-bytes)
        ;; Rebuild sessions with real blob ids.
        now 1767225600000
        att1 (attachment-ref {:attachment-id "att-1" :name "notes.txt"
                              :mime-type "text/plain"
                              :blob-id (:id b1) :size (:size b1)})
        att2 (attachment-ref {:attachment-id "att-2" :name "data.csv"
                              :mime-type "text/csv"
                              :blob-id (:id b2) :size (:size b2)})
        att-missing (attachment-ref {:attachment-id "att-missing"
                                     :name "gone.bin"
                                     :mime-type "application/octet-stream"})
        m1 (message "m1" :user "你好" {:now now})
        m2 (message "m2" :assistant "你好，有什么可以帮你？" {:now now})
        m3 (message "m3" :user "请看附件" {:now now
                                           :attachments [att1 att-missing]})
        m4 (message "m4" :assistant "已收到附件" {:now now
                                                  :attachments [att1]})
        m7 (message "m7" :user "备份新增会话" {:now now :attachments [att2]})
        m8 (message "m8" :assistant "备份新增回复" {:now now})
        m9 (message "m9" :user "已删除会话" {:now now})
        base-sessions
        [(session-doc {:session-id "s-history" :title "历史会话"
                       :status :active :assistant-id "a1"
                       :workspace-id "ws-ext"
                       :messages [m1 m2] :now now})
         (session-doc {:session-id "s-attachments" :title "附件会话"
                       :status :active :assistant-id "a1"
                       :workspace-id "ws-ext"
                       :messages [m3 m4] :now now})]
        sessions (if (= variant :backup)
                   (conj base-sessions
                         (session-doc {:session-id "s-backup-new"
                                       :title "备份新增会话"
                                       :status :active :assistant-id "a1"
                                       :workspace-id "ws-ext"
                                       :messages [m7 m8] :now now}))
                   (conj base-sessions
                         (session-doc {:session-id "s-deleted"
                                       :title "已删除会话"
                                       :status :deleted :assistant-id "a2"
                                       :workspace-id "ws-ext"
                                       :messages [m9]
                                       :deleted-at now :now now})))
        config (if (= variant :backup)
                 {:revision 1
                  :created-at "2026-01-01T00:00:00Z"
                  :updated-at "2026-01-01T00:00:00Z"
                  :assistants {"a1" {:name "助手甲·备份版" :content "备份版本"}
                               "a2" {:name "助手乙" :content "助手乙备份内容"}}
                  :connections [] :connection-order [] :role-bindings {}}
                 (config-doc))
        session-entries (into []
                              (concat
                               (mapv (fn [s]
                                       (doc-entry :session-record
                                                  (:session-id s) s
                                                  {:session-id (:session-id s)}))
                                     sessions)
                               [{:kind :session-index
                                 :id "sessions"
                                 :doc {:id "sessions"
                                       :ids (mapv :session-id sessions)}}]))
        export (build-export config sessions
                             (if (= variant :backup) "sched1" "sched-dst"))]
    (write-state! data-dir export)
    (println (json/write-str {:ok true
                         :variant (name variant)
                         :sessions (mapv :session-id sessions)}))))
