(ns hc.core.swarm-stm-matrix2-test
  "Deterministic interleavings for T01, T02, T05, T11 and T13.

  Every command goes through the real kernel, which serializes each command
  with the same immutable-board CAS used in production.  The scenarios are
  sequential on purpose: a committed first transaction followed by a second
  transaction is the deterministic outcome of the corresponding race."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.swarm-stm :as swarm]
            [hc.core.swarm-stm.commands :as commands]
            [hc.core.swarm-stm.kernel :as kernel]
            [hc.core.swarm-stm-test-util :as tu]))

(defn- check!
  [k]
  (let [violations (swarm/check-invariants k)]
    (is (empty? violations) (pr-str violations))))

(defn- holder-id
  [k]
  (get-in (kernel/snapshot k) [:token-slot :holder-id]))

(defn- holder-count
  [k]
  (count (filter #(= :holder (:role %))
                 (vals (:actors (kernel/snapshot k))))))

(defn- create-token-task!
  [k actor opts]
  (:task-id
   (tu/create-task! k actor
                    (merge {:goal "token work"
                            :remaining-work "perform token work"
                            :done-when "token work is reported"
                            :needs-token true}
                           opts))))

(defn- empty-token-decision!
  "Scripted actor decision for T13: with no token responsibility on the board,
  do not issue the empty take-or-die.  The caller monitors kernel calls."
  [k actor-id]
  (if (seq (get-in (kernel/snapshot k) [:derived :token-tasks]))
    (tu/take! k actor-id :t13-decision-take)
    :observe-only))

(defn- late-finish!
  [k b p old-claim-id request-id]
  (tu/exec! k {:op :finish :actor-id b :task-id p :claim-id old-claim-id
               :result-summary "late completion from a dead identity"
               :request-id request-id}))

(defn- dead-b-with-public-p
  "B owns and claims ordinary P, then loses the token race and dies.  The loss
  releases B's claim and publishes P.  Returns the old claim id so a late
  finish can be replayed against it."
  []
  (let [k (tu/new-kernel)
        b (tu/spawn! k :slot-b)
        h (tu/spawn! k :slot-h)
        p (:task-id
           (tu/create-task! k b {:goal "ordinary parent P"
                                 :remaining-work "integrate the result"
                                 :done-when "P is integrated"
                                 :owner-id b}))
        claim (tu/claim! k b p)]
    (create-token-task! k h {:goal "holder's token work"})
    (tu/take! k h :t11-h-wins)
    (let [lost (tu/take! k b :t11-b-loses)]
      (when-not (= :attempt-lost (:outcome lost))
        (throw (ex-info "T11 setup: B did not lose the token race"
                        {:lost lost}))))
    {:k k :b b :p p :old-claim-id (:claim-id claim)}))

(deftest t01-simultaneous-token-race-one-winner
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        proposer (tu/spawn! k :slot-p)
        p (:task-id
           (tu/create-task! k b {:goal "B's ordinary responsibility"
                                 :remaining-work "hand over on loss"
                                 :done-when "P is integrated"
                                 :owner-id b}))
        claim-p (tu/claim! k b p)
        t (create-token-task! k proposer {:goal "sole token task"})
        observed (kernel/snapshot k)]
    (testing "both actors observe the same free token before either commits"
      (is (nil? (get-in observed [:token-slot :holder-id])))
      (is (contains? (get-in observed [:derived :token-tasks]) t)))
    (let [won (tu/take! k a :t01-a-request)
          lost (tu/take! k b :t01-b-request)]
      (testing "exactly one win and one loss in the same tenure"
        (is (= :attempt-won (:outcome won)))
        (is (= :attempt-lost (:outcome lost)))
        (is (= 1 (holder-count k)))
        (is (= a (holder-id k)))
        (is (= 1 (get-in (kernel/snapshot k) [:token-slot :term-id])))
        (is (= #{t} (kernel/responsibility k a))))
      (testing "the loser hands over ordinary responsibility and claims"
        (is (contains? (set (:published-task-ids lost)) p))
        (is (contains? (set (:released-claim-ids lost)) (:claim-id claim-p)))
        (is (= :dead (:life-state (kernel/actor k b))))
        (is (empty? (kernel/responsibility k b)))
        (is (= :ready (:state (kernel/task k p))))
        (is (nil? (:owner-id (kernel/task k p))))
        (is (nil? (:claim-id (kernel/task k p)))))
      (testing "a loser cannot continue ordinary work from its old observation"
        (is (= :ACTOR_INACTIVE
               (:error (tu/exec! k {:op :claim :actor-id b :task-id p
                                    :atomicity-reason "late ordinary claim"})))))
      ;; T01 forbids two effective holders and a loser continuing work.
      (is (not= b (holder-id k)))
      (is (not (contains? (kernel/responsibility k a) p))))
    (check! k)))

(deftest t02-lost-response-replays-the-same-win
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        proposer (tu/spawn! k :slot-p)
        t (create-token-task! k proposer {:goal "token task"})
        win (tu/take! k a :t02-win-request)
        claim (tu/claim! k a t)
        before (kernel/snapshot k)
        responsibility-before (kernel/responsibility k a)
        replay (tu/take! k a :t02-win-request)
        after (kernel/snapshot k)]
    (testing "the retried request returns the committed win unchanged"
      (is (= :attempt-won (:outcome win)))
      (is (:ok? replay))
      (is (= :attempt-won (:outcome replay)))
      (is (= win replay)))
    (testing "no second holder, no second tenure, no duplicate responsibility"
      (is (= before after))
      (is (= a (holder-id k)))
      (is (= 1 (get-in after [:token-slot :term-id])))
      (is (= responsibility-before (kernel/responsibility k a)))
      (is (= (:claim-id claim) (get-in after [:tasks t :claim-id])))
      (is (= (:counters before) (:counters after)))
      (is (= (:actors before) (:actors after)))
      (is (= 1 (holder-count k))))
    (testing "a different payload under the same request id is not replayed"
      (let [conflict (tu/exec! k {:op :take-or-die :actor-id a
                                  :request-id :t02-win-request
                                  :confirm :i-hereby-confirm-i-die-without-regret
                                  :unexpected :different-payload})]
        (is (= :REQUEST_ID_CONFLICT (:error conflict)))
        (is (= before (kernel/snapshot k)))))
    ;; T02 forbids a second holder or a slot respawn caused by retrying.
    (is (not= :ALREADY_HOLDER (:error replay)))
    (is (not= 2 (holder-count k)))
    (check! k)))

(deftest t05-new-token-task-joins-live-holder-before-finish
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        proposer (tu/spawn! k :slot-p)
        x (create-token-task! k proposer {:goal "A's current token task X"})]
    (is (= :attempt-won (:outcome (tu/take! k a :t05-a-wins))))
    (let [y (create-token-task! k b {:goal "new token task Y"
                                     :handoff-note "proposed by B"})
          _ (tu/exec-ok! k {:op :comment :actor-id b :task-id y
                            :text "Y proposed before X finished"})
          y0 (kernel/task k y)]
      (testing "Y is dynamically A's token responsibility"
        (is (= :ready (:state y0)))
        (is (true? (:needs-token y0)))
        (is (nil? (:owner-id y0)))
        (is (contains? (kernel/responsibility k a) y)))
      (let [claim-x (tu/claim! k a x)
            finished (tu/finish! k a x (:claim-id claim-x) "X reported")]
        (testing "finishing X does not retire the holder because Y remains"
          (is (false? (:retired? finished)))
          (is (= :alive (:life-state (kernel/actor k a))))
          (is (= :holder (:role (kernel/actor k a))))
          (is (= a (holder-id k)))
          (is (contains? (kernel/responsibility k a) y)))
        (testing "Y's proposal relationship survives A's finish transaction"
          (let [y1 (kernel/task k y)]
            (is (= :ready (:state y1)))
            (is (true? (:needs-token y1)))
            (is (nil? (:owner-id y1)))
            (is (= "proposed by B" (:handoff-note y1))))
          (is (some #(= "Y proposed before X finished" (:text %))
                    (get-in (kernel/snapshot k) [:comments y]))))
        (let [claim-y (tu/claim! k a y)]
          (is (= a (get-in (kernel/snapshot k)
                           [:claims (:claim-id claim-y) :agent-id]))))
        ;; T05 forbids treating a nil explicit owner as "A has no work".
        (is (not (:retired? finished)))
        (is (not= :dead (:life-state (kernel/actor k a))))
        (is (not (empty? (kernel/responsibility k a))))
        (is (nil? (:owner-id (kernel/task k y))))))
    (check! k)))

(deftest t11-dead-actors-late-finish-never-changes-parent
  (let [s1 (dead-b-with-public-p)
        s2 (dead-b-with-public-p)
        s3 (dead-b-with-public-p)]
    (testing "P is still unclaimed when the late finish arrives"
      (let [{:keys [k b p old-claim-id]} s1
            b-new (tu/spawn! k :slot-b)
            before (kernel/snapshot k)
            result (late-finish! k b p old-claim-id :t11-unclaimed)]
        (is (false? (:ok? result)))
        (is (= :ACTOR_INACTIVE (:error result)))
        (is (= before (kernel/snapshot k)))
        (is (= :ready (:state (kernel/task k p))))
        (is (nil? (:owner-id (kernel/task k p))))
        (is (nil? (:claim-id (kernel/task k p))))
        (is (= :alive (:life-state (kernel/actor k b-new))))
        (is (not= b b-new))
        (is (= :dead (:life-state (kernel/actor k b))))))
    (testing "P already belongs to C when the late finish arrives"
      (let [{:keys [k b p old-claim-id]} s2
            c (tu/spawn! k :slot-c)
            b-new (tu/spawn! k :slot-b)
            claim-c (tu/claim! k c p)
            before (kernel/snapshot k)
            result (late-finish! k b p old-claim-id :t11-claimed)]
        (is (false? (:ok? result)))
        (is (= :ACTOR_INACTIVE (:error result)))
        (is (= before (kernel/snapshot k)))
        (is (= :ready (:state (kernel/task k p))))
        (is (= c (:owner-id (kernel/task k p))))
        (is (= (:claim-id claim-c) (:claim-id (kernel/task k p))))
        (is (= c (get-in (kernel/snapshot k)
                         [:claims (:claim-id claim-c) :agent-id])))
        (is (= :alive (:life-state (kernel/actor k b-new))))
        (is (not= b b-new))))
    (testing "P is already done when the late finish arrives"
      (let [{:keys [k b p old-claim-id]} s3
            c (tu/spawn! k :slot-c)
            b-new (tu/spawn! k :slot-b)
            claim-c (tu/claim! k c p)
            _ (tu/finish! k c p (:claim-id claim-c) "P completed by C")
            before (kernel/snapshot k)
            result (late-finish! k b p old-claim-id :t11-done)]
        (is (false? (:ok? result)))
        (is (= :ACTOR_INACTIVE (:error result)))
        (is (= before (kernel/snapshot k)))
        (is (= :done (:state (kernel/task k p))))
        (is (nil? (:claim-id (kernel/task k p))))
        (is (= :alive (:life-state (kernel/actor k b-new))))
        (is (not= b b-new))))
    ;; T11 forbids accepting a dead identity's old claim in any of the states.
    (doseq [{:keys [k]} [s1 s2 s3]]
      (check! k))))

(deftest t13-empty-token-and-cancelled-last-responsibility
  (testing "T13(i): no token task means no empty token grab"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          calls (atom 0)
          real-execute commands/execute]
      (is (empty? (get-in (kernel/snapshot k) [:derived :token-tasks])))
      (with-redefs [commands/execute
                    (fn [board cmd env]
                      (when (= :take-or-die (:op cmd))
                        (swap! calls inc))
                      (real-execute board cmd env))]
        (is (= :observe-only (empty-token-decision! k a))))
      (is (zero? @calls))
      (is (nil? (holder-id k)))
      (is (zero? (holder-count k)))
      ;; If an actor ignores the rule, the kernel still refuses the empty grab.
      (let [probe (tu/take! k a :t13-empty-probe)]
        (is (= :TOKEN_TASK_REQUIRED (:error probe)))
        (is (= :alive (:life-state (kernel/actor k a))))
        (is (nil? (holder-id k)))
        (is (zero? (holder-count k)))
        (is (not-any? #(= :attempt-won (:type %)) (kernel/events k))))
      (check! k)))
  (testing "T13(ii): cancel last token task in the competition's commit"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          b (tu/spawn! k :slot-b)
          t (create-token-task! k b {:goal "last token task"})
          v0 (kernel/board-version k)
          result (kernel/execute-batch!
                  k [{:op :take-or-die :actor-id a
                      :confirm :i-hereby-confirm-i-die-without-regret}
                     {:op :cancel :actor-id b :task-id t
                      :reason "cancel the last token responsibility before commit"}])
          [win cancel] (:results result)]
      (is (:ok? result))
      (is (= :attempt-won (:outcome win)))
      (is (= :cancel (:op cancel)))
      (is (= (inc v0) (:board-version result)))
      (is (:retired? result))
      (is (= a (:retired-agent-id result)))
      (is (= :dead (:life-state (kernel/actor k a))))
      (is (= (:board-version result)
             (:dead-at-version (kernel/actor k a))))
      (is (nil? (holder-id k)))
      (is (= :free (get-in (kernel/snapshot k) [:token-slot :state])))
      (is (= 2 (get-in (kernel/snapshot k) [:token-slot :term-id])))
      (is (empty? (kernel/responsibility k a)))
      (is (not (contains? (:tasks (kernel/snapshot k)) t)))
      (is (zero? (holder-count k)))
      ;; T13 forbids a live "return to normal identity" exit after the win.
      (is (not= :alive (:life-state (kernel/actor k a))))
      (check! k))))
