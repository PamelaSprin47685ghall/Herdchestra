(ns hc.server.backup.command-epoch-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup :as backup]
            [hc.server.backup.cli :as cli]
            [hc.server.backup.command-epoch :as command-epoch]
            [hc.server.backup.maintenance-journal :as journal]
            [hc.server.backup.offline :as offline]
            [hc.server.backup.storage-adapter :as storage-adapter]
            [hc.server.commands.durable :as durable]
            [hc.server.commands.storage :as command-storage]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

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
  `(let [~sym (str (Files/createTempDirectory "hc-command-epoch-test-"
                                               (make-array FileAttribute 0)))]
     (try
       ~@body
       (finally
         (delete-tree ~sym)))))

(defn- open-store
  [path]
  (storage/open-store {:path path :create? true :durability :sync}))

(defn- app-gate
  [store]
  (durable/gate (command-storage/store
                 store {:partition "commands/app" :id "app"})))

(defn- window
  [gate]
  (select-keys (durable/state gate) [:command-epoch :retired-through]))

(deftest rotation-is-above-pre-restore-and-restored-high-water
  (with-temp-dir [root]
    (let [db-path (str root "/db")
          store (open-store db-path)]
      (try
        (let [gate (app-gate store)]
          (durable/issue-epoch! gate)        ; epoch 1
          (durable/issue-epoch! gate)        ; epoch 2
          (let [window (window gate)
                accepted (durable/accept!
                          gate window
                          {:command-id "cmd-1"
                           :command-epoch (:command-epoch window)
                           :payload {:kind :settings/update}})]
            (is (true? (:ok? accepted)))
            (is (some? (durable/receipt-for gate "cmd-1")))))
        (finally (storage/close-store! store)))
      (testing "pre-restore epoch 10 fences restored epoch 2"
        (let [rotation (command-epoch/rotate-store!
                        db-path {:command-epoch 10 :retired-through 9})]
          (is (true? (:ok? rotation)))
          (is (> (:epoch rotation) 10))
          (is (>= (:retired-through rotation) 10))))
      (let [store (open-store db-path)]
        (try
          (let [gate (app-gate store)
                state (durable/state gate)]
            (is (> (:command-epoch state) 10))
            (is (>= (:retired-through state) 10))
            (is (empty? (:receipts state)))
            (is (nil? (durable/receipt-for gate "cmd-1")))
            (testing "the new epoch accepts a fresh command"
              (let [accepted (durable/accept!
                              gate (window gate)
                              {:command-id "cmd-2"
                               :command-epoch (:command-epoch state)
                               :payload {:kind :settings/update}})]
                (is (true? (:ok? accepted)))
                (is (some? (durable/receipt-for gate "cmd-2"))))))
          (finally (storage/close-store! store)))))))

(deftest rotation-also-advances-when-restored-window-is-higher
  (with-temp-dir [root]
    (let [db-path (str root "/db")
          store (open-store db-path)]
      (try
        (let [gate (app-gate store)]
          (dotimes [_ 5] (durable/issue-epoch! gate)))
        (finally (storage/close-store! store)))
      (let [rotation (command-epoch/rotate-store!
                      db-path {:command-epoch 2 :retired-through 1})]
        (is (true? (:ok? rotation)))
        (is (> (:epoch rotation) 5))
        (is (>= (:retired-through rotation) 5))))))

(deftest rotation-with-no-restored-window-starts-at-one
  (with-temp-dir [root]
    (let [db-path (str root "/db")
          store (open-store db-path)]
      (try (app-gate store)
           (finally (storage/close-store! store)))
      (let [rotation (command-epoch/rotate-store! db-path nil)]
        (is (true? (:ok? rotation)))
        (is (= 1 (:epoch rotation)))))))

(deftest corrupt-pre-restore-window-is-a-structured-failure
  (with-temp-dir [root]
    (let [db-path (str root "/db")
          store (open-store db-path)]
      (try (app-gate store)
           (finally (storage/close-store! store)))
      (let [rotation (command-epoch/rotate-store!
                      db-path {:command-epoch "not-an-epoch"
                               :retired-through 1})]
        (is (false? (:ok? rotation)))
        (is (= :COMMAND_EPOCH_WINDOW_INVALID
               (get-in rotation [:error :code]))))
      (let [rotation (command-epoch/rotate-store!
                      db-path {:command-epoch 0 :retired-through 0})]
        (is (false? (:ok? rotation)))
        (is (= :COMMAND_EPOCH_WINDOW_INVALID
               (get-in rotation [:error :code])))))))

(deftest offline-restore-fences-the-app-command-epoch
  (with-temp-dir [root]
    (let [db-path (str root "/db")
          opts {:db-path db-path
                :data-dir (str root "/data")
                :backup-id "backup-1"
                :mode :merge
                :categories [:conversations]}]
      (let [store (open-store db-path)]
        (try
          (let [gate (app-gate store)]
            (durable/issue-epoch! gate)
            (durable/issue-epoch! gate)
            (durable/issue-epoch! gate))
          (finally
            (storage/close-store! store))))
      (testing "a successful restore surfaces the applied fence"
        (with-redefs [backup/restore!
                      (fn [& _]
                        {:status :complete
                         :applied {:command-epoch 7
                                   :command-retired-through 6}})]
          (let [result (offline/restore! opts)]
            (is (= :complete (:status result)))
            (is (= 7 (:command-epoch result)))
            (is (= 6 (:command-retired-through result))))))
      (testing "a fence failure is a failed, needs-attention restore"
        (with-redefs [backup/restore!
                      (fn [& _]
                        {:status :failed
                         :needs-attention? true
                         :error {:code :BACKUP_COMMAND_EPOCH_WINDOW_INVALID
                                 :message "window is invalid"}})]
          (let [result (offline/restore! opts)]
            (is (= :failed (:status result)))
            (is (true? (:needs-attention? result)))
            (is (= :BACKUP_COMMAND_EPOCH_WINDOW_INVALID
                   (get-in result [:error :code])))
            (is (nil? (:command-epoch result))))))
      (testing "a no-op restore does not rotate"
        (with-redefs [backup/restore! (fn [& _] {:status :no-changes})]
          (let [result (offline/restore! opts)]
            (is (= :no-changes (:status result)))
            (is (nil? (:command-epoch result))))))
      (testing "a completed recovery-point rollback is fenced too"
        (with-redefs [backup/restore-recovery-point!
                      (fn [& _]
                        {:status :complete
                         :applied {:command-epoch 9
                                   :command-retired-through 8}})]
          (let [result (offline/restore-recovery-point!
                        {:db-path db-path
                         :data-dir (str root "/data")
                         :recovery-point-id "rp-1"})]
            (is (= :complete (:status result)))
            (is (= 9 (:command-epoch result)))
            (is (= 8 (:command-retired-through result)))))))))

(deftest fence-app-export-writes-the-new-window-in-the-restore-transaction
  (let [export {:partitions
                {"commands/app"
                 {:version 1
                  :docs [{:kind :owner-state
                          :id "app"
                          :session-id "app"
                          :doc {:command-epoch 3
                                :retired-through 2
                                :receipts {"cmd" 1}}}]}
                 "sessions"
                 {:version 1
                  :docs [{:kind :session-record
                          :id "s1"
                          :doc {:creation-command
                                {:scope "app"
                                 :command-epoch 5
                                 :command-id "cmd"}}}]}}}
        {:keys [export fence]} (#'storage-adapter/fence-app-export export true)
        state (-> export :partitions (get "commands/app") :docs first :doc)]
    (is (= {:command-epoch 6 :command-retired-through 5} fence))
    (is (= 6 (:command-epoch state)))
    (is (= 5 (:retired-through state)))
    (is (= {} (:receipts state)))
    (is (= {} (:receipt-index state))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"window is invalid"
       (#'storage-adapter/fence-app-export
        {:partitions
         {"commands/app"
          {:version 1
           :docs [{:kind :owner-state
                   :id "app"
                   :session-id "app"
                   :doc {:command-epoch 1
                         :retired-through 2}}]}}}
        true)))
  (let [missing (try
                  (#'storage-adapter/fence-app-export {:partitions {}} false)
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (= :BACKUP_COMMAND_EPOCH_WINDOW_MISSING
           (:error (ex-data missing)))))
  (let [{:keys [fence]} (#'storage-adapter/fence-app-export
                         {:partitions {}} true)]
    (is (= 1 (:command-epoch fence)))
    (is (= 0 (:command-retired-through fence)))))

(deftest offline-cli-does-not-report-a-failed-restore-as-success
  (with-temp-dir [root]
    (let [password-file (str root "/password")
          opts {:db-path (str root "/db")
                :data-dir (str root "/data")
                :backup-id "backup-1"
                :mode :merge
                :categories [:conversations]
                :password-file password-file}]
      (spit password-file "secret")
      (testing "a failed restore exits as a failure"
        (with-redefs [offline/restore!
                      (fn [_]
                        {:status :failed
                         :error {:code :BACKUP_RESTORE_FAILED
                                 :message "bad password"}})]
          (let [result (cli/run :restore opts)]
            (is (false? (:ok? result)))
            (is (= :BACKUP_RESTORE_FAILED (get-in result [:error :code])))
            (is (= :failed (get-in result [:receipt :status]))))))
      (testing "a complete restore is a success"
        (with-redefs [offline/restore! (fn [_] {:status :complete})]
          (is (true? (:ok? (cli/run :restore opts))))))
      (testing "a needs-attention restore is a failure even with :complete"
        (with-redefs [offline/restore!
                      (fn [_]
                        {:status :complete
                         :needs-attention? true
                         :error {:code :BACKUP_COMMAND_EPOCH_FENCE_FAILED
                                 :message "fence failed"}})]
          (let [result (cli/run :restore opts)]
            (is (false? (:ok? result)))
            (is (= :BACKUP_COMMAND_EPOCH_FENCE_FAILED
                   (get-in result [:error :code])))))))))

(deftest offline-restore-exception-leaves-a-failed-attention-journal
  (with-temp-dir [root]
    (let [db-path (str root "/db")
          data-dir (str root "/data")
          opts {:db-path db-path
                :data-dir data-dir
                :backup-id "backup-1"
                :mode :merge
                :categories [:conversations]}]
      (let [store (open-store db-path)]
        (storage/close-store! store))
      (with-redefs [backup/restore!
                    (fn [& _]
                      (throw (ex-info "boom" {:error :TEST_RESTORE_FAILED})))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"boom"
             (offline/restore! opts))))
      (let [state (:state (#'journal/read-journal {:data-dir data-dir}))]
        (is (= :failed-before-commit (:status state))
            "the store was observed unchanged before and after the throw")
        (is (true? (:needs-attention? state)))
        (is (= :TEST_RESTORE_FAILED (get-in state [:error :code])))
        (is (nil? (journal/startup-blocking-state {:data-dir data-dir}))
            "a proven pre-commit failure does not block the next start")))))
