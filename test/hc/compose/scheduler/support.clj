(ns hc.compose.scheduler.support
  "Shared fixtures for scheduler tests.

  The EDN file store is a real persistence adapter for tests and the runtime
  smoke.  It writes a temporary sibling and atomically replaces the target, so
  a reader never sees a half-written state."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.compose.scheduler.core :as core]
            [hc.compose.scheduler.store :as store])
  (:import (java.nio.file CopyOption Files LinkOption StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDateTime ZoneId)))

(defn epoch
  "Epoch milliseconds for a local date-time in ZONE."
  [zone local-date-time]
  (.toEpochMilli
   (.toInstant (.atZone (LocalDateTime/parse local-date-time)
                        (ZoneId/of zone)))))

(defn temp-dir
  []
  (str (Files/createTempDirectory "hc-scheduler-"
                                  (make-array FileAttribute 0))))

(defn delete-tree
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (io/delete-file file true)))))

(defn edn-file-store
  "A whole-state EDN store with atomic replacement."
  [path]
  (let [target (.toPath (io/file path))
        temp (.resolveSibling target (str (.getFileName target) ".tmp"))]
    (reify store/Store
      (load-state [_]
        (if (Files/exists target (make-array LinkOption 0))
          (let [text (slurp (str target) :encoding "UTF-8")]
            (when-not (str/blank? text)
              (edn/read-string text)))
          nil))
      (save-state! [_ state]
        (when-let [parent (.getParent target)]
          (Files/createDirectories parent (make-array FileAttribute 0)))
        (spit (str temp) (pr-str state) :encoding "UTF-8")
        (try
          (Files/move temp target
                      (into-array CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING
                                   StandardCopyOption/ATOMIC_MOVE]))
          (catch java.nio.file.AtomicMoveNotSupportedException _
            (Files/move temp target
                        (into-array CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING]))))
        nil))))

(def base-snapshot
  {:project {:id "project-1" :name "Workspace"}
   :profile {:id "default" :revision 3}
   :model {:connection-id "conn-1"
           :model-id "model-a"
           :preset-id "preset-1"
           :preset-revision 7}
   :capability-scope {:tools #{:read-file :write-file}}
   :credentials [{:name "model-key" :scope :model}]})

(def base-spec
  {:name "Morning report"
   :prompt "Summarize the workspace and write a short report."
   :snapshot base-snapshot
   :execution {:mode :new-chat}
   :recurrence {:kind :weekly :days [1 3] :time {:hour 8 :minute 30}}
   :timezone {:mode :fixed :zone "Asia/Shanghai"}})

(defn fixed-clock
  "An atom-backed clock.  Returns `{:clock f :at! f :value f}`."
  [initial]
  (let [value (atom initial)]
    {:clock #(deref value)
     :at! #(reset! value %)
     :value #(deref value)}))

(defn create-enabled
  [state spec now]
  (core/create-schedule state (assoc spec :enable? true) now))
