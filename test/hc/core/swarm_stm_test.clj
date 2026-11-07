(ns hc.core.swarm-stm-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.commands :as commands]
            [hc.core.swarm-stm.kernel :as kernel]
            [hc.core.swarm-stm-test-util :as tu]))

(deftest empty-board-is-valid
  (let [b (board/empty-board)]
    (is (= [] (board/check-invariants b)))
    (is (false? (board/global-progressable? b)))
    (is (= #{} (board/responsibility b :nobody)))))

(deftest actor-registration-reuses-slot-with-new-identity
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)]
    (is (= :SLOT_OCCUPIED (:error (tu/exec! k {:op :register-actor :slot-id :slot-a}))))
    (let [b (tu/spawn! k :slot-b)
          _ (tu/create-task! k b {:goal "hold" :remaining-work "hold" :done-when "held"
                                  :owner-id b})
          _ (tu/create-task! k b {:goal "token" :remaining-work "run"
                                  :done-when "run" :needs-token true})
          _ (tu/exec-ok! k {:op :take-or-die :actor-id b
                            :confirm :i-hereby-confirm-i-die-without-regret})
          lost (tu/take! k a :loss-request)]
      (is (= :attempt-lost (:outcome lost)))
      (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
      (let [c (tu/spawn! k :slot-a)]
        (is (not= a c))
        (is (= :alive (get-in (kernel/snapshot k) [:actors c :life-state])))
        (is (= 2 (get-in (kernel/snapshot k) [:actors c :generation])))
        (tu/assert-invariants! k)))))

(deftest split-sets-parent-waiting-and-creates-public-children
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "before"
                                          :done-when "integrated" :owner-id a}))
        split (tu/split! k a p {:remaining-work "after split"
                                :children [(tu/child-spec 1) (tu/child-spec 2)]})
        parent (kernel/task k p)]
    (is (= :waiting (:state parent)))
    (is (= (set (:child-ids split)) (:wait-for parent)))
    (is (= "after split" (:remaining-work parent)))
    (is (= a (:owner-id parent)))
    (doseq [cid (:child-ids split)]
      (is (= :ready (:state (kernel/task k cid))))
      (is (= p (:parent-id (kernel/task k cid))))
      (is (nil? (:owner-id (kernel/task k cid)))))
    (tu/assert-invariants! k)))

(deftest finish-child-recovers-parent-to-ready-not-done
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "integrated" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1) (tu/child-spec 2)]})
        [c1 c2] (:child-ids split)]
    (let [claim (tu/claim! k b c1)
          r1 (tu/finish! k b c1 (:claim-id claim) "c1 result")]
      (is (empty? (:parent-ids r1))
          "a still-waiting parent is not a recovery"))
    (is (= :waiting (:state (kernel/task k p))))
    (is (not= :done (:state (kernel/task k p))))
    (let [claim (tu/claim! k b c2)]
      (tu/finish! k b c2 (:claim-id claim) "c2 result"))
    (is (= :ready (:state (kernel/task k p))))
    (is (not= :done (:state (kernel/task k p))))
    (is (= a (:owner-id (kernel/task k p))))
    (tu/assert-invariants! k)
    ;; Explicit completion is the only path to :done.
    (tu/finish! k a p nil "parent integrated")
    (is (= :done (:state (kernel/task k p))))
    (tu/assert-invariants! k)))

(deftest multi-level-parent-recovery-never-cascades
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        g (:task-id (tu/create-task! k a {:goal "grandparent" :remaining-work "integrate"
                                          :done-when "all integrated" :owner-id a}))
        gsplit (tu/split! k a g {:remaining-work "wait for parent"
                                 :children [{:goal "parent" :remaining-work "integrate children"
                                             :done-when "children integrated" :owner-id a}]})
        p (first (:child-ids gsplit))
        psplit (tu/split! k a p {:remaining-work "wait for children"
                                 :children [(tu/child-spec 1) (tu/child-spec 2)]})
        [c1 c2] (:child-ids psplit)]
    (doseq [cid [c1 c2]]
      (let [claim (tu/claim! k a cid)]
        (tu/finish! k a cid (:claim-id claim) (str cid " done"))))
    (is (= :ready (:state (kernel/task k p))))
    (is (= :waiting (:state (kernel/task k g))))
    (is (not= :done (:state (kernel/task k p))))
    (is (not= :done (:state (kernel/task k g))))
    (tu/finish! k a p nil "parent integrated")
    (is (= :done (:state (kernel/task k p))))
    (is (= :ready (:state (kernel/task k g))))
    (is (not= :done (:state (kernel/task k g))))
    (tu/assert-invariants! k)))

(deftest split-conflict-preserves-winner-structure
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "before"
                                          :done-when "done" :owner-id a}))
        latch (promise)
        f1 (future @latch
                   (tu/exec! k {:op :split :actor-id a :task-id p
                                :expected-cond {:rev 0}
                                :remaining-work "a"
                                :children [{:goal "a-child" :remaining-work "a"
                                            :done-when "a"}]}))
        f2 (future @latch
                   (tu/exec! k {:op :split :actor-id b :task-id p
                                :expected-cond {:rev 0}
                                :remaining-work "b"
                                :children [{:goal "b-child" :remaining-work "b"
                                            :done-when "b"}]}))
        _ (deliver latch true)
        r1 @f1
        r2 @f2
        results [r1 r2]
        winner (first (filter :ok? results))
        loser (first (remove :ok? results))]
    (is (= 1 (count (filter :ok? results))))
    (is (= :EXPECTED_CONDITION_FAILED (:error loser)))
    (is (= 1 (count (get-in (kernel/snapshot k) [:tasks p :child-ids]))))
    (is (= (set (:child-ids winner))
           (get-in (kernel/snapshot k) [:tasks p :wait-for])))
    (tu/assert-invariants! k)))

(deftest claim-is-exclusive
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        t (:task-id (tu/create-task! k a {:goal "atomic" :remaining-work "do"
                                          :done-when "done"}))
        c (tu/claim! k a t)]
    (is (= :ALREADY_CLAIMED (:error (tu/exec! k {:op :claim :actor-id b :task-id t
                                                 :atomicity-reason "also atomic"}))))
    (is (= (:claim-id c) (get-in (kernel/task k t) [:claim-id])))
    (is (= a (get-in (kernel/task k t) [:owner-id])))
    (is (= :CLAIM_CHANGED
           (:error (tu/exec! k {:op :finish :actor-id b :task-id t
                                :claim-id (:claim-id c)
                                :result-summary "not mine"}))))
    (tu/assert-invariants! k)))

(deftest release-clears-claim-and-stops-after-side-effect
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "atomic" :remaining-work "do"
                                          :done-when "done"}))
        c1 (tu/claim! k a t)]
    (tu/exec-ok! k {:op :release :actor-id a :task-id t
                    :claim-id (:claim-id c1)})
    (is (nil? (:claim-id (kernel/task k t))))
    (let [c2 (tu/claim! k a t)
          action (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                                 :claim-id (:claim-id c2) :kind :edit})]
      (tu/exec-ok! k {:op :advance-action :actor-id a
                      :action-id (:action-id action) :phase :applying})
      (is (= :ACTION_IN_FLIGHT
             (:error (tu/exec! k {:op :release :actor-id a :task-id t
                                  :claim-id (:claim-id c2)}))))
      (is (= :ACTION_IN_FLIGHT
             (:error (tu/exec! k {:op :finish :actor-id a :task-id t
                                  :claim-id (:claim-id c2)
                                  :result-summary "too early"}))))
      (tu/exec-ok! k {:op :advance-action :actor-id a
                      :action-id (:action-id action) :phase :finished})
      (tu/exec-ok! k {:op :release :actor-id a :task-id t
                      :claim-id (:claim-id c2)})
      (is (nil? (:claim-id (kernel/task k t))))
      (tu/finish! k a t nil "done after release"))
    (tu/assert-invariants! k)))

(deftest sequential-actions-under-one-claim
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "two sequential actions"
                                          :remaining-work "run then edit"
                                          :done-when "both effects reported"}))
        claim (tu/claim! k a t)
        first-action (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                                     :claim-id (:claim-id claim) :kind :edit})
        _ (tu/exec-ok! k {:op :advance-action :actor-id a
                          :action-id (:action-id first-action)
                          :phase :applying})
        _ (tu/exec-ok! k {:op :advance-action :actor-id a
                          :action-id (:action-id first-action)
                          :phase :finished})
        second-action (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                                      :claim-id (:claim-id claim) :kind :edit})]
    (is (= :finished (get-in (kernel/snapshot k)
                             [:actions (:action-id first-action) :phase])))
    (is (not= (:action-id first-action) (:action-id second-action)))
    (is (= :claimed (get-in (kernel/snapshot k)
                            [:actions (:action-id second-action) :phase])))
    (tu/assert-invariants! k)))

(deftest finish-clears-pre-side-effect-actions
  (doseq [phase [nil :preparing :waiting-gate]]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          t (:task-id (tu/create-task! k a {:goal "finish with pre-action"
                                            :remaining-work "one action"
                                            :done-when "reported"}))
          c (tu/claim! k a t)
          act (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                              :claim-id (:claim-id c) :kind :edit})
          aid (:action-id act)
          _ (when phase
              (tu/exec-ok! k {:op :advance-action :actor-id a
                              :action-id aid :phase phase}))
          r (tu/finish! k a t (:claim-id c) "done")]
      (is (= [aid] (:removed-action-ids r)) (str phase))
      (is (nil? (get-in (kernel/snapshot k) [:actions aid])) (str phase))
      (is (nil? (get-in (kernel/snapshot k) [:actors a :active-action-id]))
          (str phase))
      (is (= :done (:state (kernel/task k t))) (str phase))
      (tu/assert-invariants! k))))

(deftest invariants-reject-orphan-pre-side-effect-actions
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "orphan action"
                                          :remaining-work "one action"
                                          :done-when "reported"}))
        c (tu/claim! k a t)
        _ (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                          :claim-id (:claim-id c) :kind :edit})
        board (kernel/snapshot k)
        orphan-claim (-> board
                         (update :claims dissoc (:claim-id c))
                         (assoc-in [:tasks t :claim-id] nil))
        dead-action (assoc-in board [:actors a :life-state] :dead)]
    (is (some #(= :pre-side-effect-action-claim-missing (:invariant %))
              (board/check-invariants orphan-claim)))
    (is (some #(= :pre-side-effect-action-by-dead-actor (:invariant %))
              (board/check-invariants dead-action)))))

(deftest token-task-requires-holder-and-confirmation
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "shell" :remaining-work "run"
                                          :done-when "run" :needs-token true}))]
    (is (= :TOKEN_REQUIRED (:error (tu/exec! k {:op :claim :actor-id a :task-id t
                                                :atomicity-reason "run"}))))
    (is (= :CONFIRMATION_REQUIRED
           (:error (tu/exec! k {:op :take-or-die :actor-id a :confirm :nope}))))
    (let [won (tu/take! k a :win)]
      (is (= :attempt-won (:outcome won)))
      (is (false? (:retired? won)))
      (let [c (tu/claim! k a t)]
        (is (= (:claim-id c) (get-in (kernel/task k t) [:claim-id])))))
    (tu/assert-invariants! k)))

(deftest empty-token-without-token-task-is-refused
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        refused (tu/take! k a :empty-refused)]
    (is (= :TOKEN_TASK_REQUIRED (:error refused)))
    (is (false? (:ok? refused)))
    (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
    (is (= :free (get-in (kernel/snapshot k) [:token-slot :state])))
    (is (not (some #(= :attempt-won (:type %)) (kernel/events k))))
    (tu/assert-invariants! k)))

(deftest take-or-die-loser-publishes-responsibility-and-replays
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        p (:task-id (tu/create-task! k a {:goal "owned by a" :remaining-work "do"
                                          :done-when "done" :owner-id a}))
        _ (tu/create-task! k b {:goal "b keeps holder alive" :remaining-work "do"
                                :done-when "done" :owner-id b})
        _ (tu/create-task! k b {:goal "token" :remaining-work "run"
                                :done-when "run" :needs-token true})
        _ (tu/exec-ok! k {:op :take-or-die :actor-id b
                          :confirm :i-hereby-confirm-i-die-without-regret})
        lost (tu/take! k a :a-loses)]
    (is (= :attempt-lost (:outcome lost)))
    (is (= [p] (:published-task-ids lost)))
    (is (nil? (:owner-id (kernel/task k p))))
    (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
    (is (= :holder (get-in (kernel/snapshot k) [:actors b :role])))
    (let [before (kernel/snapshot k)
          replay (tu/take! k a :a-loses)]
      (is (= lost replay))
      (is (= before (kernel/snapshot k))))
    (is (= :REQUEST_ID_CONFLICT
           (:error (tu/exec! k {:op :take-or-die :actor-id a :request-id :a-loses
                                :confirm :i-hereby-confirm-i-die-without-regret
                                :unexpected :payload}))))
    (is (= :ACTOR_INACTIVE
           (:error (tu/exec! k {:op :claim :actor-id a :task-id p
                                :atomicity-reason "late"}))))
    (tu/assert-invariants! k)))

(deftest holder-retires-only-when-complete-responsibility-empty
  (testing "ordinary parent keeps holder alive after token child"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                            :done-when "integrated" :owner-id a}))
          split (tu/split! k a p {:remaining-work "wait for test"
                                  :children [{:goal "token test" :remaining-work "run"
                                              :done-when "reported" :needs-token true}]})
          _ (tu/exec-ok! k {:op :take-or-die :actor-id a
                            :confirm :i-hereby-confirm-i-die-without-regret})
          t (first (:child-ids split))
          c (tu/claim! k a t)]
      (tu/finish! k a t (:claim-id c) "test done")
      (is (= :ready (:state (kernel/task k p))))
      (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
      (is (= :holder (get-in (kernel/snapshot k) [:actors a :role])))
      (let [r (tu/finish! k a p nil "integrated")]
        (is (:retired? r))
        (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
        (is (= :free (get-in (kernel/snapshot k) [:token-slot :state]))))
      (tu/assert-invariants! k)))
  (testing "holder with only a running action does not retire"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          t (:task-id (tu/create-task! k a {:goal "token task" :remaining-work "run"
                                            :done-when "reported" :needs-token true}))
          _ (tu/exec-ok! k {:op :take-or-die :actor-id a
                            :confirm :i-hereby-confirm-i-die-without-regret})
          c (tu/claim! k a t)
          act (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                              :claim-id (:claim-id c) :kind :shell})
          _ (tu/exec-ok! k {:op :advance-action :actor-id a
                            :action-id (:action-id act) :phase :running})]
      (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
      (is (contains? (kernel/responsibility k a) (:action-id act)))
      (tu/exec-ok! k {:op :advance-action :actor-id a
                      :action-id (:action-id act) :phase :finished})
      (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
      (tu/finish! k a t (:claim-id c) "reported")
      (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
      (tu/assert-invariants! k))))

(deftest park-rejects-global-progress-and-wakes-on-resume
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "integrated" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait for test"
                                :children [{:goal "token test" :remaining-work "run"
                                            :done-when "reported" :needs-token true}]})
        _ (tu/exec-ok! k {:op :take-or-die :actor-id a
                          :confirm :i-hereby-confirm-i-die-without-regret})
        t (first (:child-ids split))
        c (tu/claim! k a t)
        act (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                            :claim-id (:claim-id c) :kind :shell})
        _ (tu/exec-ok! k {:op :advance-action :actor-id a
                          :action-id (:action-id act) :phase :running})
        parked (tu/park! k b)]
    (is (:ok? parked))
    (is (= #{b} (kernel/waiters k)))
    (is (false? (kernel/global-progressable? k)))
    (let [advanced (tu/exec-ok! k {:op :advance-action :actor-id a
                                   :action-id (:action-id act) :phase :finished})]
      (is (= #{b} (:woken advanced)))
      (is (= #{} (kernel/waiters k)))
      (is (true? (kernel/global-progressable? k))))
    (tu/finish! k a t (:claim-id c) "reported")
    (tu/assert-invariants! k)))

(deftest park-rejects-ready-task-input-and-action
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        _ (tu/create-task! k a {:goal "ready" :remaining-work "do" :done-when "done"})]
    (is (= :PROGRESS_AVAILABLE (:error (tu/park! k a))))
    (tu/assert-invariants! k))
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        _ (tu/exec-ok! k {:op :user-input :text "new input"})]
    (is (= :PROGRESS_AVAILABLE (:error (tu/park! k a))))
    (tu/assert-invariants! k))
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        t (:task-id (tu/create-task! k a {:goal "atomic" :remaining-work "do"
                                          :done-when "done"}))
        c (tu/claim! k a t)
        _ (tu/exec-ok! k {:op :begin-action :actor-id a :task-id t
                          :claim-id (:claim-id c) :kind :edit})]
    (is (= :PROGRESS_AVAILABLE (:error (tu/park! k b))))
    (tu/assert-invariants! k)))

(deftest user-input-keeps-only-attachment-reference-metadata
  (let [k (tu/new-kernel)
        _ (tu/spawn! k :a)
        result (tu/exec-ok!
                k
                {:op :user-input
                 :text "See the attached specification."
                 :message-id "m-1"
                 :attachments [{:id "a1"
                                :attachment-id "a1"
                                :blob-id "blob-1"
                                :name "spec.txt"
                                :mime "text/plain"
                                :size 12
                                :local-copy? true
                                :send-route :text
                                :content "attachment body must not persist"
                                :resolution "agent summary must not persist"}]})
        input (get-in (kernel/snapshot k) [:inputs :input-1])
        attachment (first (:attachments input))]
    (is (:ok? result))
    (is (= "See the attached specification." (:text input)))
    (is (= "m-1" (:message-id input)))
    (is (= {:attachment-id "a1"
            :blob-id "blob-1"
            :name "spec.txt"
            :mime "text/plain"
            :size 12
            :local-copy? true
            :send-route :text}
           attachment))
    (is (not (contains? attachment :content)))
    (is (not (contains? attachment :resolution)))
    (tu/assert-invariants! k)))

(deftest park-requires-current-cursor
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        cursor (kernel/board-version k)
        _ (tu/exec-ok! k {:op :user-input :text "advance version"})
        result (tu/exec! k {:op :park-slot :actor-id a :observed-cursor cursor})]
    (is (= :STALE_CURSOR (:error result)))
    (is (= #{} (kernel/waiters k)))
    (tu/assert-invariants! k)))

(deftest cancel-leaf-updates-parent-and-ledger
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "root parent" :remaining-work "integrate"
                                          :done-when "integrated" :owner-id a
                                          :ledger-key :ledger-1}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1) (tu/child-spec 2)]})
        [c1 c2] (:child-ids split)
        claim (tu/claim! k a c1)]
    (tu/finish! k a c1 (:claim-id claim) "c1 done")
    (let [r (tu/exec-ok! k {:op :cancel :actor-id a :task-id c2
                            :reason "not needed"})]
      (is (= [p] (:parent-ids r)))
      (is (= :ready (:state (kernel/task k p))))
      (is (nil? (kernel/task k c2)))
      (is (= #{:ledger-1} (set (keys (get (kernel/snapshot k) :ledger))))))
    (let [r (tu/exec-ok! k {:op :cancel :actor-id a :task-id p
                            :reason "goal withdrawn"})]
      (is (= :ledger-1 (:ledger-released r)))
      (is (empty? (get (kernel/snapshot k) :ledger))))
    (tu/assert-invariants! k)))

(deftest cancel-rejects-non-leaf-and-done
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "done" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1)]})
        c (first (:child-ids split))]
    (is (= :HAS_UNFINISHED_CHILDREN
           (:error (tu/exec! k {:op :cancel :actor-id a :task-id p
                                :reason "too early"}))))
    (let [claim (tu/claim! k a c)]
      (tu/finish! k a c (:claim-id claim) "c done"))
    (tu/finish! k a p nil "parent done")
    (is (= :TASK_DONE
           (:error (tu/exec! k {:op :cancel :actor-id a :task-id p
                                :reason "too late"}))))
    (tu/assert-invariants! k)))

(deftest cancel-external-dependency-allows-fresh-decision
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        dep (:task-id (tu/create-task! k a {:goal "dependency" :remaining-work "do"
                                            :done-when "done" :owner-id a}))
        waiter (:task-id (tu/create-task! k a {:goal "waiter" :remaining-work "use"
                                               :done-when "done" :owner-id a
                                               :wait-for #{dep}}))]
    (is (= :waiting (:state (kernel/task k waiter))))
    (let [r (tu/exec-ok! k {:op :cancel :actor-id a :task-id dep
                            :reason "obsolete"})]
      (is (= [waiter] (:parent-ids r)))
      (is (= :ready (:state (kernel/task k waiter))))
      (is (empty? (:wait-for (kernel/task k waiter)))))
    (tu/assert-invariants! k)))

(deftest replace-swaps-dependency-and-ledger
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "done" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1)]})
        c (first (:child-ids split))
        r (tu/exec-ok! k {:op :replace :actor-id a :task-id c
                          :reason "better boundary"
                          :replacement {:goal "replacement" :remaining-work "do"
                                        :done-when "done"}})
        c2 (:new-task-id r)]
    (is (nil? (kernel/task k c)))
    (is (= [c2] (get-in (kernel/task k p) [:child-ids])))
    (is (= #{c2} (get-in (kernel/task k p) [:wait-for])))
    (is (= :ready (:state (kernel/task k c2))))
    (tu/assert-invariants! k))
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        root (:task-id (tu/create-task! k a {:goal "root" :remaining-work "do"
                                             :done-when "done" :owner-id a
                                             :ledger-key :L}))
        r (tu/exec-ok! k {:op :replace :actor-id a :task-id root
                          :reason "new root"
                          :replacement {:goal "new root" :remaining-work "do"
                                        :done-when "done"}})
        new-id (:new-task-id r)]
    (is (= new-id (get-in (get (kernel/snapshot k) :ledger) [:L :task-id])))
    (is (= :L (get-in (kernel/task k new-id) [:ledger-key])))
    (tu/assert-invariants! k)))

(deftest reopen-restores-ready-and-ledger
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        root (:task-id (tu/create-task! k a {:goal "root" :remaining-work "do"
                                             :done-when "done" :owner-id a
                                             :ledger-key :L}))]
    (tu/finish! k a root nil "done")
    (is (empty? (get (kernel/snapshot k) :ledger)))
    (tu/exec-ok! k {:op :reopen :actor-id a :task-id root :reason "missing edge"})
    (is (= :ready (:state (kernel/task k root))))
    (is (= root (get-in (get (kernel/snapshot k) :ledger) [:L :task-id])))
    (tu/finish! k a root nil "done again")
    (is (empty? (get (kernel/snapshot k) :ledger)))
    (tu/assert-invariants! k)))

(deftest finished-root-ledger-key-is-not-reusable
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        root (:task-id (tu/create-task! k a {:goal "root" :remaining-work "do"
                                             :done-when "done" :owner-id a
                                             :ledger-key :L}))]
    (tu/finish! k a root nil "done")
    (is (= :LEDGER_KEY_IN_USE
           (:error (tu/exec! k {:op :create-task :actor-id a
                                :goal "replacement"
                                :remaining-work "do"
                                :done-when "done"
                                :ledger-key :L}))))
    (tu/exec-ok! k {:op :reopen :actor-id a :task-id root
                    :reason "missing edge"})
    (is (= :ready (:state (kernel/task k root))))
    (is (= root (get-in (get (kernel/snapshot k) :ledger) [:L :task-id])))
    (tu/assert-invariants! k)))

(deftest reopen-clears-dead-owner
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        p (:task-id (tu/create-task! k a {:goal "owned by a" :remaining-work "do"
                                          :done-when "done" :owner-id a}))
        _ (tu/create-task! k b {:goal "keeps b alive" :remaining-work "do"
                                :done-when "done" :owner-id b})
        _ (tu/create-task! k b {:goal "token" :remaining-work "run"
                                :done-when "run" :needs-token true})]
    (tu/finish! k a p nil "done")
    (tu/exec-ok! k {:op :take-or-die :actor-id b
                    :confirm :i-hereby-confirm-i-die-without-regret})
    (is (= :attempt-lost (:outcome (tu/take! k a :a-dead))))
    (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))
    (tu/exec-ok! k {:op :reopen :actor-id b :task-id p :reason "regression"})
    (is (= :ready (:state (kernel/task k p))))
    (is (nil? (:owner-id (kernel/task k p))))
    (let [claim (tu/claim! k b p)]
      (is (= (:claim-id claim) (get-in (kernel/task k p) [:claim-id]))))
    (tu/assert-invariants! k)))

(deftest update-enforces-fields-and-dependencies
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        t (:task-id (tu/create-task! k a {:goal "task" :remaining-work "old"
                                          :done-when "old" :owner-id a}))]
    (tu/exec-ok! k {:op :update :actor-id a :task-id t
                    :remaining-work "new" :done-when "new" :handoff-note "note"})
    (is (= "new" (:remaining-work (kernel/task k t))))
    (is (= :IMMUTABLE_FIELD
           (:error (tu/exec! k {:op :update :actor-id a :task-id t :goal "changed"}))))
    (tu/exec-ok! k {:op :update :actor-id a :task-id t :owner-id b})
    (is (= b (:owner-id (kernel/task k t))))
    (let [dep (:task-id (tu/create-task! k b {:goal "dep" :remaining-work "do"
                                              :done-when "done" :owner-id b}))]
      (is (= :DEPENDENCY_CYCLE
             (:error (tu/exec! k {:op :update :actor-id b :task-id dep
                                  :wait-for #{dep}}))))
      (tu/exec-ok! k {:op :update :actor-id b :task-id t :wait-for #{dep}})
      (is (= :waiting (:state (kernel/task k t))))
      (is (= :DEPENDENCY_CYCLE
             (:error (tu/exec! k {:op :update :actor-id b :task-id dep
                                  :wait-for #{t}})))))
    (tu/assert-invariants! k)))

(deftest comment-is-append-only-and-does-not-bump-task-rev
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "task" :remaining-work "do"
                                          :done-when "done" :owner-id a}))
        rev (:rev (kernel/task k t))]
    (tu/exec-ok! k {:op :comment :actor-id a :task-id t :text "observation"})
    (is (= rev (:rev (kernel/task k t))))
    (is (= 1 (count (get-in (kernel/snapshot k) [:comments t]))))
    (tu/assert-invariants! k)))

(deftest read-set-conflict-does-not-mutate
  (let [k (tu/new-kernel
           {:verify-read-set (fn [read-set]
                               (if (some #(= "conflict" (:path %)) read-set)
                                 {:ok? false :reason :stamp-changed}
                                 {:ok? true}))})
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "task" :remaining-work "do"
                                          :done-when "done" :owner-id a}))
        before (kernel/snapshot k)
        r (tu/exec-under-write!
           k {:op :finish :actor-id a :task-id t
              :result-summary "conclusion"
              :read-set [{:path "conflict" :stamp {:size 1}}]})]
    (is (= :FILE_CONFLICT (:error r)))
    (is (= before (kernel/snapshot k)))
    (is (= :ready (:state (kernel/task k t))))
    (tu/finish! k a t nil "conclusion" )
    (is (= :done (:state (kernel/task k t))))
    (tu/assert-invariants! k)))

(deftest nonempty-read-set-requires-explicit-verifier
  (testing "default kernel refuses file-dependent finish"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          t (:task-id (tu/create-task! k a {:goal "file dependent"
                                            :remaining-work "read config"
                                            :done-when "conclusion published"
                                            :owner-id a}))
          before (kernel/snapshot k)
          r (tu/exec! k {:op :finish :actor-id a :task-id t
                         :result-summary "config is compatible"
                         :read-set [{:path "config.edn"
                                     :stamp {:mtime-sec 1 :mtime-nsec 0
                                             :size 10}}]})]
      (is (= :WRITE_GATE_REQUIRED (:error r)))
      (is (= before (kernel/snapshot k)))
      (is (= :ready (:state (kernel/task k t))))
      (is (= :READ_SET_VERIFICATION_REQUIRED
             (:error (tu/exec-under-write!
                      k {:op :finish :actor-id a :task-id t
                         :result-summary "no verifier"
                         :read-set [{:path "config.edn" :stamp {:size 10}}]}))))
      (is (= before (kernel/snapshot k)))
      (tu/finish! k a t nil "no file basis")
      (is (= :done (:state (kernel/task k t))))
      (tu/assert-invariants! k)))
  (testing "explicit verifier may accept a verified read-set"
    (let [seen (atom nil)
          k (tu/new-kernel {:verify-read-set (fn [read-set]
                                               (reset! seen read-set)
                                               {:ok? true})})
          a (tu/spawn! k :a)
          t (:task-id (tu/create-task! k a {:goal "file dependent"
                                            :remaining-work "read config"
                                            :done-when "conclusion published"
                                            :owner-id a}))
          read-set [{:path "config.edn" :stamp {:size 10}}]
          plain (tu/exec! k {:op :finish :actor-id a :task-id t
                             :result-summary "plain"
                             :read-set read-set})
          r (tu/exec-under-write!
             k {:op :finish :actor-id a :task-id t
                :result-summary "verified conclusion"
                :read-set read-set})]
      (is (= :WRITE_GATE_REQUIRED (:error plain)))
      (is (:ok? r))
      (is (= read-set @seen))
      (is (= :done (:state (kernel/task k t))))
      (is (= read-set (get-in (kernel/task k t) [:result-refs 0 :read-set])))
      (tu/assert-invariants! k)))
  (testing "a verifier must return an explicit positive verdict"
    (let [k (tu/new-kernel {:verify-read-set (constantly nil)})
          a (tu/spawn! k :a)
          t (:task-id (tu/create-task! k a {:goal "file dependent"
                                            :remaining-work "read config"
                                            :done-when "conclusion published"
                                            :owner-id a}))
          before (kernel/snapshot k)
          r (tu/exec-under-write!
             k {:op :finish :actor-id a :task-id t
                :result-summary "unverified"
                :read-set [{:path "config.edn" :stamp {:size 1}}]})]
      (is (= :FILE_CONFLICT (:error r)))
      (is (= before (kernel/snapshot k)))
      (is (= :ready (:state (kernel/task k t))))
      (tu/assert-invariants! k))))

(deftest verified-read-set-is-recorded-under-write-gate
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "caller verified"
                                          :remaining-work "read config"
                                          :done-when "conclusion published"
                                          :owner-id a}))
        read-set [{:path "config.edn" :stamp {:size 10}}]
        plain (tu/exec! k {:op :finish :actor-id a :task-id t
                           :result-summary "plain"
                           :verified-read-set read-set})
        r (tu/exec-under-write!
           k {:op :finish :actor-id a :task-id t
              :result-summary "verified conclusion"
              :verified-read-set read-set})]
    (is (= :WRITE_GATE_REQUIRED (:error plain)))
    (is (:ok? r))
    (is (= read-set (get-in (kernel/task k t) [:result-refs 0 :read-set])))
    (is (= read-set
           (get-in (kernel/task k t) [:result-refs 0 :verified-read-set])))
    (tu/assert-invariants! k)))

(deftest write-gate-port-failures-are-explicit
  (let [k (tu/new-kernel {:verify-read-set (fn [_] {:ok? true})})
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "file dependent"
                                          :remaining-work "read config"
                                          :done-when "conclusion published"
                                          :owner-id a}))
        cmd {:op :finish :actor-id a :task-id t
             :result-summary "conclusion"
             :read-set [{:path "config.edn" :stamp {:size 1}}]}
        before (kernel/snapshot k)
        not-acquired (kernel/execute-under-write-gate!
                      k {:acquire (fn [_] nil) :release (fn [_] nil)}
                      :test-owner cmd)
        acquire-failed (kernel/execute-under-write-gate!
                        k {:acquire (fn [_] (throw (ex-info "gate down" {})))
                           :release (fn [_] nil)}
                        :test-owner cmd)
        no-port (kernel/execute-under-write-gate! k nil :test-owner cmd)]
    (is (= :WRITE_GATE_NOT_ACQUIRED (:error not-acquired)))
    (is (= :WRITE_GATE_ACQUIRE_FAILED (:error acquire-failed)))
    (is (= :WRITE_GATE_PORT_REQUIRED (:error no-port)))
    (is (= before (kernel/snapshot k)))
    (is (= :ready (:state (kernel/task k t))))
    (tu/assert-invariants! k)))

(deftest file-dependent-replay-does-not-reacquire-w
  (let [acquires (atom 0)
        gate {:acquire (fn [_] (swap! acquires inc) ::permit)
              :release (fn [_] nil)}
        k (tu/new-kernel {:verify-read-set (fn [_] {:ok? true})})
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "file dependent"
                                          :remaining-work "read config"
                                          :done-when "conclusion published"
                                          :owner-id a}))
        cmd {:op :finish :actor-id a :task-id t :request-id :finish-once
             :result-summary "conclusion"
             :read-set [{:path "config.edn" :stamp {:size 1}}]}
        first-result (kernel/execute-under-write-gate! k gate :test-owner cmd)
        replay (kernel/execute-under-write-gate! k gate :test-owner cmd)
        conflict (kernel/execute-under-write-gate!
                  k gate :test-owner (assoc cmd :result-summary "different"))]
    (is (:ok? first-result))
    (is (= first-result replay))
    (is (= :REQUEST_ID_CONFLICT (:error conflict)))
    (is (= 1 @acquires) "replay/conflict must not acquire W again")
    (tu/assert-invariants! k)))

(deftest batch-is-atomic
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        before (kernel/snapshot k)
        failed (tu/exec! k {:op :batch
                            :commands [{:op :create-task :actor-id a
                                        :goal "first" :remaining-work "do"
                                        :done-when "done"}
                                       {:op :no-such-op}]})]
    (is (= :UNKNOWN_OP (:error failed)))
    (is (= before (kernel/snapshot k)))
    (let [ok (tu/exec-ok! k {:op :batch
                             :commands [{:op :create-task :actor-id a
                                         :goal "first" :remaining-work "do"
                                         :done-when "done"}
                                        {:op :user-input :text "input"}]})]
      (is (= 2 (count (:results ok))))
      (is (= 1 (count (get (kernel/snapshot k) :tasks))))
      (is (= 1 (count (get (kernel/snapshot k) :inputs)))))
    (is (= :REQUEST_ID_NOT_ALLOWED_IN_BATCH
           (:error (tu/exec! k {:op :batch
                                :commands [{:op :create-task :actor-id a
                                            :request-id :nested
                                            :goal "second" :remaining-work "do"
                                            :done-when "done"}]}))))
    (tu/assert-invariants! k))
  (testing "batch cannot bypass the W requirement"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          t (:task-id (tu/create-task! k a {:goal "file dependent"
                                            :remaining-work "read config"
                                            :done-when "conclusion published"
                                            :owner-id a}))
          before (kernel/snapshot k)
          r (tu/exec! k {:op :batch
                         :commands [{:op :finish :actor-id a :task-id t
                                     :result-summary "conclusion"
                                     :read-set [{:path "config.edn"
                                                 :stamp {:size 1}}]}
                                    {:op :user-input :text "must not commit"}]})]
      (is (= :WRITE_GATE_REQUIRED (:error r)))
      (is (= before (kernel/snapshot k)))
      (is (= :ready (:state (kernel/task k t))))
      (is (empty? (get (kernel/snapshot k) :inputs)))
      (tu/assert-invariants! k))))

(deftest split-child-wait-for-and-dependency-cycle
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "done" :owner-id a}))
        dep (:task-id (tu/create-task! k a {:goal "dep" :remaining-work "do"
                                            :done-when "done" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(assoc (tu/child-spec 1) :wait-for #{dep})]})
        c (first (:child-ids split))]
    (is (= :waiting (:state (kernel/task k c))))
    (is (= :waiting (:state (kernel/task k p))))
    (is (= :DEPENDENCY_CYCLE
           (:error (tu/exec! k {:op :split :actor-id a :task-id p
                                :remaining-work "bad"
                                :children [{:goal "bad" :remaining-work "bad"
                                            :done-when "bad" :wait-for #{p}}]}))))
    (let [claim (tu/claim! k a dep)]
      (tu/finish! k a dep (:claim-id claim) "dep done"))
    (is (= :ready (:state (kernel/task k c))))
    (is (= :waiting (:state (kernel/task k p))))
    (tu/assert-invariants! k)))

(deftest split-rejects-done-task-in-requested-wait-for
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "before"
                                          :done-when "done" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1)]})
        c (first (:child-ids split))
        claim (tu/claim! k a c)]
    (tu/finish! k a c (:claim-id claim) "c done")
    (is (= :WAIT_TASK_DONE
           (:error (tu/exec! k {:op :split :actor-id a :task-id p
                                :remaining-work "wait again"
                                :wait-for #{c}
                                :children [(tu/child-spec 2)]}))))
    (tu/assert-invariants! k)))

(deftest invariants-after-scripted-sequence
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        c (tu/spawn! k :c)]
    (doseq [step [(fn [] (tu/create-task! k a {:goal "root" :remaining-work "do"
                                               :done-when "done" :owner-id a}))
                  (fn [] (tu/create-task! k b {:goal "token" :remaining-work "run"
                                               :done-when "run" :needs-token true}))
                  (fn [] (tu/exec-ok! k {:op :take-or-die :actor-id b
                                         :confirm :i-hereby-confirm-i-die-without-regret}))
                  (fn [] (tu/exec-ok! k {:op :comment :actor-id c :task-id :task-1
                                         :text "watching"}))
                  (fn [] (tu/exec-ok! k {:op :user-input :text "note"}))]]
      (step)
      (tu/assert-invariants! k))))

(deftest event-persistence-failure-fails-the-command-without-rollback
  (let [fail? (atom true)
        k (kernel/create
           {:on-events
            (fn [events]
              (when (and @fail?
                         (some #(= :action-begun (:type %)) events))
                {:ok? false
                 :error {:code :FLEET_STATE_PERSIST_FAILED
                         :message "injected persistence failure"}}))})
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "write-ahead"
                                          :remaining-work "begin one action"
                                          :done-when "action is durable"
                                          :owner-id a}))
        claim (tu/claim! k a t)
        result (kernel/execute! k {:op :begin-action
                                   :actor-id a
                                   :task-id t
                                   :claim-id (:claim-id claim)
                                   :kind :edit
                                   :request-id "persist-rq"})
        action (get-in (kernel/snapshot k)
                       [:actions (:action-id result)])]
    (is (false? (:ok? result)))
    (is (= :BOARD_EVENT_PERSIST_FAILED (:error result)))
    (is (false? (:persisted? result)))
    (is (= :FLEET_STATE_PERSIST_FAILED
           (get-in result [:persist-error :code])))
    (is (= :claimed (:phase action))
        "the board CAS is not rolled back when the event hook fails")
    (is (= "persist-rq" (:request-id action)))
    (is (empty? (kernel/check-invariants k)))
    (reset! fail? false)
    (let [checkpoint (kernel/execute! k {:op :checkpoint
                                         :actor-id a
                                         :reason :test})]
      (is (:ok? checkpoint))
      (is (= :claimed (get-in (kernel/snapshot k)
                              [:actions (:action-id action) :phase]))))))

(deftest acknowledge-recovery-refuses-pending-actions-without-changing-them
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "recovery ack"
                                          :remaining-work "verify"
                                          :done-when "verified"
                                          :owner-id a}))
        claim (tu/claim! k a t)
        action (:action-id (tu/exec-ok!
                            k {:op :begin-action
                               :actor-id a
                               :task-id t
                               :claim-id (:claim-id claim)
                               :kind :edit
                               :request-id "old-edit"}))
        _ (tu/exec-ok! k {:op :advance-action
                          :actor-id a
                          :action-id action
                          :phase :running})
        result (tu/exec! k {:op :acknowledge-recovery
                            :actor-id a
                            :evidence "worktree inspected"})]
    (is (false? (:ok? result)))
    (is (= :RECOVERY_ACTIONS_PENDING (:error result)))
    (is (= :running (get-in (kernel/snapshot k)
                            [:actions action :phase]))
        "the acknowledgement never resolves or advances the action")
    (is (nil? (get-in (kernel/snapshot k) [:recovery :acknowledgement])))
    (tu/assert-invariants! k)))

(deftest request-id-replay-after-persist-failure-requires-checkpoint
  (let [fail? (atom true)
        k (kernel/create
           {:on-events
            (fn [events]
              (when (and @fail?
                         (some #(= :action-begun (:type %)) events))
                {:ok? false
                 :error {:code :FLEET_STATE_PERSIST_FAILED
                         :message "injected"}}))})
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "write-ahead"
                                          :remaining-work "begin"
                                          :done-when "durable"
                                          :owner-id a}))
        claim (tu/claim! k a t)
        cmd {:op :begin-action :actor-id a :task-id t
             :claim-id (:claim-id claim) :kind :edit
             :request-id :persist-rq}
        failed (kernel/execute! k cmd)
        board-after (kernel/snapshot k)
        replay (kernel/execute! k cmd)]
    (is (false? (:ok? failed)))
    (is (false? (:persisted? failed)))
    (is (= :BOARD_EVENT_PERSIST_FAILED (:error failed)))
    (is (true? (get-in failed [:detail :requires-checkpoint?])))
    (is (= :claimed (get-in board-after [:actions (:action-id failed) :phase])))
    (is (false? (:ok? replay)))
    (is (= :BOARD_EVENT_PERSIST_FAILED (:error replay)))
    (is (false? (:persisted? replay)))
    (is (= board-after (kernel/snapshot k))
        "replay of an unpersisted request must not change the board")
    (reset! fail? false)
    (let [checkpoint (kernel/execute! k {:op :checkpoint :actor-id a
                                         :reason :test})]
      (is (:ok? checkpoint))
      (is (true? (get-in (kernel/snapshot k) [:requests :persist-rq :persisted?])))
      (let [replayed (kernel/execute! k cmd)]
        (is (:ok? replayed))
        (is (= (:action-id failed) (:action-id replayed)))))
    (tu/assert-invariants! k)))

(deftest replace-removes-reference-from-done-parent
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "done" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1)]})
        c (first (:child-ids split))]
    (tu/finish! k a p nil "parent done early")
    (is (= :done (:state (kernel/task k p))))
    (let [r (tu/exec-ok! k {:op :replace :actor-id a :task-id c
                            :reason "new boundary"
                            :replacement {:goal "replacement"
                                          :remaining-work "do"
                                          :done-when "done"}})
          c2 (:new-task-id r)]
      (is (nil? (:parent-id (kernel/task k c2))))
      (is (not (contains? (set (:child-ids (kernel/task k p))) c)))
      (is (not (contains? (set (:child-ids (kernel/task k p))) c2)))
      (is (= :done (:state (kernel/task k p)))))
    (tu/assert-invariants! k)))

(deftest replace-inherits-live-owner-and-delays-holder-retirement
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "ordinary" :remaining-work "do"
                                          :done-when "done" :owner-id a}))
        u (:task-id (tu/create-task! k a {:goal "token" :remaining-work "run"
                                          :done-when "reported" :needs-token true}))]
    (tu/exec-ok! k {:op :take-or-die :actor-id a
                    :confirm :i-hereby-confirm-i-die-without-regret})
    (let [claim-u (tu/claim! k a u)]
      (tu/finish! k a u (:claim-id claim-u) "token done"))
    (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
    (let [r (tu/exec-ok! k {:op :replace :actor-id a :task-id t
                            :reason "new boundary"
                            :replacement {:goal "replacement"
                                          :remaining-work "do"
                                          :done-when "done"}})
          t2 (:new-task-id r)]
      (is (= a (:owner-id (kernel/task k t2))))
      (is (= :alive (get-in (kernel/snapshot k) [:actors a :life-state])))
      (let [done (tu/finish! k a t2 nil "replacement done")]
        (is (:retired? done))
        (is (= :dead (get-in (kernel/snapshot k) [:actors a :life-state])))))
    (tu/assert-invariants! k)))

(deftest update-rejects-orphan-child-and-normalizes-token-owner
  (testing "removing an unfinished child dependency is rejected"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                            :done-when "done" :owner-id a}))
          split (tu/split! k a p {:remaining-work "wait"
                                  :children [(tu/child-spec 1) (tu/child-spec 2)]})
          [c1 c2] (:child-ids split)
          before (kernel/snapshot k)
          r (tu/exec! k {:op :update :actor-id a :task-id p
                         :wait-for #{c2}})]
      (is (= :ORPHAN_DEPENDENCY (:error r)))
      (is (= before (kernel/snapshot k)))
      (let [dep (:task-id (tu/create-task! k a {:goal "dep" :remaining-work "do"
                                                :done-when "done" :owner-id a}))]
        (tu/exec-ok! k {:op :update :actor-id a :task-id p
                        :wait-for #{c1 c2 dep}})
        (is (= :waiting (:state (kernel/task k p))))
        (tu/exec-ok! k {:op :update :actor-id a :task-id p
                        :wait-for #{c1 c2}})
        (is (= :waiting (:state (kernel/task k p)))))
      (tu/assert-invariants! k)))
  (testing "needs-token true clears owner and false keeps it cleared"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          t (:task-id (tu/create-task! k a {:goal "ordinary" :remaining-work "do"
                                            :done-when "done" :owner-id a}))
          _u (:task-id (tu/create-task! k a {:goal "token" :remaining-work "run"
                                             :done-when "reported" :needs-token true}))]
      (tu/exec-ok! k {:op :take-or-die :actor-id a
                      :confirm :i-hereby-confirm-i-die-without-regret})
      (tu/exec-ok! k {:op :update :actor-id a :task-id t :needs-token true})
      (is (true? (:needs-token (kernel/task k t))))
      (is (nil? (:owner-id (kernel/task k t))))
      (tu/exec-ok! k {:op :update :actor-id a :task-id t :needs-token false})
      (is (false? (:needs-token (kernel/task k t))))
      (is (nil? (:owner-id (kernel/task k t))))
      (tu/assert-invariants! k))))

(deftest invariants-detect-wait-for-cycle-and-stale-derived
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t1 (:task-id (tu/create-task! k a {:goal "one" :remaining-work "do"
                                           :done-when "done"}))
        t2 (:task-id (tu/create-task! k a {:goal "two" :remaining-work "do"
                                           :done-when "done"}))
        b (kernel/snapshot k)
        cyclic (-> b
                   (assoc-in [:tasks t1 :state] :waiting)
                   (assoc-in [:tasks t1 :wait-for] #{t2})
                   (assoc-in [:tasks t2 :state] :waiting)
                   (assoc-in [:tasks t2 :wait-for] #{t1})
                   board/derive)]
    (is (some #(= :wait-for-cycle (:invariant %))
              (board/check-invariants cyclic)))
    (let [stale (assoc-in b [:derived :ready-tasks] #{:bogus})]
      (is (some #(and (= :derived-index-mismatch (:invariant %))
                      (= :ready-tasks (:index %)))
                (board/check-invariants stale))))))

(deftest finish-events-put-task-finished-before-parent-recovery
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "done" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1)]})
        c (first (:child-ids split))
        claim (tu/claim! k a c)]
    (tu/finish! k a c (:claim-id claim) "child done")
    (let [types (mapv :type (kernel/events k))
          finished-idx (first (keep-indexed #(when (= :task-finished %2) %1) types))
          parent-idx (first (keep-indexed #(when (= :parent-recovered %2) %1) types))]
      (is (some? finished-idx))
      (is (some? parent-idx))
      (is (< finished-idx parent-idx)))
    (tu/assert-invariants! k)))

(deftest split-default-expected-cond-uses-observed-board
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "before"
                                          :done-when "done" :owner-id a}))
        _split (tu/split! k a p {:remaining-work "wait"
                                 :children [(tu/child-spec 1)]})
        board (kernel/snapshot k)
        stale (assoc-in board [:tasks p :rev] 0)
        result (commands/execute board
                                 {:op :split :actor-id a :task-id p
                                  :remaining-work "stale"
                                  :children [(tu/child-spec 2)]}
                                 {:observed-board stale})]
    (is (= :EXPECTED_CONDITION_FAILED (get-in result [:result :error])))
    (is (= board (kernel/snapshot k)))))

(deftest shell-action-requires-token-marked-task
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        t (:task-id (tu/create-task! k a {:goal "ordinary" :remaining-work "do"
                                          :done-when "done" :owner-id a}))
        claim (tu/claim! k a t)
        r1 (tu/exec! k {:op :begin-action :actor-id a :task-id t
                        :claim-id (:claim-id claim) :kind :shell})]
    (is (= :TOKEN_REQUIRED (:error r1)))
    (tu/create-task! k a {:goal "token" :remaining-work "run"
                          :done-when "run" :needs-token true})
    (tu/exec-ok! k {:op :take-or-die :actor-id a
                    :confirm :i-hereby-confirm-i-die-without-regret})
    (let [r2 (tu/exec! k {:op :begin-action :actor-id a :task-id t
                          :claim-id (:claim-id claim) :kind :shell})]
      (is (= :TOKEN_REQUIRED (:error r2)))
      (is (= :task-does-not-need-token (get-in r2 [:detail :reason]))))
    (tu/assert-invariants! k)))

(deftest take-or-die-loser-publishes-only-ordinary-tasks
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        b (tu/spawn! k :b)
        ordinary (:task-id (tu/create-task! k a {:goal "ordinary"
                                                 :remaining-work "do"
                                                 :done-when "done"
                                                 :owner-id a}))
        token (:task-id (tu/create-task! k a {:goal "token"
                                              :remaining-work "run"
                                              :done-when "reported"
                                              :needs-token true
                                              :owner-id a}))
        _ (tu/create-task! k b {:goal "keeps b alive" :remaining-work "do"
                                :done-when "done" :owner-id b})
        _ (tu/exec-ok! k {:op :take-or-die :actor-id b
                          :confirm :i-hereby-confirm-i-die-without-regret})
        lost (tu/take! k a :a-loses)]
    (is (= :attempt-lost (:outcome lost)))
    (is (= [ordinary] (:published-task-ids lost)))
    (is (nil? (:owner-id (kernel/task k ordinary))))
    (is (nil? (:owner-id (kernel/task k token))))
    (tu/assert-invariants! k)))

(deftest replace-supports-and-validates-wait-for
  (let [k (tu/new-kernel)
        a (tu/spawn! k :a)
        p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                          :done-when "done" :owner-id a}))
        split (tu/split! k a p {:remaining-work "wait"
                                :children [(tu/child-spec 1)]})
        c (first (:child-ids split))
        dep (:task-id (tu/create-task! k a {:goal "dep" :remaining-work "do"
                                            :done-when "done" :owner-id a}))
        r (tu/exec-ok! k {:op :replace :actor-id a :task-id c
                          :reason "needs input"
                          :replacement {:goal "replacement"
                                        :remaining-work "do"
                                        :done-when "done"
                                        :wait-for #{dep}}})
        c2 (:new-task-id r)]
    (is (= :waiting (:state (kernel/task k c2))))
    (is (= #{dep} (:wait-for (kernel/task k c2))))
    (let [before (kernel/snapshot k)]
      (is (= :DEPENDENCY_CYCLE
             (:error (tu/exec! k {:op :replace :actor-id a :task-id c2
                                  :reason "bad self reference"
                                  :replacement {:goal "bad"
                                                :remaining-work "bad"
                                                :done-when "bad"
                                                :wait-for #{c2}}}))))
      (is (= :DEPENDENCY_CYCLE
             (:error (tu/exec! k {:op :replace :actor-id a :task-id c2
                                  :reason "bad ancestor reference"
                                  :replacement {:goal "bad"
                                                :remaining-work "bad"
                                                :done-when "bad"
                                                :wait-for #{p}}}))))
      (is (= before (kernel/snapshot k)))
      (is (= :INVALID_REPLACEMENT
             (:error (tu/exec! k {:op :replace :actor-id a :task-id c2
                                  :reason "bad type"
                                  :replacement {:goal "bad"
                                                :remaining-work "bad"
                                                :done-when "bad"
                                                :wait-for [dep]}})))))
    (tu/assert-invariants! k)))

(deftest resolve-input-requires-live-actor
  (let [k (tu/new-kernel)
        _ (tu/exec-ok! k {:op :user-input :text "input"})
        r (tu/exec! k {:op :resolve-input :input-id :input-1
                       :summary "resolved"})]
    (is (= :ACTOR_REQUIRED (:error r)))
    (is (= :unhandled (get-in (kernel/snapshot k) [:inputs :input-1 :state])))
    (tu/assert-invariants! k)))
