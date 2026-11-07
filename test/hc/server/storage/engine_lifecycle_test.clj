(ns hc.server.storage.engine-lifecycle-test
  "Behavior tests for the Datalevin last-store lifecycle.

  Datalevin 1.2.10 owns the process-global non-daemon scheduler and async
  executor.  `close-kv` removes the environment from
  `datalevin.lmdb/lmdb-dirs`; when the set becomes empty it calls
  `shutdown-last-lmdb-executors!`.  These tests prove the engine reaches that
  path on the last close, on a failed last open, and only after every open
  store has closed.  They also pin the boundary: another open LMDB keeps the
  pools alive, while a pool-only user without an open LMDB loses them at the
  product's last close."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [datalevin.binding.cpp.lifecycle :as dtlv-lifecycle]
            [datalevin.async :as dasync]
            [datalevin.core :as d]
            [datalevin.lmdb :as dlmdb]
            [datalevin.util :as dutil]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.io RandomAccessFile)
           (java.util.concurrent CountDownLatch Executors ExecutorService
                                 ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(defn- scheduler-shutdown?
  [^ExecutorService scheduler]
  (.isShutdown scheduler))

(defn- executor-running?
  [executor]
  (boolean (and executor (dasync/running? executor))))

(defn- scheduler-stopped?
  []
  (let [scheduler @dutil/scheduler-atom]
    (or (nil? scheduler)
        (.isShutdown ^ExecutorService scheduler))))

(defn- async-stopped?
  []
  (let [executor @dasync/executor-atom]
    (or (nil? executor)
        (not (dasync/running? executor)))))

(defn- shutdown-datalevin!
  []
  (try
    (dutil/shutdown-scheduler)
    (catch Throwable _ nil))
  (try
    (dasync/shutdown-executor)
    (catch Throwable _ nil)))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn- assert-no-open-stores!
  [phase]
  (let [dirs @dlmdb/lmdb-dirs]
    (when (seq dirs)
      (throw (ex-info (str "Datalevin stores still open " phase)
                      {:dirs (vec dirs)})))))

(use-fixtures :each
  (fn [run-tests]
    (shutdown-datalevin!)
    (assert-no-open-stores! "before the test")
    (try
      (run-tests)
      (finally
        (shutdown-datalevin!)
        (assert-no-open-stores! "after the test")))))

(defn- corrupt-store!
  [dir]
  (with-open [^RandomAccessFile raf (RandomAccessFile. (io/file dir "data.mdb")
                                                       "rw")]
    (.setLength raf 32)))

(defn- daemon-thread-factory
  []
  (let [^AtomicLong counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str "hc-engine-lifecycle-"
                            (.incrementAndGet counter)))
          (.setDaemon true))))))

(deftest last-close-stops-global-pools
  (let [dir (support/temp-dir "hc-engine-runtime-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (let [scheduler (dutil/get-scheduler)
                executor (dasync/get-executor)]
            (is (false? (scheduler-shutdown? scheduler)))
            (is (true? (executor-running? executor)))
            (storage/close-store! store)
            (is (true? (scheduler-shutdown? scheduler))
                "the last close stops the Datalevin scheduler")
            (is (false? (executor-running? executor))
                "the last close stops the Datalevin async executor"))
          (finally
            (storage/close-store! store))))
      (is (empty? @dlmdb/lmdb-dirs))
      (finally
        (support/delete-tree! dir)))))

(deftest one-open-store-keeps-the-global-pools
  (let [dir-a (support/temp-dir "hc-engine-runtime-a-")
        dir-b (support/temp-dir "hc-engine-runtime-b-")]
    (try
      (let [a (storage/open-store {:path dir-a :create? true})
            b (storage/open-store {:path dir-b :create? true})]
        (try
          (let [scheduler (dutil/get-scheduler)
                executor (dasync/get-executor)]
            (storage/close-store! a)
            (is (false? (scheduler-shutdown? scheduler))
                "another open store keeps the scheduler alive")
            (is (true? (executor-running? executor))
                "another open store keeps the async executor alive")
            (storage/close-store! b)
            (is (true? (scheduler-shutdown? scheduler)))
            (is (false? (executor-running? executor))))
          (finally
            (storage/close-store! a)
            (storage/close-store! b))))
      (is (empty? @dlmdb/lmdb-dirs))
      (finally
        (support/delete-tree! dir-a)
        (support/delete-tree! dir-b)))))

(deftest failed-open-does-not-release-an-open-store
  (let [dir-a (support/temp-dir "hc-engine-runtime-a-")
        dir-b (support/temp-dir "hc-engine-runtime-b-")]
    (try
      ;; Create a corrupt environment whose open reaches Datalevin and fails.
      (let [b (storage/open-store {:path dir-b :create? true})]
        (storage/close-store! b))
      (corrupt-store! dir-b)
      (let [a (storage/open-store {:path dir-a :create? true})]
        (try
          (let [scheduler (dutil/get-scheduler)
                executor (dasync/get-executor)]
            (let [data (error-data
                        #(storage/open-store {:path dir-b :create? true}))]
              (is (contains? #{:STORAGE_OPEN_FAILED :STORAGE_CORRUPT}
                             (:error data))
                  (pr-str data)))
            (is (false? (scheduler-shutdown? scheduler))
                "a failed open must not release the still-open store")
            (is (true? (executor-running? executor)))
            (storage/close-store! a)
            (is (true? (scheduler-shutdown? scheduler)))
            (is (false? (executor-running? executor))))
          (finally
            (storage/close-store! a))))
      (is (empty? @dlmdb/lmdb-dirs))
      (finally
        (support/delete-tree! dir-a)
        (support/delete-tree! dir-b)))))

(deftest failed-last-open-stops-pools-it-created
  (let [dir (support/temp-dir "hc-engine-runtime-")]
    (try
      (let [created (atom nil)]
        (with-redefs [d/open-dbi
                      (fn [& _]
                        ;; Simulate a failed open that already started
                        ;; Datalevin background pools before the DBI check.
                        (reset! created {:scheduler (dutil/get-scheduler)
                                         :executor (dasync/get-executor)})
                        (throw (ex-info "simulated open failure" {})))]
          (let [data (error-data
                      #(storage/open-store {:path dir :create? true}))]
            (is (= :STORAGE_CORRUPT (:error data)) (pr-str data))))
        (is (some? @created)
            "the failed open reached the DBI step and created pools")
        (when-let [{:keys [scheduler executor]} @created]
          (is (true? (scheduler-shutdown? scheduler))
              "a failed last open releases the scheduler it started")
          (is (false? (executor-running? executor))
              "a failed last open releases the async executor it started")))
      (is (empty? @dlmdb/lmdb-dirs))
      (finally
        (shutdown-datalevin!)
        (support/delete-tree! dir)))))

(deftest external-pools-are-not-protected
  (let [dir (support/temp-dir "hc-engine-runtime-")]
    (try
      ;; Datalevin owns the pools; the last close stops them even if another
      ;; user created them first.  Sharing Datalevin is unsupported.
      (let [scheduler (dutil/get-scheduler)
            executor (dasync/get-executor)]
        (is (false? (scheduler-shutdown? scheduler)))
        (is (true? (executor-running? executor)))
        (let [store (storage/open-store {:path dir :create? true})]
          (storage/close-store! store))
        (is (true? (scheduler-shutdown? scheduler)))
        (is (false? (executor-running? executor))))
      (is (empty? @dlmdb/lmdb-dirs))
      (finally
        (shutdown-datalevin!)
        (support/delete-tree! dir)))))

(deftest close-is-idempotent
  (let [dir (support/temp-dir "hc-engine-runtime-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (storage/close-store! store)
        (storage/close-store! store))
      (is (empty? @dlmdb/lmdb-dirs))
      (finally
        (shutdown-datalevin!)
        (support/delete-tree! dir)))))

(deftest removed-runtime-option-is-rejected
  (let [dir (support/temp-dir "hc-engine-runtime-")]
    (try
      (let [data (error-data
                  #(storage/open-store
                    {:path dir
                     :create? true
                     :manage-datalevin-runtime? false}))]
        (is (= :STORAGE_INVALID_OPTIONS (:error data)) (pr-str data))
        (is (some #{:manage-datalevin-runtime?} (:unknown data))
            (pr-str data)))
      (is (not (.exists (io/file dir "data.mdb")))
          "an invalid option must not create a store")
      (finally
        (support/delete-tree! dir)))))

(deftest open-close-tracks-the-lmdb-directory-set
  (let [dir (support/temp-dir "hc-engine-runtime-")]
    (try
      (is (empty? @dlmdb/lmdb-dirs))
      (let [store (storage/open-store {:path dir :create? true})]
        (is (seq @dlmdb/lmdb-dirs)
            "an open store is registered with Datalevin")
        (storage/close-store! store))
      (is (empty? @dlmdb/lmdb-dirs)
          "the last close unregisters the store")
      (finally
        (shutdown-datalevin!)
        (support/delete-tree! dir)))))

(deftest concurrent-open-close-returns-to-a-clean-runtime
  (let [dirs (mapv #(support/temp-dir (str "hc-engine-concurrent-" % "-"))
                   (range 2))
        ^ExecutorService pool (Executors/newFixedThreadPool
                               2 (daemon-thread-factory))
        ^CountDownLatch start (CountDownLatch. 1)
        results (atom [])]
    (try
      (doseq [dir dirs]
        (.submit pool
                 ^Runnable
                 (fn []
                   (try
                     (.await start)
                     (let [store (storage/open-store {:path dir
                                                      :create? true})]
                       (try
                         (Thread/sleep 20)
                         (finally
                           (storage/close-store! store))))
                     (swap! results conj :ok)
                     (catch Throwable t
                       (swap! results conj t))))))
      (.countDown start)
      (.shutdown pool)
      (is (true? (.awaitTermination pool 10 TimeUnit/SECONDS)))
      (is (= [:ok :ok] @results)
          "both concurrent opens and closes complete")
      (is (empty? @dlmdb/lmdb-dirs)
          "the final close leaves no open Datalevin environment")
      (is (true? (scheduler-stopped?))
          "the final close stops the scheduler")
      (is (true? (async-stopped?))
          "the final close stops the async executor")
      (finally
        (.shutdownNow pool)
        (shutdown-datalevin!)
        (doseq [dir dirs]
          (support/delete-tree! dir))))))

(deftest datalevin-jvm-shutdown-hook-waits-for-the-ordered-close
  (let [dir (support/temp-dir "hc-store-hook-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})
            path (str (:path store))]
        (try
          (is (true? (get-in store
                             [:engine :shutdown-close-registered?]))
              "the engine must register the higher-level close action")
          (is (contains? @@#'dtlv-lifecycle/shutdown-close-actions path)
              "Datalevin's JVM hook must wait for the application close")
          (finally
            (storage/close-store! store)))
        (is (not (contains? @@#'dtlv-lifecycle/shutdown-close-actions path))
            "the ordered close releases the action"))
      (finally
        (support/delete-tree! dir)))))
