(ns hc.server.backup.storage-adapter-regressions-test
  "Regression tests for the backup adapter's derived-state rules.

  These use a real Datalevin store and real file target/recovery stores.  They
  cover the review findings that are invisible to the neutral in-memory
  fixtures: real memory status, message-version attachments, the actual
  sampling instant, restore-safety persistence and in-flight obligation
  fencing."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup :as backup]
            [hc.server.backup.file-recovery :as file-recovery]
            [hc.server.backup.file-target :as file-target]
            [hc.server.backup.storage-adapter :as adapter]
            [hc.server.blob-store :as blob-store]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(def ^:private password "correct horse battery staple")
(def ^:private fast {:kdf-iterations 10000})

(defmacro with-temp-dir
  [[sym] & body]
  `(let [~sym (str (Files/createTempDirectory "hc-adapter-regression-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally (support/delete-tree! ~sym)))))

(defn- mid [] (str (UUID/randomUUID)))

(defn- commit!
  [store partition put & [opts]]
  (storage/commit-migration!
   store
   (merge {:migration-id (mid)
           :partition partition
           :reason :test-seed
           :put put}
          opts)))

(defn- seed-session!
  [store session-id messages]
  (commit! store "sessions"
           [{:kind :session-record
             :id session-id
             :doc {:session-id session-id
                   :status :active
                   :title "Session"
                   :messages messages
                   :drafts {}
                   :revision 1}}
            {:kind :session-index
             :id "sessions"
             :doc {:id "sessions" :ids [session-id]}}])
  (commit! store (str "session/" session-id)
           [{:kind :owner-state
             :id session-id
             :doc {:session-id session-id
                   :messages messages
                   :drafts {}
                   :board {}}}]
           {:session-id session-id}))

(defn- memory-doc
  [id status]
  {:id id :status status :text (str "memory " id) :scope {:kind :global}})

(deftest real-memory-status-is-authoritative
  (with-temp-dir [root]
    (let [target (file-target/store (str root "/target"))
          recovery (file-recovery/store (str root "/recovery"))]
      (support/with-temp-store [source {}]
        (commit! source "materials"
                 [{:kind :materials-memory :id "mem1" :doc (memory-doc "mem1" :active)}])
        (let [domain (adapter/domain source {:quiescent? true})
              made (backup/backup! domain target
                                    (merge fast {:password password
                                                 :categories [:memories]
                                                 :now "2026-11-03T00:00:00Z"}))
              id (get-in made [:backup :id])]
          (testing "replace without confirmation imports a candidate, never active"
            (support/with-temp-store [empty-target {}]
              (let [domain (adapter/domain empty-target {:quiescent? true})
                    result (backup/restore! domain target recovery id
                                            (merge fast {:password password
                                                         :mode :replace
                                                         :no-prior-app-commands? true
                                                         :categories [:memories]}))
                    export (storage/export-state empty-target {:partition "materials"})
                    doc (:doc (first (:docs (get-in export [:partitions "materials"]))))]
                (is (= :complete (:status result)))
                (is (= :candidate (:status doc)))
                (is (nil? (:state (:recovery-point result))))
                (is (some? (:id (:recovery-point result))))
                (is (= :paused (get-in (adapter/restore-safety empty-target)
                                       [:permissions :schedules])))
                (is (false? (get-in (adapter/restore-safety empty-target)
                                    [:implicit-authority?]))))))
          (testing "merge keeps a local archived decision and adds a candidate"
            (support/with-temp-store [local {}]
              (commit! local "materials"
                       [{:kind :materials-memory :id "mem1"
                         :doc (memory-doc "mem1" :archived)}])
              (let [domain (adapter/domain local {:quiescent? true})
                    result (backup/restore! domain target recovery id
                                            (merge fast {:password password
                                                         :mode :merge
                                                         :no-prior-app-commands? true
                                                         :categories [:memories]}))
                    export (storage/export-state local {:partition "materials"})
                    docs (mapv :doc (get-in export [:partitions "materials" :docs]))
                    local-doc (first (filter #(= "mem1" (:id %)) docs))
                    copy (first (filter #(and (not= "mem1" (:id %))
                                              (= "mem1" (get-in % [:imported-from :source-id])))
                                        docs))]
                (is (= :complete (:status result)))
                (is (= :archived (:status local-doc)))
                (is (some? copy))
                (is (= :candidate (:status copy)))))))))))

(deftest message-version-attachments-are-packaged-and-restored
  (with-temp-dir [root]
    (let [target (file-target/store (str root "/target"))
          recovery (file-recovery/store (str root "/recovery"))
          source-blobs (str root "/source-blobs")
          target-blobs (str root "/target-blobs")
          bytes (.getBytes "version attachment" "UTF-8")
          blob (blob-store/put-bytes! source-blobs bytes)
          messages {"m1" {:message-id "m1"
                          :role :user
                          :attachments []
                          :versions [{:version-id "m1-v1"
                                      :content "old"
                                      :attachments [{:attachment-id "att-v"
                                                     :blob-id (:id blob)}]}]}}]
      (support/with-temp-store [source {}]
        (seed-session! source "s1" messages)
        (let [domain (adapter/domain source {:quiescent? true :blob-root source-blobs})
              made (backup/backup! domain target
                                    (merge fast {:password password
                                                 :categories [:conversations]
                                                 :now "2026-11-03T00:00:00Z"}))
              opened (backup/open-backup target (get-in made [:backup :id]) password)]
          (is (contains? (get-in opened [:payload :attachments]) "att-v")
              "a version-only attachment is packaged")
          (support/with-temp-store [restored {}]
            (let [domain (adapter/domain restored
                                         {:quiescent? true
                                          :blob-root target-blobs
                                          :workspace-store-root (str root "/workspaces")})]
              (backup/restore! domain target recovery (get-in made [:backup :id])
                               (merge fast {:password password
                                            :mode :replace
                                            :no-prior-app-commands? true
                                            :categories [:conversations]}))
              (let [export (storage/export-state restored {:partition "sessions"})
                    session (:doc (first (filter #(= :session-record (:kind %))
                                                 (get-in export
                                                         [:partitions "sessions" :docs]))))
                    ref (get-in session [:messages "m1" :versions 0 :attachments 0])
                    id (:blob-id ref)]
                (is (string? id))
                (is (= "version attachment"
                       (String. ^bytes (blob-store/read-bytes target-blobs id)
                                "UTF-8")))))))))))

(deftest sampling-uses-the-actual-read-instant
  (with-temp-dir [root]
    (let [target (file-target/store (str root "/target"))]
      (support/with-temp-store [source {:now-fn (fn [] "2026-11-03T00:00:05Z")}]
        (let [domain (adapter/domain source {:quiescent? true})
              made (backup/backup! domain target
                                    (merge fast {:password password
                                                 :categories [:conversations]
                                                 :now "2026-11-03T00:00:00Z"}))]
          (is (= "2026-11-03T00:00:05Z" (get-in made [:manifest :sampled-at])))
          (is (= "2026-11-03T00:00:05Z" (get-in made [:backup :sampled-at]))))))))

(deftest appearance-replace-removes-keys-absent-from-the-backup
  (with-temp-dir [root]
    (let [target (file-target/store (str root "/target"))
          recovery (file-recovery/store (str root "/recovery"))]
      (support/with-temp-store [source {}]
        (commit! source "config"
                 [{:kind :app-config :id "config"
                   :doc {:theme :light :assistants {} :connections {}}}])
        (let [domain (adapter/domain source {:quiescent? true})
              made (backup/backup! domain target
                                    (merge fast {:password password
                                                 :categories [:appearance]
                                                 :now "2026-11-03T00:00:00Z"}))]
          (support/with-temp-store [local {}]
            (commit! local "config"
                     [{:kind :app-config :id "config"
                       :doc {:theme :dark :font-size 110
                             :assistants {} :connections {}}}])
            (let [domain (adapter/domain local {:quiescent? true})]
              (backup/restore! domain target recovery (get-in made [:backup :id])
                               (merge fast {:password password
                                            :mode :replace
                                            :no-prior-app-commands? true
                                            :categories [:appearance]}))
              (let [export (storage/export-state local {:partition "config"})
                    config (:doc (first (get-in export [:partitions "config" :docs])))]
                (is (= :light (:theme config)))
                (is (nil? (:font-size config)))))))))))

(deftest restore-closes-pre-restore-action-obligations
  (with-temp-dir [root]
    (let [target (file-target/store (str root "/target"))
          recovery (file-recovery/store (str root "/recovery"))]
      (support/with-temp-store [source {}]
        (commit! source "materials"
                 [{:kind :materials-memory :id "mem1"
                   :doc (memory-doc "mem1" :active)}])
        (let [domain (adapter/domain source {:quiescent? true})
              made (backup/backup! domain target
                                    (merge fast {:password password
                                                 :categories [:memories]
                                                 :now "2026-11-03T00:00:00Z"}))]
          (support/with-temp-store [local {}]
            (commit! local "session/s1"
                     [{:kind :owner-state :id "s1"
                       :doc {:session-id "s1" :messages {} :drafts {} :board {}}}]
                     {:session-id "s1"})
            (storage/commit-migration!
             local
             {:migration-id "open-obligation"
              :partition "session/s1"
              :session-id "s1"
              :obligations
              {:put [{:obligation/id "report/r1"
                      :obligation/kind :report
                      :open? true
                      :phase :report-pending
                      :report {:report-id "r1" :parent-call-id "c1"}}]}})
            (let [domain (adapter/domain local {:quiescent? true})]
              (backup/restore! domain target recovery (get-in made [:backup :id])
                               (merge fast {:password password
                                            :mode :replace
                                            :no-prior-app-commands? true
                                            :categories [:memories]}))
              (let [export (storage/export-state local {:partition "session/s1"})
                    obligation (:doc (first (get-in export
                                                    [:partitions "session/s1"
                                                     :obligations])))]
                (is (false? (:open? obligation)))
                (is (= :cancelled (:phase obligation)))))))))))
