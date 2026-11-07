(ns hc.compose.nudge.policy-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.nudge.policy :as policy]
            [hc.core.relay :as relay]
            [hc.core.swarm-stm.board :as board]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- tenure
  ([]
   (tenure {}))
  ([opts]
   (merge {:tenure-id "tenure-1"
           :epoch 1
           :lane :plan
           :role :plan
           :milestone :S1
           :status :active
           :initial? true
           :first-review? true
           :asked? false
           :awaiting-user? false
           :review-window :open
           :swarm-phase :none
           :relay-pending? false
           :ledger (sorted-map)
           :root-tasks []}
          opts)))

(defn- swarm-tenure
  ([]
   (swarm-tenure {}))
  ([opts]
   (tenure (merge {:lane :execution
                   :role :reviewer
                   :milestone :execution
                   :initial? false
                   :first-review? false
                   :asked? false
                   :review-window :closed
                   :swarm-phase :active}
                  opts))))

(defn- central-observation
  ([role tenure]
   (central-observation role tenure nil {}))
  ([role tenure execution-tenure]
   (central-observation role tenure execution-tenure {}))
  ([role tenure execution-tenure opts]
   (policy/observation nil
                       (merge {:role role
                               :tenure tenure
                               :execution-tenure execution-tenure
                               :turn-ended? true
                               :in-flight? false}
                              opts))))

(defn- worker-observation
  ([tenure board agent-id]
   (worker-observation tenure board agent-id {}))
  ([tenure board agent-id opts]
   (policy/observation nil
                       (merge {:role :worker
                               :tenure tenure
                               :board board
                               :agent-id agent-id
                               :turn-ended? true
                               :in-flight? false}
                              opts))))

(defn- ready-board
  ([]
   (ready-board {:owner-id :worker-1}))
  ([task-opts]
   {:tasks {:task-1 (merge {:task-id :task-1
                            :state :ready
                            :needs-token false
                            :owner-id :worker-1}
                           task-opts)}
    :actions {}
    :token-slot {:holder-id nil}
    :inputs {}}))

(defn- waiting-board
  []
  {:tasks {:task-1 {:task-id :task-1
                    :state :waiting
                    :needs-token false
                    :owner-id :worker-1
                    :wait-for #{:task-2}}}
   :actions {}
   :token-slot {:holder-id nil}
   :inputs {}})

;; ---------------------------------------------------------------------------
;; Centralized role and user-wait distinctions

(deftest central-legal-exits-test
  (testing "Plan S1 ask is a legal user-wait exit"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S1
                              :asked? true
                              :awaiting-user? true})))]
      (is (false? (:enabled? decision)))
      (is (= :plan-s1-ask (:reason decision)))
      (is (= :plan-s1-ask (get-in decision [:exit :kind])))))

  (testing "execution initial empty-ledger ask is a legal user-wait exit"
    (let [decision (policy/decide
                    (central-observation
                     :reviewer
                     (tenure {:lane :execution
                              :role :reviewer
                              :milestone :execution
                              :initial? true
                              :first-review? true
                              :asked? true
                              :awaiting-user? true})))]
      (is (false? (:enabled? decision)))
      (is (= :execution-initial-ask (:reason decision)))
      (is (= :execution-initial-ask (get-in decision [:exit :kind])))))

  (testing "an initial ask with an open ledger is not the empty-ledger exit"
    (let [decision (policy/decide
                    (central-observation
                     :reviewer
                     (tenure {:lane :execution
                              :role :reviewer
                              :milestone :execution
                              :initial? true
                              :first-review? true
                              :asked? true
                              :awaiting-user? true
                              :ledger (sorted-map "work" "open")})))]
      (is (false? (:enabled? decision)))
      (is (= :user-wait (:reason decision)))
      (is (= :user-wait (get-in decision [:exit :kind])))))

  (testing "a resumed ask is no longer an exit"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S1
                              :asked? true
                              :awaiting-user? false})))]
      (is (true? (:enabled? decision)))
      (is (= :milestone-open (:reason decision)))))

  (testing "a committed wait outside the two named asks still suppresses"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :awaiting-user? true})))]
      (is (false? (:enabled? decision)))
      (is (= :user-wait (:reason decision)))
      (is (= :user-wait (get-in decision [:exit :kind])))))

  (testing "Plan handoff completion is terminal"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :pending})
                     {:tenure-id "execution-1"}))]
      (is (false? (:enabled? decision)))
      (is (= :handoff-complete (:reason decision)))
      (is (= :handoff-complete (get-in decision [:exit :kind])))))

  (testing "Plan pending without execution still nudges for handoff"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :pending})))]
      (is (true? (:enabled? decision)))
      (is (= :milestone-open (:reason decision)))
      (is (= (relay/central-nudge-message {}) (:message decision)))))

  (testing "execution completion is terminal"
    (let [decision (policy/decide
                    (central-observation
                     :reviewer
                     (tenure {:lane :execution
                              :role :reviewer
                              :milestone :completed
                              :status :completed})))]
      (is (false? (:enabled? decision)))
      (is (= :terminal-completed (:reason decision)))
      (is (= :terminal-completed (get-in decision [:exit :kind]))))))

(deftest closing-reviewer-test
  (testing "a closing Reviewer with an empty ledger nudges for the closing review"
    (let [decision (policy/decide
                    (central-observation
                     :reviewer
                     (tenure {:lane :execution
                              :role :reviewer
                              :milestone :closing
                              :first-review? false})))]
      (is (true? (:enabled? decision)))
      (is (= :milestone-open (:reason decision)))
      (is (= (relay/central-nudge-message {}) (:message decision))))))

(deftest central-role-and-ledger-test
  (testing "Plan uses the Plan ledger and lists entries in key order"
    (let [ledger (sorted-map "b" "second"
                             "a" "first")
          decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false
                              :ledger ledger})))]
      (is (true? (:enabled? decision)))
      (is (= :central (:kind decision)))
      (is (= :ledger-open (:reason decision)))
      (is (= [{:key "a" :value "first"}
              {:key "b" :value "second"}]
             (:ledger decision)))
      (is (= (relay/central-nudge-message ledger) (:message decision)))
      (is (str/includes? (:message decision) "- a: first\n- b: second"))))

  (testing "Plan ignores a foreign execution ledger"
    (let [ledger (sorted-map "plan" "p")
          decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false
                              :ledger ledger})
                     {:tenure-id "execution-1"
                      :ledger (sorted-map "exec" "e")}))]
      (is (true? (:enabled? decision)))
      (is (= [{:key "plan" :value "p"}] (:ledger decision)))
      (is (= (relay/central-nudge-message ledger) (:message decision)))
      (is (not (str/includes? (:message decision) "exec: e")))))

  (testing "Reviewer uses the execution ledger"
    (let [ledger (sorted-map "exec" "work")
          decision (policy/decide
                    (central-observation
                     :reviewer
                     (tenure {:lane :execution
                              :role :reviewer
                              :milestone :execution
                              :first-review? false
                              :ledger ledger})))]
      (is (true? (:enabled? decision)))
      (is (= [{:key "exec" :value "work"}] (:ledger decision)))
      (is (= (relay/central-nudge-message ledger) (:message decision)))
      (is (str/includes? (:message decision) "- exec: work"))))

  (testing "an empty ledger points at the unfinished milestone"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})))]
      (is (true? (:enabled? decision)))
      (is (= :milestone-open (:reason decision)))
      (is (= (relay/central-nudge-message {}) (:message decision))))))

(deftest central-suppression-test
  (testing "an in-flight attempt defers Nudge"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:in-flight? true}))]
      (is (false? (:enabled? decision)))
      (is (= :in-flight-work (:reason decision)))
      (is (true? (:deferred? decision)))))

  (testing "a Reviewer is suspended during swarm construction"
    (let [decision (policy/decide
                    (central-observation
                     :reviewer
                     (swarm-tenure {:ledger (sorted-map "x" "work")})))]
      (is (false? (:enabled? decision)))
      (is (= :reviewer-suspended-in-swarm (:reason decision)))))

  (testing "a pending relay keeps Reviewer suspended"
    (let [decision (policy/decide
                    (central-observation
                     :reviewer
                     (swarm-tenure {:swarm-phase :none
                                    :review-window :open
                                    :relay-pending? true})))]
      (is (false? (:enabled? decision)))
      (is (= :reviewer-suspended-in-swarm (:reason decision)))))

  (testing "a retired tenure has no Nudge"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:status :retired})))]
      (is (false? (:enabled? decision)))
      (is (= :inactive-tenure (:reason decision)))))

  (testing "a turn that has not ended does not nudge"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2})
                     nil
                     {:turn-ended? false}))]
      (is (false? (:enabled? decision)))
      (is (= :turn-not-ended (:reason decision)))))

  (testing "unsupported roles and role/lane mismatches are explicit"
    (is (= :unsupported-role
           (:reason (policy/decide {:role :steward
                                    :turn-ended? true
                                    :tenure (tenure {})}))))
    (is (= :role-lane-mismatch
           (:reason (policy/decide {:role :plan
                                    :lane :execution
                                    :turn-ended? true
                                    :tenure (tenure {})}))))))

(deftest permanent-failure-blocks-nudge-test
  (testing "an invalid snapshot is permanent and keeps the original error"
    (let [failure {:error/kind :invalid-snapshot
                   :reason :unknown-field
                   :path [:messages 0]
                   :unknown [:quotes]}
          decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure failure
                      :failure-outcome :failed}))]
      (is (false? (:enabled? decision)))
      (is (true? (:blocked? decision)))
      (is (= :permanent-failure (:reason decision)))
      (is (= :NUDGE_BLOCKED_PERMANENT_FAILURE
             (get-in decision [:diagnostic :code])))
      (is (= failure (get-in decision [:diagnostic :failure])))
      (is (= :fix-configuration
             (get-in decision [:diagnostic :remedy])))
      (is (= :record-nudge-blocked
             (get-in decision [:prepare :transition :op])))
      (is (= [:diagnostic :nudge/blocked]
             (mapv :type (get-in decision [:prepare :effects]))))
      (is (nil? (get-in decision [:prepare :effects 1 :prompt])))))

  (testing "a tools-less main model blocks instead of nudging"
    (let [failure {:kind :model-tools-unavailable
                   :code :MODEL_TOOLS_UNAVAILABLE
                   :category :permanent
                   :remedy :select-model
                   :retryable? false}
          decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure failure
                      :failure-outcome :failed}))]
      (is (false? (:enabled? decision)))
      (is (true? (:blocked? decision)))
      (is (= :permanent-failure (:reason decision)))
      (is (= :NUDGE_BLOCKED_PERMANENT_FAILURE
             (get-in decision [:diagnostic :code])))
      (is (= :select-model (get-in decision [:diagnostic :remedy])))
      (is (= failure (get-in decision [:diagnostic :failure])))))

  (testing "the normalized adapter shape is permanent, not only :invalid-snapshot"
    (let [failure {:kind :unknown-field
                   :reason :unknown-field
                   :path [:messages 0]
                   :unknown [:quotes]
                   :category :unclassified
                   :disposition :fail
                   :retryable? false}
          decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure failure
                      :failure-outcome :failed}))]
      (is (true? (:blocked? decision)))
      (is (= :permanent-failure (:reason decision)))
      (is (= :NUDGE_BLOCKED_PERMANENT_FAILURE
             (get-in decision [:diagnostic :code])))
      (is (= failure (get-in decision [:diagnostic :failure])))))

  (testing "context overflow has its own remedy"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure {:kind :input-too-long}
                      :failure-outcome :failed}))]
      (is (true? (:blocked? decision)))
      (is (= :context-overflow (:reason decision)))
      (is (= :NUDGE_BLOCKED_CONTEXT_OVERFLOW
             (get-in decision [:diagnostic :code])))
      (is (= :adjust-context
             (get-in decision [:diagnostic :remedy])))))

  (testing "a transient failure is not blocked"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure {:kind :timeout :retryable? true}
                      :failure-outcome :failed}))]
      (is (true? (:enabled? decision)))
      (is (not (:blocked? decision)))))

  (testing "a deadline is not a configuration failure"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure {:kind :deadline-exceeded}
                      :failure-outcome :failed}))]
      (is (true? (:enabled? decision)))
      (is (not (:blocked? decision)))))

  (testing "HTTP 409 is not a permanent status"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure {:status 409}
                      :failure-outcome :failed}))]
      (is (true? (:enabled? decision)))
      (is (not (:blocked? decision)))))

  (testing "a cancelled attempt is not a permanent failure"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure {:error/kind :invalid-snapshot}
                      :failure-outcome :cancelled}))]
      (is (true? (:enabled? decision)))
      (is (not (:blocked? decision)))))

  (testing "a failure the host carries inline still blocks"
    ;; `turn-observation` forwards the whole turn, so a host fact such as a
    ;; required-search refusal can arrive as observation facts rather than
    ;; under `:failure`.  Reading only `:failure` lost it: the guard nudged
    ;; and the agent repeated a request that cannot succeed.
    (let [decision (policy/decide
                    (central-observation
                     :reviewer
                     (swarm-tenure {:ledger (sorted-map)})
                     nil
                     {:permanent-failure? true
                      :failure nil
                      :kind :search-required-failed
                      :category :permanent
                      :failure-outcome :failed}))]
      (is (true? (:blocked? decision)))
      (is (= :permanent-failure (:reason decision)))
      (is (= :NUDGE_BLOCKED_PERMANENT_FAILURE
             (get-in decision [:diagnostic :code])))))

  (testing "an inline failure classifies without the host verdict"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure nil
                      :kind :model-start-failed
                      :category :permanent
                      :failure-outcome :failed}))]
      (is (= :NUDGE_BLOCKED_PERMANENT_FAILURE
             (get-in decision [:diagnostic :code])))))

  (testing "the host can mark an unclassified failure permanent"
    (let [decision (policy/decide
                    (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false})
                     nil
                     {:failure {:kind :unclassified}
                      :failure-outcome :failed
                      :permanent-failure? true}))]
      (is (true? (:blocked? decision)))
      (is (= :permanent-failure (:reason decision))))))

;; ---------------------------------------------------------------------------
;; Phase B worker distinctions

(deftest worker-progressable-test
  (testing "a worker with a ready task is nudged with its responsibility"
    (let [decision (policy/decide
                    (worker-observation (swarm-tenure)
                                        (ready-board)
                                        :worker-1))]
      (is (true? (:enabled? decision)))
      (is (= :worker (:kind decision)))
      (is (= :global-progressable (:reason decision)))
      (is (= [:task-1] (:responsibility decision)))
      (is (= [:ready-task] (:progressable-reasons decision)))
      (is (str/includes? (:message decision) "你的未结责任：:task-1。"))
      (is (str/includes? (:message decision) "全局仍有可推进工作：ready-task。"))
      (is (str/includes? (:message decision) policy/worker-directive))
      (is (not (str/includes? (:message decision) "review 账本")))))

  (testing "global work nudges a worker with no personal responsibility"
    (let [decision (policy/decide
                    (worker-observation (swarm-tenure)
                                        (ready-board {:owner-id nil})
                                        :worker-1))]
      (is (true? (:enabled? decision)))
      (is (= [] (:responsibility decision)))
      (is (str/includes? (:message decision) "暂无个人未结责任"))))

  (testing "a waiting task alone does not nudge"
    (let [decision (policy/decide
                    (worker-observation (swarm-tenure)
                                        (waiting-board)
                                        :worker-1))]
      (is (false? (:enabled? decision)))
      (is (= :no-progressable-work (:reason decision)))
      (is (= [:task-1] (:responsibility decision)))))

  (testing "a holder uses token responsibility"
    (let [holder-board {:tasks {:task-1 {:task-id :task-1
                                         :state :ready
                                         :needs-token true}}
                        :actions {}
                        :token-slot {:holder-id :worker-1}
                        :inputs {}}
          decision (policy/decide
                    (policy/observation
                     nil
                     {:role :holder
                      :tenure (swarm-tenure)
                      :board holder-board
                      :agent-id :worker-1
                      :turn-ended? true
                      :in-flight? false}))]
      (is (true? (:enabled? decision)))
      (is (= [:task-1] (:responsibility decision)))
      (is (str/includes? (:message decision) "你是持令者。")))))

(deftest worker-suppression-test
  (testing "awaiting-user tenure suppresses worker Nudge"
    (let [decision (policy/decide
                    (worker-observation
                     (swarm-tenure {:awaiting-user? true
                                    :initial? true
                                    :first-review? true
                                    :asked? true})
                     (ready-board)
                     :worker-1))]
      (is (false? (:enabled? decision)))
      (is (= :execution-initial-ask (:reason decision)))
      (is (= :execution-initial-ask (get-in decision [:exit :kind])))))

  (testing "workers do not nudge outside Phase B"
    (let [decision (policy/decide
                    (worker-observation
                     (swarm-tenure {:swarm-phase :none
                                    :review-window :open})
                     (ready-board)
                     :worker-1))]
      (is (false? (:enabled? decision)))
      (is (= :not-in-swarm (:reason decision)))))

  (testing "missing board and agent id are explicit"
    (is (= :missing-board
           (:reason (policy/decide
                     (policy/observation
                      nil
                      {:role :worker
                       :tenure (swarm-tenure)
                       :agent-id :worker-1
                       :turn-ended? true})))))
    (is (= :missing-agent-id
           (:reason (policy/decide
                     (worker-observation (swarm-tenure)
                                         (ready-board)
                                         nil))))))

  (testing "an in-flight worker attempt defers Nudge"
    (let [decision (policy/decide
                    (worker-observation (swarm-tenure)
                                        (ready-board)
                                        :worker-1
                                        {:in-flight? true}))]
      (is (false? (:enabled? decision)))
      (is (= :in-flight-work (:reason decision))))))

;; ---------------------------------------------------------------------------
;; Pure transition plan and observation normalization

(deftest prepare-test
  (let [observation (central-observation
                     :plan
                     (tenure {:milestone :S2
                              :first-review? false
                              :ledger (sorted-map "a" "work")}))
        decision (policy/decide observation)
        plan (policy/prepare decision)]
    (is (true? (:enabled? decision)))
    (is (= :inject-unattended-nudge (get-in plan [:transition :op])))
    (is (= (:nudge decision) (get-in plan [:transition :nudge])))
    (is (= 2 (count (:effects plan))))
    (is (= :nudge/inject (get-in plan [:effects 0 :type])))
    (is (= :model/request (get-in plan [:effects 1 :type])))
    (is (= (:message decision) (get-in plan [:effects 1 :prompt])))
    (is (= decision (policy/decide observation)))
    (is (= plan (policy/prepare decision))))
  (is (nil? (policy/prepare {:enabled? false})))
  (is (= "nudge-plan-tenure-1-2"
         (policy/nudge-id {:role :plan
                           :tenure-id "tenure-1"
                           :nudge-seq 2})))
  (is (= "nudge-plan-tenure-1-1"
         (policy/nudge-id (policy/observation
                           nil
                           {:role :plan
                            :tenure (tenure {})})))
      "the selected tenure supplies the id when no top-level :tenure-id exists")
  (is (= "nudge-worker-tenure-1-worker-1-2"
         (policy/nudge-id {:role :worker
                           :tenure-id "tenure-1"
                           :agent-id :worker-1
                           :nudge-seq 2})))
  (is (not= (policy/nudge-id {:role :worker
                              :tenure-id "tenure-1"
                              :agent-id :worker-1
                              :nudge-seq 2})
            (policy/nudge-id {:role :worker
                              :tenure-id "tenure-1"
                              :agent-id :worker-2
                              :nudge-seq 2}))
      "workers sharing one tenure keep distinct ids")
  (let [observation (worker-observation (swarm-tenure)
                                        (ready-board)
                                        :worker-1)
        decision (policy/decide observation)
        plan (policy/prepare decision)]
    (is (= :worker-1 (get-in plan [:transition :agent-id])))
    (is (= :worker-1 (get-in plan [:effects 1 :agent-id])))))

(deftest observation-test
  (let [plan (tenure {:milestone :S1 :awaiting-user? true})
        state {:session-id "session-1"
               :lanes {:plan plan :execution nil}}
        observation (policy/observation state {:role :plan})]
    (is (= :plan (:lane observation)))
    (is (= plan (:tenure observation)))
    (is (= "session-1" (:session-id observation)))
    (is (true? (:awaiting-user? observation)))
    (is (true? (:turn-ended? observation)))
    (is (false? (:in-flight? observation))))

  (let [plan (tenure {:awaiting-user? true})
        observation (policy/observation nil {:role :plan
                                             :tenure plan
                                             :awaiting-user? false})]
    (is (true? (:awaiting-user? observation))
        "committed state wins over a false override"))

  (let [decision (policy/decide
                  {:role :plan
                   :tenure (tenure {:milestone :S2
                                    :first-review? false})})]
    (is (true? (:enabled? decision))
        "raw decisions infer the lane and default turn-ended"))

  (testing "multiple execution tenures are exposed without guessing"
    (let [plan (tenure {:milestone :pending})
          execution-1 (swarm-tenure {:tenure-id "execution-1"})
          execution-2 (swarm-tenure {:tenure-id "execution-2"})
          state {:lanes {:plan plan
                         :execution {"execution-1" execution-1
                                     "execution-2" execution-2}}}
          plan-observation (policy/observation state {:role :plan})
          reviewer-observation (policy/observation state {:role :reviewer})
          explicit-observation (policy/observation
                                state
                                {:role :reviewer :tenure execution-1})
          by-id-observation (policy/observation
                             state
                             {:role :reviewer :tenure-id "execution-2"})]
      (is (= 2 (count (:execution-tenures plan-observation))))
      (is (= :handoff-complete
             (get-in (policy/decide plan-observation) [:exit :kind])))
      (is (nil? (:tenure reviewer-observation))
          "a Reviewer must name one of several execution tenures")
      (is (= :ambiguous-tenure
             (:reason (policy/decide reviewer-observation))))
      (is (= execution-1 (:tenure explicit-observation)))
      (is (= execution-2 (:tenure by-id-observation)))
      (is (= "execution-2" (:tenure-id by-id-observation)))
      (is (= "nudge-reviewer-execution-2-1"
             (policy/nudge-id by-id-observation)))))

  (testing "a missing Plan tenure is inactive, not ambiguous"
    (let [executions {"execution-1" (swarm-tenure {:tenure-id "execution-1"})
                      "execution-2" (swarm-tenure {:tenure-id "execution-2"})}
          observation (policy/observation
                       nil
                       {:role :plan
                        :execution-tenures executions
                        :turn-ended? true
                        :in-flight? false})]
      (is (= :inactive-tenure (:reason (policy/decide observation)))))))

;; ---------------------------------------------------------------------------
;; Property tests

(def gen-nonblank
  (gen/such-that #(not (str/blank? %)) gen/string-alphanumeric 50))

(def gen-ledger
  (gen/map gen-nonblank gen-nonblank))

(def gen-central-role-milestone
  (gen/one-of
   [(gen/fmap (fn [milestone] [:plan milestone])
              (gen/elements [:S1 :S2 :S3 :pending]))
    (gen/fmap (fn [milestone] [:reviewer milestone])
              (gen/elements [:execution :closing :completed]))]))

(def gen-central-case
  (gen/fmap
   (fn [[[role milestone] awaiting? ledger swarm-phase review-window
         relay-pending? initial? asked? in-flight? execution-started?]]
     {:role role
      :execution-started? execution-started?
      :in-flight? in-flight?
      :tenure {:tenure-id "tenure-1"
               :epoch 1
               :lane (if (= role :plan) :plan :execution)
               :role (if (= role :plan) :plan :reviewer)
               :milestone milestone
               :status :active
               :initial? initial?
               :first-review? true
               :asked? asked?
               :awaiting-user? awaiting?
               :review-window review-window
               :swarm-phase swarm-phase
               :relay-pending? relay-pending?
               :ledger ledger
               :root-tasks []}})
   (gen/tuple gen-central-role-milestone
              gen/boolean
              gen-ledger
              (gen/elements [:none :active])
              (gen/elements [:open :closed])
              gen/boolean
              gen/boolean
              gen/boolean
              gen/boolean
              gen/boolean)))

(defspec active-central-turn-end-nudges-or-exits 60
  (prop/for-all [{:keys [role tenure in-flight? execution-started?]}
                 gen-central-case]
    (let [execution-tenure (when execution-started? {:tenure-id "execution-1"})
          observation (policy/observation
                       nil
                       {:role role
                        :tenure tenure
                        :execution-tenure execution-tenure
                        :turn-ended? true
                        :in-flight? in-flight?})
          decision (policy/decide observation)
          terminal? (or (= :completed (:status tenure))
                        (= :completed (:milestone tenure)))
          handoff? (and (= :plan (:lane tenure))
                        (= :pending (:milestone tenure))
                        execution-started?)
          awaiting? (:awaiting-user? tenure)
          suspended? (and (= :reviewer role)
                          (or (= :active (:swarm-phase tenure))
                              (= :closed (:review-window tenure))
                              (:relay-pending? tenure)))]
      (cond
        terminal?
        (and (false? (:enabled? decision))
             (= :terminal-completed (get-in decision [:exit :kind])))

        handoff?
        (and (false? (:enabled? decision))
             (= :handoff-complete (get-in decision [:exit :kind])))

        awaiting?
        (and (false? (:enabled? decision))
             (some? (:exit decision)))

        in-flight?
        (and (false? (:enabled? decision))
             (= :in-flight-work (:reason decision)))

        suspended?
        (and (false? (:enabled? decision))
             (= :reviewer-suspended-in-swarm (:reason decision)))

        :else
        (and (true? (:enabled? decision))
             (contains? #{:ledger-open :milestone-open} (:reason decision))
             (= (relay/central-nudge-message (:ledger tenure))
                (:message decision)))))))

(defspec central-nudge-lists-every-ledger-entry 60
  (prop/for-all [ledger gen-ledger]
    (let [tenure {:tenure-id "tenure-1"
                  :epoch 1
                  :lane :plan
                  :role :plan
                  :milestone :S2
                  :status :active
                  :initial? false
                  :first-review? false
                  :asked? false
                  :awaiting-user? false
                  :review-window :open
                  :swarm-phase :none
                  :relay-pending? false
                  :ledger ledger
                  :root-tasks []}
          observation (policy/observation
                       nil
                       {:role :plan
                        :tenure tenure
                        :turn-ended? true
                        :in-flight? false})
          decision (policy/decide observation)
          entries (sort-by key ledger)]
      (and (true? (:enabled? decision))
           (= (mapv (fn [[k v]] {:key k :value v}) entries)
              (:ledger decision))
           (= (relay/central-nudge-message ledger) (:message decision))
           (every? (fn [[k v]]
                     (and (str/includes? (:message decision) k)
                          (str/includes? (:message decision) v)))
                   entries)))))

(def gen-task-id
  (gen/elements [:task-1 :task-2 :task-3]))

(def gen-agent-id
  (gen/elements [:worker-1 :worker-2]))

(def gen-task
  (gen/fmap
   (fn [[id state needs-token owner wait-for]]
     [id {:task-id id
          :state state
          :needs-token needs-token
          :owner-id owner
          :wait-for wait-for}])
   (gen/tuple gen-task-id
              (gen/elements [:ready :waiting :done])
              gen/boolean
              (gen/elements [nil :worker-1 :worker-2])
              (gen/set gen-task-id))))

(def gen-action
  (gen/fmap
   (fn [[id task-id agent-id phase]]
     [id {:action-id id
          :task-id task-id
          :agent-id agent-id
          :phase phase}])
   (gen/tuple (gen/fmap #(keyword (str "action-" %)) (gen/choose 1 3))
              gen-task-id
              gen-agent-id
              (gen/elements [:claimed :preparing :waiting-gate :applying
                             :running :finished :uncertain]))))

(def gen-input
  (gen/fmap
   (fn [[id state]]
     [id {:input-id id :state state}])
   (gen/tuple (gen/fmap #(keyword (str "input-" %)) (gen/choose 1 2))
              (gen/elements [:unhandled :handled]))))

(def gen-board
  (gen/fmap
   (fn [[tasks actions holder inputs]]
     {:tasks (into {} tasks)
      :actions (into {} actions)
      :token-slot {:holder-id holder}
      :inputs (into {} inputs)})
   (gen/tuple (gen/vector gen-task 0 5)
              (gen/vector gen-action 0 4)
              (gen/elements [nil :worker-1 :worker-2])
              (gen/vector gen-input 0 3))))

(defspec worker-nudge-tracks-global-progress 60
  (prop/for-all [board gen-board
                 agent gen-agent-id
                 ledger gen-ledger
                 in-flight? gen/boolean]
    (let [tenure {:tenure-id "tenure-execution"
                  :epoch 1
                  :lane :execution
                  :role :reviewer
                  :milestone :execution
                  :status :active
                  :initial? false
                  :first-review? false
                  :asked? false
                  :awaiting-user? false
                  :review-window :closed
                  :swarm-phase :active
                  :relay-pending? false
                  :ledger ledger
                  :root-tasks []}
          observation (policy/observation
                       nil
                       {:role :worker
                        :tenure tenure
                        :board board
                        :agent-id agent
                        :turn-ended? true
                        :in-flight? in-flight?})
          decision (policy/decide observation)
          progressable? (board/global-progressable? board)
          responsibility (vec (sort-by str
                                       (board/responsibility board agent)))]
      (cond
        in-flight?
        (and (false? (:enabled? decision))
             (= :in-flight-work (:reason decision)))

        progressable?
        (and (true? (:enabled? decision))
             (= :worker (:kind decision))
             (= responsibility (:responsibility decision))
             (= (vec (sort-by name
                              (board/progressable-reasons board)))
                (:progressable-reasons decision))
             (str/includes? (:message decision) policy/worker-directive)
             (not (str/includes? (:message decision) "review 账本")))

        :else
        (and (false? (:enabled? decision))
             (= :no-progressable-work (:reason decision))
             (= responsibility (:responsibility decision)))))))

(defspec user-wait-suppresses-worker-nudge 60
  (prop/for-all [board gen-board
                 agent gen-agent-id
                 wait-kind (gen/elements [:plan-s1
                                          :execution-initial
                                          :generic])]
    (let [[role tenure exit-kind]
          (case wait-kind
            :plan-s1
            [:plan
             {:tenure-id "tenure-plan"
              :epoch 1
              :lane :plan
              :role :plan
              :milestone :S1
              :status :active
              :initial? true
              :first-review? true
              :asked? true
              :awaiting-user? true
              :review-window :open
              :swarm-phase :none
              :relay-pending? false
              :ledger {}
              :root-tasks []}
             :plan-s1-ask]

            :execution-initial
            [:worker
             {:tenure-id "tenure-execution"
              :epoch 1
              :lane :execution
              :role :reviewer
              :milestone :execution
              :status :active
              :initial? true
              :first-review? true
              :asked? true
              :awaiting-user? true
              :review-window :closed
              :swarm-phase :active
              :relay-pending? false
              :ledger {}
              :root-tasks []}
             :execution-initial-ask]

            :generic
            [:worker
             {:tenure-id "tenure-execution"
              :epoch 1
              :lane :execution
              :role :reviewer
              :milestone :closing
              :status :active
              :initial? false
              :first-review? false
              :asked? false
              :awaiting-user? true
              :review-window :open
              :swarm-phase :none
              :relay-pending? false
              :ledger {}
              :root-tasks []}
             :user-wait])
          observation (policy/observation
                       nil
                       {:role role
                        :tenure tenure
                        :board board
                        :agent-id agent
                        :turn-ended? true
                        :in-flight? false})
          decision (policy/decide observation)]
      (and (false? (:enabled? decision))
           (some? (:exit decision))
           (= exit-kind (get-in decision [:exit :kind]))))))
