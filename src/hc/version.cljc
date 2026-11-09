(ns hc.version
  "Single source of truth for the application release version.

  Build manifests (`pixi.toml`, `package.json`) and user-facing run/release
  notes must be kept in step with this value.  Runtime health, diagnostics,
  backup metadata, and MCP client identity read it from here.

  `build-identity` adds the git commit and dirty flag on the JVM so a running
  process can name the tree it was started from.  The browser has no git
  access and reports the version alone."
  #?(:clj (:require [clojure.java.io :as io]
                    [clojure.string :as str]))
  #?(:clj (:import (java.util.concurrent TimeUnit))))

(def version "0.0.3")

#?(:clj
   (defn- git-output
     "Run `git ARGS` in the process working directory with a 2 s deadline.

     Returns trimmed stdout when git exits zero, else nil.  A missing git,
     a non-repository directory, a failing command, or an overrun all read as
     nil so startup identity never blocks or throws."
     [& args]
     (try
       (let [command (into-array String (cons "git" args))
             process (-> (ProcessBuilder. command)
                         (.redirectErrorStream true)
                         (.start))]
         (if (.waitFor process 2 TimeUnit/SECONDS)
           (let [output (with-open [reader (io/reader (.getInputStream process))]
                          (slurp reader))]
             (when (zero? (.exitValue process))
               (str/trim output)))
           (do (.destroyForcibly process)
               nil)))
       (catch Throwable _ nil))))

#?(:clj
   (defn- git-identity
     "Git hash and dirty flag for the checked-out tree.

     Both fields fall back to `\"unknown\"` / `false` when git cannot answer,
     so a source tarball or a non-repository install still starts cleanly."
     []
     (let [hash (git-output "rev-parse" "--short=12" "HEAD")
           status (git-output "status" "--porcelain")]
       {:git-hash (or hash "unknown")
        :git-dirty? (boolean (and (string? status)
                                  (not (str/blank? status))))})))

(def ^:private identity-cache
  #?(:clj (delay (merge {:version version} (git-identity)))
     :cljs (delay {:version version})))

(defn build-identity
  "Identity of the running build: `:version` everywhere, plus `:git-hash`
  and `:git-dirty?` on the JVM.  The JVM answer is sampled once per process."
  []
  @identity-cache)
