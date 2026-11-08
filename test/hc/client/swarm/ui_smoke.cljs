(ns hc.client.swarm.ui-smoke
  "Node smoke runner for the swarm dashboard.

  This is not a test namespace.  It renders the real React/Framework7 element
  tree with ReactDOMServer and prints the checks that the Node verification
  script consumes.  The ClojureScript test namespace exercises the same view
  with `cljs.test`."
  (:require ["react" :as react]
            ["react-dom/server" :as rds]
            [clojure.string :as str]
            [hc.client.swarm.model :as model]
            [hc.client.swarm.ui :as ui]))

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
   {"slot-a" {:slot-id "slot-a"}
    "slot-b" {:slot-id "slot-b"}
    "slot-c" {:slot-id "slot-c"}
    "slot-d" {:slot-id "slot-d"}}
   :token-slot/by-id
   {"swarm-1" {:holder-id "agent-holder" :term-id 4 :state :held}}
   :write-gate/by-id
   {"swarm-1" {:held? true
               :owner "agent-holder"
               :current-action "action-shell"
               :queue ["action-edit"]}}
   :ui/selected-swarm-id "swarm-1"})

(defn- check [label present?]
  (println (if present? "FOUND" "MISSING") label))

(defn -main [& _]
  (let [dashboard (model/dashboard-from-db fixture)
        html (rds/renderToStaticMarkup (ui/dashboard-element dashboard))
        component-html (rds/renderToStaticMarkup
                        (react/createElement ui/SwarmDashboard #js {:db fixture}))]
    (println "HTML-LENGTH" (count html))
    (doseq [needle ["Swarm 总览"
                    "auth-core"
                    "ui-shell"
                    "/worktrees/mission/swarm-1"
                    "Integrate authentication core"
                    "等待已解除"
                    "Wait for parser"
                    "Deep dependency"
                    "task-waiting"
                    "slot-a"
                    "agent-holder"
                    "令牌持有者"
                    "R(holder)"
                    "工作区写入口 W"
                    "托管 shell"
                    "action-edit"
                    "已合入"
                    "REPORT-END-9f3c"
                    "aria-live"
                    "@media (min-width: 820px)"
                    "@media (max-width: 480px)"
                    "hc-status"
                    "details"
                    "summary"]]
      (check needle (str/includes? html needle)))
    (println "COMPONENT-HTML-LENGTH" (count component-html))
    (println "COMPONENT-HAS-REPORT" (str/includes? component-html "REPORT-END-9f3c"))
    (println "COMPONENT-HAS-ARIA" (str/includes? component-html "aria-label"))
    (println "HTML-BEGIN")
    (println html)
    (println "HTML-END")))

(set! *main-cli-fn* -main)
