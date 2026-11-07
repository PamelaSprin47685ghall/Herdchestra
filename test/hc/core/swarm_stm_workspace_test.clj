(ns hc.core.swarm-stm-workspace-test
  "Focused integration with hc.core.workspace-gate's read-set verifier.

  The swarm kernel stays independent: this test injects the public
  workspace-gate closure and W port through the kernel seam.  It proves that
  verification runs while W is held, the board CAS/commit happens before W is
  released, and a changed file rejects the finish without mutating the board."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as workspace-gate])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root
  []
  (Files/createTempDirectory "hc-swarm-stm-" (make-array FileAttribute 0)))

(defn- write-text!
  [root path text]
  (spit (io/file (str root) path) text))

(defn- cleanup!
  [root]
  (doseq [f (reverse (file-seq (io/file (str root))))]
    (io/delete-file f true)))

(defn- spawn!
  [k slot]
  (:agent-id (swarm/execute! k {:op :register-actor :slot-id slot})))

(defn- task!
  [k actor]
  (:task-id (swarm/execute! k {:op :create-task :actor-id actor
                               :goal "publish file conclusion"
                               :remaining-work "verify config file"
                               :done-when "conclusion published"
                               :owner-id actor})))

(defn- finish-command
  [actor task-id read-set]
  {:op :finish :actor-id actor :task-id task-id
   :result-summary "config conclusion"
   :read-set read-set})

(defn- gate-port
  [gate]
  {:acquire (fn [owner] (workspace-gate/acquire-write-gate! gate owner))
   :release (fn [permit] (workspace-gate/release-write-gate! permit))})

(deftest workspace-verifier-accepts-matching-stamp-under-w
  (let [root (temp-root)]
    (try
      (write-text! root "config.edn" "v1")
      (let [read (workspace-gate/read-file root "config.edn")
            gate (workspace-gate/write-gate)
            verify-held? (atom false)
            commit-held? (atom false)
            verifier (fn [read-set]
                       (is (seq read-set))
                       (reset! verify-held? (workspace-gate/write-gate-held? gate))
                       {:ok? true})
            k (swarm/create
               {:verify-read-set verifier
                :on-events (fn [events]
                             (when (some #(= :task-finished (:type %)) events)
                               (reset! commit-held?
                                       (workspace-gate/write-gate-held? gate))))})
            a (spawn! k :a)
            t (task! k a)
            read-set [{:path (:path read) :stamp (:stamp read)}]
            result (swarm/execute-under-write-gate!
                    k (gate-port gate) :test-owner
                    (finish-command a t read-set))]
        (is (= :OBSERVED (:status read)))
        (is (:ok? result) (pr-str result))
        (is (true? @verify-held?) "verifier ran outside W")
        (is (true? @commit-held?) "board commit happened after W release")
        (is (false? (workspace-gate/write-gate-held? gate))
            "W was not released after commit")
        (is (= :done (get-in (swarm/snapshot k) [:tasks t :state])))
        (is (= read-set
               (get-in (swarm/snapshot k) [:tasks t :result-refs 0 :read-set])))
        (is (empty? (swarm/check-invariants k))))
      (finally
        (cleanup! root)))))

(deftest workspace-verifier-rejects-changed-file-under-w
  (let [root (temp-root)]
    (try
      (write-text! root "config.edn" "v1")
      (let [read (workspace-gate/read-file root "config.edn")
            gate (workspace-gate/write-gate)
            verify-held? (atom false)
            verifier (fn [read-set]
                       (reset! verify-held? (workspace-gate/write-gate-held? gate))
                       (workspace-gate/verify-read-set root read-set))
            k (swarm/create {:verify-read-set verifier})
            a (spawn! k :a)
            t (task! k a)
            read-set [{:path (:path read) :stamp (:stamp read)}]
            before (swarm/snapshot k)]
        ;; Change both content length and mtime; the old stamp must fail.
        (write-text! root "config.edn" "version-two")
        (let [result (swarm/execute-under-write-gate!
                      k (gate-port gate) :test-owner
                      (finish-command a t read-set))]
          (is (true? @verify-held?) "verifier ran outside W")
          (is (= :FILE_CONFLICT (:error result)) (pr-str result))
          (is (= before (swarm/snapshot k)))
          (is (= :ready (get-in (swarm/snapshot k) [:tasks t :state])))
          (is (false? (workspace-gate/write-gate-held? gate))
              "W was not released after rejection")
          (is (empty? (swarm/check-invariants k)))))
      (finally
        (cleanup! root)))))

(deftest file-dependent-finish-cannot-run-outside-w
  (let [k (swarm/create)
        a (spawn! k :a)
        t (task! k a)
        before (swarm/snapshot k)
        result (swarm/execute! k (finish-command a t [{:path "config.edn"
                                                       :stamp {:size 1}}]))]
    (is (= :WRITE_GATE_REQUIRED (:error result)) (pr-str result))
    (is (= before (swarm/snapshot k)))
    (is (= :ready (get-in (swarm/snapshot k) [:tasks t :state])))
    (is (empty? (swarm/check-invariants k))))
  (testing "W alone is not enough without a verifier"
    (let [k (swarm/create)
          a (spawn! k :a)
          t (task! k a)
          before (swarm/snapshot k)
          result (swarm/execute-under-write-gate!
                  k (gate-port (workspace-gate/write-gate)) :test-owner
                  (finish-command a t [{:path "config.edn" :stamp {:size 1}}]))]
      (is (= :READ_SET_VERIFICATION_REQUIRED (:error result)) (pr-str result))
      (is (= before (swarm/snapshot k)))
      (is (= :ready (get-in (swarm/snapshot k) [:tasks t :state])))
      (is (empty? (swarm/check-invariants k))))))
