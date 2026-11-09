(ns hc.core.swarm-stm-v-matrix-test
  "Deterministic coverage for the V01-V08 control properties.

  V01/V02/V03/V04/V05/V08 are driven through the real kernel.  Races are
  expressed as consecutive committed commands or as one batch transition, so
  the expected interleaving is decided by the test rather than by thread
  scheduling."
  (:require [clojure.test :refer [are deftest is testing]]
            [hc.core.swarm-stm :as swarm]
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

;; ---------------------------------------------------------------------------
;; V01

(deftest v01-at-most-one-effective-holder
  (testing "sequential serialization: B saw a free token but still loses"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          b (tu/spawn! k :slot-b)
          c (tu/spawn! k :slot-c)
          _ (create-token-task! k c {:goal "sole token task"})
          observed (kernel/snapshot k)
          observed-version (:board-version observed)]
      (is (nil? (get-in observed [:token-slot :holder-id])))
      (let [won (tu/take! k a :v01-a-request)
            mid (kernel/snapshot k)
            mid-version (:board-version mid)
            lost (tu/take! k b :v01-b-request)]
        (is (= :attempt-won (:outcome won)))
        (is (< observed-version mid-version))
        (is (= a (get-in mid [:token-slot :holder-id])))
        (is (= 1 (holder-count k)))
        (is (= :attempt-lost (:outcome lost)))
        (is (= 1 (holder-count k)))
        (is (= a (holder-id k)))
        (is (= 1 (get-in (kernel/snapshot k) [:token-slot :term-id])))
        ;; V01 forbids a second effective holder at any observation point.
        (is (not= b (holder-id k)))
        (is (not= 2 (holder-count k))))
      (check! k)))
  (testing "both competitions in one batch still leave exactly one holder"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          b (tu/spawn! k :slot-b)
          c (tu/spawn! k :slot-c)
          _ (create-token-task! k c {:goal "batch token task"})
          v0 (kernel/board-version k)
          batch (kernel/execute-batch!
                 k [{:op :take-or-die :actor-id a
                     :confirm :i-hereby-confirm-i-die-without-regret}
                    {:op :take-or-die :actor-id b
                     :confirm :i-hereby-confirm-i-die-without-regret}])
          [win lose] (:results batch)]
      (is (:ok? batch))
      (is (= :attempt-won (:outcome win)))
      (is (= :attempt-lost (:outcome lose)))
      (is (= (inc v0) (:board-version batch)))
      (is (= a (holder-id k)))
      (is (= 1 (holder-count k)))
      (is (= :dead (:life-state (kernel/actor k b))))
      ;; The same forbidden outcome, now across one batch commit.
      (is (not= b (holder-id k)))
      (is (not= 2 (holder-count k)))
      (check! k))))

;; ---------------------------------------------------------------------------
;; V02

(deftest v02-dead-identity-cannot-submit-any-entry
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        c (tu/spawn! k :slot-c)
        p (:task-id
           (tu/create-task! k c {:goal "live task owned by C"
                                 :remaining-work "keep working"
                                 :done-when "C reports"
                                 :owner-id c}))
        claim-p (tu/claim! k c p)
        _ (create-token-task! k c {:goal "token task"})
        _ (tu/take! k a :v02-a-wins)
        lost (tu/take! k b :v02-b-loses)
        baseline (kernel/snapshot k)]
    (is (= :attempt-lost (:outcome lost)))
    (is (= :dead (:life-state (kernel/actor k b))))
    (testing "all thirteen mutating entries reject the dead identity"
      (are [cmd] (let [before (kernel/snapshot k)
                       result (tu/exec! k cmd)]
                   (is (false? (:ok? result)))
                   (is (= :ACTOR_INACTIVE (:error result)))
                   (is (= before (kernel/snapshot k))))
        {:op :claim :actor-id b :task-id p :atomicity-reason "late"}
        {:op :finish :actor-id b :task-id p
         :claim-id (:claim-id claim-p) :result-summary "late"}
        {:op :release :actor-id b :task-id p :claim-id (:claim-id claim-p)}
        {:op :comment :actor-id b :task-id p :text "late"}
        {:op :update :actor-id b :task-id p :remaining-work "late"}
        {:op :split :actor-id b :task-id p :remaining-work "late"
         :children [{:goal "late child" :remaining-work "late"
                     :done-when "late"}]}
        {:op :cancel :actor-id b :task-id p :reason "late"}
        {:op :replace :actor-id b :task-id p :reason "late"
         :replacement {:goal "late replacement" :remaining-work "late"
                       :done-when "late"}}
        {:op :reopen :actor-id b :task-id p :reason "late"}
        {:op :begin-action :actor-id b :task-id p
         :claim-id (:claim-id claim-p) :kind :edit}
        {:op :take-or-die :actor-id b :request-id :v02-late-take
         :confirm :i-hereby-confirm-i-die-without-regret}
        {:op :park-slot :actor-id b :observed-cursor (kernel/board-version k)}
        {:op :create-task :actor-id b :goal "late" :remaining-work "late"
         :done-when "late"}))
    ;; V02 forbids any dead-identity side effect, including a fresh request.
    (is (= baseline (kernel/snapshot k)))
    (is (nil? (get-in (kernel/snapshot k) [:actors b :active-action-id])))
    (is (not (contains? (kernel/waiters k) b)))
    (check! k)))

;; ---------------------------------------------------------------------------
;; V03

(deftest v03-competition-results-are-closed
  (let [k (tu/new-kernel)
        a (tu/spawn! k :slot-a)
        b (tu/spawn! k :slot-b)
        c (tu/spawn! k :slot-c)
        _ (create-token-task! k c {:goal "token task"})
        win (tu/take! k a :v03-a-win)
        win-replay (tu/take! k a :v03-a-win)
        loss (tu/take! k b :v03-b-lose)
        loss-replay (tu/take! k b :v03-b-lose)
        before-rejections (kernel/snapshot k)
        no-confirm (tu/exec! k {:op :take-or-die :actor-id c
                                :request-id :v03-no-confirm})
        wrong-confirm (tu/exec! k {:op :take-or-die :actor-id c
                                   :request-id :v03-wrong-confirm
                                   :confirm :nope})
        already-holder (tu/exec! k {:op :take-or-die :actor-id a
                                    :request-id :v03-already-holder
                                    :confirm :i-hereby-confirm-i-die-without-regret})
        after-rejections (kernel/snapshot k)
        occupied (tu/take! k c :v03-occupied)
        successes [win win-replay loss loss-replay occupied]
        rejections [no-confirm wrong-confirm already-holder]
        outcomes (set (keep :outcome successes))
        errors (set (map :error rejections))]
    (testing "valid competitions only produce win or loss"
      (is (= #{:attempt-won :attempt-lost} outcomes))
      (is (every? #{:attempt-won :attempt-lost} outcomes))
      (is (= (:outcome win) (:outcome win-replay)))
      (is (= (:outcome loss) (:outcome loss-replay))))
    (testing "protocol rejection is disjoint from competition outcome"
      (is (= #{:CONFIRMATION_REQUIRED :ALREADY_HOLDER} errors))
      (is (not-any? outcomes errors))
      (is (every? #(nil? (:outcome %)) rejections))
      (is (= before-rejections after-rejections)))
    ;; V03 forbids a third competition state or a rejection masquerading as one.
    (is (not (contains? outcomes :ALREADY_HOLDER)))
    (is (not (contains? outcomes :RETRY)))
    (is (not (contains? outcomes :OK-BUT-NO-TOKEN)))
    (is (not= 2 (holder-count k)))
    (check! k)))

;; ---------------------------------------------------------------------------
;; V04

(defn- references-to
  [snap agent-id]
  (let [tasks (vals (:tasks snap))
        claims (vals (:claims snap))
        actions (vals (:actions snap))]
    {:owned-tasks (filterv #(= agent-id (:owner-id %)) tasks)
     :claims (filterv #(= agent-id (:agent-id %)) claims)
     :actions (filterv #(= agent-id (:agent-id %)) actions)
     :active-action-id (get-in snap [:actors agent-id :active-action-id])
     :parked? (contains? (:waiters snap) agent-id)
     :requests (filterv #(= agent-id (get-in % [:command :actor-id]))
                        (vals (:requests snap)))}))

(defn- assert-no-references!
  [snap agent-id]
  (let [refs (references-to snap agent-id)]
    (is (empty? (:owned-tasks refs)))
    (is (empty? (:claims refs)))
    (is (empty? (:actions refs)))
    (is (nil? (:active-action-id refs)))
    (is (false? (:parked? refs)))
    ;; A committed :attempt-lost result is idempotency history, not a pending
    ;; turn.  No request may remain unpersisted, and B's only recorded turn is
    ;; that irreversible loss.
    (is (not-any? #(false? (:persisted? %)) (vals (:requests snap))))
    (is (every? #(= :attempt-lost (get-in % [:result :outcome]))
                (:requests refs)))
    (is (not-any? (fn [task]
                    (= agent-id (get-in snap [:claims (:claim-id task) :agent-id])))
                  (:tasks snap)))))

(deftest v04-loser-leaves-no-transferable-responsibility
  (testing "owner and claim references are removed in the losing transaction"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          b (tu/spawn! k :slot-b)
          c (tu/spawn! k :slot-c)
          x (:task-id
             (tu/create-task! k b {:goal "ordinary child X"
                                   :remaining-work "finish X"
                                   :done-when "X is reported"
                                   :owner-id b}))
          claim-x (tu/claim! k b x)
          p (:task-id
             (tu/create-task! k b {:goal "ordinary parent P"
                                   :remaining-work "integrate X"
                                   :done-when "X is integrated"
                                   :owner-id b
                                   :wait-for #{x}}))
          _ (create-token-task! k c {:goal "token task"})
          _ (tu/take! k a :v04-a-wins)
          lost (tu/take! k b :v04-b-loses)
          snap (kernel/snapshot k)]
      (is (= :attempt-lost (:outcome lost)))
      (is (contains? (set (:released-claim-ids lost)) (:claim-id claim-x)))
      (is (= :dead (:life-state (kernel/actor k b))))
      (is (empty? (kernel/responsibility k b)))
      (is (contains? (:tasks snap) p))
      (is (= :waiting (:state (kernel/task k p))))
      (is (nil? (:owner-id (kernel/task k p))))
      (is (= :ready (:state (kernel/task k x))))
      (is (nil? (:owner-id (kernel/task k x))))
      (is (nil? (:claim-id (kernel/task k x))))
      (assert-no-references! snap b)
      (check! k)))
  (testing "a parked loser's waiter and owner references are cleared"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          b (tu/spawn! k :slot-b)
          c (tu/spawn! k :slot-c)
          y (:task-id
             (tu/create-task! k c {:goal "blocked ready task Y"
                                   :remaining-work "run Y"
                                   :done-when "Y is reported"
                                   :owner-id c}))
          claim-y (tu/claim! k c y)
          action (tu/exec-ok! k {:op :begin-action :actor-id c :task-id y
                                 :claim-id (:claim-id claim-y) :kind :edit})
          _ (tu/exec-ok! k {:op :advance-action :actor-id c
                            :action-id (:action-id action) :phase :running})
          t (create-token-task! k c {:goal "token task waiting on Y"
                                     :wait-for #{y}})
          p (:task-id
             (tu/create-task! k b {:goal "B's waiting parent P"
                                   :remaining-work "integrate T"
                                   :done-when "T is integrated"
                                   :owner-id b
                                   :wait-for #{t}}))]
      (is (false? (kernel/global-progressable? k)))
      (is (:ok? (tu/park! k b)))
      (is (contains? (kernel/waiters k) b))
      (is (= :attempt-won (:outcome (tu/take! k a :v04-a-wins-parked))))
      (let [lost (tu/take! k b :v04-b-loses-parked)
            snap (kernel/snapshot k)]
        (is (= :attempt-lost (:outcome lost)))
        (is (contains? (set (:published-task-ids lost)) p))
        (is (= :dead (:life-state (kernel/actor k b))))
        (is (empty? (kernel/responsibility k b)))
        (is (not (contains? (kernel/waiters k) b)))
        (is (contains? (:tasks snap) p))
        (is (= :waiting (:state (kernel/task k p))))
        (is (nil? (:owner-id (kernel/task k p))))
        (is (= #{t} (:wait-for (kernel/task k p))))
        (assert-no-references! snap b))
      ;; V04 forbids any owner/claim/action/waiter reference to the dead loser.
      (is (not (contains? (kernel/waiters k) b)))
      (is (not= b (:owner-id (kernel/task k p))))
      (check! k))))

;; ---------------------------------------------------------------------------
;; V05

(deftest v05-retirement-and-empty-responsibility-commit-together
  (testing "finish the last responsibility"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          c (tu/spawn! k :slot-c)
          x (create-token-task! k c {:goal "A's last responsibility"})
          _ (tu/take! k a :v05-a-wins)
          claim-x (tu/claim! k a x)
          v0 (kernel/board-version k)
          finished (tu/finish! k a x (:claim-id claim-x) "last result")
          snap (kernel/snapshot k)
          finish-event (first (filter #(and (= :task-finished (:type %))
                                            (= x (:task-id %)))
                                      (:events snap)))
          retire-event (first (filter #(and (= :holder-retired (:type %))
                                            (= a (:agent-id %)))
                                      (:events snap)))
          v (:board-version snap)]
      (is (:retired? finished))
      (is (= a (:retired-agent-id finished)))
      (is (= (inc v0) v))
      (is (= v (:at-version finish-event) (:at-version retire-event)))
      (is (= v (:dead-at-version (kernel/actor k a))))
      (is (nil? (holder-id k)))
      (is (= :free (get-in snap [:token-slot :state])))
      (is (empty? (kernel/responsibility k a)))
      (is (= :dead (:life-state (kernel/actor k a))))
      ;; V05 forbids a committed version with empty R(a) and a live holder.
      (is (not (and (some? (holder-id k))
                    (empty? (kernel/responsibility k a)))))
      (is (not= :alive (:life-state (kernel/actor k a))))
      (check! k)))
  (testing "cancel the last responsibility"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          b (tu/spawn! k :slot-b)
          x (create-token-task! k b {:goal "A's last token responsibility"})
          _ (tu/take! k a :v05-a-wins-cancel)
          v0 (kernel/board-version k)
          cancelled (tu/exec-ok! k {:op :cancel :actor-id b :task-id x
                                    :reason "last responsibility cancelled"})
          snap (kernel/snapshot k)
          cancel-event (first (filter #(and (= :task-cancelled (:type %))
                                            (= x (:task-id %)))
                                      (:events snap)))
          retire-event (first (filter #(and (= :holder-retired (:type %))
                                            (= a (:agent-id %)))
                                      (:events snap)))
          v (:board-version snap)]
      (is (:retired? cancelled))
      (is (= a (:retired-agent-id cancelled)))
      (is (= (inc v0) v))
      (is (= v (:at-version cancel-event) (:at-version retire-event)))
      (is (= v (:dead-at-version (kernel/actor k a))))
      (is (nil? (holder-id k)))
      (is (= :free (get-in snap [:token-slot :state])))
      (is (empty? (kernel/responsibility k a)))
      (is (= :dead (:life-state (kernel/actor k a))))
      (is (not (and (some? (holder-id k))
                    (empty? (kernel/responsibility k a)))))
      (is (not= :alive (:life-state (kernel/actor k a))))
      (check! k))))

;; ---------------------------------------------------------------------------
;; V08

(defn- park-or-throw!
  [k agent-id]
  (let [result (tu/park! k agent-id)]
    (when-not (:ok? result)
      (throw (ex-info "V08 setup: park failed" {:result result})))
    result))

(defn- prepare-new-task
  []
  (let [k (tu/new-kernel)
        parked (tu/spawn! k :slot-parked)
        creator (tu/spawn! k :slot-creator)]
    (park-or-throw! k parked)
    {:k k
     :parked parked
     :trigger (fn [k]
                (tu/create-task! k creator {:goal "new ready work"
                                           :remaining-work "do it"
                                           :done-when "it is reported"}))}))

(defn- prepare-action-finish
  []
  (let [k (tu/new-kernel)
        parked (tu/spawn! k :slot-parked)
        worker (tu/spawn! k :slot-worker)
        y (:task-id
           (tu/create-task! k worker {:goal "blocked ready task"
                                      :remaining-work "run"
                                      :done-when "run is reported"
                                      :owner-id worker}))
        claim (tu/claim! k worker y)
        action (tu/exec-ok! k {:op :begin-action :actor-id worker :task-id y
                               :claim-id (:claim-id claim) :kind :edit})
        _ (tu/exec-ok! k {:op :advance-action :actor-id worker
                          :action-id (:action-id action) :phase :running})]
    (when (kernel/global-progressable? k)
      (throw (ex-info "V08 setup: blocked action is still progressable" {})))
    (park-or-throw! k parked)
    {:k k
     :parked parked
     :trigger (fn [k]
                (tu/exec-ok! k {:op :advance-action :actor-id worker
                                :action-id (:action-id action) :phase :finished}))}))

(defn- prepare-cancel-dependency
  []
  (let [k (tu/new-kernel)
        parked (tu/spawn! k :slot-parked)
        worker (tu/spawn! k :slot-worker)
        parent-owner (tu/spawn! k :slot-parent)
        y (:task-id
           (tu/create-task! k worker {:goal "blocked ready task"
                                      :remaining-work "run"
                                      :done-when "run is reported"
                                      :owner-id worker}))
        claim (tu/claim! k worker y)
        action (tu/exec-ok! k {:op :begin-action :actor-id worker :task-id y
                               :claim-id (:claim-id claim) :kind :edit})
        _ (tu/exec-ok! k {:op :advance-action :actor-id worker
                          :action-id (:action-id action) :phase :running})
        x (:task-id
           (tu/create-task! k worker {:goal "waiting leaf X"
                                      :remaining-work "wait for Y"
                                      :done-when "X can run"
                                      :wait-for #{y}}))
        _ (tu/create-task! k parent-owner {:goal "parent waiting on X"
                                           :remaining-work "integrate X"
                                           :done-when "X is integrated"
                                           :owner-id parent-owner
                                           :wait-for #{x}})]
    (when (kernel/global-progressable? k)
      (throw (ex-info "V08 setup: waiting chain is still progressable" {})))
    (park-or-throw! k parked)
    {:k k
     :parked parked
     :trigger (fn [k]
                (tu/exec-ok! k {:op :cancel :actor-id worker :task-id x
                                :reason "release the waiting parent"}))}))

(deftest v08-wake-and-park-registration-cannot-be-lost
  (testing "check-then-commit race: park is rejected and not registered"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)
          v0 (kernel/board-version k)]
      (is (false? (kernel/global-progressable? k)))
      (tu/exec-ok! k {:op :user-input :text "work arrives after the check"})
      (let [stale (tu/exec! k {:op :park-slot :actor-id a
                               :observed-cursor v0})
            current (tu/park! k a)]
        (is (= :STALE_CURSOR (:error stale)))
        (is (= :PROGRESS_AVAILABLE (:error current)))
        (is (not (contains? (kernel/waiters k) a))))
      (check! k)))
  (testing "registered park is woken and cleared in the same commit"
    (let [k (tu/new-kernel)
          a (tu/spawn! k :slot-a)]
      (is (:ok? (tu/park! k a)))
      (is (= #{a} (kernel/waiters k)))
      (let [result (tu/exec-ok! k {:op :user-input :text "work after registration"})
            snap (kernel/snapshot k)
            wake (last (filter #(= :wake-all (:type %)) (:events snap)))]
        (is (contains? (:woken result) a))
        (is (contains? (:woken wake) a))
        (is (empty? (kernel/waiters k)))
        (is (= (:board-version result) (:at-version wake)))
        (is (not (contains? (kernel/waiters k) a))))
      (check! k)))
  (testing "every empty->nonempty progress transition wakes every waiter"
    (are [prepare] (let [{:keys [k parked trigger]} (prepare)
                         result (trigger k)
                         snap (kernel/snapshot k)
                         wake (last (filter #(= :wake-all (:type %))
                                            (:events snap)))]
                     (is (:ok? result))
                     (is (some? wake))
                     (is (contains? (:woken wake) parked))
                     (is (contains? (:woken result) parked))
                     (is (empty? (kernel/waiters k)))
                     (is (true? (kernel/global-progressable? k)))
                     (is (= (:board-version result) (:at-version wake)))
                     (is (not (contains? (kernel/waiters k) parked)))
                     (check! k))
      prepare-new-task
      prepare-action-finish
      prepare-cancel-dependency)))
