(ns hc.compose.mission.smoke-test
  "One deterministic end-to-end mission state transition.

  The scenario covers every role boundary required by root AGENTS.md §2–§3:
  Plan S1 ask and resume, Plan relay, milestone promotion, draft ownership,
  execution handoff, Reviewer swarm start, worker binding, root-task
  completion, the atomic relay barrier, successor Reviewer, closing
  quiescence, terminal completion, and Nudge suppression at every legal
  exit.  The test uses only the public mission API and the existing relay,
  swarm board, and Nudge policy."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.support :as support]
            [hc.core.relay.barrier :as barrier]))

(defn- ok!
  [result]
  (is (:ok? result) (pr-str (:error result)))
  result)

(deftest full-plan-reviewer-swarm-lifecycle
  (let [h (support/host {:user-input "Build the smoke-test feature."})
        plan-id (support/plan-tenure-id h)]
    (testing "initial Plan uses the main model and has no dedicated worker"
      (is (= :plan (get-in (mission/snapshot h) [:bindings :plan :role])))
      (is (= :main (get-in (mission/snapshot h) [:bindings :plan :model-role])))
      (is (= "main-model" (get-in (mission/snapshot h) [:bindings :plan :model-id])))
      (is (nil? (mission/role-binding (mission/snapshot h)
                                      {:role :plan-worker}))))

    (testing "S1 ask is a legal stop exit and suppresses Nudge"
      (let [asked (support/review! h :plan plan-id [])
            nudge (mission/decide-nudge h {:role :plan})]
        (is (= :ask (get-in asked [:receipt :outcome])))
        (is (= "我需要干什么？" (get-in asked [:receipt :question])))
        (is (false? (:enabled? nudge)))
        (is (= :plan-s1-ask (get-in nudge [:exit :kind])))))

    (testing "resume continues the same tenure without a review"
      (let [resumed (ok! (mission/resume! h {:lane :plan
                                             :tenure-id plan-id}))]
        (is (= :resumed (get-in resumed [:receipt :outcome])))
        (is (false? (get-in (mission/snapshot h)
                            [:relay :lanes :plan :awaiting-user?])))))

    (testing "the first post-ask empty review relays to a fresh Plan tenure"
      (let [relayed (ok! (support/review! h :plan plan-id []))]
        (is (= :relayed (get-in relayed [:receipt :outcome])))
        (is (= :main (get-in (mission/snapshot h)
                             [:bindings :plan :model-role])))
        (is (not= plan-id (support/plan-tenure-id h)))))

    (testing "the successor promotes S1 -> S2 and unlocks draft writing"
      (let [promoted (ok! (support/review! h :plan
                                           (support/plan-tenure-id h) []))]
        (is (= :promoted (get-in promoted [:receipt :outcome])))
        (is (= :S2 (get-in promoted [:receipt :to-milestone]))))
      (is (:ok? (mission/set-draft! h {:path "plans/smoke.md"
                                       :content "Smoke draft body."
                                       :tenure-id (support/plan-tenure-id h)}))))

    (testing "S2 -> S3 -> pending stays on the same Plan tenure"
      (let [to-s3 (ok! (support/review! h :plan
                                        (support/plan-tenure-id h) []))
            to-pending (ok! (support/review! h :plan
                                             (support/plan-tenure-id h) []))]
        (is (= :S3 (get-in to-s3 [:receipt :to-milestone])))
        (is (= :pending (get-in to-pending [:receipt :to-milestone])))
        (is (= :pending (get-in (mission/snapshot h)
                                [:relay :lanes :plan :milestone])))))

    (testing "an empty pending review starts an execution tenure"
      (let [handoff (ok! (support/review! h :plan
                                          (support/plan-tenure-id h) []))
            execution-id (get-in handoff [:receipt :execution-tenure-id])]
        (is (= :execution-started (get-in handoff [:receipt :outcome])))
        (is (= :execution (get-in (mission/snapshot h)
                                  [:relay :lanes :execution execution-id
                                   :milestone])))
        (is (= :main (get-in (mission/snapshot h)
                             [:bindings :execution execution-id
                              :reviewer :model-role])))
        (is (= :handoff-complete
               (get-in (mission/decide-nudge h {:role :plan})
                       [:exit :kind])))))

    (let [execution-id (support/sole-execution-id h)]
      (testing "the Reviewer is a centralized main-model role before its review"
        (is (= "main-model" (mission/model-for-role h :reviewer)))
        (let [nudge (mission/decide-nudge h {:role :reviewer
                                             :tenure-id execution-id})]
          (is (true? (:enabled? nudge)))
          (is (= :central (:kind nudge)))))

      (testing "a non-empty Reviewer ledger creates real board root tasks"
        (let [swarm (ok! (support/review! h :execution execution-id
                                         [{:key "alpha" :value "Do alpha"}]))
              roots (support/root-task-ids h execution-id)
              state (mission/snapshot h)]
          (is (= :swarm-started (get-in swarm [:receipt :outcome])))
          (is (= 1 (count roots)))
          (is (= #{"alpha"}
                 (set (map :ledger-key
                           (support/board-tasks h execution-id)))))
          (is (= :closed (get-in state [:relay :lanes :execution execution-id
                                        :review-window])))
          (is (= :swarm (get-in state [:bindings :execution execution-id
                                       :reviewer :phase])))
          (is (false? (:enabled?
                       (mission/decide-nudge h {:role :reviewer
                                                :tenure-id execution-id}))))))

      (testing "a swarm worker is bound to the task model and can finish the root"
        (let [worker-id (support/register-worker! h execution-id :slot-a)
              worker-binding (mission/role-binding (mission/snapshot h)
                                                   {:role :worker
                                                    :tenure-id execution-id
                                                    :agent-id worker-id})
              worker-nudge (mission/decide-nudge h {:role :worker
                                                    :tenure-id execution-id
                                                    :agent-id worker-id})
              task-id (first (support/root-task-ids h execution-id))
              claimed (ok! (mission/claim-task! h
                                                {:tenure-id execution-id
                                                 :actor-id worker-id
                                                 :task-id task-id
                                                 :atomicity-reason
                                                 "one root task"}))
              finished (ok! (support/finish-task!
                             h execution-id worker-id task-id
                             (get-in claimed [:receipt :claim-id])
                             "alpha is complete"))]
          (is (= :task (:model-role worker-binding)))
          (is (= "task-model" (:model-id worker-binding)))
          (is (true? (:enabled? worker-nudge)))
          (is (= :worker (:kind worker-nudge)))
          (is (= :relayed (get-in finished [:receipt :outcome])))
          (is (true? (get-in finished [:receipt :relayed?])))
          (is (string? (get-in finished [:receipt :barrier-id])))))

      (testing "the atomic barrier leaves only the successor Reviewer"
        (let [state (mission/snapshot h)
              new-id (support/sole-execution-id h)
              barrier-record (last (get-in state [:relay :barriers]))]
          (is (not= execution-id new-id))
          (is (contains? (get-in state [:relay :retired-tenure-ids])
                         execution-id))
          (is (not (contains? (get-in state [:boards]) execution-id)))
          (is (not (contains? (get-in state [:bindings :execution])
                              execution-id)))
          (is (= :main (get-in state [:bindings :execution new-id
                                      :reviewer :model-role])))
          (is (map? (get-in state [:boards new-id])))
          (is (= execution-id (:from-tenure-id barrier-record)))
          (is (= new-id (:to-tenure-id barrier-record)))
          (is (true? (:predecessor-hidden? barrier-record)))
          (is (every? barrier/visible-context-keys
                      (keys (:context barrier-record))))))

      (testing "the successor promotes to closing and then completes"
        (let [reviewer-id (support/sole-execution-id h)
              to-closing (ok! (support/review! h :execution reviewer-id []))]
          (is (= :closing (get-in to-closing [:receipt :to-milestone])))
          (let [completed (ok! (support/review! h :execution reviewer-id []
                                                (support/quiescent-facts)))
                terminal (mission/decide-nudge h {:role :reviewer
                                                  :tenure-id reviewer-id})]
            (is (= :completed (get-in completed [:receipt :outcome])))
            (is (= :completed (get-in (mission/snapshot h)
                                      [:relay :lanes :execution reviewer-id
                                       :milestone])))
            (is (= :completed (get-in (mission/snapshot h)
                                      [:bindings :execution reviewer-id
                                       :reviewer :phase])))
            (is (false? (:enabled? terminal)))
            (is (= :terminal-completed (get-in terminal [:exit :kind])))))))

    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest root-cancel-and-replace-release-relay-ledger
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id
                                [{:key "a" :value "Do a"}
                                 {:key "b" :value "Do b"}])
        worker-id (support/register-worker! h execution-id :slot-a)
        by-key (into {} (map (juxt :ledger-key identity)
                             (support/board-tasks h execution-id)))
        a-task (:task-id (get by-key "a"))
        b-task (:task-id (get by-key "b"))
        cancelled (ok! (mission/cancel-root-task!
                        h
                        {:tenure-id execution-id
                         :actor-id worker-id
                         :task-id a-task
                         :reason "obsolete"}))
        state-after-cancel (mission/snapshot h)]
    (testing "root cancel releases only its own relay obligation"
      (is (= :root-cancelled (get-in cancelled [:receipt :outcome])))
      (is (= #{"b"} (set (keys (get-in state-after-cancel
                                       [:relay :lanes :execution execution-id
                                        :ledger])))))
      (is (= #{"b"} (set (map :ledger-key
                              (get-in state-after-cancel
                                      [:relay :lanes :execution execution-id
                                       :root-tasks])))))
      (is (= #{"b"} (set (keep :ledger-key
                               (support/board-tasks h execution-id)))))
      (is (= :active (get-in state-after-cancel
                             [:relay :lanes :execution execution-id
                              :swarm-phase]))))
    (let [replaced (ok! (mission/replace-root-task!
                         h
                         {:tenure-id execution-id
                          :actor-id worker-id
                          :task-id b-task
                          :reason "better scope"
                          :replacement {:goal "Do b better"
                                        :remaining-work "Do b better"
                                        :done-when "b is done better"}}))
          new-task-id (get-in replaced [:receipt :new-task-id])
          state-after-replace (mission/snapshot h)]
      (testing "root replace keeps the obligation and swaps the board task"
        (is (= :root-replaced (get-in replaced [:receipt :outcome])))
        (is (= #{"b"} (set (keys (get-in state-after-replace
                                         [:relay :lanes :execution execution-id
                                          :ledger])))))
        (is (= "Do b better"
               (->> (get-in state-after-replace
                            [:relay :lanes :execution execution-id :root-tasks])
                    (filter #(= "b" (:ledger-key %)))
                    first
                    :goal)))
        (is (= "b" (get-in state-after-replace
                           [:boards execution-id :tasks new-task-id
                            :ledger-key])))
        (is (= :active (get-in state-after-replace
                               [:relay :lanes :execution execution-id
                                :swarm-phase]))))
      (testing "the replacement finishes and raises the atomic barrier"
        (let [claim (ok! (mission/claim-task!
                          h
                          {:tenure-id execution-id
                           :actor-id worker-id
                           :task-id new-task-id
                           :atomicity-reason "replacement root"}))
              finished (ok! (support/finish-task!
                             h execution-id worker-id new-task-id
                             (get-in claim [:receipt :claim-id])
                             "replacement done"))]
          (is (= :relayed (get-in finished [:receipt :outcome])))
          (is (not (contains? (get-in (mission/snapshot h) [:boards])
                              execution-id)))
          (is (= :main
                 (get-in (mission/snapshot h)
                         [:bindings :execution
                          (support/sole-execution-id h) :reviewer
                          :model-role]))))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))
