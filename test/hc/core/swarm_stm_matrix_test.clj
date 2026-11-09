(ns hc.core.swarm-stm-matrix-test
  "Deterministic board-level scenarios for the acceptance matrix.

  Covers V07 and T04, T06, T08, T10, T12.  Every scenario drives the real
  kernel; no board transition is mocked or replaced.  Each negative assertion
  names the forbidden outcome from AGENTS.md, so a regression reads as a
  protocol failure rather than a changed expectation."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.swarm-stm :as swarm]
            [hc.core.swarm-stm.commands :as commands]
            [hc.core.swarm-stm.kernel :as kernel]
            [hc.core.swarm-stm-test-util :as tu]))

(defn- check!
  "Assert the global protocol invariants after a scenario."
  [k]
  (let [violations (swarm/check-invariants k)]
    (is (empty? violations) (pr-str violations))))

(defn- holder-id
  [k]
  (get-in (kernel/snapshot k) [:token-slot :holder-id]))

(defn- create-token-task!
  [k actor opts]
  (:task-id
   (tu/create-task! k actor
                    (merge {:goal "token work"
                            :remaining-work "perform token work"
                            :done-when "token work is reported"
                            :needs-token true}
                           opts))))

(deftest v07-ordinary-ownership-survives-foreign-holder
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        p (:task-id
           (tu/create-task! k b {:goal "ordinary parent owned by B"
                                 :remaining-work "integrate B's result"
                                 :done-when "B's parent is integrated"
                                 :owner-id b}))
        x (create-token-task! k b {:goal "A's token work"})]
    (testing "A wins the token while B keeps the ordinary parent"
      (let [won (tu/take! k a :v07-token)]
        (is (= :attempt-won (:outcome won)))
        (is (= a (holder-id k)))
        (is (= b (:owner-id (kernel/task k p))))
        (is (contains? (kernel/responsibility k b) p))
        (is (not (contains? (kernel/responsibility k a) p)))))
    (testing "finishing token work does not move the ordinary parent"
      (let [claim (tu/claim! k a x)]
        (tu/finish! k a x (:claim-id claim) "token work done"))
      (is (= b (:owner-id (kernel/task k p))))
      (is (= :ready (:state (kernel/task k p))))
      ;; V07 forbids the foreign holder from absorbing ordinary responsibility.
      (is (not= a (:owner-id (kernel/task k p))))
      (is (not= :done (:state (kernel/task k p))))
      (is (not (contains? (kernel/responsibility k a) p))))
    (check! k)))

(deftest t04-losing-competitor-publishes-waiting-parent
  (let [k (tu/new-kernel)
        b (tu/spawn! k :slot-b)
        c (tu/spawn! k :slot-c)
        d (tu/spawn! k :slot-d)
        x (:task-id
           (tu/create-task! k b {:goal "ordinary child X"
                                 :remaining-work "finish X"
                                 :done-when "X is reported"
                                 :owner-id b}))
        p (:task-id
           (tu/create-task! k b {:goal "ordinary parent P"
                                 :remaining-work "integrate X"
                                 :done-when "X is integrated"
                                 :owner-id b
                                 :wait-for #{x}}))
        claim-x (tu/claim! k b x)]
    (create-token-task! k c {:goal "keep C's tenure"})
    (is (= :waiting (:state (kernel/task k p))))
    (is (= :attempt-won (:outcome (tu/take! k c :t04-c-wins))))
    (let [lost (tu/take! k b :t04-b-loses)
          p0 (kernel/task k p)
          x0 (kernel/task k x)]
      (testing "the loss publishes P with its waiting structure intact"
        (is (= :attempt-lost (:outcome lost)))
        (is (contains? (set (:published-task-ids lost)) p))
        (is (contains? (set (:released-claim-ids lost)) (:claim-id claim-x)))
        (is (some? p0))
        (is (= :waiting (:state p0)))
        (is (nil? (:owner-id p0)))
        (is (= #{x} (:wait-for p0)))
        (is (= "integrate X" (:remaining-work p0)))
        (is (= :ready (:state x0)))
        (is (nil? (:owner-id x0)))
        (is (nil? (:claim-id x0)))
        (is (not= b (:owner-id p0))))
      (testing "X completes, P recovers to public ready, never auto-done"
        (let [claim (tu/claim! k d x)]
          (tu/finish! k d x (:claim-id claim) "X reported"))
        (let [p1 (kernel/task k p)]
          (is (= :ready (:state p1)))
          (is (nil? (:owner-id p1)))
          (is (contains? (get-in (kernel/snapshot k)
                                 [:derived :public-ready-tasks])
                         p))
          (is (= #{} (:wait-for p1)))
          (is (= "integrate X" (:remaining-work p1)))
          (is (not= :done (:state p1))))
        ;; A surviving individual can actually take the published parent.
        (let [claim-p (tu/claim! k d p)]
          (is (= d (:owner-id (kernel/task k p))))
          (is (= (:claim-id claim-p) (:claim-id (kernel/task k p)))))
        (is (not= b (:owner-id (kernel/task k p))))
        (is (not (contains? (kernel/responsibility k b) p)))))
    (check! k)))

(deftest t06-retirement-precedes-new-token-responsibility
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        e (tu/spawn! k :slot-e)
        x (create-token-task! k a {:goal "A's last token task"})]
    (is (= :attempt-won (:outcome (tu/take! k a :t06-a-wins))))
    (let [claim (tu/claim! k a x)
          retired (tu/finish! k a x (:claim-id claim) "last result")]
      (is (:retired? retired))
      (is (= a (:retired-agent-id retired)))
      (is (= :dead (:life-state (kernel/actor k a))))
      (is (nil? (holder-id k))))
    (let [y (create-token-task! k e {:goal "new token task Y"})
          y0 (kernel/task k y)
          events (kernel/events k)
          retire-seq (:event-seq
                      (first (filter #(and (= :holder-retired (:type %))
                                           (= a (:agent-id %)))
                                     events)))
          create-seq (:event-seq
                      (first (filter #(and (= :task-created (:type %))
                                           (= y (:task-id %)))
                                     events)))]
      (testing "Y arrives after A retired: no holder, still open"
        (is (some? y0))
        (is (true? (:needs-token y0)))
        (is (= :ready (:state y0)))
        (is (not= :done (:state y0)))
        (is (nil? (holder-id k)))
        (is (nil? (:owner-id y0)))
        (is (and (integer? retire-seq)
                 (integer? create-seq)
                 (< retire-seq create-seq))))
      (testing "A's dead identity cannot submit or take the new task"
        (doseq [cmd [{:op :comment :actor-id a :task-id y :text "late"}
                     {:op :claim :actor-id a :task-id y
                      :atomicity-reason "late"}
                     {:op :create-task :actor-id a
                      :goal "late" :remaining-work "late" :done-when "late"}]]
          (is (= :ACTOR_INACTIVE (:error (tu/exec! k cmd)))))
        (is (= :ACTOR_INACTIVE (:error (tu/take! k a :t06-late-take))))
        (is (not (contains? (kernel/responsibility k a) y))))
      (testing "E exhausts protected work, then wins and carries Y"
        (is (empty? (kernel/responsibility k e)))
        (is (= :attempt-won (:outcome (tu/take! k e :t06-e-wins))))
        (is (= e (holder-id k)))
        (is (contains? (kernel/responsibility k e) y))
        (let [claim (tu/claim! k e y)]
          (is (= e (get-in (kernel/snapshot k)
                           [:claims (:claim-id claim) :agent-id])))))
      ;; T06 forbids resurrecting A or tracing Y back to A's earlier tenure.
      (is (= :dead (:life-state (kernel/actor k a))))
      (is (nil? (:owner-id (kernel/task k y))))
      (is (not= a (holder-id k))))
    (check! k)))

(deftest t08-token-child-completion-keeps-ordinary-parent-owner
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        x (create-token-task! k b {:goal "token child X"})
        p (:task-id
           (tu/create-task! k b {:goal "ordinary parent P"
                                 :remaining-work "integrate X"
                                 :done-when "X is integrated"
                                 :owner-id b
                                 :wait-for #{x}}))]
    (is (= :attempt-won (:outcome (tu/take! k a :t08-a-wins))))
    (is (contains? (kernel/responsibility k a) x))
    (is (not (contains? (kernel/responsibility k a) p)))
    (let [claim (tu/claim! k a x)
          finished (tu/finish! k a x (:claim-id claim) "X reported")]
      (testing "A retires after its last token responsibility"
        (is (:retired? finished))
        (is (= a (:retired-agent-id finished)))
        (is (= :dead (:life-state (kernel/actor k a))))
        (is (empty? (kernel/responsibility k a)))
        (is (nil? (holder-id k))))
      (testing "P recovers ready and stays B's ordinary responsibility"
        (let [p1 (kernel/task k p)]
          (is (= :ready (:state p1)))
          (is (= b (:owner-id p1)))
          (is (contains? (kernel/responsibility k b) p))
          (is (= :alive (:life-state (kernel/actor k b))))
          ;; T08 forbids transferring P's ordinary responsibility to A.
          (is (not= a (:owner-id p1)))
          (is (not (contains? (kernel/responsibility k a) p)))
          (is (not= :done (:state p1))))))
    (check! k)))

(deftest t10-split-token-work-into-ordinary-and-token-children
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        x (create-token-task! k a {:goal "token task X"})]
    (is (= :attempt-won (:outcome (tu/take! k a :t10-a-wins))))
    (let [v0 (kernel/board-version k)
          split (tu/split! k a x
                            {:remaining-work "wait for ordinary E and token S"
                             :children [{:goal "ordinary child E"
                                         :remaining-work "produce E"
                                         :done-when "E is reported"
                                         :needs-token false}
                                        {:goal "token child S"
                                         :remaining-work "run S"
                                         :done-when "S is reported"
                                         :needs-token true}]})
          [e s] (:child-ids split)
          x0 (kernel/task k x)
          e0 (kernel/task k e)
          s0 (kernel/task k s)]
      (testing "one transaction applies split, token adjustment, and waits"
        (is (= (inc v0) (:board-version split)))
        (is (= 2 (count (:child-ids split))))
        (is (= :waiting (:state x0)))
        (is (= #{e s} (:wait-for x0)))
        (is (= (set (:child-ids split)) (:wait-for x0)))
        (is (= "wait for ordinary E and token S" (:remaining-work x0)))
        (is (false? (:needs-token e0)))
        (is (nil? (:owner-id e0)))
        (is (contains? (get-in (kernel/snapshot k)
                               [:derived :public-ready-tasks])
                       e))
        (is (true? (:needs-token s0)))
        (is (nil? (:owner-id s0)))
        (is (contains? (get-in (kernel/snapshot k) [:derived :token-tasks]) s)))
      (testing "the holder keeps X and S responsibility and does not die"
        (is (= :alive (:life-state (kernel/actor k a))))
        (is (= :holder (:role (kernel/actor k a))))
        (is (contains? (kernel/responsibility k a) x))
        (is (contains? (kernel/responsibility k a) s))
        (is (not (contains? (kernel/responsibility k a) e)))
        (is (not (:retired? split))))
      (testing "ordinary E completes first; the parent stays waiting"
        (let [claim (tu/claim! k b e)]
          (tu/finish! k b e (:claim-id claim) "E reported"))
        (is (= :waiting (:state (kernel/task k x))))
        (is (not= :done (:state (kernel/task k x))))
        (is (= #{s} (:wait-for (kernel/task k x)))))
      (testing "token S completes; the parent returns ready, not done"
        (let [claim (tu/claim! k a s)
              finished (tu/finish! k a s (:claim-id claim) "S reported")]
          (is (false? (:retired? finished))))
        (let [x1 (kernel/task k x)]
          (is (= :ready (:state x1)))
          (is (= #{} (:wait-for x1)))
          (is (not= :done (:state x1))))
        (is (= a (holder-id k)))
        (is (= :alive (:life-state (kernel/actor k a))))
        ;; T10 forbids auto-completing the parent or killing the holder here.
        (is (not= :done (:state (kernel/task k x))))))
    (check! k)))

(defn- c-seeks-work!
  "Scripted new normal actor C: when the sole token is already held, a
  take-or-die call is a known loss, so C asks to park instead.  The caller's
  monitor counts any take-or-die that still reaches the kernel."
  [k actor-id]
  (if (some? (get-in (kernel/snapshot k) [:token-slot :holder-id]))
    (tu/park! k actor-id)
    (tu/take! k actor-id :t12-c-take)))

(deftest t12-token-holder-blocks-doomed-competition-and-sleep
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        c (tu/spawn! k :slot-c)
        x (create-token-task! k a {:goal "A's token work"})
        won (tu/take! k a :t12-a-wins)
        c-take-calls (atom 0)
        real-execute commands/execute]
    (is (= :attempt-won (:outcome won)))
    (is (= a (holder-id k)))
    (is (empty? (kernel/responsibility k c)))
    (testing "C cannot continue ordinary work through the occupied token"
      (is (= :TOKEN_REQUIRED
             (:error (tu/exec! k {:op :claim :actor-id c :task-id x
                                  :atomicity-reason "try token work"}))))
      (is (not (contains? (kernel/responsibility k c) x))))
    (testing "C does not issue take-or-die and is refused active sleep"
      (with-redefs [commands/execute
                    (fn [board cmd env]
                      (when (and (= :take-or-die (:op cmd))
                                 (= c (:actor-id cmd)))
                        (swap! c-take-calls inc))
                      (real-execute board cmd env))]
        (let [rejection (c-seeks-work! k c)]
          (is (false? (:ok? rejection)))
          (is (= :PROGRESS_AVAILABLE (:error rejection)))
          (is (contains? (set (get-in rejection [:detail :reasons]))
                         :ready-task))
          (is (zero? @c-take-calls))
          (is (not (contains? (kernel/waiters k) c))))))
    (testing "a doomed competition still cannot create a second holder"
      (let [d (tu/spawn! k :slot-d)
            lost (tu/take! k d :t12-doomed)]
        (is (= :attempt-lost (:outcome lost)))
        (is (= :dead (:life-state (kernel/actor k d))))
        (is (= a (holder-id k)))
        (is (not= d (holder-id k)))
        (is (= 1 (count (filter #(= :holder (:role %))
                                (vals (:actors (kernel/snapshot k)))))))))
    ;; T12 forbids silent sleep while work exists and forbids a second holder.
    (is (zero? @c-take-calls))
    (is (not (contains? (kernel/waiters k) c)))
    (is (= :alive (:life-state (kernel/actor k c))))
    (check! k)))
