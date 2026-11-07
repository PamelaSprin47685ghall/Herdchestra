(ns hc.core.swarm-stm-smoke-test
  "Scripted smoke scenarios for the phase-one control kernel.

  These are deterministic event drivers, not model runs.  Concurrency is
  started with a promise latch so that the competing commands overlap."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.kernel :as kernel]
            [hc.core.swarm-stm-test-util :as tu]))

(defn- concurrent
  [fns]
  (let [latch (promise)
        futures (mapv (fn [f] (future @latch (f))) fns)]
    (deliver latch true)
    (mapv deref futures)))

(deftest scripted-three-agent-swarm
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        c (tu/spawn! k :slot-c)
        p (:task-id (tu/create-task! k a {:goal "connect new config loader"
                                          :remaining-work "split and integrate"
                                          :done-when "startup uses new loader"
                                          :owner-id a}))
        split (tu/split! k a p {:remaining-work "integrate parser, compat and tests"
                                :children [(tu/child-spec 1) (tu/child-spec 2)
                                           (tu/child-spec 3)]})
        [e1 e2 e3] (:child-ids split)
        claim-e1 (tu/claim! k b e1)
        claim-e2 (tu/claim! k c e2)
        e3-results (concurrent [(fn [] (tu/exec! k {:op :claim :actor-id b :task-id e3
                                                    :atomicity-reason "race e3"}))
                                (fn [] (tu/exec! k {:op :claim :actor-id c :task-id e3
                                                    :atomicity-reason "race e3"}))])
        e3-winner (first (filter :ok? e3-results))
        e3-loser (first (remove :ok? e3-results))]
    (is (= 1 (count (filter :ok? e3-results))))
    (is (= :ALREADY_CLAIMED (:error e3-loser)))
    (tu/finish! k b e1 (:claim-id claim-e1) "parser result")
    (tu/finish! k c e2 (:claim-id claim-e2) "compat result")
    (tu/finish! k (:agent-id e3-winner) e3 (:claim-id e3-winner) "test result")
    (is (= :ready (:state (kernel/task k p))))
    (is (not= :done (:state (kernel/task k p))))
    (is (= a (:owner-id (kernel/task k p))))

    ;; A now has a token responsibility to justify competition.
    (let [split2 (tu/split! k a p {:remaining-work "integrate and verify"
                                   :children [{:goal "run startup verification"
                                               :remaining-work "run"
                                               :done-when "report exists"
                                               :needs-token true}]})
          t (first (:child-ids split2))
          won (tu/take! k a :smoke-win)]
      (is (= :attempt-won (:outcome won)))
      (is (= :waiting (:state (kernel/task k p))))
      (let [claim-t (tu/claim! k a t)
            action (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                                   :claim-id (:claim-id claim-t) :kind :shell})
            _ (tu/exec-ok! k {:op :advance-action :actor-id a
                              :action-id (:action-id action) :phase :running})
            parked [(tu/park! k b) (tu/park! k c)]]
        (is (every? :ok? parked))
        (is (= #{b c} (kernel/waiters k)))
        (is (false? (kernel/global-progressable? k)))

        (tu/exec-ok! k {:op :user-input :text "also treat empty string as empty config"})
        (is (= #{} (kernel/waiters k)))
        (is (= #{b c} (:woken (last (filter #(= :wake-all (:type %))
                                            (kernel/events k))))))

        (tu/exec-ok! k {:op :advance-action :actor-id a
                        :action-id (:action-id action) :phase :finished})
        (tu/finish! k a t (:claim-id claim-t) "startup report")
        (is (= :ready (:state (kernel/task k p))))
        (is (not= :done (:state (kernel/task k p))))
        (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
        (is (= :holder (get-in (kernel/snapshot k) [:actors a :role])))

        (let [done (tu/finish! k a p nil "startup uses new loader")]
          (is (:retired? done))
          (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
          (is (= :free (get-in (kernel/snapshot k) [:token-slot :state]))))
        (is (= :done (:state (kernel/task k p))))
        (tu/assert-invariants! k)

        ;; The user input is still public work; resolve it, then a new
        ;; identity can occupy the released slot.
        (let [input-id (->> (get (kernel/snapshot k) :inputs)
                            vals
                            (filter #(= :unhandled (:state %)))
                            first
                            :input-id)]
          (tu/exec-ok! k {:op :resolve-input :actor-id b :input-id input-id
                          :task-id p :summary "captured as follow-up"})
          (is (false? (kernel/global-progressable? k)))
          (let [d (tu/spawn! k :slot-a)]
            (is (not= a d))
            (is (:ok? (tu/park! k d)))
            (is (= #{d} (kernel/waiters k))))
          (tu/assert-invariants! k))))))

(deftest concurrent-split-and-claim
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "before"
                                          :done-when "done" :owner-id a}))
        split-results (concurrent
                       [(fn [] (tu/exec! k {:op :split :actor-id a :task-id p
                                            :expected-cond {:rev 0}
                                            :remaining-work "from a"
                                            :children [(tu/child-spec 1)]}))
                        (fn [] (tu/exec! k {:op :split :actor-id b :task-id p
                                            :expected-cond {:rev 0}
                                            :remaining-work "from b"
                                            :children [(tu/child-spec 2)]}))])
        winner (first (filter :ok? split-results))
        loser (first (remove :ok? split-results))]
    (is (= 1 (count (filter :ok? split-results))))
    (is (= :EXPECTED_CONDITION_FAILED (:error loser)))
    (is (= (set (:child-ids winner))
           (get-in (kernel/task k p) [:wait-for])))
    (let [child (first (:child-ids winner))
          claim-results (concurrent
                         [(fn [] (tu/exec! k {:op :claim :actor-id a :task-id child
                                              :atomicity-reason "race a"}))
                          (fn [] (tu/exec! k {:op :claim :actor-id b :task-id child
                                              :atomicity-reason "race b"}))])]
      (is (= 1 (count (filter :ok? claim-results))))
      (is (= :ALREADY_CLAIMED (:error (first (remove :ok? claim-results)))))
      (is (= (:agent-id (first (filter :ok? claim-results)))
             (get-in (kernel/task k child) [:owner-id]))))
    (tu/assert-invariants! k)))

(deftest park-wake-race-loop
  (dotimes [_ 100]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          results (concurrent
                   [(fn [] (tu/park! k a))
                    (fn [] (tu/exec! k {:op :user-input :text "race input"}))])
          [park-result input-result] results]
      (is (:ok? input-result))
      (is (or (:ok? park-result)
              (= :PROGRESS_AVAILABLE (:error park-result))
              (= :STALE_CURSOR (:error park-result)))
          (pr-str park-result))
      (is (= #{} (kernel/waiters k)))
      (is (true? (kernel/global-progressable? k)))
      (tu/assert-invariants! k))))

(deftest batch-keeps-holder-alive-until-final-responsibility
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "first token task"
                                          :remaining-work "run"
                                          :done-when "reported"
                                          :needs-token true}))
        _ (tu/exec-ok! k {:op :take-or-die :actor-id a
                          :confirm :i-hereby-confirm-i-die-without-regret})
        claim (tu/claim! k a t)
        batch (tu/exec-ok! k {:op :batch
                              :commands [{:op :finish :actor-id a :task-id t
                                          :claim-id (:claim-id claim)
                                          :result-summary "first done"}
                                         {:op :create-task :actor-id a
                                          :goal "second token task"
                                          :remaining-work "run"
                                          :done-when "reported"
                                          :needs-token true}]})]
    (is (= 2 (count (:results batch))))
    (is (false? (:retired? batch)))
    (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
    (is (= :holder (get-in (kernel/snapshot k) [:actors a :role])))
    (let [u (->> (get (kernel/snapshot k) :tasks)
                 vals
                 (filter #(and (= :ready (:state %)) (:needs-token %)))
                 first
                 :task-id)
          claim-u (tu/claim! k a u)]
      (tu/finish! k a u (:claim-id claim-u) "second done")
      (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
      (is (= :free (get-in (kernel/snapshot k) [:token-slot :state]))))
    (tu/assert-invariants! k)))

(deftest batch-finishes-last-responsibilities-then-retires
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "ordinary" :remaining-work "do"
                                          :done-when "done" :owner-id a}))
        t (:task-id (tu/create-task! k a {:goal "token" :remaining-work "run"
                                          :done-when "reported" :needs-token true}))
        _ (tu/exec-ok! k {:op :take-or-die :actor-id a
                          :confirm :i-hereby-confirm-i-die-without-regret})
        claim (tu/claim! k a t)
        batch (tu/exec-ok! k {:op :batch
                              :commands [{:op :finish :actor-id a :task-id t
                                          :claim-id (:claim-id claim)
                                          :result-summary "token done"}
                                         {:op :finish :actor-id a :task-id p
                                          :result-summary "ordinary done"}]})]
    (is (:retired? batch))
    (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
    (is (= :done (:state (kernel/task k p))))
    (is (= :done (:state (kernel/task k t))))
    (is (= :free (get-in (kernel/snapshot k) [:token-slot :state])))
    (tu/assert-invariants! k)))

(deftest file-dependent-finish-needs-explicit-verifier
  (let [read-set [{:path "config.edn"
                   :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}]
        default-kernel (tu/new-kernel)
        a (tu/spawn! default-kernel :a)
        t (:task-id (tu/create-task! default-kernel a
                                     {:goal "publish file conclusion"
                                      :remaining-work "read config"
                                      :done-when "conclusion published"
                                      :owner-id a}))
        before (kernel/snapshot default-kernel)
        refused (tu/exec! default-kernel {:op :finish :actor-id a :task-id t
                                          :result-summary "config compatible"
                                          :read-set read-set})]
    (is (= :WRITE_GATE_REQUIRED (:error refused)))
    (is (= before (kernel/snapshot default-kernel)))
    (is (= :ready (:state (kernel/task default-kernel t))))
    (tu/assert-invariants! default-kernel))
  (let [verified-kernel (tu/new-kernel {:verify-read-set (fn [_] {:ok? true})})
        a (tu/spawn! verified-kernel :a)
        t (:task-id (tu/create-task! verified-kernel a
                                     {:goal "publish file conclusion"
                                      :remaining-work "read config"
                                      :done-when "conclusion published"
                                      :owner-id a}))
        done (tu/exec-under-write!
              verified-kernel {:op :finish :actor-id a :task-id t
                               :result-summary "config compatible"
                               :read-set [{:path "config.edn"
                                           :stamp {:size 3}}]})]
    (is (:ok? done))
    (is (= :done (:state (kernel/task verified-kernel t))))
    (tu/assert-invariants! verified-kernel)))
