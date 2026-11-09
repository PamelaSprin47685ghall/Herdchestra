(ns hc.server.backup.core
  "Backup creation, preview and restore orchestration.

  All domain and file side effects go through the explicit ports in
  `hc.server.backup.port`.  This namespace owns policy: password requirements,
  coherent sampling, merge/replace planning, the recovery point before a
  mutation, and the 7+4+3 retention rule."
  (:require [hc.server.backup.codec :as codec]
            [hc.server.backup.crypto :as crypto]
            [hc.server.backup.error :as err]
            [hc.server.backup.merge :as merge]
            [hc.server.backup.model :as model]
            [hc.server.backup.package :as package]
            [hc.server.backup.port :as port]
            [hc.server.backup.recovery :as recovery]
            [hc.server.backup.snapshot :as snapshot]
            [hc.version :as version])
  (:import (java.net InetAddress)
           (java.time Instant)
           (java.util UUID)))

(def default-options
  (merge model/default-policy
         {:mode :merge
          :retention-days 30
          :kdf-iterations crypto/default-iterations
          :app-version version/version}))

(def ^:private preview-fingerprint-keys
  "The side-effect-free plan projection a maintenance operator confirms.

  It deliberately excludes secrets, attachment bytes and the recovery-point
  plan, so the fingerprint can be persisted and compared across a process
  stop without leaking or churning on presentation-only fields."
  [:mode :selected-categories :summary :conflicts :placeholders
   :missing-relations :relink-required :needs-review :incomplete-attachments
   :memory-reactivation :expired-skipped :replace-all?
   :memory-reactivation-required?])

(defn preview-fingerprint
  "Stable SHA-256 of the confirmed restore plan projection."
  [preview]
  (codec/sha256-hex
   (codec/canonical-bytes (select-keys preview preview-fingerprint-keys))))

(defn- now-string
  [opts]
  (let [now (:now opts)]
    (cond
      (string? now) now
      (instance? Instant now) (str now)
      (nil? now) (model/now)
      :else (str now))))

(defn- local-device
  []
  (try
    (.getHostName (InetAddress/getLocalHost))
    (catch Exception _ "unknown")))

(defn- emit
  [opts phase data]
  (when-let [f (:on-progress opts)]
    (try
      (f (merge {:phase phase} data))
      (catch Exception _ nil))))

(defn- cancelled!
  [opts]
  (when-let [f (:cancelled? opts)]
    (try
      (boolean (f))
      (catch Exception _ false))))

(defn- check-cancelled!
  [opts phase]
  (when (cancelled! opts)
    (err/fail! :BACKUP_CANCELLED
               "Backup operation was cancelled"
               {:phase phase})))

(defn- normalize-create-options
  [opts]
  (let [opts (merge default-options opts)
        ;; Only an explicit false disables encryption.  A nil value means
        ;; the caller did not decide, and a new backup must be encrypted by
        ;; default (root AGENTS.md §7.2-1).
        encrypt? (not (false? (:encrypt? opts)))
        password (:password opts)
        confirmation (:password-confirm opts)
        _ (when encrypt?
            (crypto/validate-password password)
            (when (contains? opts :password-confirm)
              (crypto/validate-password-confirmation password confirmation)))
        include-secrets? (boolean (:include-secrets? opts))
        _ (when (and include-secrets? (not encrypt?)
                     (not (:confirm-plaintext-secrets? opts)))
            (err/fail! :BACKUP_PLAINTEXT_SECRETS_UNCONFIRMED
                       "Including secrets in an unencrypted backup needs explicit confirmation"
                       {:include-secrets? true
                        :encrypt? false}))
        selection (or (:categories opts) (:selection opts))
        selected (model/selected-categories selection)
        _ (when (empty? selected)
            (err/fail! :BACKUP_CATEGORY_REQUIRED
                       "At least one category must be selected"
                       {}))
        now (now-string opts)]
    (-> opts
        (assoc :encrypt? encrypt?
               :password password
               :include-secrets? include-secrets?
               :categories selected
               :sampled-at now
               :created-at now
               :date (model/date-suffix now)
               :device (or (:device opts) (local-device))
               :backup-id (or (:backup-id opts) (str (UUID/randomUUID)))
               :kdf-iterations (long (or (:kdf-iterations opts)
                                         crypto/default-iterations))
               :max-attachment-bytes
               (long (or (:max-attachment-bytes opts)
                         (get model/default-policy :max-attachment-bytes)))
               :max-attachment-total-bytes
               (long (or (:max-attachment-total-bytes opts)
                         (get model/default-policy
                              :max-attachment-total-bytes)))))))

(defn- normalize-restore-options
  [opts payload]
  (let [opts (merge default-options opts)
        selection (or (:categories opts) (:selection opts))
        selected (if (some? selection)
                   (model/selected-categories selection)
                   (vec (sort-by #(.indexOf ^java.util.List model/category-order %)
                                 (keys (:categories payload)))))
        unknown (remove model/category? selected)
        _ (when (seq unknown)
            (err/fail! :BACKUP_CATEGORY_UNKNOWN
                       "Restore selection names an unknown category"
                       {:categories (vec unknown)}))
        now (now-string opts)
        mode (let [m (or (:mode opts) :merge)]
               (cond
                 (keyword? m) m
                 (string? m) (keyword m)
                 :else m))]
    (when-not (contains? #{:merge :replace} mode)
      (err/fail! :BACKUP_MODE_INVALID
                 "Restore mode must be :merge or :replace"
                 {:mode mode}))
    (assoc opts
           :mode mode
           :categories selected
           :now now
           :sampled-at now
           :date (model/date-suffix now)
           :retention-days (long (or (:retention-days opts) 30))
           :resolutions (merge/normalize-resolutions (:resolutions opts))
           :include-expired? (boolean (:include-expired? opts))
           :confirm-memory-reactivation? (boolean (:confirm-memory-reactivation? opts)))))

(defn- snapshot-request
  [opts purpose]
  {:sampled-at (:sampled-at opts)
   :categories (:categories opts)
   :include-deleted? (boolean (:include-deleted? opts))
   :include-temp? (boolean (:include-temp? opts))
   :include-cache? (boolean (:include-cache? opts))
   :include-secrets? (boolean (:include-secrets? opts))
   :include-external-files? (boolean (:include-external-files? opts))
   :purpose purpose})

(defn- category-stats
  [payload]
  (into {}
        (map (fn [category]
               [category
                (let [items (get-in payload [:categories category :items] [])]
                  {:count (count items)
                   :bytes (reduce + 0 (map #(alength (codec/canonical-bytes %))
                                           items))})]))
        model/category-order))

(defn- attachment-stats
  [payload]
  (let [attachments (or (:attachments payload) {})]
    {:count (count attachments)
     :bytes (reduce + 0 (map :size (vals attachments)))}))

(defn- deleted-included-count
  [payload]
  (reduce + 0
          (map (fn [category]
                 (count (filter model/deleted?
                                (get-in payload [:categories category :items] []))))
               model/category-order)))

(defn manifest
  "The user-facing manifest of one opened backup."
  [opened]
  (let [{:keys [header payload encrypted?]} opened
        stats (category-stats payload)]
    {:format :hc.backup/manifest
     :format-version package/format-version
     :backup-id (:backup-id header)
     :sampled-at (:sampled-at header)
     :created-at (:created-at header)
     :device (:device header)
     :app-version (:app-version header)
     :encryption (:encryption header)
     :encrypted? encrypted?
     :categories stats
     :deleted-included (deleted-included-count payload)
     :attachments (attachment-stats payload)
     :exclusions (:exclusions payload)
     :incomplete-attachments (:incomplete-attachments payload)
     :missing-references (:missing-references payload)
     :secrets (:secrets payload)
     :permissions (:permissions payload)
     :payload-sha256 (:payload-sha256 header)
     :payload-size (:payload-size header)
     :plaintext-sha256 (:plaintext-sha256 header)
     :plaintext-size (:plaintext-size header)}))

(defn- backup-metadata
  [opened]
  (merge (package/public-metadata (:header opened))
         {:encrypted? (:encrypted? opened)}))

(defn backup!
  "Create, encrypt and write one coherent backup.

  The sampling instant is fixed before the domain snapshot is read.  Changes
  made after that instant are not in this backup and are not claimed to be.
  `:encrypt?` defaults to true and then requires a valid password."
  [domain target opts]
  (let [opts (normalize-create-options opts)
        _ (emit opts :preparing {:sampled-at (:sampled-at opts)})
        raw (port/read-snapshot domain (snapshot-request opts :backup))
        sampled-at (or (:sampled-at raw) (:sampled-at opts))
        opts (assoc opts :sampled-at sampled-at)
        _ (check-cancelled! opts :preparing)
        {:keys [payload]} (snapshot/prepare raw opts)
        payload (assoc payload
                       :backup-id (:backup-id opts)
                       :created-at (:created-at opts)
                       :source {:device (:device opts)
                                :app-version (:app-version opts)})
        _ (emit opts :packaging {:categories (:categories opts)})
        plaintext (codec/canonical-bytes payload)
        _ (check-cancelled! opts :packaging)
        _ (emit opts :encrypting {:encrypted? (:encrypt? opts)})
        sealed (package/seal-payload
                {:backup-id (:backup-id opts)
                 :sampled-at sampled-at
                 :created-at (:created-at opts)
                 :device (:device opts)
                 :app-version (:app-version opts)}
                plaintext
                {:encrypt? (:encrypt? opts)
                 :password (:password opts)
                 :kdf-iterations (:kdf-iterations opts)})
        _ (check-cancelled! opts :encrypting)
        _ (emit opts :writing {:bytes (alength ^bytes (:bytes sealed))})
        object {:id (:backup-id opts)
                :bytes (:bytes sealed)
                :metadata {:backup-id (:backup-id opts)
                           :sampled-at sampled-at}}
        target-meta (port/put-object! target object)
        _ (emit opts :verifying {:id (:backup-id opts)})
        verification (try
                       {:metadata (port/verify-object
                                   target
                                   (:backup-id opts)
                                   (codec/sha256-hex (:bytes sealed)))}
                       (catch Exception e
                         {:error {:code (err/code e)
                                  :message (.getMessage ^Throwable e)
                                  :data (ex-data e)}}))
        opened {:header (:header sealed)
                :payload payload
                :encrypted? (:encrypted? sealed)}
        manifest (manifest opened)
        result {:status (if (= :verified (get-in verification [:metadata :integrity]))
                          :complete
                          :needs-verification)
                :id (:backup-id (:header sealed))
                :backup (backup-metadata opened)
                :manifest manifest
                :categories (:categories manifest)
                :deleted-included (:deleted-included manifest)
                :attachments (:attachments manifest)
                :exclusions (:exclusions manifest)
                :incomplete-attachments (:incomplete-attachments manifest)
                :missing-references (:missing-references manifest)
                :secrets (:secrets manifest)
                :target target-meta
                :verification verification}]
    (emit opts :done {:id (:backup-id opts)})
    result))

(defn open-backup
  "Read and verify a backup.  A wrong password is reported before any
  plaintext is decoded."
  [target backup-id password]
  (let [bytes (port/get-object target backup-id)]
    (when-not bytes
      (err/fail! :BACKUP_NOT_FOUND
                 "Backup does not exist"
                 {:id backup-id}))
    (let [opened (package/open-container bytes password)]
      (assoc opened :id backup-id :manifest (manifest opened)))))

(defn- local-state
  [domain opts purpose]
  (let [selected (set (:categories opts))
        ;; Relations are only needed when conversations are being compared.
        ;; Requesting every category here would force extension/blob ports
        ;; even for a memory-only restore.
        categories (vec (distinct
                         (concat (:categories opts)
                                 (when (contains? selected :conversations)
                                   [:conversations :assistants :workspaces]))))
        request {:sampled-at (:sampled-at opts)
                 :categories categories
                 :include-deleted? true
                 :include-temp? true
                 :include-cache? true
                 :include-secrets? true
                 :include-external-files? true
                 :purpose purpose}]
    (model/normalize-state (port/read-snapshot domain request))))

(declare preview-restore)

(defn preview
  "UI-friendly wrapper around `preview-restore`: returns `{:ok? false :error ...}`
  instead of throwing an expected failure."
  [domain target backup-id opts]
  (try
    (preview-restore domain target backup-id opts)
    (catch clojure.lang.ExceptionInfo e
      {:ok? false
       :error {:code (err/code e)
               :message (.getMessage ^Throwable e)
               :data (ex-data e)}})))

(defn preview-restore
  "Verify a backup and show what merge or replace would do.  Nothing is
  written and no recovery point is created."
  [domain target backup-id opts]
  (let [opened (open-backup target backup-id (:password opts))
        opts (normalize-restore-options opts (:payload opened))
        local (local-state domain opts :preview)
        plan (merge/build-plan local (:payload opened)
                               (assoc opts
                                      :backup-id (:backup-id (:header opened))
                                      :backup-digest (codec/sha256-hex
                                                      (codec/canonical-bytes (:payload opened)))))
        no-changes? (:no-changes? plan)]
    (let [preview
          {:ok? true
           :backup (backup-metadata opened)
           :manifest (:manifest opened)
           :mode (:mode plan)
           :selected-categories (:selected-categories plan)
           :categories (:categories plan)
           :summary (:summary plan)
           :conflicts (:conflicts plan)
           :placeholders (:placeholders plan)
           :missing-relations (:missing-relations plan)
           :relink-required (:relink-required plan)
           :needs-review (:needs-review plan)
           :needs-reconfiguration (vec (distinct
                                        (concat (:missing-relations plan)
                                                (:placeholders plan)
                                                (:relink-required plan)
                                                (:needs-review plan))))
           :incomplete-attachments (get-in opened [:payload :incomplete-attachments])
           :memory-reactivation (:memory-reactivation plan)
           :expired-skipped (:expired-skipped plan)
           :permissions (:permissions plan)
           :recovery-point {:required? (not no-changes?)
                            :status (if no-changes? :not-needed :will-create)
                            :retention recovery/default-policy}
           :no-changes? no-changes?
           :already-imported? (:already-imported? plan)
           :replace-all? (:replace-all? plan)
           :memory-reactivation-required? (:memory-reactivation-required? plan)
           :warnings (:warnings plan)}]
      (assoc preview :fingerprint (preview-fingerprint preview)))))

(defn- recovery-point
  [domain recovery-store opts reason scope]
  (let [point-id (str "rp-" (UUID/randomUUID))
        categories (if (seq scope) (vec (distinct scope)) model/category-order)
        state (port/read-snapshot domain
                                  {:sampled-at (:now opts)
                                   :categories categories
                                   :include-deleted? true
                                   :include-temp? true
                                   :include-cache? true
                                   :include-secrets? true
                                   :include-external-files? true
                                   :purpose :recovery-point})
        point {:id point-id
               :created-at (:now opts)
               :reason reason
               :scope (vec scope)
               :state (model/normalize-state state)}]
    (try
      (let [written (port/write-recovery-point! recovery-store point)]
        ;; The caller gets identity and size, never the snapshot.  The state
        ;; contains every conversation, attachment byte and secret.
        (merge (select-keys point [:id :created-at :reason :scope])
               (select-keys written [:bytes :sha256 :state-bytes])))
      (catch clojure.lang.ExceptionInfo e
        (if (= :BACKUP_STORAGE_FULL (err/code e))
          (throw e)
          (err/fail! :BACKUP_RECOVERY_POINT_FAILED
                     "Could not create a local recovery point; restore did not start"
                     {:cause-code (err/code e)
                      :recovery-point-id point-id}
                     e))))))

(defn- same-category?
  [pre post categories]
  (every? (fn [category]
            (= (model/items pre category)
               (model/items post category)))
          categories))

(defn- apply-failure
  [domain opts pre plan recovery-point error]
  (let [post (try
               (local-state domain (assoc opts :sampled-at (:now opts)) :post-restore)
               (catch Exception _ nil))
        rolled-back? (if post
                       (and (same-category? pre post (:apply-categories plan))
                            (or (nil? (:attachments plan))
                                (= (:attachments pre) (:attachments post))))
                       :unknown)]
    {:status :failed
     :mode (:mode plan)
     :error {:code (err/code error)
             :message (.getMessage ^Throwable error)
             :class (.getName (class error))
             :data (ex-data error)}
     :rolled-back? rolled-back?
     :needs-recovery? (not (true? rolled-back?))
     :recovery-point recovery-point
     :summary (:summary plan)
     :warnings [{:reason :restore-failed
                 :rolled-back? rolled-back?}]}))

(defn restore!
  "Preview, protect and apply a backup.

  A recovery point is always created before a mutation.  If it cannot be
  created, restore stops and the current data is untouched.  A successful
  restore never grants execution authority from the backup."
  [domain target recovery-store backup-id opts]
  (let [opened (open-backup target backup-id (:password opts))
        opts (normalize-restore-options opts (:payload opened))
        local (local-state domain opts :preview)
        plan (merge/build-plan local (:payload opened)
                               (assoc opts
                                      :backup-id (:backup-id (:header opened))
                                      :backup-digest (codec/sha256-hex
                                                      (codec/canonical-bytes (:payload opened)))))
        fingerprint-source
        (assoc plan
               :incomplete-attachments
               (get-in opened [:payload :incomplete-attachments]))
        fingerprint (preview-fingerprint fingerprint-source)
        _ (when (and (:expected-fingerprint opts)
                     (not= (:expected-fingerprint opts) fingerprint))
            (err/fail! :BACKUP_PREVIEW_STALE
                       "The restore plan changed after the preview; nothing was written"
                       {:expected (:expected-fingerprint opts)
                        :actual fingerprint}))
        base {:mode (:mode plan)
              :backup (backup-metadata opened)
              :manifest (:manifest opened)
              :summary (:summary plan)
              :permissions (:permissions plan)
              :incomplete-attachments (get-in opened [:payload :incomplete-attachments])
              :relink-required (:relink-required plan)
              :needs-review (:needs-review plan)
              :needs-reconfiguration (vec (distinct
                                           (concat (:missing-relations plan)
                                                   (:placeholders plan)
                                                   (:relink-required plan)
                                                   (:needs-review plan))))
              :replace-all? (:replace-all? plan)
              :retry-external? (:retry-external? plan)
              :memory-reactivation-required? (:memory-reactivation-required? plan)
              :warnings (:warnings plan)}]
    (cond
      (:already-imported? plan)
      (assoc base :status :already-imported)

      (and (:no-changes? plan) (not (:retry-external? plan)))
      (assoc base :status :no-changes)

      (cancelled! opts)
      (assoc base :status :cancelled)

      :else
      (let [scope (if (:retry-external? plan)
                    (:selected-categories plan)
                    (keys (:apply-categories plan)))
            point (recovery-point domain recovery-store opts
                                  :before-restore scope)]
        (if (cancelled! opts)
          (assoc base :status :cancelled :recovery-point point)
          (do
            (emit opts :applying {:categories (vec scope)})
            (try
              (let [applied (port/apply-restore! domain plan)
                    _ (when (= :failed (:status applied))
                        (err/fail! :BACKUP_RESTORE_FAILED
                                   "Storage port reported a failed restore"
                                   {:backup-id (:backup-id plan)
                                    :result applied}))
                    pruned (try
                             (let [points (port/list-recovery-points recovery-store)
                                   retention (recovery/plan-retention
                                              points
                                              {:current-id (:id point)})]
                               {:deleted (mapv (fn [id]
                                                 (try
                                                   (port/delete-recovery-point! recovery-store id)
                                                   {:id id :status :deleted}
                                                   (catch Exception e
                                                     {:id id :status :failed
                                                      :error (err/summary e)})))
                                               (:delete-ids retention))
                                :keep-ids (:keep-ids retention)})
                             (catch Exception e
                               {:deleted []
                                :error (err/summary e)}))]
                (emit opts :done {:backup-id (:backup-id (:header opened))})
                (assoc base
                       :status :complete
                       :recovery-point point
                       :applied applied
                       :pruned pruned))
              (catch clojure.lang.ExceptionInfo e
                (apply-failure domain opts local plan point e))
              (catch Exception e
                (apply-failure domain opts local plan point e)))))))))

(defn list-backups
  "Public backup metadata from the target.  A listing failure throws; it is
  never reported as an empty list."
  [target]
  (port/list-objects target))

(defn verify-backup
  [target backup-id opts]
  (let [opened (open-backup target backup-id (:password opts))]
    {:ok? true
     :backup (backup-metadata opened)
     :manifest (:manifest opened)}))

(defn list-recovery-points
  [recovery-store]
  (port/list-recovery-points recovery-store))

(defn read-recovery-point
  [recovery-store id]
  (port/read-recovery-point recovery-store id))

(defn delete-recovery-point!
  [recovery-store id]
  (port/delete-recovery-point! recovery-store id))

(defn prune-recovery-points!
  "Delete only the successful local points outside the 7+4+3 set.  The point a
  running restore depends on is passed as `:current-id` and never deleted."
  [recovery-store opts]
  (let [points (port/list-recovery-points recovery-store)
        retention (recovery/plan-retention points opts)
        results (mapv (fn [id]
                        (try
                          (port/delete-recovery-point! recovery-store id)
                          {:id id :status :deleted}
                          (catch Exception e
                            {:id id :status :failed :error (err/summary e)})))
                      (:delete-ids retention))]
    {:keep-ids (:keep-ids retention)
     :deleted results
     :policy (:policy retention)}))

(defn restore-recovery-point!
  "Restore a local recovery point.  A fresh recovery point protects the
  current state first.  External authority is still not revived: the restored
  state is applied through the same safe permission policy."
  [domain recovery-store id opts]
  (let [point (port/read-recovery-point recovery-store id)]
    (when-not point
      (err/fail! :BACKUP_NOT_FOUND
                 "Recovery point does not exist"
                 {:id id}))
    (let [state (model/normalize-state (:state point))
          scope (or (seq (:scope point)) model/category-order)
          scope-set (set scope)
          now (now-string opts)
          guard (try
                  (recovery-point domain recovery-store
                                  (assoc opts :now now)
                                  :before-recovery-point-restore
                                  (keys (:categories state)))
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :BACKUP_STORAGE_FULL (err/code e))
                      (throw e)
                      (err/fail! :BACKUP_RECOVERY_POINT_FAILED
                                 "Could not protect the current state before restoring a recovery point"
                                 {:cause-code (err/code e)}
                                 e))))
          plan {:backup-id id
                :mode :replace
                :apply-categories (select-keys (:categories state) scope)
                :attachments (when (contains? scope-set :conversations)
                               (:attachments state))
                :imports (:imports state)
                :permissions (model/safe-permission-map nil)
                :summary {:local 0 :imported 0 :added 0 :replaced 0 :removed 0
                          :skipped 0 :conflicts 0 :expired-skipped 0
                          :memory-pending 0}}]
      (try
        (let [applied (port/apply-restore! domain plan)]
          (if (= :failed (:status applied))
            {:status :failed
             :recovery-point guard
             :restored-from id
             :error {:code :BACKUP_RESTORE_FAILED
                     :message "Storage port reported a failed restore"
                     :data applied}
             :needs-recovery? true}
            {:status :complete
             :recovery-point guard
             :restored-from id
             :applied applied}))
        (catch Exception e
          {:status :failed
           :recovery-point guard
           :restored-from id
           :error {:code (err/code e)
                   :message (.getMessage ^Throwable e)
                   :data (ex-data e)}
           :needs-recovery? true})))))
