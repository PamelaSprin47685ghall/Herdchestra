(ns hc.server.app.orphan-watchdog-test
  "Orphan watchdog: a JVM started under `flock ... bash -c` must shut down
  when its launcher dies and no flock process still holds the lock, so the
  machine-wide singleton lock is released for the next start."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.app :as app]
            [hc.server.app.restore-supervisor :as supervisor]
            [hc.server.app.singleton :as singleton]
            [hc.server.app.system :as system])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent CountDownLatch TimeUnit)))

(defn- temp-dir
  []
  (str (Files/createTempDirectory "hc-orphan-" (make-array FileAttribute 0))))

(defn- delete-tree!
  [root]
  (doseq [file (reverse (file-seq (io/file root)))]
    (try (.delete ^java.io.File file) (catch Exception _ nil))))

(defn- wait-for
  "Poll PRED until it returns true or TIMEOUT-MS elapses."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(deftest shell-launcher-detection
  (is (true? (#'app/shell-launcher? "bash")))
  (is (true? (#'app/shell-launcher? "/usr/bin/bash")))
  (is (true? (#'app/shell-launcher? "bash -c 'clojure -M:server'")))
  (is (true? (#'app/shell-launcher? "sh")))
  (is (false? (#'app/shell-launcher? "clojure")))
  (is (false? (#'app/shell-launcher? "java")))
  (is (false? (#'app/shell-launcher? nil)))
  (is (false? (#'app/shell-launcher? ""))))

(deftest parent-alive-tracks-the-recorded-launcher
  ;; The test JVM's own parent (the clojure/java launcher chain) is alive for
  ;; the whole test run, so the recorded launcher must report alive.
  (is (true? (#'app/parent-alive?))))

(deftest start-matches-rejects-a-recycled-pid
  (let [handle (ProcessHandle/current)
        start (.orElse (.startInstant (.info handle)) nil)]
    (is (true? (#'app/start-matches? start (java.util.Optional/of handle))))
    (is (false? (#'app/start-matches?
                (.minusSeconds start 3600)
                (java.util.Optional/of handle))))
    (is (true? (#'app/start-matches? nil java.util.Optional/empty))
        "a missing start instant cannot disprove identity")))

(deftest lock-held-by-flock-sees-a-live-flock-holder
  (let [dir (temp-dir)
        lock-file (str (singleton/lock-path dir))]
    (try
      ;; Hold the lock file with a real flock subprocess; the watchdog must
      ;; see it as held and must not consider the app orphaned.  `flock
      ;; FILE CMD` keeps the lock for the command's lifetime.
      (let [proc (.start (ProcessBuilder.
                          ["flock" lock-file "sleep" "30"]))]
        (try
          (is (wait-for #(#'app/lock-held-by-flock? dir) 10000)
              "a live flock holding instance.lock is detected")
          (finally
            (.destroyForcibly proc)
            (.waitFor proc 5 TimeUnit/SECONDS))))
      (finally (delete-tree! dir)))))

(deftest lock-held-by-flock-is-false-without-a-holder
  (let [dir (temp-dir)]
    (try
      (is (false? (#'app/lock-held-by-flock? dir)))
      (finally (delete-tree! dir)))))

(deftest watchdog-fires-stop-once-when-orphaned
  (let [fired (CountDownLatch. 1)
        calls (atom [])]
    (with-redefs [hc.server.app/orphaned? (fn [& _] true)]
      (let [stop-watch (#'app/start-orphan-watchdog!
                        (fn [] (swap! calls conj :stop) (.countDown fired))
                        {:poll-ms 50 :grace-ms 0})]
        (try
          (is (.await fired 10 TimeUnit/SECONDS)
              "watchdog calls stop-once! once the app is orphaned")
          (is (= [:stop] @calls))
          (finally (stop-watch)))))))

(deftest watchdog-stays-quiet-while-not-orphaned
  (let [fired (atom false)]
    (with-redefs [hc.server.app/orphaned? (fn [& _] false)]
      (let [stop-watch (#'app/start-orphan-watchdog!
                        (fn [] (reset! fired true))
                        {:poll-ms 50 :grace-ms 0})]
        (try
          (Thread/sleep 400)
          (is (false? @fired)
              "a live launcher keeps the app running")
          (finally (stop-watch)))))))

(deftest run-primary-exits-when-launcher-dies
  ;; End-to-end through run-primary!: with every subsystem stubbed, an
  ;; orphaned app must run the normal stop path and return the exit code
  ;; instead of blocking on the never-delivered promise forever.
  (let [released (atom nil)
        stopped (atom nil)
        stop-fns (atom nil)
        started {:blocked? false
                 :url "http://127.0.0.1:0/"
                 :workspace-root "/tmp"
                 :db-path "/tmp/db"}
        handle {:dir "dir" :lock :fake :channel :fake}]
    ;; The watchdog's stop-once! runs the real shutdown path; we capture it
    ;; and invoke it from the test thread so run-primary!'s blocking
    ;; promise is delivered through the normal stop path.
    (with-redefs [supervisor/prepare! (fn [_] nil)
                  system/start! (fn [_ _] started)
                  singleton/start-server!
                  (fn [_ _] {:stop! (fn [] {:stopped? true})})
                  hc.server.app/start-orphan-watchdog!
                  (fn [stop-once! & _]
                    (reset! stop-fns stop-once!)
                    (fn [] nil))
                  singleton/release! (fn [h] (reset! released h))
                  supervisor/stop-current!
                  (fn [] (reset! stopped true) {:stopped? true})]
      ;; run-primary! blocks on @(blocking-promise); the watchdog would
      ;; normally fire stop-once! which stops the system and delivers the
      ;; promise.  We invoke the captured stop-once! on a timer thread.
      (let [done (promise)
            runner (future
                     (let [code (#'app/run-primary! handle {})]
                       (deliver done code)))]
        (Thread/sleep 200)
        (is (some? @stop-fns) "watchdog captured the stop-once! fn")
        (@stop-fns)
        (is (= 0 (deref done 10000 ::timeout))
            "run-primary! returns 0 after the stop path runs")
        (is (= handle @released) "the singleton handle is released")
        (is (true? @stopped) "the live system is stopped")))))

(deftest orphaned-requires-dead-shell-launcher-and-no-flock
  (let [shell-launcher {:pid 1 :start nil :command "bash -c 'clojure'"}
        other-launcher {:pid 1 :start nil :command "java"}]
    (testing "live launcher is not orphaned"
      (with-redefs [hc.server.app/launcher (delay shell-launcher)
                    hc.server.app/parent-alive? (fn [] true)
                    hc.server.app/lock-held-by-flock? (fn [& _] false)]
        (is (false? (#'app/orphaned? "/tmp/dir")))))
    (testing "dead shell launcher with flock still holding the lock is not orphaned"
      (with-redefs [hc.server.app/launcher (delay shell-launcher)
                    hc.server.app/parent-alive? (fn [] false)
                    hc.server.app/lock-held-by-flock? (fn [& _] true)]
        (is (false? (#'app/orphaned? "/tmp/dir")))))
    (testing "dead shell launcher and no flock holder is orphaned"
      (with-redefs [hc.server.app/launcher (delay shell-launcher)
                    hc.server.app/parent-alive? (fn [] false)
                    hc.server.app/lock-held-by-flock? (fn [& _] false)]
        (is (true? (#'app/orphaned? "/tmp/dir")))))
    (testing "dead non-shell launcher is not orphaned"
      (with-redefs [hc.server.app/launcher (delay other-launcher)
                    hc.server.app/parent-alive? (fn [] false)
                    hc.server.app/lock-held-by-flock? (fn [& _] false)]
        (is (false? (#'app/orphaned? "/tmp/dir")))))))
