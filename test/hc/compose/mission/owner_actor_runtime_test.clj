(ns hc.compose.mission.owner-actor-runtime-test
  "Actual Owner runtime path with a stub model gateway.

  The Owner commits :mission and :actors together, the wake-actors guard
  creates normal model calls, the existing model claim/dispatch path runs
  them, and the stub gateway returns events through the Owner mailbox.  The
  test inspects the committed actor state and the model requests that
  actually reached the gateway."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission.support :as support]
            [hc.compose.owner :as owner]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- snapshot
  [handle]
  ((:snapshot handle)))

(defn- execution-id
  [handle]
  (-> (snapshot handle) :state :mission :relay :lanes :execution keys first))

(defn- actor-slots
  [handle]
  (let [tid (execution-id handle)]
    (get-in (snapshot handle) [:state :actors :tenures tid :slots])))

(defn- worker-requests
  [requests]
  (filter #(and (= :task (get-in % [:snapshot :model-role]))
                (= :clj-worker (get-in % [:snapshot :program-role])))
          @requests))

(defn- request-text
  [request]
  (str/join "\n" (map :text (get-in request [:snapshot :messages]))))

(deftest owner-dispatches-task-model-swarm-turns-and-rebirths
  (let [requests (atom [])
        seen (atom #{})
        blocked (atom [])
        gateway
        {:start! (fn [request deliver!]
                   (let [program-role (get-in request [:snapshot :program-role])
                         agent-id (get-in request [:snapshot :agent-id])]
                     (swap! requests conj request)
                     (if (and (= :clj-worker program-role)
                              (not (contains? @seen agent-id)))
                       (do
                         (swap! seen conj agent-id)
                         (deliver! {:event :chunk :text "working"})
                         (deliver! {:event :completed
                                    :text (str "done " agent-id)}))
                       (swap! blocked conj {:request request :deliver! deliver!}))
                     {:attempt-id (:attempt-id request)}))
         :cancel! (fn [_] nil)}
        handle (owner/start-owner!
                {:session-id "actor-owner"
                 :ephemeral? true
                 :models (support/models)
                 :plan-milestone :pending
                 :draft {:path "plans/actor.md" :content "Actor plan"}
                 :model-gateway gateway})]
    (try
      (testing "Plan handoff and Reviewer swarm start run through the Owner"
        ((:submit-command! handle)
         {:command-id "actor-r1" :command-epoch 1
          :payload {:kind :review/submit :lane :plan
                    :tenure-id "tenure-1" :findings []}})
        (is (wait-for #(some? (execution-id handle)) 3000))
        (let [execution (execution-id handle)]
          ((:submit-command! handle)
           {:command-id "actor-r2" :command-epoch 1
            :payload {:kind :review/submit :lane :execution
                      :tenure-id execution
                      :findings [{:key "k" :value "Do the work"}]}}))
        (is (wait-for #(seq (worker-requests requests)) 5000)))
      (testing "the Owner created real @task worker calls with private context"
        (is (wait-for #(>= (count (worker-requests requests)) 2) 5000))
        (doseq [request (take 2 (worker-requests requests))]
          (is (= :task (get-in request [:snapshot :role])))
          (is (= :worker (get-in request [:snapshot :mission-role])))
          (is (= :task (get-in request [:snapshot :model-role])))
          (is (= "task-model" (get-in request [:snapshot :model-id])))
          (is (= :clj-worker (get-in request [:snapshot :program-role])))
          (is (str/includes? (request-text request)
                             (str (get-in request [:snapshot :agent-id]))))
          (is (not (str/includes? (request-text request)
                                  "Reviewer ledger")))))
      (testing "model terminals commit each agent's own assistant context"
        (is (wait-for
             (fn []
               (let [slots (actor-slots handle)]
                 (and (= 2 (count slots))
                      (every? #(some (fn [m]
                                       (= :assistant (:role m)))
                                     (:context %))
                              (vals slots)))))
             5000)))
      (testing "holder death produces a fresh empty identity and a new task turn"
        (let [slots (actor-slots handle)
              slot-a (get slots :slot-a)
              old-agent (:agent-id slot-a)
              execution (execution-id handle)
              root-id (->> (get-in (snapshot handle)
                                   [:state :mission :boards execution :tasks])
                           vals
                           (filter #(nil? (:parent-id %)))
                           first
                           :task-id)
              split ((:execute-board! handle)
                     {:op :split
                      :tenure-id execution
                      :actor-id old-agent
                      :task-id root-id
                      :remaining-work "wait for token task"
                      :children [{:goal "token task"
                                  :remaining-work "run"
                                  :done-when "done"
                                  :needs-token true}]
                      :wait-for #{}})
              child-id (or (first (:child-ids split))
                           (first (get-in split [:receipt :child-ids])))
              won ((:execute-board! handle)
                   {:op :take-or-die
                    :tenure-id execution
                    :actor-id old-agent
                    :request-id "actor-holder-1"
                    :confirm :i-hereby-confirm-i-die-without-regret})
              claim ((:execute-board! handle)
                     {:op :claim
                      :tenure-id execution
                      :actor-id old-agent
                      :task-id child-id
                      :atomicity-reason "token task"})
              claim-id (or (:claim-id claim)
                           (get-in claim [:receipt :claim-id])
                           (get-in claim [:error :details :current-claim-id]))
              finished ((:execute-board! handle)
                        {:op :finish
                         :tenure-id execution
                         :actor-id old-agent
                         :task-id child-id
                         :claim-id claim-id
                         :result-summary "done"})]
          (is (= :accepted (:status split)) (pr-str split))
          (is (= :attempt-won (:result won)) (pr-str won))
          (is (false? (:retired? won)) (pr-str won))
          (is (some? claim-id) (pr-str claim-id))
          (is (= :accepted (:status finished)) (pr-str finished))
          (is (wait-for
               (fn []
                 (let [new-slot (get (actor-slots handle) :slot-a)]
                   (and new-slot
                        (not= old-agent (:agent-id new-slot))
                        (> (:generation new-slot 0) (:generation slot-a 0)))))
               5000))
          (let [new-slot (get (actor-slots handle) :slot-a)]
            (is (not= old-agent (:agent-id new-slot)))
            (is (> (:generation new-slot) (:generation slot-a)))
            (is (not (str/includes? (str/join "\n" (map :text (:context new-slot)))
                                    (str old-agent)))))))
      (is (= "task-model"
             (get-in (snapshot handle)
                     [:state :mission :models :task]))
          "mission state is committed beside actor state")
      (finally
        ((:stop! handle))))))

(deftest gateway-start-failure-does-not-strand-actor-slot
  (doseq [failure [:throw :nil]]
    (testing (str "gateway start! " (name failure))
      (let [requests (atom [])
            failed? (atom false)
            gateway
            {:start! (fn [request _deliver!]
                       (let [program-role (get-in request
                                                  [:snapshot :program-role])]
                         (swap! requests conj
                                {:program-role program-role
                                 :agent-id (get-in request [:snapshot :agent-id])
                                 :attempt-id (:attempt-id request)})
                         (if (and (= :clj-worker program-role)
                                  (not @failed?))
                           (do
                             (reset! failed? true)
                             (if (= :throw failure)
                               (throw (ex-info "gateway down"
                                               {:code :gateway-down}))
                               nil))
                           {:attempt-id (:attempt-id request)})))
             :cancel! (fn [_] nil)}
            handle
            (owner/start-owner!
             {:session-id (str "actor-start-failure-" (name failure))
              :ephemeral? true
              :models (support/models)
              :plan-milestone :pending
              :draft {:path "plans/actor.md" :content "Actor plan"}
              :model-gateway gateway})]
        (try
          ((:submit-command! handle)
           {:command-id (str "plan-" (name failure)) :command-epoch 1
            :payload {:kind :review/submit :lane :plan
                      :tenure-id "tenure-1" :findings []}})
          (is (wait-for #(some? (execution-id handle)) 3000))
          (let [execution (execution-id handle)]
            ((:submit-command! handle)
             {:command-id (str "execution-" (name failure))
              :command-epoch 1
              :payload {:kind :review/submit :lane :execution
                        :tenure-id execution
                        :findings [{:key "k" :value "Do the work"}]}}))
          (is (wait-for
               #(>= (count (filter (fn [request]
                                     (= :clj-worker (:program-role request)))
                                   @requests))
                    3)
               5000)
              "the failed slot must receive a fresh turn")
          (is (wait-for
               #(some (fn [call] (= :failed (:status call)))
                      (vals (get-in (snapshot handle)
                                    [:state :actors :calls])))
               5000))
          (let [state (:state (snapshot handle))
                calls (vals (get-in state [:actors :calls]))
                failed-ids (set (keep (fn [call]
                                        (when (and (= :failed (:status call))
                                                   (:attempt-id call))
                                          (:attempt-id call)))
                                      calls))
                slots (vals (get-in state
                                    [:actors :tenures (execution-id handle)
                                     :slots]))]
            (is (seq failed-ids))
            (is (not-any? #(contains? failed-ids (:attempt-id %)) slots)
                "a failed attempt must not remain the slot's live attempt")
            (is (some #(= :running (:status %)) slots)
                "the failed slot must have a fresh running turn"))
          (finally
            ((:stop! handle))))))))
