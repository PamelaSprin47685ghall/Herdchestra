(ns hc.test-jvm
  "Explicit JVM limits for child processes started by tests.

  A child JVM does not inherit the parent's heap limit.  Without `-Xmx` it
  sizes its heap from physical memory (one quarter by default), so several
  smoke tests can reserve tens of gigabytes even when the parent test JVM is
  capped.  These limits are deliberately explicit and small: one child runs
  one short scenario, then exits."
  (:require [clojure.string :as str])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)))

(def child-jvm-opts
  ["-Xmx512m"
   "-XX:MaxDirectMemorySize=256m"
   "-XX:+ExitOnOutOfMemoryError"
   ;; A short-lived scenario does not need C2.  This cuts JIT memory and
   ;; startup time without changing the scenario's observable behavior.
   "-XX:TieredStopAtLevel=1"])

(def child-timeout-ms
  "A hung child must not hold a JVM, a store lock, and its memory forever."
  180000)

(defn java-exe
  []
  (let [name (if (str/starts-with? (System/getProperty "os.name") "Windows")
               "java.exe"
               "java")
        path (File. (File. (System/getProperty "java.home") "bin") name)]
    (str path)))

(defn sh
  "Run MAIN-CLASS in a child JVM with `child-jvm-opts` and ARGS.

  Uses the parent's classpath so the child sees the same source and test
  paths.  Returns the same map shape as `clojure.java.shell/sh`.  A child
  that exceeds `child-timeout-ms` is forcibly stopped and reported as exit
  124, so a broken scenario cannot leave an orphan JVM behind."
  [main-class & args]
  (let [command (concat [(java-exe)]
                        child-jvm-opts
                        ["-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-m" main-class]
                        args)
        process (-> (ProcessBuilder. (into-array String command))
                    (.start))
        out (future (slurp (.getInputStream process) :encoding "UTF-8"))
        err (future (slurp (.getErrorStream process) :encoding "UTF-8"))
        finished? (.waitFor process child-timeout-ms TimeUnit/MILLISECONDS)]
    (if finished?
      {:exit (.exitValue process) :out @out :err @err}
      (do
        (.destroyForcibly process)
        (.waitFor process)
        {:exit 124
         :out @out
         :err (str @err "\nchild JVM timed out after "
                   child-timeout-ms " ms")}))))
