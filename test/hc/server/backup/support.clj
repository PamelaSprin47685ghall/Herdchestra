(ns hc.server.backup.support
  "Test doubles and fixtures for the backup module.

  These are deliberately small reference implementations of the explicit
  ports, not production storage.  They let the tests exercise the real
  encryption, container, merge and recovery-point code without a database."
  (:require [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.package :as package]
            [hc.server.backup.port :as port]))

(defrecord MemoryDomain [state]
  port/DomainPort
  (read-snapshot [_ request]
    (let [snapshot @state
          result (assoc snapshot :sampled-at (:sampled-at request))]
      (when-let [f (:on-snapshot snapshot)]
        (f))
      result))
  (apply-restore! [_ plan]
    (let [snapshot @state
          imports (if-let [entry (get (:imports plan) (:backup-id plan))]
                    (assoc (:imports plan) (:backup-id plan)
                           (assoc entry :external-applied? true))
                    (:imports plan))]
      (when (:fail-apply? snapshot)
        (err/fail! :BACKUP_RESTORE_FAILED
                   "Fixture refused to apply the restore"
                   {:backup-id (:backup-id plan)}))
      (swap! state
             (fn [s]
               (cond-> (update s :categories merge (:apply-categories plan))
                 (:attachments plan) (assoc :attachments (:attachments plan))
                 (:imports plan) (assoc :imports imports)
                 (:permissions plan) (assoc :permissions (:permissions plan)))))
      {:status :applied
       :version (inc (long (or (:version snapshot) 0)))}))
  (storage-info [_]
    {:path "memory" :free-bytes Long/MAX_VALUE :used-bytes 0}))

(defrecord MemoryTarget [objects]
  port/TargetPort
  (put-object! [_ object]
    (let [id (:id object)
          bytes (:bytes object)
          existing (get @objects id)]
      (when-not (bytes? bytes)
        (err/fail! :BACKUP_FORMAT_INVALID "Test object must be bytes" {:id id}))
      (package/verify-container bytes)
      (cond
        (and existing (java.util.Arrays/equals ^bytes existing ^bytes bytes))
        (assoc (package/public-metadata (:header (package/read-header bytes)))
               :id id :size (alength ^bytes bytes)
               :sha256 (codec/sha256-hex bytes)
               :integrity :verified :deduplicated? true)

        existing
        (err/fail! :BACKUP_OBJECT_CONFLICT
                   "Test target already has different bytes"
                   {:id id})

        :else
        (do
          (swap! objects assoc id bytes)
          (assoc (package/public-metadata (:header (package/read-header bytes)))
                 :id id :size (alength ^bytes bytes)
                 :sha256 (codec/sha256-hex bytes)
                 :integrity :verified :deduplicated? false)))))

  (get-object [_ id]
    (get @objects id))

  (delete-object! [_ id]
    (swap! objects dissoc id)
    :deleted)

  (list-objects [_]
    (->> @objects
         (map (fn [[id bytes]]
                (let [{:keys [integrity header]} (package/read-header bytes)]
                  (assoc (package/public-metadata header)
                         :id id :size (alength ^bytes bytes)
                         :integrity integrity))))
         (sort-by :created-at)
         vec))

  (verify-object [_ id expected-sha256]
    (let [bytes (get @objects id)]
      (when-not bytes
        (err/fail! :BACKUP_NOT_FOUND "Test object does not exist" {:id id}))
      (let [actual (codec/sha256-hex bytes)]
        (when-not (= expected-sha256 actual)
          (err/fail! :BACKUP_INTEGRITY
                     "Test object digest mismatch"
                     {:id id :expected expected-sha256 :actual actual}))
        (let [{:keys [header]} (package/verify-container bytes)]
          (assoc (package/public-metadata header)
                 :id id :size (alength ^bytes bytes)
                 :sha256 actual :integrity :verified))))))

(defrecord FailingRecoveryStore [calls]
  port/RecoveryPointPort
  (write-recovery-point! [_ _]
    (swap! calls conj :write)
    (err/fail! :BACKUP_STORAGE_FULL
               "Fixture has no space for a recovery point"
               {}))
  (list-recovery-points [_] [])
  (read-recovery-point [_ _] nil)
  (delete-recovery-point! [_ _] :deleted)
  (recovery-storage-info [_] {:path "memory" :free-bytes 0 :used-bytes 0}))

(defn domain
  [state]
  (->MemoryDomain (atom state)))

(defn target
  []
  (->MemoryTarget (atom {})))

(defn failing-recovery-store
  []
  (->FailingRecoveryStore (atom [])))

(defn utf8
  ^bytes [s]
  (codec/utf8-bytes s))

(defn attachment
  [id text]
  (let [bytes (utf8 text)]
    {:id id
     :ready? true
     :size (alength bytes)
     :sha256 (codec/sha256-hex bytes)
     :bytes-base64 (codec/base64-encode bytes)
     :content-type "text/plain"}))

(defn local-state
  []
  {:version 7
   :categories
   {:conversations
    [{:id "c1"
      :title "Local conversation"
      :assistant-id "a1"
      :workspace-id "ws1"
      :attachment-ids ["att1"]
      :messages [{:id "m1" :text "hello"}]}
     {:id "c-temp"
      :title "Temporary"
      :temporary? true
      :messages []}
     {:id "c-deleted"
      :title "Old deleted"
      :deleted-at "2026-01-01T00:00:00Z"
      :messages []}
     {:id "c-cache"
      :title "Cache"
      :cache? true
      :messages []}]

    :assistants
    [{:id "a1" :name "Helper" :content "v1"}
     {:id "a-other" :name "Other" :content "other"}]

    :connections
    [{:id "conn1" :name "Local provider" :host "http://localhost:11434"
      :api-key "local-secret" :verified? true}]

    :tools
    [{:id "tool1" :name "Local tool" :enabled? true
      :permissions {:external-write? true :device-control? true}}]

    :skills
    [{:id "skill1" :name "Local skill" :enabled? true :files ["skill.md"]}]

    :workspaces
    [{:id "ws1" :name "External workspace"
      :external? true :path "/home/user/project" :authorized? true}]

    :worldbooks
    [{:id "wb1" :name "Lore" :content "local lore"}]

    :memories
    [{:id "m1" :name "Old memory" :content "remember this"
      :status :archived :enabled? false}]

    :appearance
    [{:id "app1" :theme :dark :font-size 110}]

    :schedules
    [{:id "sched1" :name "Nightly" :enabled? true :cron "0 3 * * *"}]}

   :attachments {"att1" (attachment "att1" "hello")}
   :imports {}
   :permissions {:full-access? true}})

(defn backup-state
  "The source state for a backup.  It intentionally contains a new
  conversation, missing relation targets, same-name conflicts, a secret and
  execution authority that must not be restored."
  []
  {:version 12
   :categories
   {:conversations
    [{:id "c1"
      :title "Local conversation"
      :assistant-id "a1"
      :workspace-id "ws1"
      :attachment-ids ["att1"]
      :messages [{:id "m1" :text "hello"}]}
     {:id "c-new"
      :title "New from backup"
      :assistant-id "a-new"
      :workspace-id "ws-missing"
      :attachment-ids ["att-new"]
      :messages [{:id "m2" :text "new"}]}
     {:id "c-temp"
      :title "Temporary backup"
      :temporary? true
      :messages []}
     {:id "c-cache"
      :title "Cache backup"
      :cache? true
      :messages []}]

    :assistants
    [{:id "a1" :name "Helper" :content "v2"}
     {:id "a2" :name "Helper" :content "another helper"}]

    :connections
    [{:id "conn1" :name "Backup provider" :host "http://example.invalid"
      :api-key "backup-secret" :verified? true}]

    :tools
    [{:id "tool1" :name "Backup tool" :enabled? true
      :permissions {:external-write? true :device-control? true}}]

    :skills
    [{:id "skill1" :name "Backup skill" :enabled? true :files ["skill.md"]}]

    :workspaces
    [{:id "ws1" :name "Backup workspace"
      :external? true :path "/home/user/project" :authorized? true}]

    :worldbooks
    [{:id "wb1" :name "Lore" :content "backup lore"}
     {:id "wb2" :name "Lore" :content "second lore"}]

    :memories
    [{:id "m1" :name "Old memory" :content "remember this"
      :status :active}
     {:id "m2" :name "New memory" :content "new memory"
      :status :active}]

    :appearance
    [{:id "app1" :theme :light :font-size 120}]

    :schedules
    [{:id "sched1" :name "Nightly" :enabled? true :cron "0 3 * * *"}]}

   :attachments {"att1" (attachment "att1" "hello")}
   :imports {}
   :permissions {:full-access? true}})

(defn add-late-item!
  [domain-atom]
  (swap! domain-atom update-in [:categories :conversations]
         conj {:id "c-late" :title "Arrived after sampling" :messages []}))

(defn thrown-code
  [f]
  (try
    (f)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:error (ex-data e)))))
