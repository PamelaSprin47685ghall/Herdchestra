(ns hc.test-runner
  "Test-runner wrapper that releases process-global dependency pools.

  The standard `cognitect.test-runner.api/test` entry point returns without
  shutting down Datalevin's process-global scheduler and async worker pool.
  A clean store close releases them, but one leaked or failed open keeps
  non-daemon threads alive and the `-X:test` JVM never exits.  The `-M:test`
  entry already exits explicitly; this wrapper gives the `-X` entry the same
  teardown without masking a test failure.

  The shutdown is best-effort and conditional: the `:test` alias alone has no
  Datalevin dependency, and a missing pool is already stopped."
  (:require [cognitect.test-runner.api :as runner]))

(defn- resolve-fn
  [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _ nil)))

(defn- shutdown-datalevin!
  "Stop Datalevin's global executors, newest namespace first.

  `datalevin.lmdb/shutdown-last-lmdb-executors!` is the complete teardown
  (async executor, transact/query pools, UDF/worker pools, scheduler).  Fall
  back to the two public entry points if that internal var is unavailable."
  []
  (if-let [shutdown (resolve-fn 'datalevin.lmdb/shutdown-last-lmdb-executors!)]
    (try (shutdown) (catch Throwable _ nil))
    (do
      (when-let [shutdown (resolve-fn 'datalevin.util/shutdown-scheduler)]
        (try (shutdown) (catch Throwable _ nil)))
      (when-let [shutdown (resolve-fn 'datalevin.async/shutdown-executor)]
        (try (shutdown) (catch Throwable _ nil))))))

(defn- open-store-dirs
  "Read Datalevin's live LMDB directory set, or nil when it is unavailable.

  A non-empty set after the suite means a store was never closed.  The
  diagnostic is read-only; it does not itself stop anything."
  []
  (when-let [dirs-atom (resolve-fn 'datalevin.lmdb/lmdb-dirs)]
    (try
      (vec (sort (map str @dirs-atom)))
      (catch Throwable _ nil))))

(defn- report-leaked-stores!
  "Print any store left open past the suite.

  Default behavior is a warning so the suite still reports its test result
  and the runner teardown can release the process-global pools.  Set the
  `hc.test.assert-no-leaked-stores` system property to make a leak fail the
  run after printing the directories."
  []
  (when-let [dirs (seq (open-store-dirs))]
    (println "WARNING: Datalevin stores still open at suite end:"
             (pr-str dirs))
    (when (Boolean/getBoolean "hc.test.assert-no-leaked-stores")
      (throw (ex-info "Datalevin stores leaked past the test suite"
                      {:dirs dirs})))))

(defn test
  "Run the test suite, then release JVM-global pools so the process exits.

  Returns the Cognitect runner result map unchanged; failures remain the
  caller's exit-code decision."
  [opts]
  (try
    (runner/test opts)
    (finally
      (try
        (report-leaked-stores!)
        (finally
          (shutdown-datalevin!)
          (shutdown-agents))))))
