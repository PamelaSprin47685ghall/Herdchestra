(ns hc.compose.workspace-write-ahead-test
  "Deterministic interleavings for the file-action write-ahead seam.

  The board event hook is the durable seam for a worktree action.  If it
  cannot persist, no file may be applied; if only the result publication
  cannot persist, a retry must checkpoint the already-committed action rather
  than claim publication from memory."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hc.compose.workspace :as ws]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-root
  []
  (Files/createTempDirectory "hc-write-ahead-" (make-array FileAttribute 0)))

(defn- cleanup!
  [root]
  (doseq [f (reverse (file-seq (io/file (str root))))]
    (io/delete-file f true)))

(defn- spawn!
  [kernel slot]
  (:agent-id (swarm/execute! kernel {:op :register-actor :slot-id slot})))

(defn- task!
  [kernel actor]
  (:task-id (swarm/execute! kernel
                            {:op :create-task
                             :actor-id actor
                             :goal "write-ahead persistence"
                             :remaining-work "publish one file"
                             :done-when "the file is durable"
                             :owner-id actor})))

(defn- claim!
  [kernel actor task-id]
  (:claim-id (swarm/execute! kernel
                             {:op :claim
                              :actor-id actor
                              :task-id task-id
                              :atomicity-reason "single protected batch"})))

(defn- kernel-with-hook
  "Kernel whose event hook records every committed event and fails when
  FAIL-EVENT? returns true.  The board CAS still commits; the command result
  is the failure under test."
  [fail-event? events]
  (swarm/create
   {:on-events
    (fn [committed]
      (swap! events into committed)
      (when (some fail-event? committed)
        {:ok? false
         :error {:code :FLEET_STATE_PERSIST_FAILED
                 :message "injected persistence failure"}}))}))

(defn- write-payload
  [actor task-id claim-id request-id]
  {:op :write
   :path "a.txt"
   :content "x"
   :agent-id actor
   :claim-id claim-id
   :task-id task-id
   :attempt-id "attempt-1"
   :call-id "call-1"
   :request-id request-id})

(deftest begin-persistence-failure-never-applies-the-file
  (let [root (temp-root)]
    (try
      (let [events (atom [])
            fail? (atom #(= :action-begun (:type %)))
            kernel (kernel-with-hook #(@fail? %) events)
            actor (spawn! kernel :slot-a)
            task-id (task! kernel actor)
            claim-id (claim! kernel actor task-id)
            applies (atom 0)
            runtime (ws/make-runtime
                     {:root (str root)
                      :kernel kernel
                      :write-gate (wg/write-gate)
                      :clock (fn [] 0)
                      :apply-fn (fn [prepared opts]
                                  (swap! applies inc)
                                  (wg/apply-plan! prepared opts))})
            payload (write-payload actor task-id claim-id "wa-begin")
            result (ws/execute! runtime payload)]
        (is (= :failed (:status result)) (pr-str result))
        (is (= :BOARD_EVENT_PERSIST_FAILED (get-in result [:error :code])))
        (is (zero? @applies) "the file applier never ran")
        (is (false? (.exists (io/file (str root) "a.txt"))))
        (let [action (some #(when (= "wa-begin" (:request-id %)) %)
                           (vals (:actions (swarm/snapshot kernel))))]
          (is (some? action))
          (is (= :uncertain (:phase action))
              "the created action is closed without claiming a file write")
          (let [retry (ws/execute! runtime payload)]
            (is (= :failed (:status retry)))
            ;; The gate registry normally replays the cached
            ;; :BOARD_EVENT_PERSIST_FAILED.  A direct adapter replay sees the
            ;; now-:uncertain action and rejects :CLAIM_CHANGED.  Both are
            ;; safe: the file applier never runs.
            (is (contains? #{:BOARD_EVENT_PERSIST_FAILED :CLAIM_CHANGED}
                           (get-in retry [:error :code])))
            (is (zero? @applies) "a replay of the same request never applies")
            (is (false? (.exists (io/file (str root) "a.txt")))))
          (reset! fail? (constantly false))
          (let [resolved (ws/execute! runtime
                                      {:op :resolve-action
                                       :agent-id actor
                                       :action-id (:action-id action)
                                       :result-ref {:status :RESOLVED
                                                    :evidence "site inspected"}})]
            (is (= :succeeded (:status resolved)) (pr-str resolved)))
          (let [after (ws/execute! runtime
                                   (assoc payload :request-id "wa-after"))]
            (is (= :succeeded (:status after)) (pr-str after))
            (is (= 1 @applies))
            (is (= "x" (slurp (io/file (str root) "a.txt")))))))
      (finally
        (cleanup! root)))))

(deftest publication-persistence-failure-checkpoints-instead-of-claiming-memory
  (let [root (temp-root)]
    (try
      (let [events (atom [])
            fail? (atom (fn [event]
                          (and (= :action-advanced (:type event))
                               (= :finished (:to event)))))
            kernel (kernel-with-hook #(@fail? %) events)
            actor (spawn! kernel :slot-a)
            task-id (task! kernel actor)
            claim-id (claim! kernel actor task-id)
            applies (atom 0)
            runtime (ws/make-runtime
                     {:root (str root)
                      :kernel kernel
                      :write-gate (wg/write-gate)
                      :clock (fn [] 0)
                      :apply-fn (fn [prepared opts]
                                  (swap! applies inc)
                                  (wg/apply-plan! prepared opts))})
            result (ws/execute! runtime
                                (write-payload actor task-id claim-id
                                               "wa-publish"))]
        (is (= :succeeded (:status result)) (pr-str result))
        (is (true? (get-in result [:value :published?])) (pr-str result))
        (is (= 1 @applies) "the file apply is never replayed")
        (is (= "x" (slurp (io/file (str root) "a.txt"))))
        (is (some #(= :board-checkpoint (:type %)) @events)
            "the retry forced the finished action through the durable hook")
        (let [action (some #(when (= "wa-publish" (:request-id %)) %)
                           (vals (:actions (swarm/snapshot kernel))))]
          (is (some? action))
          (is (= :finished (:phase action)))))
      (finally
        (cleanup! root)))))
