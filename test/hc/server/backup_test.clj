(ns hc.server.backup-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.backup :as backup]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.port :as port]
            [hc.server.backup.support :as support])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(def ^:private password
  "correct horse battery staple")

(def ^:private fast
  {:kdf-iterations 10000})

(def ^:private fixed-now
  "2026-11-03T00:00:00Z")

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
  `(let [~sym (str (Files/createTempDirectory "hc-backup-test-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree ~sym)))))

(defn- create-backup!
  [target source]
  (backup/backup! (support/domain source) target
                  (merge fast
                         {:password password
                          :now fixed-now
                          :app-version "test"
                          :device "test-device"})))

(defn- state-of
  [domain]
  @(:state domain))

(defn- items
  [state category]
  (get-in state [:categories category]))

(defn- by-id
  [coll id]
  (first (filter #(= id (:id %)) coll)))

(defn- imported?
  [item]
  (contains? item :imported-from))

(deftest backup-is-coherent-and-self-describing
  (let [state (atom (support/backup-state))
        domain (support/->MemoryDomain state)
        target (support/target)]
    (swap! state assoc :on-snapshot
           (fn [] (support/add-late-item! state)))
    (let [result (create-backup! target @state)
          opened (backup/open-backup target (get-in result [:backup :id]) password)
          payload (:payload opened)
          conversations (get-in payload [:categories :conversations :items])]
      (is (= :complete (:status result)))
      (is (= fixed-now (get-in result [:backup :sampled-at])))
      (is (= fixed-now (get-in result [:manifest :sampled-at])))
      (testing "changes after the sampling instant are not in the backup"
        (is (some #(= "c-late" (:id %)) (items @state :conversations)))
        (is (not-any? #(= "c-late" (:id %)) conversations)))
      (testing "temporary sessions and rebuildable cache are excluded"
        (is (not-any? #(= "c-temp" (:id %)) conversations))
        (is (not-any? #(= "c-cache" (:id %)) conversations))
        (is (some #(= :temporary (:reason %)) (get-in result [:manifest :exclusions])))
        (is (some #(= :cache (:reason %)) (get-in result [:manifest :exclusions]))))
      (testing "the missing attachment is listed, not left as a dangling reference"
        (let [c-new (by-id conversations "c-new")]
          (is (= ["att-new"] (:unavailable-attachment-ids c-new)))
          (is (empty? (:attachment-ids c-new))))
        (is (= 1 (count (get-in result [:manifest :incomplete-attachments])))))
      (testing "secrets are excluded by default"
        (is (false? (get-in result [:manifest :secrets :included?])))
        (is (not-any? #(contains? % :api-key)
                      (get-in payload [:categories :connections :items]))))
      (testing "the manifest counts what the payload contains"
        (is (= 2 (get-in result [:manifest :categories :conversations :count])))
        (is (= 1 (get-in result [:manifest :attachments :count])))
        (is (= 1 (count (backup/list-backups target))))
        (is (= :verified (:integrity (first (backup/list-backups target)))))))))

(deftest wrong-password-does-not-mutate-local-data
  (let [source (support/backup-state)
        target (support/target)
        result (create-backup! target source)
        domain (support/domain (support/local-state))
        before (state-of domain)]
    (is (= :BACKUP_WRONG_PASSWORD
           (support/thrown-code
            #(backup/preview-restore domain target
                                     (get-in result [:backup :id])
                                     {:password "wrong horse battery staple"}))))
    (let [safe (backup/preview domain target (get-in result [:backup :id])
                               {:password "wrong horse battery staple"})]
      (is (false? (:ok? safe)))
      (is (= :BACKUP_WRONG_PASSWORD (get-in safe [:error :code]))))
    (is (= before (state-of domain)))))

(deftest merge-preview-and-restore-protect-local-decisions
  (let [target (support/target)
        result (create-backup! target (support/backup-state))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))]
    (testing "preview names merge, conflicts, missing relations and the recovery point"
      (let [preview (backup/preview-restore domain target backup-id
                                            (merge fast {:password password
                                                         :mode :merge}))]
        (is (true? (:ok? preview)))
        (is (= :merge (:mode preview)))
        (is (true? (get-in preview [:recovery-point :required?])))
        (is (pos? (count (:conflicts preview))))
        (is (every? (fn [conflict]
                      (and (not (contains? (:local conflict) :api-key))
                           (not (contains? (:local conflict) :api_key))
                           (not (contains? (:imported conflict) :api-key))
                           (not (contains? (:imported conflict) :api_key))))
                    (:conflicts preview))
            "conflict previews never carry credentials")
        (is (some #(= :memories (:category %)) (:memory-reactivation preview)))
        (is (some #(= :assistant (:missing %)) (:missing-relations preview)))
        (is (some #(= :workspace (:missing %)) (:missing-relations preview)))
        (is (= 1 (count (:placeholders preview))))))
    (testing "restore creates a recovery point before applying"
      (with-temp-dir [root]
        (let [recovery (file-recovery/store root)
              restored (backup/restore! domain target recovery backup-id
                                        (merge fast {:password password
                                                     :mode :merge}))
              state (state-of domain)
              conversations (items state :conversations)
              assistants (items state :assistants)
              worldbooks (items state :worldbooks)
              memories (items state :memories)
              schedules (items state :schedules)
              tools (items state :tools)
              skills (items state :skills)
              connections (items state :connections)
              workspaces (items state :workspaces)]
          (is (= :complete (:status restored)))
          (is (= 1 (count (port/list-recovery-points recovery))))
          (is (some? (:recovery-point restored)))
          (is (some #(= "c-new" (:id %)) conversations))
          (is (some #(= "c-temp" (:id %)) conversations))
          (is (some #(and (= "a1" (get-in % [:imported-from :source-id]))
                          (str/includes? (:name %) "来自备份"))
                    assistants))
          (is (some #(= "a-new" (:id %)) assistants))
          (is (some #(and (= "wb1" (get-in % [:imported-from :source-id]))
                          (str/includes? (:name %) "来自备份"))
                    worldbooks))
          (is (every? #(false? (:enabled? %)) memories))
          (is (some #(true? (:pending-review? %)) memories))
          (is (every? #(false? (:enabled? %)) (filter imported? schedules)))
          (is (every? #(false? (:enabled? %)) (filter imported? tools)))
          (is (every? #(false? (:enabled? %)) (filter imported? skills)))
          (is (every? #(false? (:verified? %)) (filter imported? connections)))
          (is (every? #(true? (:relink-required? %))
                      (filter imported? workspaces)))
          (is (true? (:workspace-relink-required?
                      (by-id conversations "c-new"))))
          (testing "second import of the same backup is idempotent"
            (let [again (backup/restore! domain target recovery backup-id
                                         (merge fast {:password password
                                                      :mode :merge}))]
              (is (= :already-imported (:status again)))
              (is (pos? (get-in again [:summary :skipped]))
                  "a repeated import reports the skipped count")
              (is (= 1 (count (port/list-recovery-points recovery))))))
          (testing "without the ledger, source equality and provenance still skip"
            (swap! (:state domain) update :imports dissoc backup-id)
            (let [before (state-of domain)
                  again (backup/restore! domain target recovery backup-id
                                         (merge fast {:password password
                                                      :mode :merge}))]
              (is (= :no-changes (:status again)))
              (is (= before (state-of domain))))))))))

(deftest replace-changes-only-selected-categories
  (let [target (support/target)
        result (create-backup! target (support/backup-state))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))]
    (with-temp-dir [root]
      (let [recovery (file-recovery/store root)
            restored (backup/restore! domain target recovery backup-id
                                      (merge fast {:password password
                                                   :mode :replace
                                                   :categories [:conversations :memories]}))
            state (state-of domain)
            conversations (items state :conversations)
            assistants (items state :assistants)
            memories (items state :memories)
            worldbooks (items state :worldbooks)]
        (is (= :complete (:status restored)))
        (is (some #(= "c-new" (:id %)) conversations))
        (is (not-any? #(= "c-temp" (:id %)) conversations))
        (is (not-any? #(= "c-deleted" (:id %)) conversations))
        (testing "unselected assistants keep their local content"
          (is (= "v1" (:content (by-id assistants "a1"))))
          (is (some #(= "a-new" (:id %)) assistants)))
        (testing "unselected worldbooks are untouched"
          (is (= "local lore" (:content (by-id worldbooks "wb1")))))
        (testing "memories are restored disabled until explicitly confirmed"
          (is (every? #(false? (:enabled? %)) memories))
          (is (every? #(true? (:pending-review? %)) memories)))
        (testing "the recovery point covers the changed categories"
          (let [points (port/list-recovery-points recovery)]
            (is (= 1 (count points)))
            (is (some #{:conversations} (:scope (first points))))
            (is (some #{:memories} (:scope (first points))))))))))

(deftest replace-memory-reactivation-requires-explicit-confirmation
  (let [target (support/target)
        result (create-backup! target (support/backup-state))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))]
    (with-temp-dir [root]
      (let [recovery (file-recovery/store root)]
        (backup/restore! domain target recovery backup-id
                         (merge fast {:password password
                                      :mode :replace
                                      :categories [:memories]
                                      :confirm-memory-reactivation? true}))
        (is (every? #(true? (:enabled? %)) (items (state-of domain) :memories)))
        (is (every? #(false? (:pending-review? %)) (items (state-of domain) :memories)))))))

(deftest include-deleted-records-the-count-and-keeps-the-deleted-area
  (let [source (update-in (support/backup-state) [:categories :conversations]
                          conj {:id "c-old"
                                :title "Old deleted"
                                :deleted-at "2026-01-01T00:00:00Z"
                                :messages []})
        target (support/target)
        result (backup/backup! (support/domain source) target
                               (merge fast {:password password
                                            :now fixed-now
                                            :app-version "test"
                                            :device "test-device"
                                            :include-deleted? true}))
        backup-id (get-in result [:backup :id])
        opened (backup/open-backup target backup-id password)
        conversations (get-in opened [:payload :categories :conversations :items])]
    (testing "the advanced scope includes the deleted conversation"
      (is (some #(= "c-old" (:id %)) conversations))
      (is (= 1 (:deleted-included (:manifest result)))))
    (testing "a restore keeps it in the deleted area"
      (let [domain (support/domain (support/local-state))]
        (with-temp-dir [root]
          (let [recovery (file-recovery/store root)
                restored (backup/restore! domain target recovery backup-id
                                          (merge fast {:password password
                                                       :mode :merge
                                                       :categories [:conversations]
                                                       :include-expired? true}))
                restored-deleted (by-id (items (state-of domain) :conversations)
                                        "c-old")]
            (is (= :complete (:status restored)))
            (is (= "2026-01-01T00:00:00Z" (:deleted-at restored-deleted)))
            (is (nil? (:enabled? restored-deleted)))))))))

(deftest repeated-replace-of-an-unchanged-backup-is-a-no-op
  (let [target (support/target)
        result (create-backup! target (support/backup-state))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))]
    (with-temp-dir [root]
      (let [recovery (file-recovery/store root)
            opts (merge fast {:password password
                              :mode :replace
                              :categories [:conversations :memories]})
            first-result (backup/restore! domain target recovery backup-id opts)
            points-after-first (count (port/list-recovery-points recovery))
            second-result (backup/restore! domain target recovery backup-id opts)
            points-after-second (count (port/list-recovery-points recovery))]
        (is (= :complete (:status first-result)))
        (testing "the same backup and categories are recognised as imported"
          (is (contains? #{:already-imported :no-changes}
                         (:status second-result)))
          (is (nil? (:recovery-point second-result))))
        (testing "a no-op replace creates no recovery point"
          (is (pos? points-after-first))
          (is (= points-after-first points-after-second)))
        (testing "the content is still present once"
          (is (= 1 (count (filter #(= "c-new" (:id %))
                                  (items (state-of domain) :conversations))))))))))

(deftest recovery-point-failure-stops-the-restore
  (let [target (support/target)
        result (create-backup! target (support/backup-state))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))
        before (state-of domain)
        recovery (support/failing-recovery-store)]
    (is (= :BACKUP_STORAGE_FULL
           (support/thrown-code
            #(backup/restore! domain target recovery backup-id
                              (merge fast {:password password
                                           :mode :merge})))))
    (is (= before (state-of domain)))))

(deftest local-file-target-roundtrip
  (with-temp-dir [root]
    (let [target (file-target/store root)
          result (create-backup! target (support/backup-state))
          backup-id (get-in result [:backup :id])
          listed (backup/list-backups target)
          domain (support/domain (support/local-state))
          recovery (file-recovery/store (str root "-recovery"))]
      (try
        (is (= 1 (count listed)))
        (is (= :verified (:integrity (first listed))))
        (let [verified (backup/verify-backup target backup-id {:password password})]
          (is (true? (:ok? verified))))
        (let [preview (backup/preview-restore domain target backup-id
                                              (merge fast {:password password
                                                           :mode :merge}))]
          (is (true? (:ok? preview))))
        (let [restored (backup/restore! domain target recovery backup-id
                                        (merge fast {:password password
                                                     :mode :merge}))]
          (is (= :complete (:status restored)))
          (is (some #(= "c-new" (:id %))
                    (items (state-of domain) :conversations))))
        (finally
          (delete-tree (str root "-recovery")))))))

(deftest attachment-size-limits-stop-before-packaging
  (let [target (support/target)
        domain (support/domain (support/backup-state))]
    (is (= :BACKUP_ATTACHMENT_TOO_LARGE
           (support/thrown-code
            #(backup/backup! domain target
                              (merge fast {:password password
                                           :now fixed-now
                                           :max-attachment-bytes 4})))))
    (is (= :BACKUP_ATTACHMENTS_TOO_LARGE
           (support/thrown-code
            #(backup/backup! domain target
                              (merge fast {:password password
                                           :now fixed-now
                                           :max-attachment-total-bytes 4})))))
    (is (empty? @(:objects target))
        "a refused backup writes no container")))

(deftest plaintext-secrets-need-explicit-confirmation
  (let [target (support/target)
        domain (support/domain (support/backup-state))]
    (is (= :BACKUP_PLAINTEXT_SECRETS_UNCONFIRMED
           (support/thrown-code
            #(backup/backup! domain target
                              (merge fast {:encrypt? false
                                           :include-secrets? true
                                           :now fixed-now})))))
    (let [result (backup/backup! domain target
                                 (merge fast {:encrypt? false
                                              :include-secrets? true
                                              :confirm-plaintext-secrets? true
                                              :now fixed-now}))
          opened (backup/open-backup target (get-in result [:backup :id]) nil)
          connection (by-id (get-in opened [:payload :categories :connections :items])
                            "conn1")]
      (is (= "backup-secret" (:api-key connection)))
      (is (= :none (get-in result [:backup :encryption]))))))

(deftest expired-deleted-records-are-skipped-by-default
  (let [source (update-in (support/backup-state)
                          [:categories :conversations]
                          conj {:id "c-expired"
                                :title "Expired deleted"
                                :deleted-at "2026-01-01T00:00:00Z"
                                :messages []})
        target (support/target)
        result (backup/backup! (support/domain source) target
                               (merge fast {:password password
                                            :include-deleted? true
                                            :now fixed-now}))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))
        preview (backup/preview-restore domain target backup-id
                                        (merge fast {:password password
                                                     :mode :merge}))]
    (is (some #(= "c-expired" (:id %)) (:expired-skipped preview)))
    (with-temp-dir [root]
      (let [recovery (file-recovery/store root)]
        (backup/restore! domain target recovery backup-id
                         (merge fast {:password password
                                      :mode :merge}))
        (is (not-any? #(= "c-expired" (:id %))
                      (items (state-of domain) :conversations)))))))

(deftest a-recovery-point-can-roll-back-a-restore
  (let [target (support/target)
        result (create-backup! target (support/backup-state))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))]
    (with-temp-dir [root]
      (let [recovery (file-recovery/store root)
            restored (backup/restore! domain target recovery backup-id
                                      (merge fast {:password password
                                                   :mode :merge}))
            point (first (port/list-recovery-points recovery))]
        (is (= :complete (:status restored)))
        (is (some #(= "c-new" (:id %))
                  (items (state-of domain) :conversations)))
        (let [rolled-back (backup/restore-recovery-point!
                           domain recovery (:id point) {})]
          (is (= :complete (:status rolled-back)))
          (is (not-any? #(= "c-new" (:id %))
                        (items (state-of domain) :conversations)))
          (is (= 2 (count (port/list-recovery-points recovery)))))))))

(deftest unverifiable-write-is-marked-needs-verification
  (let [base (support/target)
        target (reify port/TargetPort
                 (put-object! [_ object] (port/put-object! base object))
                 (get-object [_ id] (port/get-object base id))
                 (delete-object! [_ id] (port/delete-object! base id))
                 (list-objects [_] (port/list-objects base))
                 (verify-object [_ _ _]
                   (throw (ex-info "target could not confirm the write"
                                   {:error :BACKUP_INTEGRITY}))))
        result (backup/backup! (support/domain (support/backup-state)) target
                               (merge fast {:password password
                                            :now fixed-now}))]
    (is (= :needs-verification (:status result)))
    (is (= :BACKUP_INTEGRITY (get-in result [:verification :error :code])))
    (is (some? (get-in result [:backup :id])))))

(deftest placeholder-assistant-keeps-the-backup-display-name
  (let [source (update-in (support/backup-state)
                          [:categories :assistants]
                          conj {:id "a-new" :name "Research Helper"
                                :content "placeholder source"})
        target (support/target)
        result (create-backup! target source)
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))]
    (with-temp-dir [root]
      (let [recovery (file-recovery/store root)
            restored (backup/restore! domain target recovery backup-id
                                      (merge fast {:password password
                                                   :mode :merge
                                                   :categories [:conversations]}))]
        (is (= :complete (:status restored)))
        (let [placeholder (first (filter #(= "a-new" (:original-assistant-id %))
                                         (items (state-of domain) :assistants)))]
          (is (some? placeholder))
          (is (= "Research Helper" (:name placeholder)))
          (is (string? (:source-note placeholder))))))))

(deftest secret-key-normalization-removes-camel-and-underscore-keys
  (let [state (assoc-in (support/local-state) [:categories :connections]
                        [{:id "conn1" :name "Normalization"
                          :apiKey "camel" :api_key "snake" :api-key "kebab"
                          :session-token "login"}])
        target (support/target)
        result (backup/backup! (support/domain state) target
                               (merge fast {:password password
                                            :categories [:connections]
                                            :now fixed-now}))
        opened (backup/open-backup target (get-in result [:backup :id]) password)
        connection (first (get-in opened [:payload :categories :connections :items]))]
    (is (nil? (:apiKey connection)))
    (is (nil? (:api_key connection)))
    (is (nil? (:api-key connection)))
    (is (nil? (:session-token connection))
        "a login session token is never exported, even when API keys are")))

(deftest retry-external-plan-runs-again-after-a-partial-import
  (let [target (support/target)
        result (create-backup! target (support/backup-state))
        backup-id (get-in result [:backup :id])
        domain (support/domain (support/local-state))]
    (with-temp-dir [root]
      (let [recovery (file-recovery/store root)]
        (is (= :complete
               (:status (backup/restore! domain target recovery backup-id
                                         (merge fast {:password password
                                                      :mode :merge})))))
        (swap! (:state domain)
               update-in [:imports backup-id] assoc :external-applied? false)
        (let [again (backup/restore! domain target recovery backup-id
                                     (merge fast {:password password
                                                  :mode :merge}))]
          (is (= :complete (:status again))
              "a ledger with external-applied? false is retryable, not already imported")
          (is (true? (:retry-external? again)))
          (is (true? (get-in (state-of domain)
                             [:imports backup-id :external-applied?]))))))))
