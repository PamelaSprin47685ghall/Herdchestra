(ns hc.server.app.restore-supervisor-integration-test
  "Real application smoke tests for the supervised maintenance path.

  These tests start the full `hc.server.app.system`, create a real encrypted
  backup through the live backup service, submit a maintenance command through
  the real router, wait for the process-level stop -> offline restore -> start
  cycle, and then verify the restarted system and its maintenance status.
  They use only temporary data and workspace directories."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app.backup :as backup-app]
            [hc.server.app.commands :as commands]
            [hc.server.app.config :as config]
            [hc.server.app.lifecycle-test-support :as lifecycle]
            [hc.server.app.restore-supervisor :as supervisor]
            [hc.server.app.system :as system]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.sessions.registry :as registry])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(def ^:private password "correct horse battery staple")

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write-file!
  [path content]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content)))

(defn- delete-tree!
  [path]
  (doseq [file (reverse (file-seq (io/file path)))]
    (try
      (.delete ^java.io.File file)
      (catch Exception _ nil))))

(defn- test-config
  []
  (let [root (temp-dir "hc-restore-root-")
        data (temp-dir "hc-restore-data-")
        static (temp-dir "hc-restore-static-")]
    (write-file! (str static "/index.html")
                 "<!doctype html><title>hc restore</title>")
    (config/normalize
     {:host "127.0.0.1"
      :port 0
      :data-dir data
      :workspace-root root
      :static-root static
      :blob-root (str data "/blobs")
      :worktree-root (str data "/worktrees")
      :artifact-root (str data "/artifacts")
      :storage {:path (str data "/db")}
      :log-level :warn
      :maintenance {:drain-timeout-ms 5000}
      :models {}})))

(defn- maintenance-handler
  []
  {:handle! (fn [payload] (supervisor/handle-command! payload))
   :status (fn [] (supervisor/status))})

(defn- start-app!
  [config handler]
  (supervisor/prepare! {:config config :maintenance handler})
  (let [app (system/start! config {:maintenance handler})]
    (when (:blocked? app)
      (throw (ex-info "application did not start"
                      (select-keys app [:error :hint :recovery-entry]))))
    (supervisor/install! app)
    (supervisor/startup!)
    app))

(defn- create-backup!
  [app]
  (let [result (backup-app/handle! (:backup app)
                                   {:kind :backup/create
                                    :password password
                                    :encrypt? true
                                    :categories [:conversations]})]
    (is (true? (:ok? result)) (pr-str result))
    (get-in result [:receipt :id])))

(defn- submit!
  [router request]
  (let [reply (promise)]
    (gateway-ports/submit-command! router request #(deliver reply %))
    (let [result (deref reply 30000 ::timeout)]
      (when (= ::timeout result)
        (throw (ex-info "command did not reply before the test deadline"
                        {:request request})))
      result)))

(defn- maintenance-request
  [maintenance-id backup-id command-epoch]
  {:command-id (str (UUID/randomUUID))
   :command-epoch command-epoch
   :payload {:kind :backup/maintenance-restore
             :maintenance-id maintenance-id
             :backup-id backup-id
             :mode :replace
             :categories [:conversations]
             :password password
             :confirm? true}})

(defn- preview-fingerprint
  [app payload]
  (let [result (backup-app/handle!
                (:backup app)
                (assoc (select-keys payload
                                    [:target-id :backup-id :mode :categories
                                     :password])
                       :kind :backup/preview))]
    (is (true? (:ok? result)) (pr-str result))
    (get-in result [:receipt :fingerprint])))

(defn- terminal?
  [state]
  (contains? #{:complete :failed :stop-failed :restart-failed
               :failed-needs-attention :failed-needs-recovery
               :failed-commit-unproven :failed-before-commit}
             (:status state)))

(defn- wait-terminal
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [status (supervisor/status)
            last (:last status)]
        (cond
          (and last (terminal? last))
          last

          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 50) (recur))

          :else
          (throw (ex-info "timed out waiting for maintenance"
                          {:status status})))))))

(defn- cleanup!
  [config]
  ;; Stop the live system through the confirmed path first.  The supervisor's
  ;; stop-current! clears its own state but swallows a failed stop; a leaked
  ;; store would then poison every later namespace.
  (when-let [app (supervisor/current-system)]
    (lifecycle/stop-system! app))
  (try
    (supervisor/stop-current!)
    (catch Throwable _ nil))
  (delete-tree! (:data-dir config))
  (delete-tree! (:workspace-root config))
  (delete-tree! (:static-root config)))

(deftest supervised-restore-restarts-and-client-can-verify
  (let [config (test-config)
        handler (maintenance-handler)]
    (try
      (let [app (start-app! config handler)
            backup-id (create-backup! app)
            maintenance-id (str (UUID/randomUUID))]
        (is (string? backup-id))
        (testing "a post-backup write gives the replace plan real work"
          (let [created (registry/create-session! (:registry app)
                                                  {:title "after-backup"})]
            (is (= :created (:status created)))))
        (let [reply (submit! (:router app)
                             (let [request
                                   (maintenance-request
                                    maintenance-id backup-id
                                    (commands/app-command-epoch (:router app)))]
                               (assoc-in request
                                         [:payload :preview-fingerprint]
                                         (preview-fingerprint app (:payload request)))))]
          (is (true? (:ok? reply)) (pr-str reply))
          (is (= :accepted (get-in reply [:receipt :status])))
          (is (= maintenance-id
                 (get-in reply [:receipt :maintenance-id]))
              "the accepted receipt carries the maintenance ID immediately"))
        (let [terminal (wait-terminal 60000)
              restarted (supervisor/current-system)]
          (is (= :complete (:status terminal)) (pr-str terminal))
          (is (string? (get-in terminal [:recovery-point :id])))
          (is (some? restarted))
          (is (string? (:url restarted)))
          (is (true? (:ok (system/health restarted))))
          (testing "the client reconnects and asks the restarted server"
            (let [status-reply
                  (submit! (:router restarted)
                           {:command-id (str (UUID/randomUUID))
                            :command-epoch
                            (commands/app-command-epoch (:router restarted))
                            :payload {:kind :backup/maintenance-status
                                      :maintenance-id maintenance-id}})]
              (is (true? (:ok? status-reply)) (pr-str status-reply))
              (is (= :complete (get-in status-reply [:receipt :status])))
              (is (true? (get-in status-reply [:receipt :observed-by-client?])))
              (is (= (get-in terminal [:recovery-point :id])
                     (get-in status-reply [:receipt :recovery-point :id])))))))
      (finally
        (cleanup! config)))))

(deftest wrong-password-never-stops-the-application
  (let [config (test-config)
        handler (maintenance-handler)]
    (try
      (let [app (start-app! config handler)
            backup-id (create-backup! app)
            maintenance-id (str (UUID/randomUUID))
            reply (submit! (:router app)
                           {:command-id (str (UUID/randomUUID))
                            :command-epoch
                            (commands/app-command-epoch (:router app))
                            :payload {:kind :backup/maintenance-restore
                                      :maintenance-id maintenance-id
                                      :backup-id backup-id
                                      :mode :merge
                                      :categories [:conversations]
                                      :password "wrong password value"
                                      :confirm? true}})]
        (is (true? (:ok? reply)) (pr-str reply))
        (is (= :accepted (get-in reply [:receipt :status])))
        (is (= maintenance-id (get-in reply [:receipt :maintenance-id])))
        (let [terminal (wait-terminal 30000)]
          (is (= :failed (:status terminal)) (pr-str terminal))
          (is (= :BACKUP_WRONG_PASSWORD (get-in terminal [:error :code])))
          (is (false? (:needs-attention? terminal)))
          (is (true? (:retryable? terminal)))
          (is (nil? (:restore terminal))
              "a failed preview must not reach the restore thunk"))
        (is (identical? app (supervisor/current-system)))
        (is (true? (:ok (system/health app))))
        (is (nil? (get-in (:last (supervisor/status)) [:restore]))))
      (finally
        (cleanup! config)))))

(deftest unconfirmed-stop-does-not-restore
  (let [config (test-config)
        handler (maintenance-handler)]
    (try
      (let [app (start-app! config handler)
            backup-id (create-backup! app)
            maintenance-id (str (UUID/randomUUID))]
        (with-redefs [system/stop! (fn [_]
                                     {:stopped? false
                                      :failures
                                      [{:component :test
                                        :error {:code :TEST_STOP}}]})]
          (let [reply (submit! (:router app)
                               (let [request
                                     (maintenance-request
                                      maintenance-id backup-id
                                      (commands/app-command-epoch (:router app)))]
                                 (assoc-in request
                                           [:payload :preview-fingerprint]
                                           (preview-fingerprint app (:payload request)))))]
            (is (true? (:ok? reply)) (pr-str reply))
            (let [terminal (wait-terminal 30000)]
              (is (= :stop-failed (:status terminal)) (pr-str terminal))
              (is (true? (:needs-attention? terminal)))
              (is (nil? (:restore terminal))
                  "restore must not run after an unconfirmed stop"))))
        (is (identical? app (supervisor/current-system))
            "the application is still live because stop was not confirmed")
        (is (true? (:ok (system/health app))))
        (commands/end-maintenance! (:router app)))
      (finally
        (cleanup! config)))))
