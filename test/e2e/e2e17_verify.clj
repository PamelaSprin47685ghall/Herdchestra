(ns e2e17-verify
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hc.server.storage :as storage]))

(set! *warn-on-reflection* true)

(defn- partition-docs
  [export p kind]
  (filterv #(= kind (:kind %))
           (get-in export [:partitions p :docs] [])))

(defn- doc-value
  [export p kind id]
  (some #(when (= id (:id %)) (:doc %))
        (partition-docs export p kind)))

(defn run
  "Print the verification map for the store at DB-PATH as one JSON line."
  [db-path]
  (let [store (storage/open-store {:path (str db-path)
                                   :create? false
                                   :durability :sync})
        export (try
                 (storage/export-state store {})
                 (finally
                   (storage/close-store! store)))]
    (let [sessions (partition-docs export "sessions" :session-record)
          session-ids (vec (sort (map #(or (:session-id (:doc %))
                                          (:id (:doc %)))
                                       sessions)))
          config (or (doc-value export "config" :app-config "config") {})
          assistants (get config :assistants {})
          assistant-names (into {}
                                (map (fn [[id a]]
                                       [id (or (:name a) (:title a))]))
                                assistants)
          schedule-docs (map :doc
                             (partition-docs export "scheduler"
                                              :scheduler-schedule))
          schedule (or (some (fn [doc]
                                (when (or (= "sched1" (:id doc))
                                          (str/includes? (str (:id doc))
                                                         "@backup-"))
                                  doc))
                              schedule-docs)
                        (first schedule-docs))
          root (doc-value export "scheduler" :scheduler-root "root")]
      (let [result {:ok true
                    :sessionIds session-ids
                    :sessionCount (count session-ids)
                    :assistantNames assistant-names
                    :assistantCount (count assistant-names)
                    :backupAssistantPresent
                    (boolean (some #(and (string? %) (str/includes? % "来自备份"))
                                   (vals assistant-names)))
                    :scheduleState (:state schedule)
                    :schedulePaused (boolean (or (:paused? schedule)
                                                 (= :paused (:state schedule))))
                    :scheduleNextRun (:next-run schedule)
                    :schedulerRunOrder (:run-order root)
                    :partitions (vec (keys (:partitions export)))}]
        (println (json/write-str result))))))
