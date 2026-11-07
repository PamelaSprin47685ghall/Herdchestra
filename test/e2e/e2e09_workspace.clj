(ns e2e.e2e09-workspace
  "Server-side E2E 09 driver: a real protected commit against a real file.

  The scenario is deterministic and uses the same `:workspace` port the server
  program receives.  An external editor changes the file after the worker's
  read; the first settle must reject with :FILE_CONFLICT and leave the
  external bytes in place.  A fresh read then commits the worker content."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [hc.compose.workspace :as ws]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- write-file! [root path text]
  (spit (io/file (str root) path) text))

(defn- read-file! [root path]
  (slurp (io/file (str root) path)))

(defn- stamp-of [root path]
  (let [stat (wg/stat-path root path)]
    (select-keys stat [:mtime-sec :mtime-nsec :size])))

(defn- same-stamp? [a b]
  (= (select-keys a [:mtime-sec :mtime-nsec :size])
     (select-keys b [:mtime-sec :mtime-nsec :size])))

(defn- expected-stamps [expected]
  (cond
    (nil? expected) []
    (map? expected) [expected]
    (sequential? expected) (vec expected)
    :else []))

(defn- setup [root]
  (let [gate (wg/write-gate)
        kernel (swarm/create {:verify-read-set (wg/read-set-verifier root)})
        agent (:agent-id (swarm/execute! kernel {:op :register-actor :slot-id :slot-e2e09}))
        task (:task-id (swarm/execute!
                        kernel {:op :create-task
                                :actor-id agent
                                :goal "E2E 09 protected commit"
                                :remaining-work "write the table"
                                :done-when "the worker content is on disk"
                                :owner-id agent}))
        claim (:claim-id (swarm/execute!
                          kernel {:op :claim
                                  :actor-id agent
                                  :task-id task
                                  :atomicity-reason "single file replacement"}))]
    {:root root :gate gate :kernel kernel :agent agent :task task :claim claim}))

(defn- runtime [s]
  (ws/make-runtime {:root (:root s)
                    :kernel (:kernel s)
                    :write-gate (:gate s)
                    :clock (fn [] 0)}))

(defn- ctx [s]
  {:agent-id (:agent s)
   :claim-id (:claim s)
   :task-id (:task s)
   :attempt-id "e2e09-attempt"
   :call-id "e2e09-call"
   :program-name "e2e09"
   :role :clj-worker})

(defn- action-for [kernel request-id]
  (some (fn [action] (when (= request-id (:request-id action)) action))
        (vals (:actions (swarm/snapshot kernel)))))

(defn -main [& args]
  (let [[root-path output-path] args
        root (-> root-path io/file .getAbsoluteFile .toPath)
        _ (Files/createDirectories root (make-array FileAttribute 0))
        _ (write-file! root "report.csv" "name,value\n原稿,1\n")
        s (setup root)
        rt (runtime s)
        port (ws/workspace-port rt)
        session ((:open-session port) (ctx s))
        original-text ((:cat port) session "report.csv")
        original-stamp (stamp-of root "report.csv")
        external-text "name,value\n原稿,1\n外部编辑,42\n"
        _ (write-file! root "report.csv" external-text)
        external-stamp (stamp-of root "report.csv")
        _ ((:write port) session "report.csv" "name,value\nworker,7\n")
        conflict ((:settle! port) session {:request-id "e2e09-conflict"
                                          :agent-id (:agent s)})
        conflict-result (:result conflict)
        after-conflict (read-file! root "report.csv")
        expected (expected-stamps (:expected conflict-result))
        actual (:actual conflict-result)
        session2 ((:open-session port) (ctx s))
        reread ((:cat port) session2 "report.csv")
        _ ((:write port) session2 "report.csv" "name,value\nworker,7\n")
        retry ((:settle! port) session2 {:request-id "e2e09-retry"
                                         :agent-id (:agent s)})
        final-text (read-file! root "report.csv")
        final-stamp (stamp-of root "report.csv")
        action (action-for (:kernel s) "e2e09-retry")
        real-path (-> (io/file (str root) "report.csv") .getAbsoluteFile .getCanonicalPath)
        changed-paths (vec (get-in action [:result-ref :summary :changed-paths]))
        card-real (first changed-paths)
        card-path (when card-real
                    (str (.relativize root (.toPath (io/file card-real)))))
        file-card {:request-id (:request-id action)
                   :status (:phase action)
                   :path card-path
                   :real-path card-real
                   :stamp final-stamp}
        checks {:conflict-status (= :FILE_CONFLICT (:status conflict))
                :conflict-code (= :stamp-mismatch (:code conflict-result))
                :external-content-kept (= external-text after-conflict)
                :conflict-expected-has-original
                (some #(same-stamp? % original-stamp) expected)
                :conflict-actual-is-external (same-stamp? actual external-stamp)
                :reread-sees-external (= external-text reread)
                :retry-applied (= :APPLIED (:status retry))
                :worker-content-on-disk (= "name,value\nworker,7\n" final-text)
                :file-card-points-to-real-file
                (and (= "report.csv" card-path)
                     (= real-path (.getCanonicalPath (io/file card-real)))
                     (Files/exists (.resolve root "report.csv")
                                   (make-array java.nio.file.LinkOption 0)))
                :file-card-stamp-is-final
                (same-stamp? (:stamp file-card) final-stamp)}
        report {:test "E2E 09"
                :mode "automated-server-side"
                :mock false
                :root (str root)
                :original-text original-text
                :original-stamp original-stamp
                :external-text external-text
                :external-stamp external-stamp
                :after-conflict after-conflict
                :conflict {:status (:status conflict)
                           :result (select-keys conflict-result
                                                [:status :code :path :expected :actual
                                                 :message :outcome :file-changed?])}
                :expected expected
                :actual actual
                :reread reread
                :retry {:status (:status retry) :result (:result retry)}
                :final-text final-text
                :final-stamp final-stamp
                :action action
                :file-card file-card
                :checks checks
                :ok (every? true? (vals checks))}]
    (let [text (json/write-str report :escape-slash false)]
      (when output-path (spit output-path text))
      (println text)
      (System/exit (if (:ok report) 0 1)))))
