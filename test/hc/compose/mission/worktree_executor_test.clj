(ns hc.compose.mission.worktree-executor-test
  "Real worktree-scoped executor smoke for a swarm actor tool call.

  The actor request carries :workspace and :program-role :clj-worker.  This
  test uses a real temporary worktree, the protected workspace bridge, and
  the real program runtime to write a file through the same contract the
  Owner/app executor must honor.  It verifies actual bytes on disk, not just
  a model request."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hc.compose.mission.worktree :as worktree]
            [hc.compose.workspace :as ws]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]
            [hc.server.program.runtime :as program])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root []
  (Files/createTempDirectory "hc-mission-worktree-" (make-array FileAttribute 0)))

(defn- cleanup! [root]
  (doseq [f (reverse (file-seq (io/file (str root))))]
    (io/delete-file f true)))

(defn- setup [root]
  (let [gate (wg/write-gate)
        kernel (swarm/create {:verify-read-set (wg/read-set-verifier root)})
        agent (:agent-id (swarm/execute!
                          kernel {:op :register-actor :slot-id :slot-a}))
        task (:task-id (swarm/execute!
                        kernel {:op :create-task
                                :actor-id agent
                                :goal "write the actor file"
                                :remaining-work "write the actor file"
                                :done-when "the file exists"
                                :owner-id agent}))
        claim (:claim-id (swarm/execute!
                          kernel {:op :claim
                                  :actor-id agent
                                  :task-id task
                                  :atomicity-reason "one worktree file"}))]
    {:root root :gate gate :kernel kernel :agent agent :task task :claim claim}))

(deftest actor-tool-writes-the-real-worktree-not-another-root
  (let [root (temp-root)
        other (temp-root)]
    (try
      (let [s (setup root)
            ws-runtime (ws/make-runtime {:root (:root s)
                                         :kernel (:kernel s)
                                         :write-gate (:gate s)
                                         :clock (fn [] 0)})
            port (ws/workspace-port ws-runtime)
            control (ws/control-port ws-runtime)
            program-runtime (program/runtime {:workspace port :control control})
            ws-runtime (assoc ws-runtime
                              :evaluate (partial program/evaluate-program!
                                                 program-runtime))
            fleet-runtime {:workspace (str root)
                           :workspace-runtime ws-runtime
                           :kernel (:kernel s)}
            source (str "(defprogram actor-worktree []\n"
                        "  (write {:path \"worktree.txt\" :content \"from actor\"})\n"
                        "  {:ok true})")
            payload {:agent-id (:agent s)
                     :claim-id (:claim s)
                     :task-id (:task s)
                     :attempt-id "actor-worktree-1"
                     :call-id "actor-worktree-1"
                     :role :clj-worker
                     :program-role :clj-worker
                     :holder? false
                     :s1? false
                     :tenure-id "tenure-a"
                     :workspace (str root)
                     :intent "write the worktree file through the actor tool"
                     :program source}
            out (worktree/execute-program!
                 {"tenure-a" fleet-runtime} payload)
            mismatch (worktree/execute-program!
                      {"tenure-a" fleet-runtime}
                      (assoc payload :workspace (str other)))]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (= "from actor" (slurp (io/file (str root) "worktree.txt"))))
        (is (not (.exists (io/file (str other) "worktree.txt")))
            "the tool must not write another worktree or master")
        (is (= :WORKSPACE_MISMATCH (get-in mismatch [:error :kind])))
        (is (not (.exists (io/file (str other) "worktree.txt"))))
        (is (= (str root) (:workspace payload)))
        (program/stop! program-runtime))
      (finally
        (cleanup! root)
        (cleanup! other)))))

(deftest holder-request-cannot-inherit-dedicated-worker-capabilities
  (let [root (temp-root)]
    (try
      (let [s (setup root)
            ws-runtime (ws/make-runtime {:root (:root s)
                                         :kernel (:kernel s)
                                         :write-gate (:gate s)
                                         :clock (fn [] 0)})
            port (ws/workspace-port ws-runtime)
            control (ws/control-port ws-runtime)
            program-runtime (program/runtime {:workspace port :control control})
            ws-runtime (assoc ws-runtime
                              :evaluate (partial program/evaluate-program!
                                                 program-runtime))
            source (str "(defprogram holder-worktree []\n"
                        "  (write {:path \"holder.txt\" :content \"holder\"})\n"
                        "  {:ok true})")
            payload {:agent-id (:agent s)
                     :claim-id (:claim s)
                     :task-id (:task s)
                     :attempt-id "holder-worktree-1"
                     :call-id "holder-worktree-1"
                     :role :holder
                     ;; Inconsistent caller: mission.worktree must correct
                     ;; this to :clj-worker, not grant dedicated-worker.
                     :program-role :dedicated-worker
                     :holder? true
                     :s1? false
                     :workspace (str root)
                     :intent "holder must keep clj-worker capabilities"
                     :program source}
            out (worktree/execute-program!
                 {"tenure-a" {:workspace (str root)
                              :workspace-runtime ws-runtime
                              :kernel (:kernel s)}}
                 payload)]
        (is (= :succeeded (:status out)) (pr-str out))
        (is (= "holder" (slurp (io/file (str root) "holder.txt"))))
        (program/stop! program-runtime))
      (finally
        (cleanup! root)))))
