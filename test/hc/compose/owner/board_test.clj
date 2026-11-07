(ns hc.compose.owner.board-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.owner :as owner]
            [hc.core.workspace-gate.board :as ws-board]))

(defn- with-owner
  [f]
  (let [handle (owner/start-owner!
                {:session-id "s1"
                 :ephemeral? true
                 :plan-milestone :pending
                 :draft {:path "plans/demo.md" :content "# plan"}})]
    (try
      (f handle)
      (finally
        ((:stop! handle))))))

(defn- start-execution!
  [handle]
  ((:submit-command! handle)
   {:command-id "start-execution"
    :command-epoch 1
    :payload {:kind :review/submit
              :lane :plan
              :tenure-id "tenure-1"
              :findings []}})
  ((:submit-command! handle)
   {:command-id "start-swarm"
    :command-epoch 1
    :payload {:kind :review/submit
              :lane :execution
              :tenure-id "tenure-2"
              :findings [{:key "k1" :value "root work"}]}})
  (get-in ((:snapshot handle)) [:state :relay :lanes :execution]))

(defn- setup-claimed-task!
  [handle needs-token?]
  (let [tenure-id "tenure-2"
        registered ((:execute-board! handle)
                    {:op :register-actor
                     :tenure-id tenure-id
                     :slot-id "w1"})
        actor-id (:agent-id registered)]
    ((:execute-board! handle) {:op :claim
                               :tenure-id tenure-id
                               :actor-id actor-id
                               :task-id :task-1
                               :atomicity-reason "own the parent before split"})
    ((:execute-board! handle) {:op :split
                               :tenure-id tenure-id
                               :actor-id actor-id
                               :task-id :task-1
                               :remaining-work "parent integration"
                               :children [{:goal "g"
                                           :remaining-work "r"
                                           :done-when "d"
                                           :needs-token needs-token?
                                           :owner-id (when-not needs-token?
                                                       actor-id)}]
                               :wait-for #{}})
    (when needs-token?
      ((:execute-board! handle)
       {:op :take-or-die
        :tenure-id tenure-id
        :actor-id actor-id
        :request-id "token-1"
        :confirm :i-hereby-confirm-i-die-without-regret}))
    ((:execute-board! handle) {:op :claim
                               :tenure-id tenure-id
                               :actor-id actor-id
                               :task-id :task-2
                               :atomicity-reason "atomic"})
    {:actor-id actor-id
     :tenure-id tenure-id
     :term-id (get-in ((:snapshot handle))
                      [:state :board :token-slot :term-id])
     :claim-id (get-in ((:snapshot handle))
                       [:state :board :tasks :task-2 :claim-id])}))

(deftest workspace-adapter-begins-and-publishes
  (with-owner
    (fn [handle]
      (start-execution! handle)
      (let [{:keys [actor-id claim-id]} (setup-claimed-task! handle false)
            adapter (:workspace-board handle)
            permit (ws-board/begin-apply!
                    adapter
                    {:actor-id actor-id
                     :task-id :task-2
                     :claim-id claim-id
                     :request-id "rq-1"}
                    {:summary "batch"})]
        (is (= :applying (:phase permit)))
        (is (= :applying
               (get-in ((:snapshot handle))
                       [:state :board :actions (:action-id permit) :phase])))
        (is (:ok? (ws-board/publish-applied!
                   adapter permit {:status :APPLIED})))
        (is (= :finished
               (get-in ((:snapshot handle))
                       [:state :board :actions (:action-id permit) :phase])))))))

(deftest workspace-adapter-rejects-an-inactive-actor
  (with-owner
    (fn [handle]
      (start-execution! handle)
      (let [adapter (:workspace-board handle)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws-board/check-eligibility!
                      adapter
                      {:actor-id :nobody
                       :task-id :task-1
                       :claim-id :claim-1})))))))

(deftest shell-port-runs-for-the-token-holder
  (with-owner
    (fn [handle]
      (start-execution! handle)
      (let [{:keys [actor-id claim-id term-id]} (setup-claimed-task! handle true)
            shell (:shell-board handle)
            eligibility ((:check-eligibility! shell)
                         {:actor-id actor-id
                          :task-id :task-2
                          :claim-id claim-id
                          :token-term term-id})]
        (is (= term-id (:term-id eligibility)))
        (let [permit ((:begin-run! shell)
                      {:actor-id actor-id
                       :task-id :task-2
                       :claim-id claim-id
                       :token-term term-id
                       :expected-eligibility eligibility
                       :request-id "shell-1"}
                      {:command "true"})]
          (is (= :running (:phase permit)))
          (is (= :running
                 (get-in ((:snapshot handle))
                         [:state :board :actions (:action-id permit) :phase])))
          (is (:ok? ((:publish-finished! shell)
                     permit
                     {:exit 0 :stdout ""})))
          (is (= :finished
                 (get-in ((:snapshot handle))
                         [:state :board :actions (:action-id permit) :phase]))))))))
