;; E2E-22 seed: old-device state for the cross-device migration spec.
;;
;; Writes one source store and one destination store through
;; `hc.server.storage/restore-state!`, plus the extension catalog that holds
;; the disabled MCP server and the managed blob objects referenced by the
;; session messages.  Run with:
;;
;;   clojure -M:server -i test/e2e/e2e22_seed.clj --eval \
;;     '(e2e22-seed/run "<data-dir>" "<blob-root>" "<extensions-root>" "<variant>")'
;;
;; variant :source     old device: session + draft + pinned model + archived
;;                     memory + disabled MCP server + soft-deleted session
;; variant :destination  new device: one pre-existing session + one active
;;                     memory (merge must keep both sides)
(ns e2e22-seed
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.blob-store :as blob-store]
            [hc.server.blob-store.content :as blob-content]
            [hc.server.blob-store.layout :as blob-layout]
            [hc.server.extensions.durable :as extensions-durable]
            [hc.server.materials.memory :as memory]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files Path StandardOpenOption)))

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

(defn- write-blob!
  "Write `bytes` into the managed blob layout under `blob-root` and return
  the content id plus byte size."
  [blob-root ^bytes bytes]
  (let [id (blob-content/id-of-bytes bytes)
        path (blob-layout/object-path blob-root id)]
    (write-file! path bytes)
    {:id id :size (alength bytes) :sha256 (blob-content/id->hex id)}))

(defn- attachment-ref
  [{:keys [attachment-id name blob-id size mime-type]}]
  (cond-> {:attachment-id attachment-id
           :name name
           :mime-type mime-type}
    blob-id (assoc :blob-id blob-id)
    size (assoc :size size)))

(defn- message
  [message-id role text {:keys [attachments now]}]
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
  [{:keys [session-id title status assistant-id workspace-id language
           messages drafts model-binding pinned? deleted-at now]}]
  {:session-id session-id
   :title title
   :status (or status :active)
   :assistant-id assistant-id
   :workspace-id workspace-id
   :language (or language "zh-CN")
   :pinned? (boolean pinned?)
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
   :model-binding model-binding
   :created-at now
   :updated-at now
   :activity-at now
   :deleted-at deleted-at})

(defn- memory-doc
  "One :materials-memory document in the current record shape."
  [id body status {:keys [source-kind now]}]
  {:id id
   :v 1
   :status status
   :body body
   :body-hash (memory/body-hash body)
   :category :working-style
   :scope {:kind :global}
   :epistemic :preference
   :always? false
   :assumption? false
   :source (when (= :automatic source-kind)
             {:session-id "s-history"
              :message-ids ["m1"]
              :excerpt "用户偏好简洁回答"})
   :source-kind source-kind
   :sensitive? false
   :sensitive-kinds []
   :created-seq 1
   :updated-seq 1
   :versions []
   :created-at now
   :updated-at now})

(defn- mk-partition
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

(defn- materials-root-doc
  []
  {:v 1
   :revision 1
   :seq 1
   :device-zone "UTC"
   :settings {:memory-read? false :memory-suggest? false}})

(defn- write-state!
  [data-dir export]
  (let [store (storage/open-store {:path (str data-dir "/db")
                                   :create? true
                                   :durability :sync})]
    (try
      (storage/restore-state! store export
                              {:quiescent? true
                               :migration-id (str "e2e22-seed-" (System/currentTimeMillis))})
      (finally
        (storage/close-store! store)))))

(defn- write-catalog!
  "Write the extension catalog under extensions-root (the app default is
  <data-dir>/extensions)."
  [extensions-root]
  (let [repository (extensions-durable/open extensions-root)
        catalog {:version 0
                 :skills {}
                 :mcp {:servers {"mcp-filesystem"
                                 {:id "mcp-filesystem"
                                  :name "旧设备文件系统 MCP"
                                  :transport {:type :stdio
                                              :command "npx"
                                              :args ["-y" "@modelcontextprotocol/server-filesystem"
                                                     "/old-device-only"]
                                              :env {}
                                              :cwd "/old/device/path"}
                                  :enabled? false
                                  :connection {:status :unconnected
                                               :checked-at nil
                                               :duration-ms nil
                                               :server-info nil
                                               :protocol-version nil
                                               :error nil}
                                  :auth {:state :none}
                                  :tools {"read_file"
                                          {:name "read_file"
                                           :description "读取文件"
                                           :input-schema {:type "object"}
                                           :enabled? false
                                           :available? true
                                           :review-required? true}}
                                  :source {:kind :manual}
                                  :created-at 1767225600000
                                  :updated-at 1767225600000}}}}
        saved (extensions-durable/save! repository catalog 0)]
    (when-not (:ok? saved)
      (throw (ex-info "catalog save failed" {:error (:error saved)})))
    extensions-root))

(defn run
  "Seed one store.  Args: data-dir, blob-root, extensions-root, variant
  (:source or :destination).  extensions-root may be nil to skip the catalog
  write (the destination store gets its catalog from the restore)."
  [data-dir blob-root extensions-root variant]
  (let [variant (keyword variant)
        source? (= variant :source)
        _ (when extensions-root (write-catalog! extensions-root))
        att-bytes (.getBytes "迁移附件内容 attachment payload" "UTF-8")
        blob (write-blob! blob-root att-bytes)
        att (attachment-ref {:attachment-id "att-mig"
                             :name "migration-note.txt"
                             :mime-type "text/plain"
                             :blob-id (:id blob) :size (:size blob)})
        now 1767225600000
        ;; deleted-at is "now-ish" (within the 30-day retention window) so the
        ;; imported record lands in the deleted area, not expired.
        deleted-at "2026-11-01T00:00:00Z"
        pinned-binding {:connection-id "conn-old"
                        :connection-name "旧设备模型服务"
                        :model-entry-id "model-old-1"
                        :provider-model-id "old-model-v1"
                        :display-name "旧设备固定模型"
                        :effective-base-url "http://127.0.0.1:9/v1"
                        :official? false
                        :capabilities {}
                        :pricing nil}
        sessions
        (if source?
          [(session-doc {:session-id "s-migrated"
                         :title "E2E22 迁移会话"
                         :status :active :assistant-id "a1"
                         :workspace-id "ws-local"
                         :pinned? true
                         :model-binding pinned-binding
                         :messages [(message "m1" :user "迁移前的消息" {:now now})
                                    (message "m2" :assistant "迁移前的回复" {:now now})]
                         :drafts {"d1" {:id "d1"
                                        :content "迁移草稿：新设备继续写"
                                        :attachments [att]
                                        :updated-at now}}
                         :now now})
           (session-doc {:session-id "s-deleted"
                         :title "E2E22 已删除会话"
                         :status :deleted :assistant-id "a2"
                         :workspace-id "ws-local"
                         :deleted-at deleted-at
                         :messages [(message "md1" :user "删除前的消息" {:now now})]
                         :now now})]
          [(session-doc {:session-id "s-local"
                         :title "E2E22 本地会话"
                         :status :active :assistant-id "a1"
                         :workspace-id "ws-local"
                         :messages [(message "ml1" :user "新设备原有消息" {:now now})
                                    (message "ml2" :assistant "新设备原有回复" {:now now})]
                         :now now})])
        session-entries
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
        ;; One owner-state partition per session keeps the restore's nested
        ;; owner record available; content can be minimal.
        owner-entries
        (mapv (fn [s]
                {:partition (str "session/" (:session-id s))
                 :entry {:kind :owner-state
                         :id (:session-id s)
                         :session-id (:session-id s)
                         :doc {:session-id (:session-id s)
                               :conversation []
                               :created-at now
                               :updated-at now}}})
              sessions)
        config
        (if source?
          {:revision 1
           :created-at "2026-01-01T00:00:00Z"
           :updated-at "2026-01-01T00:00:00Z"
           :assistants {"a1" {:assistant-id "a1" :name "迁移助手"
                              :default-model {:connection-id "conn-old"
                                              :model-entry-id "model-old-1"}}
                        "a2" {:assistant-id "a2" :name "已删除会话助手"}}
           :connections [{:id "conn-old"
                          :connection-id "conn-old"
                          :name "旧设备模型服务"
                          :kind :openai-compatible
                          :base-url "http://127.0.0.1:9/v1"
                          :api-key ""
                          :timeout-ms 60000
                          :enabled? true
                          :models [{:id "model-old-1"
                                    :model-entry-id "model-old-1"
                                    :model-name "old-model-v1"
                                    :provider-model-id "old-model-v1"
                                    :display-name "旧设备固定模型"
                                    :enabled? true
                                    :max-output-tokens 2048}]}]
           :connection-order ["conn-old"]
           :role-bindings {:main {:connection-id "conn-old"
                                 :model-entry-id "model-old-1"}}}
          {:revision 1
           :created-at "2026-01-01T00:00:00Z"
           :updated-at "2026-01-01T00:00:00Z"
           :assistants {"a1" {:assistant-id "a1" :name "本地助手"}}
           :connections []
           :connection-order []
           :role-bindings {}})
        materials-docs
        (if source?
          [{:kind :materials-root :id "root" :doc (materials-root-doc)}
           {:kind :materials-memory :id "mem-archived"
            :doc (memory-doc "mem-archived" "用户偏好简洁回答"
                             :archived {:source-kind :manual now now})}]
          [{:kind :materials-root :id "root" :doc (materials-root-doc)}
           {:kind :materials-memory :id "mem-local"
            :doc (memory-doc "mem-local" "本地记忆：保留两份"
                             :active {:source-kind :manual now now})}])
        partitions
        (reduce (fn [m {:keys [partition entry]}]
                  (if (contains? m partition)
                    (update-in m [partition :docs] conj entry)
                    (assoc m partition
                           (assoc (mk-partition nil [entry])
                                  :version 1))))
                (sorted-map
                 "config" (mk-partition nil [(doc-entry :app-config "config" config)])
                 "sessions" (mk-partition nil session-entries)
                 "materials" (mk-partition nil materials-docs))
                owner-entries)
        export {:format-version 1
                :complete? true
                :store-id "e2e22-seed"
                :exported-at "2026-01-01T00:00:00Z"
                :partitions partitions}]
      (write-state! data-dir export)
      (println (json/write-str {:ok true
                                :variant (name variant)
                                :sessions (mapv :session-id sessions)
                                :catalog (boolean extensions-root)}))))
