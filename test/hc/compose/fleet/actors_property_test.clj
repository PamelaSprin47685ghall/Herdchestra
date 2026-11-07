(ns hc.compose.fleet.actors-property-test
  "Property tests for the fleet actor adapter: two fixed slots produce two
  unique model starts on the swarm worktree, with no shared session board."
  (:require [clojure.java.io :as io]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.actors :as actors]
            [hc.compose.fleet.runtime :as runtime])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-worktree []
  (let [dir (Files/createTempDirectory "hc-actors-" (make-array FileAttribute 0))]
    (Files/createDirectory (.resolve dir ".git") (make-array FileAttribute 0))
    (str dir)))

(defspec two-slots-produce-two-unique-model-starts 50
  (prop/for-all [swarm-id (gen/such-that seq gen/string-alphanumeric 100)]
    (let [workspace (temp-worktree)
          started (runtime/start! {:swarm-id swarm-id
                                   :slice-id "slice"
                                   :workspace workspace
                                   :branch "main"
                                   :slice {:goal "g" :done-when "d"}})
          rt (:runtime started)
          result (actors/start! {:runtime rt
                                 :models {:main "main" :task "task"}
                                 :slot-ids [:slot-a :slot-b]})
          starts (filter #(= :model/start (:type %)) (:effects result))]
      (try
        (and (:ok? started)
             (:ok? result)
             (= 2 (count starts))
             (= 2 (count (distinct (map :attempt-id starts))))
             (every? #(= (str "fleet-" swarm-id) (:tenure-id %)) starts))
        (finally
          (runtime/stop! rt)
          (doseq [f (reverse (file-seq (java.io.File. workspace)))]
            (io/delete-file f true)))))))
