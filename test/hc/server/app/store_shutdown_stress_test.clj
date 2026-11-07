(ns hc.server.app.store-shutdown-stress-test
  "Repeated start/stop regression for the publisher/store shutdown race.

  A single `app-check` does not reproduce the SIGSEGV: the publisher reads
  Datalevin every 500 ms, and a stop that does not wait for that read closes
  the native environment underneath it.  This test runs the real application
  twenty times, keeps a direct `publish-now!` reader busy while `stop!` runs,
  and requires every stop to confirm that the publisher terminated and the
  store closed.  A read after the close must be refused by the storage
  barrier instead of touching the native engine."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.config :as config]
            [hc.server.app.system :as system]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private iterations 20)

(defn- temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- write-file! [path content]
  (let [file (java.io.File. ^String path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content)))

(defn- test-config
  "One isolated data directory per iteration.

  The workspace and static roots are also temporary so the test never reads
  or writes a user project."
  []
  (let [root (temp-dir "hc-stress-root-")
        data (temp-dir "hc-stress-data-")
        static (temp-dir "hc-stress-static-")]
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
      :models {}})))

(defn- read-error
  [store]
  (try
    (storage/read-session-view store {:partition "master"})
    ::read-succeeded
    (catch clojure.lang.ExceptionInfo e
      (:error (ex-data e)))))

(deftest start-stop-20-times-with-publisher-load
  (dotimes [iteration iterations]
    (let [config (test-config)
          started (system/start! config)]
      (try
        (is (false? (:blocked? started))
            (str "iteration " iteration " did not start"))
        (let [publisher (:publisher started)
              publish-now! (:publish-now! publisher)
              stop-load? (atom false)
              load-future (future
                            (while (not @stop-load?)
                              (publish-now!)))]
          (testing "the publisher reads before the stop"
            (is (map? (publish-now!))
                (str "iteration " iteration
                     " publisher did not return a publish outcome")))
          ;; Give the direct reader and the 500 ms scheduled tick a chance to
          ;; be inside Datalevin when `stop!` sets the stopped flag.
          (Thread/sleep 20)
          (let [result (try
                         (system/stop! started)
                         (finally
                           ;; A throwing stop must not leave the direct reader
                           ;; spinning after the test fails.
                           (reset! stop-load? true)
                           (deref load-future 5000 nil)))]
            (is (not= ::timeout (deref load-future 5000 ::timeout))
                (str "iteration " iteration
                     " direct publisher reader did not finish"))
            (testing "stop confirms the publisher drained before the store"
              (is (true? (:stopped? result))
                  (str "iteration " iteration " stop failed: "
                       (pr-str (select-keys result
                                            [:failures :store-closed?]))))
              (is (true? (:store-closed? result))
                  (str "iteration " iteration " store stayed open"))
              (is (true? (get-in result [:components :publisher :stopped?]))
                  (str "iteration " iteration
                       " publisher evidence is not stopped: "
                       (pr-str (get-in result [:components :publisher]))))
              (is (true? ((:stopped? publisher)))
                  (str "iteration " iteration " publisher flag is false")))
            (testing "no publisher read survives the store close"
              (is (nil? (publish-now!))
                  (str "iteration " iteration
                       " a stopped publisher still read the store"))
              (is (= :STORAGE_CLOSED
                     (read-error (:store started)))
                  (str "iteration " iteration
                       " the closed store accepted a read")))))
        (finally
          (when (storage/store-open? (:store started))
            (system/stop! started))
          (support/delete-tree! (:data-dir config)))))))
