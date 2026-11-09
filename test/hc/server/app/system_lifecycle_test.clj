(ns hc.server.app.system-lifecycle-test
  "Black-box lifecycle tests for `system/start!` and `system/stop!`.

  Every test starts the real application with temporary directories and a
  real Datalevin store.  Component stop functions are wrapped only to record
  their order; each wrapper calls the real function.

  The stop order is asserted as the relative sequence
  `supervisor -> background loops -> fleet -> owner -> store`, matching the
  current documented dependency order and the task template.

  The test never modifies `src/`.  A failure is either an assembly gap (a
  handle is nil or a component was never added to the partial system) or an
  implementation gap (the stop order or idempotence violates the contract);
  the assertion messages say which."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.git :as fleet-git]
            [hc.compose.fleet.service :as fleet]
            [hc.compose.fleet.support :as fleet-support]
            [hc.compose.supervisor :as supervisor]
            [hc.server.app.config :as config]
            [hc.server.app.fleet-gc :as fleet-gc]
            [hc.server.app.retention-gc :as retention-gc]
            [hc.server.app.steward-runtime :as steward-runtime]
            [hc.server.app.system :as system]
            [hc.server.history.index :as history-index]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as storage-support])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent CountDownLatch TimeUnit)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(declare io-make-parents)

(defn- write-file!
  [path content]
  (let [file (java.io.File. ^String path)]
    (io-make-parents file)
    (spit file content)))

(defn- io-make-parents
  [^java.io.File file]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent)))

(defn- test-config
  ([]
   (test-config nil))
  ([workspace-root]
   (let [root (or workspace-root (temp-dir "hc-life-root-"))
         data (temp-dir "hc-life-data-")
         static (temp-dir "hc-life-static-")]
     (write-file! (str static "/index.html") "<!doctype html><title>hc</title>")
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
       :models {}}))))

(defn- live-non-daemon-threads
  "Live non-daemon threads, excluding the Clojure agent pools.

  The pools are created on demand by `future`/agent use in the test runner
  and system startup.  They are shared JVM infrastructure, not application
  threads; counting them makes the leak assertion depend on test order.
  Application threads (http-kit, Datalevin, server-loop, ...) still count."
  []
  (filter (fn [^Thread thread]
            (and (.isAlive thread)
                 (not (.isDaemon thread))
                 (not (re-find #"^clojure-agent-(send-off|send)-pool-"
                               (.getName thread)))))
          (keys (Thread/getAllStackTraces))))

(defn- non-daemon-thread-count
  []
  (count (live-non-daemon-threads)))

(defn- non-daemon-thread-names
  []
  (->> (live-non-daemon-threads)
       (mapv #(.getName ^Thread %))
       sort))

(defn- await-until
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (cond
        (pred) true
        (< deadline (System/nanoTime)) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- contains-code?
  "True when CODE appears as a value anywhere in VALUE's nested data."
  [value code]
  (cond
    (= value code) true
    (map? value) (boolean (some #(contains-code? % code) (vals value)))
    (coll? value) (boolean (some #(contains-code? % code) value))
    :else false))

(defn- wrap-owners!
  "Replace each live Owner's :stop! with a recording wrapper."
  [sys order]
  (when-let [owners (:owners sys)]
    (swap! owners
           (fn [handles]
             (into {}
                   (map (fn [[session-id handle]]
                          [session-id
                           (if (fn? (:stop! handle))
                             (let [stop! (:stop! handle)]
                               (assoc handle
                                      :stop!
                                      (fn []
                                        (swap! order conj :owner)
                                        (stop!))))
                             handle)]))
                   handles)))))

(defn- stop-with-recording!
  "Call the real system/stop! while recording each wrapped component stop."
  [sys order]
  (let [real-supervisor-stop supervisor/stop!
        real-retention-stop retention-gc/stop!
        real-fleet-gc-stop fleet-gc/stop!
        real-fleet-stop fleet/stop!
        real-steward-stop steward-runtime/stop!
        real-close storage/open-close-store!]
    (with-redefs [supervisor/stop!
                  (fn
                    ([value]
                     (swap! order conj :supervisor)
                     (real-supervisor-stop value))
                    ([value options]
                     (swap! order conj :supervisor)
                     (real-supervisor-stop value options)))

                  retention-gc/stop!
                  (fn [service]
                    (swap! order conj :retention-gc)
                    (real-retention-stop service))

                  fleet-gc/stop!
                  (fn [runtime]
                    (swap! order conj :fleet-gc)
                    (real-fleet-gc-stop runtime))

                  fleet/stop!
                  (fn [service]
                    (swap! order conj :fleet)
                    (real-fleet-stop service))

                  steward-runtime/stop!
                  (fn [runtime]
                    (swap! order conj :steward)
                    (real-steward-stop runtime))

                  storage/open-close-store!
                  (fn [store options]
                    (swap! order conj :store)
                    (real-close store options))]
      (system/stop! sys))))

(defn- first-positions
  [order]
  (reduce (fn [positions [index component]]
            (if (contains? positions component)
              positions
              (assoc positions component index)))
          {}
          (map-indexed vector order)))

(defn- force-stop!
  "Release every handle even when the lifecycle assertion failed early."
  [sys]
  (doseq [stop! [(fn []
                   (when-let [value (:supervisor sys)]
                     (supervisor/stop! value {:timeout-ms 5000})))
                 (fn []
                   (when-let [value (or (:steward-runtime sys)
                                        (:steward sys))]
                     (steward-runtime/stop! value)))
                 (fn []
                   (when-let [value (:retention-gc sys)]
                     (retention-gc/stop! value)))
                 (fn []
                   (when-let [value (:fleet-gc sys)]
                     (fleet-gc/stop! value)))
                 (fn []
                   (when-let [value (:fleet sys)]
                     (fleet/stop! value)))
                 (fn []
                   (when-let [value (:store sys)]
                     (storage/close-store! value)))]]
    (try
      (stop!)
      (catch Throwable _
        nil))))

(defn- cleanup-config!
  [config]
  (try
    (storage-support/delete-tree! (:db-path config))
    (catch Throwable _
      nil)))

;; ---------------------------------------------------------------------------
;; Tests

(deftest full-system-exposes-handles-and-stops-in-dependency-order
  (let [root (temp-dir "hc-life-workspace-")
        git-available? (fleet-git/available? (fleet-git/git-cli "."))
        _ (when git-available?
            (fleet-support/init-repo! root))
        config (test-config root)
        before (non-daemon-thread-count)
        sys (system/start! config)
        events (atom [])]
    (try
      (is (false? (:blocked? sys)) (pr-str (:error sys)))
      (testing "non-nil process handles"
        (let [sup (:supervisor sys)]
          (is (some? sup) "the process supervisor handle is non-nil")
          (when sup
            (let [status (supervisor/status sup)]
              (is (true? (:running? status)))
              (is (map? (:children status))))))
        (is (some? (:retention-gc sys))
            "the retention scheduler handle is non-nil")
        (when-let [retention (:retention-gc sys)]
          (is (true? (:running? (retention-gc/status retention)))))
        (when git-available?
          (is (some? (:fleet sys))
              "a Git workspace assembles the fleet service")))
      (wrap-owners! sys events)
      (let [result (stop-with-recording! sys events)
            positions (first-positions @events)
            at (fn [component] (get positions component))]
        (println :system-stop-order @events)
        (is (true? (:stopped? result)) (pr-str result))
        (is (empty? (:failures result)) (pr-str (:failures result)))
        (is (true? (:store-closed? result)))
        (testing "relative stop order: supervisor -> loops -> fleet -> owner -> store"
          (when (at :supervisor)
            (doseq [component [:retention-gc :steward :fleet-gc
                               :fleet :owner :store]]
              (when (at component)
                (is (< (at :supervisor) (at component))
                    (str component " must stop after the process supervisor")))))
          (doseq [component [:retention-gc :steward :fleet-gc]]
            (when (and (at component) (at :fleet))
              (is (< (at component) (at :fleet))
                  (str component " must stop before the fleet service")))
            (when (and (at component) (at :owner))
              (is (< (at component) (at :owner))
                  (str component " must stop before Owners"))))
          (when (and (at :fleet) (at :owner))
            (is (< (at :fleet) (at :owner))
                "the task order is fleet before owners"))
          (when (at :store)
            (doseq [component (keys positions)]
              (when (not= component :store)
                (is (< (at component) (at :store))
                    (str component " must stop before the store closes"))))
            (is (= (apply max (vals positions)) (at :store))
                "the store close is the final recorded stop")))
        (testing "component status after stop"
          (when-let [sup (:supervisor sys)]
            (let [status (supervisor/status sup)]
              (is (false? (:running? status)))
              (is (true? (:stopped? status)))))
          (when-let [retention (:retention-gc sys)]
            (let [status (retention-gc/status retention)]
              (is (false? (:running? status)))
              (is (true? (:terminated? status)))))
          (when-let [gc (:fleet-gc sys)]
            (let [status (fleet-gc/status gc)]
              (is (false? (:running? status)))
              (is (true? (:terminated? status)))))
          (when-let [steward (or (:steward-runtime sys) (:steward sys))]
            (is (= :stopped
                   (:status (steward-runtime/health steward)))))))
      (Thread/sleep 250)
      (let [after (non-daemon-thread-count)]
        (println :non-daemon-threads {:before before :after after
                                       :after-names (non-daemon-thread-names)})
        (is (<= after (inc before))
            (str "non-daemon threads grew: before=" before
                 " after=" after
                 " (one-thread tolerance)")))
      (finally
        (force-stop! sys)
        (cleanup-config! config)))))

(deftest an-unconfirmed-component-stop-keeps-the-store-open
  (let [config (test-config)
        sys (system/start! config)
        retention (:retention-gc sys)
        real-stop retention-gc/stop!
        refusal {:ok? false
                 :error {:code :STOP_REFUSED
                         :message "the retention writer did not confirm termination"}}]
    (try
      (is (false? (:blocked? sys)) (pr-str (:error sys)))
      (is (some? retention)
          "the default configuration assembles the retention scheduler")
      (with-redefs [retention-gc/stop!
                    (fn [service]
                      ;; The real writer is released, but the returned
                      ;; evidence is deliberately unconfirmed.
                      (real-stop service)
                      refusal)]
        (let [result (system/stop! sys)]
          (println :unconfirmed-stop-failures (:failures result))
          (is (false? (:stopped? result)))
          (is (some (fn [failure]
                      (= :retention-gc (:component failure)))
                    (:failures result))
              (pr-str (:failures result)))
          (is (false? (:store-closed? result))
              "an unconfirmed component stop must keep the store open")
          (is (true? (storage/store-open? (:store sys)))
              "the store handle must still be open")))
      (finally
        (force-stop! sys)
        (cleanup-config! config)))))

(deftest repeated-stop-does-not-repeat-component-side-effects
  (let [config (test-config)
        sys (system/start! config)
        events (atom [])]
    (try
      (is (false? (:blocked? sys)) (pr-str (:error sys)))
      (wrap-owners! sys events)
      (let [first-result (stop-with-recording! sys events)
            after-first @events
            second-result (stop-with-recording! sys events)]
        (is (true? (:stopped? first-result)) (pr-str first-result))
        (is (true? (:store-closed? second-result)))
        (println :second-stop-events @events)
        (is (= after-first @events)
            "a second stop must not call any component stop again")
        (is (false? (storage/store-open? (:store sys)))))
      (finally
        (force-stop! sys)
        (cleanup-config! config)))))

(deftest a-refused-component-start-rolls-back-and-closes-the-store
  (let [config (test-config)
        real-retention-start retention-gc/start!
        real-retention-stop retention-gc/stop!
        retention-service (atom nil)
        events (atom [])]
    (try
      (with-redefs [supervisor/start!
                    (fn [_options]
                      {:ok? false
                       :error {:code :SUPERVISOR_START_FAILED
                               :message "supervisor unavailable"}})

                    retention-gc/start!
                    (fn [options]
                      (let [result (real-retention-start options)]
                        (when (:ok? result)
                          (reset! retention-service (:service result)))
                        result))

                    retention-gc/stop!
                    (fn [service]
                      (swap! events conj :retention-stop)
                      (real-retention-stop service))]
        (let [started (try
                        (system/start! config)
                        (catch clojure.lang.ExceptionInfo e
                          {:thrown (ex-data e)}))]
          (try
            (is (true? (:blocked? started))
                (str "a refused critical component start must block; got "
                     (pr-str (select-keys started [:blocked? :error :url]))))
            (is (seq (:component-errors started))
                "the refused component must be visible in component-errors")
            (is (contains-code? started :SUPERVISOR_START_FAILED)
                "the injected error code is preserved")
            (is (contains? (set @events) :retention-stop)
                "the already-started retention scheduler is rolled back")
            (is (true? (get-in started [:cleanup :store-closed?]))
                "the partial-system cleanup closes the store")
            (is (nil? (:url started)) "no listener survives the rollback")
            (finally
              (when-let [service @retention-service]
                (try
                  (real-retention-stop service)
                  (catch Throwable _ nil)))
              (when-not (:blocked? started)
                (force-stop! started))
              (cleanup-config! config)))))
    (finally
      (when-let [service @retention-service]
        (try
          (real-retention-stop service)
          (catch Throwable _ nil)))))))

(deftest history-index-bootstrap-does-not-outlive-the-store-close
  (let [config (test-config)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        real-ensure history-index/ensure-ready!
        observed (atom nil)]
    (try
      (with-redefs [history-index/ensure-ready!
                    (fn [store opts]
                      (.countDown entered)
                      ;; Simulate a rebuild that cannot cancel promptly.  The
                      ;; system's stop! sets the cancel flag and joins with a
                      ;; bound; this wrapper ignores the interrupt until the
                      ;; test releases it, then honors the cancel predicate.
                      (loop []
                        (let [released? (try
                                          (.await release 5 TimeUnit/SECONDS)
                                          (catch InterruptedException _
                                            false))]
                          (when-not released?
                            (recur))))
                      (let [cancelled? (:cancelled? opts)
                            cancelled-now? (boolean
                                            (and cancelled? (cancelled?)))]
                        (reset! observed
                                {:store-open? (storage/store-open? store)
                                 :cancelled? cancelled-now?
                                 :at (System/currentTimeMillis)})
                        (if cancelled-now?
                          {:ok? false
                           :cancelled? true
                           :error {:code :INDEX_REBUILD_CANCELLED
                                   :message
                                   "the index bootstrap was cancelled"}}
                          (real-ensure store opts))))]
        (let [sys (system/start! config)
              ;; Baseline after start!: agent executors are already created.
              before (non-daemon-thread-count)
              bootstrap (:history-index-bootstrap-thread sys)]
          (try
            (is (false? (:blocked? sys)) (pr-str (:error sys)))
            (is (instance? Thread bootstrap)
                "the bootstrap handle is a raw thread")
            (is (true? (.isDaemon ^Thread bootstrap))
                "the bootstrap thread must not keep the JVM alive")
            (is (= "hc-history-index-bootstrap"
                   (.getName ^Thread bootstrap)))
            (is (.await entered 5 TimeUnit/SECONDS)
                "the deferred rebuild started")
            (let [result (system/stop! sys)
                  after-stop (non-daemon-thread-count)]
              (is (true? (storage/store-open? (:store sys)))
                  "a live bootstrap thread must keep the store open")
              (is (false? (:store-closed? result)))
              (is (false? (:stopped? result)))
              (is (= {:abandoned? true :state :building}
                     (:history-index-bootstrap result))
                  "stop! reports the abandoned bootstrap thread")
              (is (some #(= :history-index-bootstrap (:component %))
                        (:failures result))
                  (pr-str (:failures result)))
              (is (= :STOP_NOT_CONFIRMED
                     (get-in (first (filter #(= :history-index-bootstrap
                                                (:component %))
                                            (:failures result)))
                             [:error :code])))
              (is (= :components-failed
                     (get-in result [:store-close-skipped :reason])))
              (is (true? @(:history-index-bootstrap-cancelled? sys))
                  "stop! sets the cancellation flag")
              (is (<= after-stop (inc before))
                  (str "non-daemon threads grew: before=" before
                       " after=" after-stop))
              (.countDown release)
              (is (await-until #(not (.isAlive ^Thread bootstrap)) 10000)
                  "the abandoned daemon bootstrap thread ends within 10s")
              (is (true? (:cancelled? @observed))
                  "the bootstrap honors the cancel flag")
              (is (true? (:store-open? @observed))
                  "the gated store is still open when the thread resumes")
              (is (= :INDEX_REBUILD_CANCELLED
                     (get-in @(:system-ref sys)
                             [:history-index-bootstrap :error :code]))
                  "the cancelled rebuild records its reason"))
            (finally
              (.countDown release)
              (when (.isAlive ^Thread bootstrap)
                (.interrupt ^Thread bootstrap))
              (force-stop! sys)))))
      (finally
        (cleanup-config! config)))))
