(ns hc.compose.fleet.runtime-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hc.compose.fleet.restore :as restore]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.runtime :as runtime]
            [hc.compose.fleet.support :as support]
            [hc.compose.workspace :as ws]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]))

(defn- slice
  [id]
  {:slice-id id
   :goal (str "goal " id)
   :inputs []
   :depends-on []
   :done-when "done"})

(deftest each-swarm-gets-its-own-worktree-board-token-and-write-gate
  (support/with-temp-dir [root "hc-fleet-runtime-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [adapter (git/git-cli repo)
            ws1 (str root "/swarm-1")
            ws2 (str root "/swarm-2")]
        (is (= :ok (:status (git/create-worktree! adapter
                                                  {:workspace ws1
                                                   :branch "hc/mission/swarm-1"
                                                   :base "master"}))))
        (is (= :ok (:status (git/create-worktree! adapter
                                                  {:workspace ws2
                                                   :branch "hc/mission/swarm-2"
                                                   :base "master"}))))
        (let [factory (fn [_ws]
                        (fn [request]
                          {:status :succeeded :value (:request request)}))
              r1 (runtime/start! {:swarm-id "swarm-1"
                                  :slice-id "slice-a"
                                  :workspace ws1
                                  :branch "hc/mission/swarm-1"
                                  :slice (slice "slice-a")
                                  :evaluate-factory factory})
              r2 (runtime/start! {:swarm-id "swarm-2"
                                  :slice-id "slice-b"
                                  :workspace ws2
                                  :branch "hc/mission/swarm-2"
                                  :slice (slice "slice-b")
                                  :evaluate-factory factory})]
          (is (:ok? r1))
          (is (:ok? r2))
          (let [rt1 (:runtime r1)
                rt2 (:runtime r2)
                b1 (runtime/board rt1)
                b2 (runtime/board rt2)]
            (is (not (identical? (:write-gate rt1) (:write-gate rt2)))
                "each swarm owns a distinct write gate")
            (is (not (identical? (:kernel rt1) (:kernel rt2)))
                "each swarm owns a distinct board kernel")
            (is (= 1 (count (filter #(nil? (:parent-id %)) (vals (:tasks b1))))))
            (is (= 1 (count (filter #(nil? (:parent-id %)) (vals (:tasks b2))))))
            (is (= :free (get-in b1 [:token-slot :state])))
            (is (= :free (get-in b2 [:token-slot :state])))
            (is (= "swarm-1" (:swarm-id (runtime/token-slot rt1))))
            (is (= "swarm-2" (:swarm-id (runtime/token-slot rt2))))
            (is (= false (:held? (runtime/write-gate-status rt1))))
            (is (= false (:held? (runtime/write-gate-status rt2))))
            (is (= ws1 (:worktree (runtime/observation rt1))))
            (is (= ws2 (:worktree (runtime/observation rt2))))
            (is (= 1 (:root-ready (runtime/observation rt1))))
            (is (fn? (:evaluator rt1)))
            (is (fn? (get-in rt1 [:workspace-runtime :evaluate])))))))))

(deftest refuses-a-workspace-that-is-not-a-git-worktree
  (support/with-temp-dir [root "hc-fleet-runtime-"]
    (let [result (runtime/start! {:swarm-id "swarm-1"
                                  :slice-id "slice-a"
                                  :workspace root
                                  :branch "hc/mission/swarm-1"
                                  :slice (slice "slice-a")})]
      (is (false? (:ok? result)))
      (is (= :SWARM_WORKTREE_NOT_GIT (get-in result [:error :code]))))))

(deftest restores-a-persisted-board-without-creating-a-new-one
  (support/with-temp-dir [root "hc-fleet-runtime-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [adapter (git/git-cli repo)
            workspace (str root "/swarm-1")]
        (is (= :ok (:status (git/create-worktree! adapter
                                                  {:workspace workspace
                                                   :branch "hc/mission/swarm-1"
                                                   :base "master"}))))
        (let [started (runtime/start!
                       {:swarm-id "swarm-1"
                        :slice-id "slice-a"
                        :workspace workspace
                        :branch "hc/mission/swarm-1"
                        :slice (slice "slice-a")})
              original (:runtime started)
              board (runtime/board original)
              restored (runtime/restore!
                        {:swarm-id "swarm-1"
                         :slice-id "slice-a"
                         :workspace workspace
                         :branch "hc/mission/swarm-1"
                         :slice (slice "slice-a")
                         :board board
                         :status :queued})
              runtime (:runtime restored)]
          (is (:ok? restored))
          (is (= board (runtime/board runtime))
              "the persisted board is adopted, not replaced")
          (is (= (:control-agent-id original) (:control-agent-id runtime)))
          (is (= (:root-task-id original) (:root-task-id runtime)))
          (is (= :persisted (:runtime-source runtime)))
          (is (= :queued (:status runtime)))
          (is (= 1 (:root-ready (runtime/observation runtime)))))))))

;; ---------------------------------------------------------------------------
;; Recovery write barrier

(defn- recovery-board
  "A valid worktree board with one control actor, root task and claim.  When
  UNCERTAIN? is true it also carries one action that reached :running before
  the simulated restart."
  [swarm-id slice-id uncertain?]
  (let [kernel (swarm/create {})
        control (:agent-id
                 (swarm/execute!
                  kernel
                  {:op :register-actor
                   :slot-id (keyword (str "fleet-control-" swarm-id))}))
        task (:task-id
              (swarm/execute!
               kernel
               {:op :create-task
                :actor-id control
                :ledger-key slice-id
                :goal "recover the worktree"
                :remaining-work "verify and continue"
                :done-when "the worktree is verified"}))
        claim (:claim-id
               (swarm/execute!
                kernel
                {:op :claim
                 :actor-id control
                 :task-id task
                 :atomicity-reason "recovery verification"}))]
    (if uncertain?
      (let [action (:action-id
                    (swarm/execute!
                     kernel
                     {:op :begin-action
                      :actor-id control
                      :task-id task
                      :claim-id claim
                      :kind :edit
                      :request-id "old-edit"}))
            _ (swarm/execute!
               kernel
               {:op :advance-action
                :actor-id control
                :action-id action
                :phase :running})
            recovered (restore/recover-board (swarm/snapshot kernel) 1000)]
        {:board (:board recovered)
         :control control
         :task task
         :claim claim
         :action-id action})
      {:board (swarm/snapshot kernel)
       :control control
       :task task
       :claim claim})))

(defn- recovery-workspace!
  [root]
  (let [dir (io/file root "swarm-1")]
    (.mkdirs dir)
    (spit (io/file dir ".git") (str "gitdir: " dir))
    (str dir)))

(defn- restore-recovery-runtime
  [root board control & [on-board-event!]]
  (let [workspace (recovery-workspace! root)]
    (runtime/restore!
     (cond-> {:swarm-id "swarm-1"
              :slice-id "slice-a"
              :workspace workspace
              :branch "hc/mission/swarm-1"
              :slice {:slice-id "slice-a" :goal "goal" :done-when "done"}
              :board board
              :write-gate-recovery {:held? true
                                    :outcome-unknown? true
                                    :owner control
                                    :queue 0
                                    :reason :process-restart
                                    :recovered-at 1000}
              :status :running
              :clock (fn [] 2000)}
       on-board-event! (assoc :on-board-event! on-board-event!)))))

(deftest restore-holds-w-until-uncertain-action-and-gate-evidence-are-acknowledged
  (support/with-temp-dir [root "hc-fleet-recovery-"]
    (let [{:keys [board control task claim action-id]}
          (recovery-board "swarm-1" "slice-a" true)
          restored (restore-recovery-runtime root board control)
          runtime (:runtime restored)
          workspace (:workspace runtime)
          write-gate (:write-gate runtime)
          ws-runtime (:workspace-runtime runtime)
          payload {:op :write
                   :path "blocked.txt"
                   :content "x"
                   :agent-id control
                   :claim-id claim
                   :task-id task
                   :attempt-id "attempt-1"
                   :call-id "call-1"
                   :request-id "blocked-1"}]
      (is (:ok? restored) (pr-str restored))
      (is (true? (wg/write-gate-held? write-gate)))
      (is (= :fleet-recovery-hold (wg/write-gate-owner write-gate)))
      (is (true? (get-in (runtime/write-gate-status runtime)
                         [:recovery-blocked?])))
      (is (= #{:uncertain-actions :write-gate-held}
             (get-in (runtime/write-gate-status runtime)
                     [:recovery-reasons])))
      (let [blocked (ws/execute! ws-runtime payload)]
        (is (= :failed (:status blocked)) (pr-str blocked))
        (is (= :RECOVERY_UNRESOLVED (get-in blocked [:error :code])))
        (is (false? (.exists (io/file workspace "blocked.txt")))))
      (let [ack (runtime/acknowledge-recovery!
                 runtime {:evidence "worktree inspected"})]
        (is (false? (:ok? ack)))
        (is (= :RECOVERY_ACTIONS_PENDING (get-in ack [:error :code])))
        (is (= :uncertain
               (get-in (runtime/board runtime)
                       [:actions action-id :phase]))
            "the unknown action is never resolved by the acknowledgement")
        (is (true? (wg/write-gate-held? write-gate))))
      (let [resolved (ws/execute!
                      ws-runtime
                      {:op :resolve-action
                       :agent-id control
                       :action-id action-id
                       :result-ref {:status :RESOLVED
                                    :evidence "site matches the expected file"}})]
        (is (= :succeeded (:status resolved)) (pr-str resolved)))
      (let [ack (runtime/acknowledge-recovery!
                 runtime {:evidence "worktree and board verified"})]
        (is (:ok? ack) (pr-str ack))
        (is (:released? ack))
        (is (false? (wg/write-gate-held? write-gate)))
        (is (nil? (wg/write-gate-owner write-gate)))
        (is (false? (get-in (runtime/write-gate-status runtime)
                            [:recovery-blocked?])))
        (is (true? (get-in (runtime/write-gate-status runtime)
                           [:recovery-acknowledgement :acknowledged?]))))
      (let [after (ws/execute! ws-runtime
                               (assoc payload :request-id "after-ack"))]
        (is (= :succeeded (:status after)) (pr-str after))
        (is (= "x" (slurp (io/file workspace "blocked.txt"))))))))

(deftest restore-gate-evidence-alone-blocks-edit-until-explicit-ack
  (support/with-temp-dir [root "hc-fleet-recovery-"]
    (let [{:keys [board control task claim]}
          (recovery-board "swarm-1" "slice-a" false)
          restored (restore-recovery-runtime root board control)
          runtime (:runtime restored)
          workspace (:workspace runtime)
          write-gate (:write-gate runtime)
          ws-runtime (:workspace-runtime runtime)
          payload {:op :write
                   :path "gate-only.txt"
                   :content "x"
                   :agent-id control
                   :claim-id claim
                   :task-id task
                   :attempt-id "attempt-2"
                   :call-id "call-2"
                   :request-id "gate-only-1"}]
      (is (:ok? restored) (pr-str restored))
      (is (true? (wg/write-gate-held? write-gate)))
      (is (= #{:write-gate-held}
             (get-in (runtime/write-gate-status runtime)
                     [:recovery-reasons])))
      (let [blocked (ws/execute! ws-runtime payload)]
        (is (= :failed (:status blocked)) (pr-str blocked))
        (is (= :RECOVERY_UNRESOLVED (get-in blocked [:error :code])))
        (is (false? (.exists (io/file workspace "gate-only.txt")))))
      (let [ack (runtime/acknowledge-recovery! runtime {:evidence "  "})]
        (is (false? (:ok? ack)))
        (is (= :RECOVERY_EVIDENCE_REQUIRED (get-in ack [:error :code])))
        (is (true? (wg/write-gate-held? write-gate))))
      (let [ack (runtime/acknowledge-recovery!
                 runtime {:evidence "old gate owner checked; no live shell"})]
        (is (:ok? ack) (pr-str ack))
        (is (:released? ack))
        (is (false? (wg/write-gate-held? write-gate))))
      (let [after (ws/execute! ws-runtime
                               (assoc payload :request-id "gate-only-2"))]
        (is (= :succeeded (:status after)) (pr-str after))
        (is (= "x" (slurp (io/file workspace "gate-only.txt"))))))))

(deftest recovery-ack-release-waits-for-persistence
  (support/with-temp-dir [root "hc-fleet-recovery-"]
    (let [{:keys [board control]}
          (recovery-board "swarm-1" "slice-a" false)
          fail? (atom true)
          hook (fn [events]
                 (if (and @fail?
                          (some #(= :recovery-acknowledged (:type %))
                                events))
                   {:ok? false
                    :error {:code :FLEET_STATE_PERSIST_FAILED
                            :message "injected acknowledgement persist failure"}}
                   {:ok? true}))
          restored (restore-recovery-runtime root board control hook)
          runtime (:runtime restored)
          write-gate (:write-gate runtime)]
      (is (:ok? restored) (pr-str restored))
      (is (true? (wg/write-gate-held? write-gate)))
      (let [ack (runtime/acknowledge-recovery!
                 runtime {:evidence "worktree verified"})]
        (is (false? (:ok? ack)))
        (is (= :BOARD_EVENT_PERSIST_FAILED (get-in ack [:error :code])))
        (is (true? (wg/write-gate-held? write-gate))
            "a failed acknowledgement persist must not release W")
        (is (true? (get-in (runtime/write-gate-status runtime)
                           [:recovery-blocked?]))))
      (reset! fail? false)
      (let [ack (runtime/acknowledge-recovery!
                 runtime {:evidence "worktree verified again"})]
        (is (:ok? ack) (pr-str ack))
        (is (:released? ack))
        (is (false? (wg/write-gate-held? write-gate)))))))

(deftest restore-recognizes-its-own-acknowledged-recovery-hold
  (support/with-temp-dir [root "hc-fleet-recovery-"]
    (let [{:keys [board control task claim]}
          (recovery-board "swarm-1" "slice-a" false)
          board (assoc-in board [:recovery :acknowledgement]
                          {:acknowledged? true
                           :actor-id control
                           :evidence "verified before the previous stop"
                           :write-gate-held? true
                           :at-version (inc (:board-version board))})
          workspace (recovery-workspace! root)
          restored (runtime/restore!
                    {:swarm-id "swarm-1"
                     :slice-id "slice-a"
                     :workspace workspace
                     :branch "hc/mission/swarm-1"
                     :slice {:slice-id "slice-a" :goal "goal" :done-when "done"}
                     :board board
                     ;; This is the snapshot a crash during the
                     ;; acknowledgement write leaves behind: our own hold
                     ;; marker plus the durable acknowledgement.
                     :write-gate-recovery {:held? true
                                           :outcome-unknown? true
                                           :owner :fleet-recovery-hold
                                           :queue 0
                                           :reason :process-restart
                                           :recovered-at 1000}
                     :status :running
                     :clock (fn [] 2000)})
          runtime (:runtime restored)
          write-gate (:write-gate runtime)]
      (is (:ok? restored) (pr-str restored))
      (is (false? (wg/write-gate-held? write-gate))
          "the acknowledged hold is not re-interpreted as an old holder")
      (is (false? (get-in (runtime/write-gate-status runtime)
                          [:recovery-blocked?])))
      (let [after (ws/execute! (:workspace-runtime runtime)
                               {:op :write
                                :path "after-ack.txt"
                                :content "x"
                                :agent-id control
                                :claim-id claim
                                :task-id task
                                :attempt-id "attempt-3"
                                :call-id "call-3"
                                :request-id "after-ack-1"})]
        (is (= :succeeded (:status after)) (pr-str after))
        (is (= "x" (slurp (io/file workspace "after-ack.txt"))))))))

(deftest recover-runtimes-keeps-old-gate-evidence-before-live-reattach
  (let [evidence {:held? true
                  :outcome-unknown? true
                  :owner :agent-1
                  :queue 0
                  :reason :process-restart
                  :recovered-at 1000}
        result (restore/recover-runtimes
                {:runtimes {"swarm-1" {:board (swarm/empty-board)
                                       :write-gate-recovery evidence}}}
                {:now 2000})]
    (is (:ok? result))
    (is (= evidence
           (get-in result [:runtimes "swarm-1" :write-gate-recovery]))
        "a crash before live reattach must not drop the only gate record"))
  (let [result (restore/recover-runtimes
                {:runtimes {"swarm-1"
                            {:board (swarm/empty-board)
                             :write-gate {:held? false :queue 0}
                             :write-gate-recovery
                             {:held? true
                              :outcome-unknown? true
                              :owner :agent-1
                              :queue 0
                              :reason :process-restart
                              :recovered-at 1000}}}}
                {:now 2000})]
    (is (:ok? result))
    (is (nil? (get-in result [:runtimes "swarm-1" :write-gate-recovery]))
        "a live free gate supersedes stale evidence")))
