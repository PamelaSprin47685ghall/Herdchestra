(ns e2e16-verify
  "Read-back verification for E2E-16 scheduler storage.

  Prints one JSON object describing the `scheduler` partition: every
  schedule (id, state, timezone, next-run) and every run (id, schedule-id,
  status, reason, trigger).  Used by test/e2e/e2e-16.spec.mjs after app
  restarts and offline restores.

      clojure -M:server -i test/e2e/e2e16_verify.clj \\
        --eval '(e2e16-verify/run \"<data-dir>/db\")'
  "
  (:require [clojure.data.json :as json]
            [hc.server.storage :as storage]))

(set! *warn-on-reflection* true)

(defn- docs-of
  [export kind]
  (->> (get-in export [:partitions "scheduler" :docs] [])
       (filter #(= kind (:kind %)))
       (map :doc)
       vec))

(defn- schedule-summary
  [doc]
  {:id (:id doc)
   :name (:name doc)
   :state (:state doc)
   :nextRun (:next-run doc)
   :timezone (:timezone doc)
   :activeRunId (:active-run-id doc)
   :paused (:paused? doc)})

(defn- run-summary
  [doc]
  {:id (:id doc)
   :scheduleId (:schedule-id doc)
   :status (:status doc)
   :reason (:reason doc)
   :trigger (:trigger doc)
   :scheduledAt (:scheduled-at doc)
   :startedAt (:started-at doc)
   :endedAt (:ended-at doc)
   :attempt (:attempt doc)})

(defn run
  "Print the scheduler verification map for DB-PATH as one JSON line."
  [db-path]
  (let [store (storage/open-store {:path (str db-path)
                                   :create? false
                                   ;; The app holds the single-writer lock
                                   ;; while running; verification only reads,
                                   ;; so open without the OS file lock.
                                   :lock? false
                                   :durability :sync})
        export (try
                 (storage/export-state store {})
                 (finally
                   (storage/close-store! store)))
        root (->> (docs-of export :scheduler-root) first)
        schedules (into {}
                        (map (fn [doc]
                               [(:id doc) (schedule-summary doc)]))
                        (docs-of export :scheduler-schedule))
        runs (mapv run-summary (docs-of export :scheduler-run))]
    (println
     (json/write-str
      {:ok true
       :deviceZone (get root :device-zone)
       :revision (get root :revision)
       :seq (get root :seq)
       :runOrder (vec (get root :run-order))
       :schedules schedules
       :runs runs}))))
