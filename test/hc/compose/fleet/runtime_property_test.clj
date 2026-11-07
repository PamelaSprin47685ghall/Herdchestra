(ns hc.compose.fleet.runtime-property-test
  "Property tests for one isolated swarm runtime: a valid worktree creates
  one root task, a free token, an unheld write gate, and invalid identity is
  rejected before a runtime exists."
  (:require [clojure.java.io :as io]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.runtime :as runtime])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-worktree []
  (let [dir (Files/createTempDirectory "hc-runtime-" (make-array FileAttribute 0))]
    (Files/createDirectory (.resolve dir ".git") (make-array FileAttribute 0))
    (str dir)))

(defspec valid-runtime-has-one-root-and-free-token 50
  (prop/for-all [swarm-id (gen/such-that seq gen/string-alphanumeric 100)
                 slice-id (gen/such-that seq gen/string-alphanumeric 100)
                 goal (gen/such-that seq gen/string-alphanumeric 100)]
    (let [workspace (temp-worktree)
          result (runtime/start! {:swarm-id swarm-id
                                  :slice-id slice-id
                                  :workspace workspace
                                  :branch "main"
                                  :slice {:goal goal :done-when "done"}})]
      (try
        (and (:ok? result)
             (let [rt (:runtime result)
                   observation (runtime/observation rt)
                   token (:token-slot observation)]
               (and (= 1 (:root-ready observation))
                    (zero? (:root-done observation))
                    (nil? (:holder-id token))
                    (= :free (:state token))
                    (false? (get-in observation [:write-gate :held?])))))
        (finally
          (runtime/stop! (:runtime result))
          (doseq [f (reverse (file-seq (java.io.File. workspace)))]
            (io/delete-file f true)))))))

(defspec invalid-runtime-identity-is-rejected 50
  (prop/for-all [missing (gen/elements [:swarm-id :slice-id :workspace :branch])]
    (let [workspace (temp-worktree)
          base {:swarm-id "s" :slice-id "sl" :workspace workspace
                :branch "main" :slice {:goal "g" :done-when "d"}}
          request (case missing
                    :swarm-id (assoc base :swarm-id "")
                    :slice-id (assoc base :slice-id "")
                    :workspace (assoc base :workspace "")
                    :branch (assoc base :branch ""))
          result (runtime/start! request)]
      (try
        (false? (:ok? result))
        (finally
          (doseq [f (reverse (file-seq (java.io.File. workspace)))]
            (io/delete-file f true)))))))
