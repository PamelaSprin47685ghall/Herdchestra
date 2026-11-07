(ns hc.client.swarm.fixture
  "Shared dashboard fixture for the JVM projection tests and the CLJS render
  tests.  It is plain data so both targets read the same contract.")

(def report-text
  (str "Joined after rebase.\n"
       "Implemented the authentication core and verified the table.\n"
       "Tail marker: REPORT-END-9f3c\n"))

(def fixture
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
               :report report-text
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

(def server-board-fixture
  "The shape actually emitted by hc.server.app.snapshot/board-view: board
  records and a board version, but no fleet row, token slot, write gate, or
  join queue."
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
   :board/version 7})

(def server-board-with-token
  "Board-only server shape with real token/W/slot records and no fleet row.
  This is the shape the workbench receives when the board projection has a
  swarm id and gate status but no fleet coordinator row."
  (assoc server-board-fixture
         :token-slot/by-id
         {"local" {:holder-id "agent-1" :term-id 3 :state :held
                   :swarm-id "local"}}
         :write-gate/by-id
         {"local" {:held? true :owner "agent-1"
                   :current-action "action-shell" :queue []
                   :swarm-id "local"}}
         :slot/by-id
         {"slot-a" {:slot-id "slot-a" :swarm-id "local"}
          "slot-b" {:slot-id "slot-b" :swarm-id "local"}
          "slot-c" {:slot-id "slot-c" :swarm-id "local"}
          "slot-d" {:slot-id "slot-d" :swarm-id "local"}}))

(def unresolved-calls
  "The server-published unknown-call rows for one session.

  The first call is open; the second was checked and closed by the operator.
  No request, program source, or credential is present."
  {:session/by-id
   {"s1" {:session-id "s1"
          :worker-programs
          [{:job-id "job-1"
            :task-id "task-2"
            :claim-id "claim-1"
            :agent-id "agent-4"
            :state :finished
            :outcome :failed
            :program-status :FILE_CONFLICT
            :outcome-kind :not-written
            :file-changed? false
            :file-path "/w/e2e09.txt"
            :created-at 1200
            :conflicts [{:path "/w/e2e09.txt"
                         :reason :stamp-mismatch
                         :expected {:mtime-sec 1 :mtime-nsec 2 :size 9}
                         :actual {:mtime-sec 3 :mtime-nsec 4 :size 14}}]
            :error {:code :FILE_CONFLICT
                    :message "Protected commit did not apply"}}]
          :unresolved-calls
          [{:call-id "call-1"
            :kind :tool
            :role :worker
            :lane :execution
            :tenure-id "tenure-3"
            :agent-id "agent-2"
            :member "clj-worker"
            :task-id "task-1"
            :phase :outcome-unknown
            :last-known-stage :outcome-unknown
            :outcome-unknown? true
            :created-at 1000}
           {:call-id "call-2"
            :kind :tool
            :role :plan
            :phase :succeeded
            :last-known-stage :succeeded
            :created-at 1100
            :ack {:outcome :succeeded
                  :by :operator
                  :at 2000
                  :note "checked the target file"}}]}}
   :session/active-id "s1"})
