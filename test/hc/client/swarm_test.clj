(ns hc.client.swarm-test
  "Contract tests for the pure swarm/fleet dashboard projection.

  The view is read-only.  These tests pin the distinctions the workbench must
  preserve: a released wait is not a done parent, normal token rotation is not
  a crash, a holder without W is not a broken lock, and a join report is never
  shortened.  A recovery barrier keeps its reasons, unresolved action IDs,
  old-gate evidence, and persisted acknowledgement."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hc.client.swarm.fixture :as fixture]
            [hc.client.swarm.model :as model]
            [hc.client.sync.adapter :as adapter]))

(def ^:private long-report
  (str "Joined after rebase.\n"
       "First line of the full report.\n"
       (apply str (repeat 40 "a report line that must stay complete\n"))
       "TAIL-MARKER-7f21\n"))

(defn- fixture
  []
  {:swarm/by-id
   {"swarm-1" {:id "swarm-1"
               :slice-id "auth-core"
               :worktree "/worktrees/mission/swarm-1"
               :status :running
               :root-ready 1
               :root-waiting 1
               :root-done 0
               :queued-count 0
               :last-event :result-queued
               :master-version 7}
    "swarm-2" {:id "swarm-2"
               :slice-id "ui-shell"
               :worktree "/worktrees/mission/swarm-2"
               :status :joined
               :root-ready 0
               :root-waiting 0
               :root-done 1
               :queued-count 0
               :last-event :joined
               :master-version 8
               :report long-report
               :ff-commit "abc123"
               :rebase-rounds 2}}
   :task/by-id
   {"task-root" {:id "task-root" :swarm-id "swarm-1"
                 :goal "Integrate authentication core"
                 :remaining-work "Wire the session owner and run the integration test"
                 :done-when "auth integration test passes"
                 :state :ready
                 :ledger-key :review-auth
                 :child-ids ["task-child"]}
    "task-child" {:id "task-child" :swarm-id "swarm-1"
                  :parent-id "task-root"
                  :goal "Implement token parsing"
                  :remaining-work "none"
                  :done-when "parser unit test passes"
                  :state :done}
    "task-waiting" {:id "task-waiting" :swarm-id "swarm-1"
                    :goal "Wait for parser"
                    :remaining-work "integrate parser"
                    :done-when "integration test passes"
                    :state :waiting
                    :wait-for ["task-deep"]}
    "task-deep" {:id "task-deep" :swarm-id "swarm-1"
                 :goal "Deep dependency"
                 :remaining-work "finish deep work"
                 :done-when "deep test passes"
                 :state :ready}
    "task-done" {:id "task-done" :swarm-id "swarm-2"
                 :goal "Ship the shell"
                 :remaining-work "none"
                 :done-when "shell merged"
                 :state :done
                 :ledger-key :review-shell}}
   :action/by-id
   {"action-shell" {:id "action-shell" :swarm-id "swarm-1"
                    :task-id "task-child"
                    :agent-id "agent-holder"
                    :kind :shell
                    :phase :running}
    "action-edit" {:id "action-edit" :swarm-id "swarm-1"
                   :task-id "task-root"
                   :agent-id "agent-worker"
                   :kind :edit
                   :phase :waiting-gate}}
   :agent/by-id
   {"agent-holder" {:id "agent-holder" :swarm-id "swarm-1"
                    :slot-id "slot-a" :role :holder :life-state :alive
                    :generation 2}
    "agent-worker" {:id "agent-worker" :swarm-id "swarm-1"
                    :slot-id "slot-b" :role :normal :life-state :dead
                    :death-reason :attempt-lost
                    :handoff-note "remaining ordinary work returned to the board"}
    "agent-failed" {:id "agent-failed" :swarm-id "swarm-1"
                    :slot-id "slot-c" :role :normal :life-state :terminated
                    :death-reason :failed
                    :handoff-note "tool crashed"}}
   :slot/by-id
   {"slot-a" {:slot-id "slot-a" :swarm-id "swarm-1"}
    "slot-b" {:slot-id "slot-b" :swarm-id "swarm-1"}
    "slot-c" {:slot-id "slot-c" :swarm-id "swarm-1"}
    "slot-d" {:slot-id "slot-d" :swarm-id "swarm-1"}}
   :token-slot/by-id
   {"swarm-1" {:holder-id "agent-holder" :term-id 4 :state :held}}
   :write-gate/by-id
   {"swarm-1" {:held? true
               :owner "agent-holder"
               :current-action "action-shell"
               :queue ["action-edit"]}}
   :ui/selected-swarm-id "swarm-1"})

(defn- dashboard
  []
  (model/dashboard-from-db (fixture)))

(defn- swarm
  [id]
  (some #(when (= id (:swarm-id %)) %) (:swarms (dashboard))))

(deftest fleet-rows
  (let [d (dashboard)
        rows (:swarms d)]
    (testing "every swarm is kept, including joined"
      (is (= #{"swarm-1" "swarm-2"} (set (map :swarm-id rows)))))
    (testing "fleet row fields"
      (is (= "auth-core" (:slice-id (swarm "swarm-1"))))
      (is (= "/worktrees/mission/swarm-1" (:worktree (swarm "swarm-1"))))
      (is (= :running (:status (swarm "swarm-1"))))
      (is (= "Running" (:status-label (swarm "swarm-1"))))
      (is (= 7 (:master-version (swarm "swarm-1"))))
      (is (= 0 (:queued-count (swarm "swarm-1")))))
    (testing "summary counts"
      (is (= 2 (get-in d [:summary :swarms])))
      (is (= 1 (get-in d [:summary :running])))
      (is (= 1 (get-in d [:summary :joined]))))
    (testing "selected swarm"
      (is (= "swarm-1" (:selected-swarm-id d))))))

(deftest task-hierarchy-and-wait-release
  (let [s (swarm "swarm-1")
        roots (:root-tasks s)
        root (some #(when (= "task-root" (:task-id %)) %) roots)]
    (testing "the child tree is complete"
      (is (= 1 (count (:children root))))
      (is (= "task-child" (-> root :children first :task-id)))
      (is (= :done (-> root :children first :state))))
    (testing "a ready parent whose children are done is not auto-done"
      (is (= :ready (:state root)))
      (is (str/includes? (:state-detail root) "Wait released"))
      (is (not (str/includes? (:state-detail root) "Done —"))))
    (testing "waiting tasks expose a concrete wait chain"
      (let [waiting (some #(when (= "task-waiting" (:task-id %)) %) (:tasks s))]
        (is (= :waiting (:state waiting)))
        (is (str/includes? (:state-detail waiting) "Waiting on 1"))
        (is (= "task-deep" (-> waiting :wait-chain first :task-id)))
        (is (= :ready (-> waiting :wait-chain first :state)))))))

(deftest parent-is-never-completed-by-children
  (let [tasks {"p" {:task-id "p" :goal "Parent" :state :ready
                    :remaining-work "integration" :done-when "all"
                    :child-ids ["c"]}
               "c" {:task-id "c" :parent-id "p" :goal "Child"
                    :state :done :remaining-work "none" :done-when "child"}}
        tree (model/task-tree tasks)]
    (is (= :ready (-> tree first :state)))
    (is (str/includes? (-> tree first :state-detail) "not automatically done"))))

(deftest missing-and-cycle-nodes-remain-visible
  (let [tasks {"p" {:task-id "p" :goal "Parent" :state :waiting
                    :remaining-work "x" :done-when "y"
                    :child-ids ["missing"]}
               "a" {:task-id "a" :goal "A" :state :waiting
                    :remaining-work "x" :done-when "y" :wait-for ["b"]}
               "b" {:task-id "b" :goal "B" :state :waiting
                    :remaining-work "x" :done-when "y" :wait-for ["a"]}}
        tree (model/task-tree tasks)
        parent (some #(when (= "p" (:task-id %)) %) tree)]
    (is (= "missing" (-> parent :children first :task-id)))
    (is (true? (-> parent :children first :missing?)))
    (testing "a wait cycle is marked and not followed forever"
      (let [a (some #(when (= "a" (:task-id %)) %) tree)
            chain (-> a :wait-chain first :children first)]
        (is (= "a" (:task-id chain)))
        (is (true? (:cycle? chain)))))))

(deftest normal-lifecycle-is-not-a-crash
  (let [slots (:slots (swarm "swarm-1"))
        by-slot (into {} (map (juxt :slot-id identity) slots))]
    (testing "competition loss is a normal lifecycle transition"
      (let [worker (get by-slot "slot-b")]
        (is (= :dead (:life-state worker)))
        (is (false? (get-in worker [:lifecycle :attention?])))
        (is (str/includes? (get-in worker [:lifecycle :description])
                           "normal lifecycle"))))
    (testing "a failed actor is surfaced for attention"
      (let [failed (get by-slot "slot-c")]
        (is (true? (get-in failed [:lifecycle :attention?])))
        (is (str/includes? (get-in failed [:lifecycle :description])
                           "unexpected failure"))))
    (testing "a terminated actor without a reason is not called deliberate"
      (let [row (first (model/slot-rows
                        [{:agent-id "agent-x" :slot-id "slot-x"
                          :role :normal :life-state :terminated}]
                        nil))]
        (is (= "Terminated — reason not reported"
               (get-in row [:lifecycle :label])))
        (is (true? (get-in row [:lifecycle :attention?])))
        (is (str/includes? (get-in row [:lifecycle :description])
                           "did not report"))))
    (testing "an explicit vacant slot is still shown"
      (let [vacant (get by-slot "slot-d")]
        (is (true? (:vacant? vacant)))
        (is (= "Vacant" (:life-state-label vacant)))))
    (testing "the holder is shown on its live slot"
      (let [holder (get by-slot "slot-a")]
        (is (= "agent-holder" (:agent-id holder)))
        (is (= :holder (:role holder)))
        (is (true? (:current? holder)))))))

(deftest holder-responsibility
  (let [holder (:holder (swarm "swarm-1"))]
    (testing "R(holder) includes the active shell action"
      (is (= "agent-holder" (:holder-id holder)))
      (is (= 4 (:term-id holder)))
      (is (= #{"action-shell"} (:responsibility holder)))
      (is (true? (:shell-active? holder)))
      (is (str/includes? (:explanation holder) "managed shell")))
    (testing "the holder is not retired while responsibility remains"
      (is (false? (:empty? holder)))
      (is (false? (:inconsistent? holder))))
    (testing "an explicitly reported empty responsibility set is an inconsistency"
      (let [view (model/holder-view {} {}
                                    {:holder-id "h" :term-id 1 :state :held
                                     :responsibility []})]
        (is (true? (:empty? view)))
        (is (true? (:inconsistent? view)))
        (is (false? (:computed? view)))
        (is (str/includes? (:explanation view) "R(holder) = ∅"))))
    (testing "an absent responsibility set is not the same as an empty one"
      (let [view (model/holder-view {} {} {:holder-id "h" :term-id 1 :state :held})]
        (is (false? (:empty? view)))
        (is (false? (:inconsistent? view)))
        (is (true? (:computed? view)))
        (is (str/includes? (:explanation view) "did not report R(holder)"))))))

(deftest declared-counts-are-shown-without-a-task-table
  (let [dashboard (model/dashboard
                   {:swarm/by-id
                    {"s" {:swarm-id "s" :status :running
                          :root-ready 2 :root-waiting 1 :root-done 3}}})
        row (:selected dashboard)]
    (is (= {:ready 2 :waiting 1 :done 3}
           (:root-counts-displayed row)))
    (is (= :declared (:root-counts-source row)))
    (is (false? (:root-count-mismatch? row)))
    (is (false? (:attention? row)))))

(deftest recovery-acknowledgement-requires-true
  (let [view (model/write-gate-view
              {:held? false
               :queue 0
               :recovery-acknowledgement {:acknowledged? false
                                          :evidence "not yet accepted"}}
              {} nil)]
    (is (= :none (:recovery-state view)))
    (is (str/includes? (:explanation view)
                       "without acknowledgement"))))

(deftest wait-chain-truncation-and-raw-id-cycle
  (let [tasks (into {}
                    (for [n (range 35)]
                      [(str "t" n) {:task-id (str "t" n)
                                    :state :waiting
                                    :wait-for [(str "t" (inc n))]}]))
        chain (model/wait-chain tasks (get tasks "t0"))
        last-node (loop [node (first chain)
                         depth 0]
                    (if (or (nil? node)
                            (empty? (:children node))
                            (>= depth 40))
                      node
                      (recur (first (:children node)) (inc depth))))]
    (is (true? (:truncated? last-node))))
  (let [tasks {"a" {:id "a" :state :waiting :wait-for ["a"]}}
        chain (model/wait-chain tasks (get tasks "a"))]
    (is (true? (:cycle? (first chain))))))

(deftest slot-agent-without-actor-is-not-vacant
  (let [row (first (model/slot-rows [] [{:slot-id "slot-x"
                                        :agent-id "agent-x"}]
                                    nil))]
    (is (false? (:vacant? row)))
    (is (= "agent-x" (:agent-id row)))
    (is (= "Not reported" (:life-state-label row)))
    (is (true? (:attention? row)))))

(deftest write-gate-distinguishes-holder-from-w
  (let [w (:write-gate (swarm "swarm-1"))]
    (testing "the shell holds W"
      (is (true? (:held? w)))
      (is (= :shell (:mode w)))
      (is (= "agent-holder" (:owner w)))
      (is (= "action-shell" (:current-action-id w)))
      (is (= 1 (:queued-count w)))
      (is (= "action-edit" (-> w :queue first :action-id)))
      (is (true? (:w-held-by-holder? w)))
      (is (false? (:holder-not-writing? w)))
      (is (contains? w :recovery-blocked?))
      (is (contains? w :recovery-acknowledgement)))
    (testing "a holder with an unreported W is unknown, not a free lock"
      (let [view (model/write-gate-view nil {} {:holder-id "h"})]
        (is (false? (:reported? view)))
        (is (false? (:held? view)))
        (is (false? (:holder-not-writing? view)))
        (is (contains? view :recovery-blocked?))
        (is (contains? view :recovery-acknowledgement))
        (is (str/includes? (:explanation view) "did not report W"))
        (is (str/includes? (:explanation view) "Queue state is unknown"))))
    (testing "applying is distinct from a shell"
      (let [view (model/write-gate-view
                  {:held? true :owner "h"
                   :current-action {:action-id "a" :agent-id "h"
                                    :kind :edit :phase :applying}}
                  {} {:holder-id "h"})]
        (is (= :applying (:mode view)))
        (is (str/includes? (:mode-label view) "applying"))))
    (testing "a free W with a token holder is reported honestly"
      (let [view (model/write-gate-view
                  {:held? false :queue []}
                  {} {:holder-id "h"})]
        (is (= :idle (:mode view)))
        (is (true? (:holder-not-writing? view)))
        (is (str/includes? (:explanation view) "W is free"))))))

(deftest write-gate-projects-the-recovery-barrier
  (testing "keyword IDs lose their leading colon at the identity boundary"
    (is (= "agent-1" (model/id-string :agent-1)))
    (is (= "agent-1" (model/id-string "agent-1"))))
  (let [view (model/write-gate-view
              {:held? true
               :owner :fleet-recovery-hold
               :queue 0
               :recovery-blocked? true
               :recovery-actions [:action-1]
               :recovery-reasons #{:uncertain-actions}
               :recovery-write-gate {:held? true
                                     :owner :agent-1
                                     :queue 0
                                     :reason :process-restart}}
              {} nil)]
    (is (= :recovery (:mode view)))
    (is (true? (:recovery-blocked? view)))
    (is (= ["action-1"] (:recovery-actions view)))
    (is (= [:uncertain-actions] (:recovery-reasons view)))
    (is (= :blocked (:recovery-state view)))
    (is (= ["unresolved uncertain action(s)"]
           (:recovery-reason-labels view)))
    (is (= "agent-1" (get-in view [:recovery-write-gate :owner])))
    (is (= :process-restart (get-in view [:recovery-write-gate :reason])))
    (is (true? (:attention? view)))
    (is (str/includes? (:mode-label view) "recovery"))
    (is (str/includes? (:explanation view) "unresolved uncertain action(s)"))
    (is (str/includes? (:explanation view) ":RECOVERY_UNRESOLVED")))
  (let [view (model/write-gate-view
              {:held? false
               :queue 0
               :recovery-blocked? false
               :recovery-acknowledgement {:acknowledged? true
                                          :evidence "verified"
                                          :actor-id "agent-1"
                                          :write-gate-held? true
                                          :at-version 9}}
              {} nil)]
    (is (= :idle (:mode view)))
    (is (= :acknowledged (:recovery-state view)))
    (is (true? (get-in view [:recovery-acknowledgement :acknowledged?])))
    (is (= "verified"
           (get-in view [:recovery-acknowledgement :evidence])))
    (is (= "agent-1"
           (get-in view [:recovery-acknowledgement :actor-id])))
    (is (= 9 (get-in view [:recovery-acknowledgement :at-version])))
    (is (str/includes? (:explanation view) "Recovery was acknowledged"))
    (is (str/includes? (:explanation view) "verified"))
    (is (false? (:attention? view)))))

(deftest recovery-evidence-survives-string-wire-keys
  (let [view (model/write-gate-view
              {"held?" true
               "owner" "fleet-recovery-hold"
               "queue" 0
               "recovery-blocked?" true
               "recovery-actions" ["action-9"]
               "recovery-reasons" ["write-gate-held"]
               "recovery-write-gate" {"held?" true
                                      "owner" "old-agent"
                                      "queue" 2
                                      "outcome-unknown?" true
                                      "queue-abandoned?" true
                                      "reason" "process-restart"
                                      "recovered-at" 1700000000000}
               "recovery-acknowledgement" {"acknowledged?" true
                                           "actor-id" "operator-2"
                                           "evidence" "checked by hand"
                                           "write-gate-held?" true
                                           "at-version" 11}}
              {} nil)]
    (is (= :recovery (:mode view)))
    (is (= :blocked (:recovery-state view)))
    (is (= ["action-9"] (:recovery-actions view)))
    (is (= [:write-gate-held] (:recovery-reasons view)))
    (is (= ["the previous process held W without a proven outcome"]
           (:recovery-reason-labels view)))
    (is (= "unknown recovery reason mystery"
           (model/recovery-reason-label :mystery)))
    (is (= "old-agent" (get-in view [:recovery-write-gate :owner])))
    (is (= 2 (get-in view [:recovery-write-gate :queue])))
    (is (true? (get-in view [:recovery-write-gate :outcome-unknown?])))
    (is (true? (get-in view [:recovery-write-gate :queue-abandoned?])))
    (is (= 1700000000000
           (get-in view [:recovery-write-gate :recovered-at])))
    (is (true? (get-in view [:recovery-acknowledgement :acknowledged?])))
    (is (= "operator-2"
           (get-in view [:recovery-acknowledgement :actor-id])))
    (is (= "checked by hand"
           (get-in view [:recovery-acknowledgement :evidence])))
    (is (= 11 (get-in view [:recovery-acknowledgement :at-version])))))

(deftest dashboard-preserves-recovery-evidence-for-the-workbench
  (let [dashboard
        (model/dashboard
         {:swarm/by-id
          {"swarm-1" {:swarm-id "swarm-1" :status :running}}
          :write-gate/by-id
          {"swarm-1" {:swarm-id "swarm-1"
                      :held? true
                      :owner "fleet-recovery-hold"
                      :queue 0
                      :recovery-blocked? true
                      :recovery-reasons #{:uncertain-actions :write-gate-held}
                      :recovery-actions ["action-7"]
                      :recovery-write-gate {:held? true
                                            :owner "old-agent"
                                            :queue 1
                                            :outcome-unknown? true
                                            :queue-abandoned? true
                                            :reason :process-restart
                                            :recovered-at 1700000000000}
                      :recovery-acknowledgement nil}}})
        row (:selected dashboard)
        write-gate (:write-gate row)]
    (is (= :recovery (:mode write-gate)))
    (is (= :blocked (:recovery-state write-gate)))
    (is (= ["action-7"] (:recovery-actions write-gate)))
    (is (= #{:uncertain-actions :write-gate-held}
           (set (:recovery-reasons write-gate))))
    (is (= 2 (count (:recovery-reason-labels write-gate))))
    (is (= "old-agent" (get-in write-gate [:recovery-write-gate :owner])))
    (is (true? (:attention? row)))
    (is (contains? write-gate :recovery-acknowledgement))))

(deftest join-report-is-never-shortened
  (let [s (swarm "swarm-2")
        report (:join-report s)]
    (is (= :joined (:status s)))
    (is (= long-report (:report s)))
    (is (= long-report (:report report)))
    (is (true? (:report-present? report)))
    (is (str/ends-with? (:report report) "TAIL-MARKER-7f21\n"))
    (is (= "abc123" (:ff-commit report)))
    (is (= 2 (:rebase-rounds report)))
    (is (str/includes? (:join-queue-text s) "full report"))))

(deftest declared-root-count-mismatch-is-visible
  (let [s (swarm "swarm-1")]
    ;; The fixture declares one waiting root, but the computed tree has no
    ;; waiting root.  The row must show the disagreement rather than hide it.
    (is (true? (:root-count-mismatch? s)))
    (is (true? (:attention? s)))
    (is (= {:ready 2 :waiting 1 :done 0 :total 3}
           (:root-counts s)))))

(deftest nested-board-snapshot
  (let [snapshot
        {:swarms
         [{:swarm-id "nested-1"
           :slice-id "nested"
           :worktree "/wt/nested"
           :status :running
           :board {:tasks [{:task-id "t1" :goal "Nested root"
                            :state :ready :remaining-work "x"
                            :done-when "y"}]
                   :actors [{:agent-id "a1" :slot-id "s1" :role :normal
                             :life-state :alive}]
                   :token-slot {:holder-id nil :term-id 0 :state :free}
                   :write-gate {:held? false :queue []}}}]}
        d (model/dashboard snapshot)
        s (first (:swarms d))]
    (is (= "nested-1" (:swarm-id s)))
    (is (= 1 (count (:tasks s))))
    (is (= "a1" (-> s :slots first :agent-id)))
    (is (= :idle (-> s :write-gate :mode)))))

(deftest empty-and-string-shapes
  (testing "an empty database produces an empty dashboard"
    (let [d (model/dashboard-from-db {})]
      (is (true? (:empty? d)))
      (is (zero? (get-in d [:summary :swarms])))
      (is (nil? (:selected d)))))
  (testing "string-keyed status and IDs normalize"
    (let [d (model/dashboard
             {:swarms [{:swarm-id "s" :slice-id "slice" :status "running"
                        :board {:tasks {"t" {:task-id "t" :goal "G"
                                            :state "waiting"
                                            :wait-for []}}}
                        :token-slot {:holder-id nil}}]})]
      (is (= :running (-> d :swarms first :status)))
      (is (= :waiting (-> d :swarms first :tasks first :state))))))

(deftest board-only-server-shape
  (testing "the actual server board projection has no fleet row; it still renders as a board-only row"
    (let [snapshot
          {:task/by-id
           {"task-root" {:task-id "task-root" :ledger-key :review-auth
                         :goal "Integrate authentication core"
                         :remaining-work "wire it"
                         :done-when "tests pass"
                         :state :ready
                         :child-ids ["task-child"]}
            "task-child" {:task-id "task-child" :parent-id "task-root"
                          :goal "Implement token parsing"
                          :remaining-work "none"
                          :done-when "unit test"
                          :state :done}
            "task-waiting" {:task-id "task-waiting"
                            :goal "Wait for parser"
                            :remaining-work "integrate"
                            :done-when "integration test"
                            :state :waiting
                            :wait-for #{"task-deep"}}
            "task-deep" {:task-id "task-deep"
                         :goal "Deep dependency"
                         :remaining-work "finish"
                         :done-when "deep test"
                         :state :ready}}
           :action/by-id
           {"action-shell" {:action-id "action-shell"
                            :task-id "task-child"
                            :agent-id "agent-1"
                            :kind :shell
                            :phase :running}}
           :agent/by-id
           {"agent-1" {:agent-id "agent-1" :slot-id "slot-a"
                       :role :holder :life-state :alive :generation 2}
            "agent-2" {:agent-id "agent-2" :slot-id "slot-b"
                       :role :normal :life-state :dead
                       :death-reason :attempt-lost}}
           :board/version 7}
          d (model/dashboard-from-db snapshot)
          row (first (:swarms d))]
      (is (= 1 (count (:swarms d))))
      (is (false? (:fleet-reported? row)))
      (is (= :unknown (:status row)))
      (is (= "Status not reported" (:status-label row)))
      (is (nil? (:master-version row)))
      (is (= 7 (:board-version row)))
      (is (= {:ready 2 :waiting 1 :done 0 :total 3} (:root-counts row)))
      (is (str/includes? (:join-queue-text row) "not reported"))
      (is (false? (get-in row [:holder :reported?])))
      (is (str/includes? (get-in row [:holder :explanation]) "did not report a token slot"))
      (is (str/includes? (:state-detail (some #(when (= "task-root" (:task-id %)) %) (:tasks row))) "Wait released"))
      (is (= 1 (get-in d [:summary :board-only])))
      (is (= 1 (get-in d [:summary :unknown])))
      (is (str/includes? (get-in d [:summary :text]) "board-only"))
      (is (false? (get-in row [:write-gate :reported?])))
      (is (false? (get-in row [:write-gate :holder-not-writing?])))
      (is (str/includes? (get-in row [:write-gate :explanation]) "Queue state is unknown"))
      (is (not (str/includes? (get-in row [:write-gate :explanation])
                              "No writer is waiting"))))))

(deftest unreported-w-never-claims-a-free-queue-or-holder-relation
  (let [view (model/write-gate-view nil {} {:holder-id "holder-1"})]
    (is (false? (:reported? view)))
    (is (= :unreported (:mode view)))
    (is (false? (:holder-not-writing? view)))
    (is (str/includes? (:explanation view) "did not report W"))
    (is (str/includes? (:explanation view) "Queue state is unknown"))
    (is (not (str/includes? (:explanation view) "No writer is waiting")))))

(deftest board-only-with-real-token-w-and-slots
  (let [d (model/dashboard-from-db fixture/server-board-with-token)
        row (first (:swarms d))
        slot-d (some #(when (= "slot-d" (:slot-id %)) %) (:slots row))]
    (is (= 1 (count (:swarms d))))
    (is (false? (:fleet-reported? row)))
    (is (true? (get-in row [:holder :reported?])))
    (is (= "agent-1" (get-in row [:holder :holder-id])))
    (is (= 3 (get-in row [:holder :term-id])))
    (is (= #{"action-shell"} (get-in row [:holder :responsibility])))
    (is (true? (get-in row [:holder :shell-active?])))
    (is (true? (get-in row [:write-gate :reported?])))
    (is (= :shell (get-in row [:write-gate :mode])))
    (is (= "agent-1" (get-in row [:write-gate :owner])))
    (is (= "action-shell" (get-in row [:write-gate :current-action-id])))
    (is (some? slot-d))
    (is (true? (:vacant? slot-d)))
    (is (= "Vacant" (:life-state-label slot-d)))))

(deftest unknown-calls-are-projected-with-acknowledgement
  (let [d (model/dashboard-from-db fixture/unresolved-calls)
        calls (:unresolved-calls d)
        open (first calls)
        closed (second calls)]
    (is (= "s1" (:session-id d)))
    (is (= 2 (count calls)))
    (is (= {:total 2 :open 1 :acknowledged 1}
           (:unresolved-summary d)))
    (is (true? (:outcome-unknown? open)))
    (is (nil? (:ack open)))
    (is (= "clj-worker" (:member open)))
    (is (= "task-1" (:task-id open)))
    (is (= "outcome-unknown" (:last-known-stage open)))
    (is (nil? (:ack-label open)))
    (is (false? (:outcome-unknown? closed)))
    (is (= :succeeded (:ack-outcome closed)))
    (is (= "已确认成功" (:ack-label closed)))
    (is (= "checked the target file" (:ack-note closed)))
    (is (not (contains? open :request))
        "the browser projection never carries the request or program source")
    (is (not (contains? open :source)))))

(deftest worker-programs-are-projected-with-conflict-stamps
  (let [d (model/dashboard-from-db fixture/unresolved-calls)
        program (first (:worker-programs d))]
    (is (= 1 (count (:worker-programs d))))
    (is (= "job-1" (:job-id program)))
    (is (= "FILE_CONFLICT" (:program-status program)))
    (is (true? (:conflict? program)))
    (is (= "stamp-mismatch" (get-in program [:conflicts 0 :reason])))
    (is (= 1 (get-in program [:conflicts 0 :expected :mtime-sec])))
    (is (= 3 (get-in program [:conflicts 0 :actual :mtime-sec])))
    (is (= "/w/e2e09.txt" (:file-path program)))
    (is (= {:total 1 :conflicts 1 :applied 0}
           (:worker-program-summary d)))))

(deftest sync-preserves-worker-programs-from-the-server-snapshot
  (let [snapshot {:session/by-id
                  {"s1" {:session-id "s1"
                         :worker-programs
                         [{:job-id "job-sync"
                           :task-id "task-2"
                           :program-status :FILE_CONFLICT
                           :conflicts [{:path "/w/e2e09.txt"
                                        :reason :stamp-mismatch
                                        :expected {:mtime-sec 1 :mtime-nsec 2 :size 9}
                                        :actual {:mtime-sec 3 :mtime-nsec 4 :size 14}}]}]}}
                  :session/active-id "s1"}
        db (adapter/merge-into-db {} {:db snapshot})
        d (model/dashboard-from-db db)
        program (first (:worker-programs d))]
    (is (= 1 (count (:worker-programs d)))
        "the sync adapter must not drop the session summary field")
    (is (= "job-sync" (:job-id program)))
    (is (= "FILE_CONFLICT" (:program-status program)))
    (is (= 1 (get-in program [:conflicts 0 :expected :mtime-sec])))))
