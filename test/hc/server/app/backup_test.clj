(ns hc.server.app.backup-test
  "Backup target configuration commands: save/test/delete through
  `hc.server.backup.service/handle!` with a real store, a memory settings
  persistence and file targets."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.service :as backup-service]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

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
  `(let [~sym (str (Files/createTempDirectory "hc-backup-app-test-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree ~sym)))))

(defn- open-store
  [path]
  (storage/open-store {:path path
                       :create? true
                       :durability :sync
                       :mapsize-mib 32}))

(defn- config-store
  [persistence]
  {:read (fn [] (ports/read-config persistence))
   :commit! (fn [expected-revision doc]
              (ports/commit-config! persistence expected-revision doc))})

(defn- stored-config
  [persistence]
  (get-in (ports/memory-state persistence) [:config]))

(defn- targets-doc
  [persistence]
  (get-in (stored-config persistence) [:backup :targets]))

(defn- service-fixture
  "Build `[service persistence store]` over one temp root."
  [root]
  (let [store (open-store (str root "/store"))
        persistence (ports/memory-persistence
                     {:config {:revision 0
                               :roles {:admin #{"ops"}}
                               :backup {:default-target-id :local}}})]
    [store persistence
     (backup-service/service
      {:store store
       :data-dir (str root "/data")
       :config-store (config-store persistence)
       :target-prober (fn [kind _config _data-dir]
                        {:ok? true
                         :status 200
                         :detail (str "probe " kind)
                         :url "https://example.invalid/"})})]))

(deftest save-target-persists-and-status-reflects
  (with-temp-dir [root]
    (let [[store persistence service] (service-fixture root)]
      (try
        (testing "save writes the settings document and status sees it"
          (let [saved (backup-service/handle!
                       service
                       {:kind :backup/save-target
                        :config {:kind :webdav
                                 :remote-url "https://dav.example.com/base"
                                 :directory "backups"
                                 :username "u"
                                 :password "s3cr3t"}
                        :default? true})]
            (is (true? (:ok? saved)))
            (is (= :accepted (get-in saved [:receipt :status])))
            (is (= :webdav (get-in saved [:receipt :target-id])))
            (is (= :webdav (get-in saved [:receipt :kind])))
            (is (true? (get-in saved [:receipt :persisted?])))
            (is (= :webdav (get-in saved [:receipt :default-target-id])))
            (let [doc (targets-doc persistence)]
              (is (= "https://dav.example.com/base"
                     (get-in doc [:webdav :base-url])))
              (is (= "backups" (get-in doc [:webdav :directory])))
              (is (= "s3cr3t" (get-in doc [:webdav :password])))
              (is (= :webdav
                     (get-in (stored-config persistence)
                             [:backup :default-target-id]))))
            (let [status (backup-service/status service {})]
              ;; The saved WebDAV target cannot be reached from the test
              ;; host, so status reports a listing error; the receipt must
              ;; still carry the configured targets.
              (is (seq (get-in status [:receipt :targets])))
              (is (= :webdav (get-in status [:receipt :default-target-id])))
              (is (= #{:local :webdav}
                     (set (map :id (get-in status [:receipt :targets])))))))
        (testing "a second webdav save derives webdav-2"
          (let [saved (backup-service/handle!
                       service
                       {:kind :backup/save-target
                        :config {:kind :webdav :base-url "https://d2.example.com"}})]
            (is (= :webdav-2 (get-in saved [:receipt :target-id])))))
        (testing "other settings sections survive target writes"
          (is (= {:admin #{"ops"}}
                 (get-in (stored-config persistence) [:roles])))))
        (finally
          (storage/close-store! store))))))

(deftest save-target-retains-blank-secrets
  (with-temp-dir [root]
    (let [[store persistence service] (service-fixture root)]
      (try
        (backup-service/handle!
         service
         {:kind :backup/save-target
          :config {:kind :webdav :base-url "https://dav.example.com"
                   :password "old-pass"}})
        (let [edited (backup-service/handle!
                      service
                      {:kind :backup/save-target
                       :target-id :webdav
                       :config {:kind :webdav :base-url "https://dav.example.com"
                                :username "u2"
                                :password "  "}})]
          (is (true? (:ok? edited)))
          (is (= "old-pass" (get-in (targets-doc persistence)
                                    [:webdav :password]))))
        (testing "s3 secret aliases normalize to :secret-key"
          (backup-service/handle!
           service
           {:kind :backup/save-target
            :config {:kind :s3 :endpoint "https://s3.example.com"
                     :bucket "b" :region "us-east-1"
                     :access-key "AK" :secret "shh"}})
          (is (= "shh" (get-in (targets-doc persistence)
                               [:s3 :secret-key]))))
        (finally
          (storage/close-store! store))))))

(deftest delete-target-removes-and-reselects-default
  (with-temp-dir [root]
    (let [[store persistence service] (service-fixture root)]
      (try
        (doseq [id [:a :b]]
          (backup-service/handle!
           service
           {:kind :backup/save-target
            :target-id id
            :config {:kind :file
                     :root (str root "/t" (name id))}
            :default? (= :b id)}))
        (is (= :b (get-in (backup-service/status service {})
                          [:receipt :default-target-id])))
        (let [deleted (backup-service/handle!
                       service {:kind :backup/delete-target :target-id :b})]
          (is (true? (:ok? deleted)))
          (is (true? (get-in deleted [:receipt :deleted?])))
          (is (= :a (get-in deleted [:receipt :default-target-id])))
          (is (nil? (get-in (targets-doc persistence) [:b]))))
        (is (= :BACKUP_TARGET_UNKNOWN
               (get-in (backup-service/handle!
                        service {:kind :backup/delete-target :target-id :b})
                       [:error :code])))
        (testing "deleting the last target clears the default"
          (backup-service/handle!
           service {:kind :backup/delete-target :target-id :a})
          (is (= #{:local}
                 (set (map :id
                           (get-in (backup-service/status service {})
                                   [:receipt :targets]))))))
        (finally
          (storage/close-store! store))))))

(deftest test-target-covers-config-and-stored-id
  (with-temp-dir [root]
    (let [[store persistence service] (service-fixture root)]
      (try
        (backup-service/handle!
         service
         {:kind :backup/save-target
          :target-id :s3-test
          :config {:kind :s3 :endpoint "https://s3.example.com"
                   :bucket "b" :region "r" :access-key "AK" :secret "shh"}})
        (testing "unsaved form config probes without persisting"
          (let [result (backup-service/handle!
                        service
                        {:kind :backup/test-target
                         :command-id "cmd-1"
                         :config {:kind :webdav
                                  :remote-url "https://dav.example.com"}})]
            (is (true? (:ok? result)))
            (is (= :complete (get-in result [:receipt :status])))
            (is (= "cmd-1" (get-in result [:receipt :command-id])))
            (is (true? (get-in result [:receipt :probe :ok?])))
            (is (= :webdav (get-in result [:receipt :probe :kind])))
            (is (nil? (get-in result [:receipt :probe :target-id])))
            (is (= "probe :webdav"
                   (get-in result [:receipt :probe :detail])))))
        (testing "stored id probes the persisted config"
          (let [result (backup-service/handle!
                        service {:kind :backup/test-target
                                 :target-id :s3-test})]
            (is (true? (:ok? result)))
            (is (= :s3 (get-in result [:receipt :probe :kind])))
            (is (= :s3-test (get-in result [:receipt :probe :target-id])))))
        (testing "probe failure is still a successful command"
          (let [failing (backup-service/service
                         {:store store
                          :data-dir (str root "/data2")
                          :config-store (config-store persistence)
                          :target-prober (fn [_kind _config _dir]
                                           {:ok? false :status 403
                                            :detail "denied"
                                            :url "https://x"})})
                result (backup-service/handle!
                        failing {:kind :backup/test-target
                                 :config {:kind :s3 :endpoint "https://s3.example.com"
                                          :bucket "b" :region "us-east-1"
                                          :access-key "AK" :secret "SK"}})]
            (is (true? (:ok? result)))
            (is (= :complete (get-in result [:receipt :status])))
            (is (false? (get-in result [:receipt :probe :ok?])))
            (is (= 403 (get-in result [:receipt :probe :status])))))
        (testing "unknown stored id fails the command"
          (is (= :BACKUP_TARGET_UNKNOWN
                 (get-in (backup-service/handle!
                          service {:kind :backup/test-target
                                   :target-id :nope})
                         [:error :code]))))
        (finally
          (storage/close-store! store))))))
