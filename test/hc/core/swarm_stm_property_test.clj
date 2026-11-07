(ns hc.core.swarm-stm-property-test
  (:refer-clojure :exclude [ensure])
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.kernel :as kernel]
            [hc.core.swarm-stm-test-util :as tu])
  (:import [java.util Random]))

(defn- ensure
  [pred message data]
  (when-not pred
    (throw (ex-info message data))))

(defn- ensure-invariants
  [k]
  (let [violations (kernel/check-invariants k)]
    (ensure (empty? violations) "board invariant violation"
            {:violations violations :board (kernel/snapshot k)}))
  k)

;; ---------------------------------------------------------------------------
;; V06: a parent only ever moves :waiting -> :ready; explicit finish is the
;; only path to :done.

(defspec parent-never-auto-done 100
  (prop/for-all [n (gen/choose 1 6)
                 order (gen/shuffle (range 6))]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          p (:task-id (tu/create-task! k a {:goal "parent" :remaining-work "integrate"
                                            :done-when "integrated" :owner-id a}))
          split (tu/split! k a p {:remaining-work "wait for children"
                                  :children (mapv tu/child-spec (range n))})
          children (:child-ids split)
          ordered (take n (map children (filter #(< % n) order)))]
      (doseq [cid ordered]
        (let [claim (tu/claim! k a cid)]
          (tu/finish! k a cid (:claim-id claim) (str cid " done")))
        (ensure (not= :done (:state (kernel/task k p)))
                "parent auto-completed before explicit finish"
                {:parent (kernel/task k p) :finished cid}))
      (ensure (= :ready (:state (kernel/task k p)))
              "parent did not recover to :ready after all children"
              {:parent (kernel/task k p)})
      (ensure-invariants k)
      (tu/finish! k a p nil "parent integrated")
      (ensure (= :done (:state (kernel/task k p)))
              "explicit parent finish did not complete"
              {:parent (kernel/task k p)})
      (ensure-invariants k)
      true)))

;; ---------------------------------------------------------------------------
;; V02: a logically dead identity cannot produce any valid submission.

(defn- dead-command
  [k choice]
  (let [a (first (keys (get (kernel/snapshot k) :actors)))
        task-id (first (keys (get (kernel/snapshot k) :tasks)))
        t (kernel/task k task-id)
        claim-id (:claim-id t)]
    (case choice
      :claim {:op :claim :actor-id a :task-id task-id
              :atomicity-reason "late"}
      :finish {:op :finish :actor-id a :task-id task-id
               :claim-id claim-id :result-summary "late"}
      :split {:op :split :actor-id a :task-id task-id
              :remaining-work "late"
              :children [{:goal "late" :remaining-work "late" :done-when "late"}]}
      :update {:op :update :actor-id a :task-id task-id
               :remaining-work "late"}
      :take {:op :take-or-die :actor-id a
             :confirm :i-hereby-confirm-i-die-without-regret}
      :park {:op :park-slot :actor-id a
             :observed-cursor (kernel/board-version k)}
      :comment {:op :comment :actor-id a :task-id task-id :text "late"}
      :create {:op :create-task :actor-id a :goal "late"
               :remaining-work "late" :done-when "late"}
      :release {:op :release :actor-id a :task-id task-id :claim-id claim-id})))

(defspec dead-actor-cannot-commit 100
  (prop/for-all [choice (gen/elements [:claim :finish :split :update :take
                                       :park :comment :create :release])]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          b (tu/spawn! k :b)
          _ (tu/create-task! k a {:goal "owned by a" :remaining-work "do"
                                  :done-when "done" :owner-id a})
          _ (tu/create-task! k b {:goal "keeps b alive" :remaining-work "do"
                                  :done-when "done" :owner-id b})
          _ (tu/create-task! k b {:goal "token" :remaining-work "run"
                                  :done-when "run" :needs-token true})
          _ (tu/exec-ok! k {:op :take-or-die :actor-id b
                            :confirm :i-hereby-confirm-i-die-without-regret})
          lost (tu/take! k a :dead)
          _ (ensure (= :attempt-lost (:outcome lost)) "expected loss" lost)
          before (kernel/snapshot k)
          r (tu/exec! k (dead-command k choice))]
      (ensure (not (:ok? r)) "dead actor command succeeded" {:result r})
      (ensure (= :ACTOR_INACTIVE (:error r))
              "dead actor command had wrong error" {:result r})
      (ensure (= before (kernel/snapshot k))
              "dead actor command mutated the board" {:result r})
      (ensure-invariants k)
      true)))

;; ---------------------------------------------------------------------------
;; V01/V03/V04: concurrent take-or-die has one winner, losers die and publish.

(defspec take-or-die-single-winner 50
  (prop/for-all [_ (gen/return nil)]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          b (tu/spawn! k :b)
          c (tu/spawn! k :c)
          _ (doseq [id [a b c]]
              (tu/create-task! k id {:goal (str "hold " id)
                                     :remaining-work "hold"
                                     :done-when "held"
                                     :owner-id id}))
          _ (tu/create-task! k a {:goal "token" :remaining-work "run"
                                  :done-when "run" :needs-token true})
          latch (promise)
          f (mapv (fn [[id rid]]
                    (future @latch (tu/take! k id rid)))
                  [[a :ta] [b :tb] [c :tc]])
          _ (deliver latch true)
          results (mapv deref f)
          wins (filter #(= :attempt-won (:outcome %)) results)
          losses (filter #(= :attempt-lost (:outcome %)) results)]
      (ensure (= 1 (count wins)) "expected exactly one winner" {:results results})
      (ensure (= 2 (count losses)) "expected exactly two losers" {:results results})
      (let [winner (:agent-id (first wins))
            snapshot (kernel/snapshot k)]
        (ensure (= winner (get-in snapshot [:token-slot :holder-id]))
                "winner is not the token holder" {:winner winner})
        (ensure (= 1 (count (filter #(= :holder (:role %))
                                    (vals (:actors snapshot)))))
                "multiple holder roles" {})
        (ensure (= :alive (get-in snapshot [:actors winner :life-state]))
                "winner died with remaining responsibility" {})
        (doseq [loser (map :agent-id losses)]
          (ensure (= :dead (get-in snapshot [:actors loser :life-state]))
                  "loser did not die" {:loser loser})
          (ensure (empty? (board/responsibility snapshot loser))
                  "loser retained responsibility" {:loser loser}))
        (ensure-invariants k))
      true)))

;; ---------------------------------------------------------------------------
;; Claim is a single-winner transition even under concurrent attempts.

(defspec claim-single-winner 50
  (prop/for-all [_ (gen/return nil)]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          b (tu/spawn! k :b)
          c (tu/spawn! k :c)
          t (:task-id (tu/create-task! k a {:goal "atomic" :remaining-work "do"
                                            :done-when "done"}))
          latch (promise)
          f (mapv (fn [id]
                    (future @latch
                            (tu/exec! k {:op :claim :actor-id id :task-id t
                                         :atomicity-reason "concurrent claim"})))
                  [a b c])
          _ (deliver latch true)
          results (mapv (fn [x] (try @x (catch Exception e {:ok? false :error e}))) f)
          wins (filter :ok? results)
          losses (remove :ok? results)]
      (ensure (= 1 (count wins)) "expected one claim winner" {:results results})
      (ensure (= 2 (count losses)) "expected two claim losers" {:results results})
      (ensure (every? #(= :ALREADY_CLAIMED (:error %)) losses)
              "losing claims had wrong error" {:results results})
      (let [claim-id (:claim-id (first wins))
            t' (kernel/task k t)]
        (ensure (= claim-id (:claim-id t')) "claim not attached" {})
        (ensure (= (:agent-id (first wins)) (:owner-id t'))
                "claim did not establish ownership" {}))
      (ensure-invariants k)
      true)))

;; ---------------------------------------------------------------------------
;; V08: park is one transaction with the condition check; no wake is lost.

(defspec park-then-progress-wakes 100
  (prop/for-all [action (gen/elements [:input :task :wake])]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          parked (tu/park! k a)]
      (ensure (:ok? parked) "park failed on an idle board" {:result parked})
      (ensure (= #{a} (kernel/waiters k)) "park was not registered" {})
      (case action
        :input (tu/exec-ok! k {:op :user-input :text "new requirement"})
        :task (tu/exec-ok! k {:op :create-task :actor-id a
                              :goal "new work" :remaining-work "do"
                              :done-when "done"})
        :wake (tu/exec-ok! k {:op :wake-all}))
      (ensure (empty? (kernel/waiters k))
              "waiter survived a progress transition" {:action action})
      (ensure (some #(= :wake-all (:type %)) (kernel/events k))
              "no wake-all event was published" {:action action})
      (when (kernel/global-progressable? k)
        (ensure (empty? (kernel/waiters k))
                "parked slot exists while global progress is available" {}))
      (ensure-invariants k)
      true)))

(defspec park-input-race-never-loses-wakeup 100
  (prop/for-all [_ (gen/return nil)]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          latch (promise)
          park-f (future @latch (tu/park! k a))
          input-f (future @latch (tu/exec! k {:op :user-input :text "race input"}))
          _ (deliver latch true)
          park-result @park-f
          input-result @input-f]
      (ensure (:ok? input-result) "user input failed" {:result input-result})
      (ensure (or (:ok? park-result)
                  (= :PROGRESS_AVAILABLE (:error park-result))
                  (= :STALE_CURSOR (:error park-result)))
              "park had an unexpected result" {:result park-result})
      (ensure (empty? (kernel/waiters k))
              "race left a parked slot with progress available" {})
      (ensure (true? (kernel/global-progressable? k))
              "input did not create global progress" {})
      (ensure-invariants k)
      true)))

;; ---------------------------------------------------------------------------
;; Request-id replay is stable; a different payload is rejected.

(defspec request-id-is-idempotent 100
  (prop/for-all [kind (gen/elements [:take :create])]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          _ (tu/create-task! k a {:goal "holder" :remaining-work "do"
                                  :done-when "done" :owner-id a})
          _ (tu/create-task! k a {:goal "token" :remaining-work "run"
                                  :done-when "run" :needs-token true})
          cmd (case kind
                :take {:op :take-or-die :actor-id a :request-id :rid
                       :confirm :i-hereby-confirm-i-die-without-regret}
                :create {:op :create-task :actor-id a :request-id :rid
                         :goal "one" :remaining-work "do" :done-when "done"})
          first-result (tu/exec! k cmd)
          _ (ensure (:ok? first-result) "first request failed" {:result first-result})
          before (kernel/snapshot k)
          replay (tu/exec! k cmd)
          after-replay (kernel/snapshot k)
          conflict (tu/exec! k (assoc cmd :request-id :rid
                                      :goal "different" :text "different"
                                      :confirm :different))]
      (ensure (= first-result replay) "replay result changed" {})
      (ensure (= before after-replay) "replay mutated the board" {})
      (ensure (= :REQUEST_ID_CONFLICT (:error conflict))
              "different payload reused request-id" {:result conflict})
      (ensure (= after-replay (kernel/snapshot k))
              "conflicting request mutated the board" {})
      (ensure-invariants k)
      true)))

;; ---------------------------------------------------------------------------
;; V05: holder retirement happens exactly when the complete R(a) is empty.

(defspec holder-retires-when-responsibility-empty 100
  (prop/for-all [order (gen/shuffle [:parent :token])]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          p (:task-id (tu/create-task! k a {:goal "ordinary parent"
                                            :remaining-work "integrate"
                                            :done-when "integrated"
                                            :owner-id a}))
          t (:task-id (tu/create-task! k a {:goal "token work"
                                            :remaining-work "run"
                                            :done-when "reported"
                                            :needs-token true}))
          _ (tu/exec-ok! k {:op :take-or-die :actor-id a
                            :confirm :i-hereby-confirm-i-die-without-regret})
          claim (tu/claim! k a t)
          finish-one (fn [kind]
                       (case kind
                         :parent (tu/finish! k a p nil "parent integrated")
                         :token (tu/finish! k a t (:claim-id claim) "reported")))]
      (finish-one (first order))
      (ensure (= :alive (get-in (kernel/snapshot k) [:actors a :life-state]))
              "holder retired while one responsibility remained"
              {:order order :responsibility (kernel/responsibility k a)})
      (finish-one (second order))
      (ensure (= :dead (get-in (kernel/snapshot k) [:actors a :life-state]))
              "holder did not retire after R(a) became empty" {:order order})
      (ensure (= :free (get-in (kernel/snapshot k) [:token-slot :state]))
              "token was not released on retirement" {})
      (ensure-invariants k)
      true)))

;; ---------------------------------------------------------------------------
;; A deterministic random walk over the real command surface.  This is a fuzz
;; test, not a second model: commands may be rejected, but every committed
;; state must satisfy the structural invariants.

(defn- pick
  [^Random r coll]
  (when (seq coll)
    (nth (vec coll) (.nextInt r (count coll)))))

(defn- random-command
  [k ^Random r step]
  (let [snapshot (kernel/snapshot k)
        alive (tu/alive-actors k)
        actor (pick r alive)
        task-id (pick r (keys (:tasks snapshot)))
        t (get-in snapshot [:tasks task-id])
        claim-id (:claim-id t)
        claimed (tu/claimed-tasks k)
        claimed-id (pick r claimed)
        claimed-task (get-in snapshot [:tasks claimed-id])
        claimed-claim (get-in snapshot [:claims (:claim-id claimed-task)])
        actions (vals (:actions snapshot))
        action (pick r actions)
        inputs (keys (:inputs snapshot))
        request-id (keyword (str "rw-" step "-" (.nextInt r 100000000)))]
    (case (.nextInt r 14)
      0 (if actor
          {:op :create-task :actor-id actor :request-id request-id
           :goal (str "task " step) :remaining-work "do"
           :done-when "done"}
          {:op :register-actor :slot-id (keyword (str "slot-" step))})
      1 (if (and actor task-id)
          {:op :split :actor-id actor :task-id task-id :request-id request-id
           :remaining-work (str "remaining " step)
           :children [{:goal (str "child " step)
                       :remaining-work "do"
                       :done-when "done"}]}
          {:op :wake-all})
      2 (if (and actor task-id)
          {:op :claim :actor-id actor :task-id task-id :request-id request-id
           :atomicity-reason "random walk"}
          {:op :wake-all})
      3 (if (and actor task-id)
          {:op :finish :actor-id actor :task-id task-id :request-id request-id
           :claim-id claim-id :result-summary "random finish"}
          {:op :wake-all})
      4 (if (and actor task-id)
          {:op :cancel :actor-id actor :task-id task-id :request-id request-id
           :reason "random cancel"}
          {:op :wake-all})
      5 (if (and actor task-id)
          {:op :update :actor-id actor :task-id task-id :request-id request-id
           :remaining-work (str "remaining " step)}
          {:op :wake-all})
      6 (if (and actor task-id)
          {:op :comment :actor-id actor :task-id task-id :request-id request-id
           :text (str "note " step)}
          {:op :wake-all})
      7 (if actor
          {:op :take-or-die :actor-id actor :request-id request-id
           :confirm :i-hereby-confirm-i-die-without-regret}
          {:op :wake-all})
      8 (if actor
          {:op :park-slot :actor-id actor :request-id request-id
           :observed-cursor (:board-version snapshot)}
          {:op :wake-all})
      9 {:op :user-input :request-id request-id
         :text (str "user input " step)}
      10 (if (and actor (seq inputs))
           {:op :resolve-input :actor-id actor :request-id request-id
            :input-id (pick r inputs) :summary "resolved"}
           {:op :wake-all})
      11 (if (and claimed-id claimed-claim)
           (let [owner (:agent-id claimed-claim)]
             (if (and owner (= :alive (get-in snapshot [:actors owner :life-state])))
               {:op :begin-action :actor-id owner :request-id request-id
                :task-id claimed-id :claim-id (:claim-id claimed-claim)
                :kind :edit}
               {:op :wake-all}))
           {:op :wake-all})
      12 (if (and action
                 (= :alive (get-in snapshot [:actors (:agent-id action) :life-state]))
                 (not= :finished (:phase action)))
           {:op :advance-action :actor-id (:agent-id action)
            :request-id request-id :action-id (:action-id action)
            :phase (if (contains? #{:applying :running :uncertain} (:phase action))
                     :finished
                     :applying)}
           {:op :wake-all})
      13 {:op :batch :request-id request-id
          :commands (cond-> []
                      actor (conj {:op :create-task :actor-id actor
                                   :goal (str "batch task " step)
                                   :remaining-work "do" :done-when "done"})
                      true (conj {:op :user-input :text (str "batch input " step)}))})))

(defspec random-walk-invariants 100
  (prop/for-all [seed gen/large-integer]
    (let [r (Random. (long seed))
          k (tu/new-kernel)
          a (tu/spawn! k :a)
          b (tu/spawn! k :b)
          c (tu/spawn! k :c)
          _ (tu/create-task! k a {:goal "seed parent" :remaining-work "integrate"
                                  :done-when "done" :owner-id a})
          _ (tu/create-task! k b {:goal "seed token" :remaining-work "run"
                                  :done-when "done" :needs-token true})]
      (ensure-invariants k)
      (dotimes [step 30]
        (let [cmd (random-command k r step)]
          (tu/exec! k cmd)
          (ensure-invariants k)))
      true)))
