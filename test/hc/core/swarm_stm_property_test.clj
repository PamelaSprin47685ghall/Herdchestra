(ns hc.core.swarm-stm-property-test
  (:refer-clojure :exclude [ensure])
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.kernel :as kernel]
            [hc.core.swarm-stm-test-util :as tu]))

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

(defspec take-or-die-single-winner 100
  (prop/for-all [n (gen/choose 2 6)
                 order (gen/shuffle (range 6))
                 rid-seeds (gen/vector (gen/choose 0 1000000000) 6 6)]
    (let [slots (take n [:a :b :c :d :e :f])
          k (tu/new-kernel)
          actors (mapv #(tu/spawn! k %) slots)
          ;; A permutation of 0..5 filtered to 0..n-1 is a random order of
          ;; the participating indexes without an out-of-range pick.
          participation-order (vec (filter #(< % n) order))
          _ (doseq [id actors]
              (tu/create-task! k id {:goal (str "hold " (name id))
                                     :remaining-work "hold"
                                     :done-when "held"
                                     :owner-id id}))
          _ (tu/create-task! k (first actors)
                             {:goal "token" :remaining-work "run"
                              :done-when "run" :needs-token true})
          ;; Every participant gets a unique request-id, so every non-winner
          ;; must execute the loss path instead of short-circuiting as a
          ;; request-id replay conflict.
          attempts (mapv (fn [step]
                           (let [i (nth participation-order step)
                                 actor-id (nth actors i)
                                 rid (keyword (str "take-" i "-"
                                                   (nth rid-seeds i)))]
                             {:actor-id actor-id
                              :request-id rid
                              :result (tu/take! k actor-id rid)}))
                         (range n))
          results (mapv :result attempts)
          wins (filter #(= :attempt-won (:outcome %)) results)
          losses (filter #(= :attempt-lost (:outcome %)) results)
          snapshot (kernel/snapshot k)]
      (ensure (= 1 (count wins))
              "expected exactly one winner" {:attempts attempts})
      (ensure (= (dec n) (count losses))
              "every non-winner must take the loss path"
              {:attempts attempts :losses losses})
      (ensure (apply distinct? (map :request-id attempts))
              "request-ids must be unique within one trial"
              {:attempts attempts})
      (let [winner (:agent-id (first wins))]
        (ensure (= winner (get-in snapshot [:token-slot :holder-id]))
                "winner is not the token holder"
                {:winner winner :attempts attempts})
        (ensure (= 1 (count (filter #(= :holder (:role %))
                                    (vals (:actors snapshot)))))
                "multiple holder roles" {:attempts attempts})
        (ensure (= :alive (get-in snapshot [:actors winner :life-state]))
                "winner died with remaining responsibility"
                {:winner winner})
        (doseq [{:keys [actor-id result]} attempts
                :when (not= actor-id winner)]
          (ensure (= :attempt-lost (:outcome result))
                  "non-winner had an unexpected outcome"
                  {:actor-id actor-id :result result :attempts attempts})
          (ensure (= :dead (get-in snapshot [:actors actor-id :life-state]))
                  "loser did not die" {:loser actor-id :attempts attempts})
          (ensure (empty? (board/responsibility snapshot actor-id))
                  "loser retained responsibility"
                  {:loser actor-id :attempts attempts})
          (let [published (:published-task-ids result)]
            (ensure (seq published)
                    "loser did not publish its ordinary task"
                    {:loser actor-id :result result})
            (doseq [task-id published]
              (ensure (some? (get-in snapshot [:tasks task-id]))
                      "published loser task disappeared from the board"
                      {:loser actor-id :task-id task-id :result result})
              (ensure (nil? (get-in snapshot [:tasks task-id :owner-id]))
                      "published loser task still has an owner"
                      {:loser actor-id :task-id task-id :result result}))))
      (ensure-invariants k)
      true))))

;; ---------------------------------------------------------------------------
;; Claim is a single-winner transition even under concurrent attempts.

(defspec claim-single-winner 100
  (prop/for-all [n (gen/choose 2 6)
                 order (gen/shuffle (range 6))
                 request-ids (gen/vector (gen/elements [:ca :cb :cc :cd]) 6 6)]
    (let [slots (take n [:a :b :c :d :e :f])
          k (tu/new-kernel)
          actors (mapv #(tu/spawn! k %) slots)
          participation-order (vec (filter #(< % n) order))
          t (:task-id (tu/create-task! k (first actors)
                                       {:goal "atomic" :remaining-work "do"
                                        :done-when "done"}))
          attempts (mapv (fn [step]
                           (let [i (nth participation-order step)
                                 actor-id (nth actors i)
                                 rid (nth request-ids i)]
                             {:actor-id actor-id
                              :result (tu/exec! k
                                                {:op :claim
                                                 :actor-id actor-id
                                                 :task-id t
                                                 :request-id rid
                                                 :atomicity-reason
                                                 "deterministic concurrent claim"})}))
                         (range n))
          wins (filter #(:ok? (:result %)) attempts)
          losses (remove #(:ok? (:result %)) attempts)]
      (ensure (= 1 (count wins))
              "expected one claim winner" {:attempts attempts})
      (ensure (= (dec n) (count losses))
              "every other claimant must be refused" {:attempts attempts})
      (ensure (every? #(contains? #{:ALREADY_CLAIMED :REQUEST_ID_CONFLICT}
                                  (get-in % [:result :error]))
                      losses)
              "losing claims had wrong error" {:attempts attempts})
      (let [winner (first wins)
            claim-id (get-in winner [:result :claim-id])
            t' (kernel/task k t)]
        (ensure (= claim-id (:claim-id t')) "claim not attached"
                {:winner winner :task t'})
        (ensure (= (:actor-id winner) (:owner-id t'))
                "claim did not establish ownership"
                {:winner winner :task t'}))
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
  (prop/for-all [ops (gen/vector
                      (gen/tuple (gen/elements [:park :input :task :wake])
                                 (gen/choose 0 3)
                                 (gen/choose 0 5))
                      1 8)]
    ;; Deterministic interleaving: the generator chooses the order and the
    ;; actor, the driver executes the resulting schedule in one thread.
    (let [k (tu/new-kernel)
          actors (mapv #(tu/spawn! k %) [:a :b :c])
          final
          (reduce
           (fn [{:keys [waiters progress?] :as acc} [kind actor-choice text-choice]]
             (let [actor (nth actors (mod actor-choice (count actors)))
                   result
                   (case kind
                     :park (tu/park! k actor)
                     :input (tu/exec! k {:op :user-input
                                         :text (str "race input " text-choice)})
                     :task (tu/exec! k
                                     {:op :create-task :actor-id actor
                                      :goal (str "race task " text-choice)
                                      :remaining-work "do"
                                      :done-when "done"})
                     :wake (tu/exec! k {:op :wake-all}))]
               (when (= kind :park)
                 (ensure (or (:ok? result)
                             (contains? #{:PROGRESS_AVAILABLE :ALREADY_PARKED}
                                        (:error result)))
                         "park had an unexpected result"
                         {:kind kind :actor actor :result result}))
               (let [waiters' (case kind
                                :park (if (:ok? result)
                                        (conj waiters actor)
                                        waiters)
                                (:input :task) (if (:ok? result) #{} waiters)
                                :wake #{})
                     progress?' (or progress?
                                    (and (contains? #{:input :task} kind)
                                         (:ok? result))
                                    (and (= kind :park)
                                         (= :PROGRESS_AVAILABLE (:error result))))]
                 (ensure (= waiters' (kernel/waiters k))
                         "park/wake bookkeeping diverged"
                         {:kind kind :actor actor :result result
                          :expected waiters' :actual (kernel/waiters k)})
                 (when progress?'
                   (ensure (empty? (kernel/waiters k))
                           "a parked slot survived global progress"
                           {:kind kind :result result}))
                 (ensure (= progress?' (boolean (kernel/global-progressable? k)))
                         "progress flag diverged from the board"
                         {:kind kind :result result})
                 (ensure-invariants k)
                 {:waiters waiters' :progress? progress?'})))
           {:waiters #{} :progress? false}
           ops)]
      (ensure (= (:waiters final) (kernel/waiters k))
              "final parked set diverged from the board"
              {:final final :actual (kernel/waiters k)})
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

(def ^:private walk-kinds
  [:register-actor :create-task :split :claim :finish :cancel :update :comment
   :take-or-die :park-slot :user-input :resolve-input :begin-action
   :advance-action :batch])

(def ^:private gen-walk-step
  (gen/hash-map
   :kind (gen/elements walk-kinds)
   ;; Choices are resolved against the current board.  Modulo selection
   ;; keeps every generated step executable while the shrinker can shorten
   ;; the vector and simplify each field.
   :actor (gen/choose 0 1023)
   :task (gen/choose 0 1023)
   :aux (gen/choose 0 1023)))

(def ^:private gen-walk-steps
  ;; Inject one state-independent command so every generated walk has at
  ;; least one command that must commit.  The position is generated, and the
  ;; vector still shrinks by shortening the underlying step vector.
  (gen/fmap
   (fn [[steps position]]
     (let [at (mod position (inc (count steps)))
           guaranteed {:kind :user-input :actor 0 :task 0 :aux 0}]
       (vec (concat (take at steps) [guaranteed] (drop at steps)))))
   (gen/tuple (gen/vector gen-walk-step 1 29)
              (gen/choose 0 29))))

(defn- pick-by
  [choice coll]
  (when (seq coll)
    (nth (vec coll) (mod (long choice) (count coll)))))

(defn- walk-command
  [k {:keys [kind actor task aux]} step]
  (let [snapshot (kernel/snapshot k)
        actor-id (pick-by actor (tu/alive-actors k))
        task-id (pick-by task (sort (keys (:tasks snapshot))))
        claim-id (get-in snapshot [:tasks task-id :claim-id])
        claimed-id (pick-by aux (sort (tu/claimed-tasks k)))
        claimed-claim (get-in snapshot
                              [:claims (get-in snapshot
                                               [:tasks claimed-id :claim-id])])
        action-id (pick-by aux (sort (keys (:actions snapshot))))
        action (get-in snapshot [:actions action-id])
        input-id (pick-by aux (sort (keys (:inputs snapshot))))
        command
        (case kind
          :register-actor
          {:op :register-actor :slot-id (keyword (str "slot-" step))}

          :create-task
          (if actor-id
            {:op :create-task :actor-id actor-id
             :goal (str "walk task " step) :remaining-work "do"
             :done-when "done"}
            {:op :wake-all})

          :split
          (if (and actor-id task-id)
            {:op :split :actor-id actor-id :task-id task-id
             :remaining-work (str "remaining " step)
             :children [{:goal (str "child " step)
                         :remaining-work "do"
                         :done-when "done"}]}
            {:op :wake-all})

          :claim
          (if (and actor-id task-id)
            {:op :claim :actor-id actor-id :task-id task-id
             :atomicity-reason "random walk"}
            {:op :wake-all})

          :finish
          (if (and actor-id task-id)
            {:op :finish :actor-id actor-id :task-id task-id
             :claim-id claim-id :result-summary "random finish"}
            {:op :wake-all})

          :cancel
          (if (and actor-id task-id)
            {:op :cancel :actor-id actor-id :task-id task-id
             :reason "random cancel"}
            {:op :wake-all})

          :update
          (if (and actor-id task-id)
            {:op :update :actor-id actor-id :task-id task-id
             :remaining-work (str "remaining " step)}
            {:op :wake-all})

          :comment
          (if (and actor-id task-id)
            {:op :comment :actor-id actor-id :task-id task-id
             :text (str "note " step)}
            {:op :wake-all})

          :take-or-die
          (if actor-id
            {:op :take-or-die :actor-id actor-id
             :confirm :i-hereby-confirm-i-die-without-regret}
            {:op :wake-all})

          :park-slot
          (if actor-id
            {:op :park-slot :actor-id actor-id
             :observed-cursor (:board-version snapshot)}
            {:op :wake-all})

          :user-input
          {:op :user-input :text (str "user input " step)}

          :resolve-input
          (if (and actor-id input-id)
            {:op :resolve-input :actor-id actor-id
             :input-id input-id :summary "resolved"}
            {:op :wake-all})

          :begin-action
          (if (and claimed-id claimed-claim
                   (= :alive
                      (get-in snapshot
                              [:actors (:agent-id claimed-claim) :life-state])))
            {:op :begin-action :actor-id (:agent-id claimed-claim)
             :task-id claimed-id :claim-id (:claim-id claimed-claim)
             :kind :edit}
            {:op :wake-all})

          :advance-action
          (if (and action
                   (= :alive
                      (get-in snapshot [:actors (:agent-id action) :life-state]))
                   (not= :finished (:phase action)))
            {:op :advance-action :actor-id (:agent-id action)
             :action-id (:action-id action)
             :phase (if (contains? #{:applying :running :uncertain}
                                   (:phase action))
                      :finished
                      :applying)}
            {:op :wake-all})

          :batch
          {:op :batch
           :commands (cond-> [{:op :user-input :text (str "batch input " step)}]
                       actor-id (conj {:op :create-task :actor-id actor-id
                                       :goal (str "batch task " step)
                                       :remaining-work "do"
                                       :done-when "done"}))})]
    ;; The step index is the request identity: every command in one walk is
    ;; unique, so a replay never masks a rejected command.
    (assoc command :request-id (keyword (str "rw-" step)))))

(defspec random-walk-invariants 100
  (prop/for-all [steps gen-walk-steps]
    (let [k (tu/new-kernel)
          a (tu/spawn! k :a)
          b (tu/spawn! k :b)
          c (tu/spawn! k :c)
          _ (tu/create-task! k a {:goal "seed parent" :remaining-work "integrate"
                                  :done-when "done" :owner-id a})
          _ (tu/create-task! k b {:goal "seed token" :remaining-work "run"
                                  :done-when "done" :needs-token true})]
      (ensure-invariants k)
      (loop [remaining (map-indexed vector steps)
             successes 0]
        (if-let [[step-index step] (first remaining)]
          (let [before (kernel/snapshot k)
                command (walk-command k step step-index)
                result (tu/exec! k command)
                after (kernel/snapshot k)
                committed? (boolean (:ok? result))]
            (if committed?
              (do
                ;; A batch is one explicit multi-command form; every other
                ;; successful command commits exactly one board version.
                (when-not (= :batch (:op command))
                  (ensure (= (inc (:board-version before))
                             (:board-version after))
                          "a successful command must commit exactly one version"
                          {:step command :result result
                           :before (:board-version before)
                           :after (:board-version after)}))
                (ensure (>= (:board-version after) (:board-version before))
                        "board version moved backwards"
                        {:step command :result result}))
              (ensure (= before after)
                      "a failed command must leave the board unchanged"
                      {:step command :result result
                       :before before :after after}))
            (ensure-invariants k)
            (recur (rest remaining) (if committed? (inc successes) successes)))
          (do
            ;; A walk that rejects every command proves nothing about the
            ;; post-commit state; require at least one real transition.
            (ensure (pos? successes)
                    "the generated walk never committed a command"
                    {:steps steps})
            true))))))
