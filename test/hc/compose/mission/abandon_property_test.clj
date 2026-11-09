(ns hc.compose.mission.abandon-property-test
  "Property-level guarantees for `abandon-swarm!`.

  The transition clears a whole execution tenure at once: relay ledger,
  root-task mirror, board tasks/claims/actions/ledger/waiters, token slot,
  and holder role.  These tests build valid states through the real mission
  host, then abandon a random swarm and assert that the committed state
  still satisfies every invariant and that no sibling tenure changed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission :as mission]
            [hc.compose.mission.host :as mission-host]
            [hc.compose.mission.support :as support]
            [hc.core.relay.core :as relay]
            [hc.core.relay.tenure :as tenure]
            [hc.core.swarm-stm.board :as board]))

(def ^:private confirm-token
  :i-hereby-confirm-i-die-without-regret)

(def ^:private slots
  [:slot-a :slot-b])

(defn- ok! [result]
  (when-not (:ok? result)
    (throw (ex-info "test fixture transition failed"
                    {:result result})))
  result)

(defn- ready-task-ids
  [state tenure-id]
  (->> (vals (get-in state [:boards tenure-id :tasks]))
       (filter #(= :ready (:state %)))
       (map :task-id)
       vec))

(defn- board-tasks
  [state tenure-id]
  (vals (get-in state [:boards tenure-id :tasks])))

(defn- build-swarm!
  "Create one valid swarm tenure with a random tree, claims, and actions.

  SPEC: `{:roots n :children n :workers n :claims n :action? bool}`.  Every
  transition is the real host command, so the pre-abandon state is valid."
  [h tenure-id spec]
  (let [root-keys (mapv #(str "root-" tenure-id "-" %)
                        (range (long (:roots spec 1))))]
    (ok! (support/start-swarm! h tenure-id
                               (mapv (fn [k] {:key k :value "work"})
                                     root-keys)))
    (let [state (mission/snapshot h)
          control-id (get-in state [:bindings :execution tenure-id
                                    :control-agent-id])
          root-ids (support/root-task-ids h tenure-id)]
      ;; A real child tree.  Split happens before claims/actions because the
      ;; board refuses a split while an action is in flight.
      (when (and (pos? (long (:children spec 0))) (seq root-ids))
        (let [child-specs (mapv (fn [i]
                                  {:goal (str "child-" i)
                                   :remaining-work (str "remaining-" i)
                                   :done-when (str "done-" i)})
                                (range (long (:children spec 0))))]
          (ok! (mission/apply-command!
                h {:op :board-command
                   :tenure-id tenure-id
                   :command {:op :split
                             :actor-id control-id
                             :task-id (first root-ids)
                             :remaining-work "split"
                             :children child-specs}}))))
      (let [workers (mapv (fn [slot]
                            (support/register-worker! h tenure-id slot))
                          (take (long (:workers spec 1)) slots))
            claims
            (loop [ready (vec (ready-task-ids (mission/snapshot h) tenure-id))
                   remaining (long (:claims spec 0))
                   worker-index 0
                   out []]
              (if (or (empty? ready) (zero? remaining))
                out
                (let [task-id (first ready)
                      worker (nth workers (mod worker-index (count workers)))
                      result (mission/claim-task!
                              h {:tenure-id tenure-id
                                 :actor-id worker
                                 :task-id task-id
                                 :atomicity-reason "property fixture"})]
                  (ok! result)
                  (recur (subvec ready 1)
                         (dec remaining)
                         (inc worker-index)
                         (conj out {:task-id task-id
                                    :agent-id worker
                                    :claim-id (get-in result
                                                      [:receipt :claim-id])})))))
            actions
            (if (and (:action? spec) (seq claims))
              (let [{:keys [task-id agent-id claim-id]} (first claims)]
                (ok! (mission/apply-command!
                      h {:op :board-command
                         :tenure-id tenure-id
                         :command {:op :begin-action
                                   :actor-id agent-id
                                   :task-id task-id
                                   :claim-id claim-id
                                   :kind :edit}}))
                [{:task-id task-id :agent-id agent-id :claim-id claim-id}])
              [])]
        {:tenure-id tenure-id
         :swarm-id (str "swarm-" tenure-id)
         :slice-id (first root-keys)
         :root-keys root-keys
         :workers workers
         :claims claims
         :actions actions}))))

(defn- build-host
  "Build one valid host from SPECS.  Every spec starts a swarm, so every
  entry can be a target for a slice-based abandon."
  [specs]
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        plan-id (support/plan-tenure-id h)
        created
        (mapv (fn [index spec]
                (let [result (mission/review!
                              h {:lane :plan
                                 :tenure-id plan-id
                                 :call-id (support/call-id)
                                 :findings []})]
                  (ok! result)
                  {:tenure-id (get-in result
                                      [:receipt :execution-tenure-id])
                   :spec spec}))
              (range) specs)
        entries
        (mapv (fn [{:keys [tenure-id spec]}]
                (build-swarm! h tenure-id spec))
              created)
        state (mission/snapshot h)]
    (when-not (:ok? (mission/check-invariants state))
      (throw (ex-info "fixture generated an invalid mission state"
                      {:violations (:violations
                                   (mission/check-invariants state))})))
    {:host h
     :entries entries}))

;; ---------------------------------------------------------------------------
;; Views and orphan checks

(defn- tenure-view
  [state tenure-id]
  (let [tenure (get-in state [:relay :lanes :execution tenure-id])
        board* (get-in state [:boards tenure-id])]
    {:tenure (select-keys tenure
                          [:status :review-window :swarm-phase
                           :ledger :root-tasks])
     :board (select-keys board*
                         [:tasks :claims :actions :ledger :waiters
                          :token-slot :board-version])}))

(defn- plan-view
  [state]
  (select-keys (get-in state [:relay :lanes :plan])
               [:status :review-window :milestone :ledger :root-tasks]))

(defn- other-views
  [state target-id]
  (into {}
        (map (fn [tid] [tid (tenure-view state tid)]))
        (remove #{target-id}
                (keys (get-in state [:relay :lanes :execution])))))

(defn- orphan-violations
  "Concrete dangling references that neither the board nor the relay
  invariant should tolerate."
  [state]
  (vec
   (mapcat
    (fn [[tenure-id board*]]
      (let [tasks (:tasks board*)
            task-ids (set (keys tasks))
            claims (:claims board*)
            actions (:actions board*)
            actors (:actors board*)]
        (concat
         (for [[task-id task] tasks
               :let [parent (:parent-id task)]
               :when (and parent (not (contains? task-ids parent)))]
           [tenure-id :orphan-parent task-id parent])
         (for [[task-id task] tasks
               :let [claim-id (:claim-id task)]
               :when (and claim-id (not (contains? claims claim-id)))]
           [tenure-id :orphan-claim task-id claim-id])
         (for [[claim-id claim] claims
               :when (not (contains? task-ids (:task-id claim)))]
           [tenure-id :claim-unknown-task claim-id])
         (for [[action-id action] actions
               :when (not (contains? task-ids (:task-id action)))]
           [tenure-id :action-unknown-task action-id])
         (for [[action-id action] actions
               :let [claim-id (:claim-id action)]
               :when (and claim-id (not (contains? claims claim-id)))]
           [tenure-id :action-unknown-claim action-id claim-id])
         (for [[task-id task] tasks
               wait-id (:wait-for task)
               :when (not (contains? task-ids wait-id))]
           [tenure-id :wait-unknown-task task-id wait-id])
         (for [[waiter-id _] (:waiters board*)
               :when (not (contains? actors waiter-id))]
           [tenure-id :waiter-unknown-actor waiter-id]))))
    (:boards state))))

(defn- cleared-target?
  [state tenure-id]
  (let [board* (get-in state [:boards tenure-id])
        actors (vals (:actors board*))]
    (and (= {} (:tasks board*))
         (= {} (:claims board*))
         (= {} (:actions board*))
         (= {} (:ledger board*))
         (= {} (:waiters board*))
         (= :free (get-in board* [:token-slot :state]))
         (nil? (get-in board* [:token-slot :holder-id]))
         (not-any? #(= :holder (:role %)) actors)
         (not-any? #(some? (:active-action-id %)) actors)
         (empty? (board/check-invariants board*)))))

;; ---------------------------------------------------------------------------
;; Generators

(def gen-tenure-spec
  (gen/hash-map
   :roots (gen/choose 1 3)
   :children (gen/choose 0 2)
   :workers (gen/choose 1 2)
   :claims (gen/choose 1 3)
   :action? gen/boolean))

(def gen-specs
  (gen/vector gen-tenure-spec 1 3))

(def gen-target-index gen/nat)

;; ---------------------------------------------------------------------------
;; Properties

(defspec abandon-only-touches-the-target 20
  (prop/for-all [specs gen-specs
                 target-index gen-target-index]
    (let [{:keys [host entries]} (build-host specs)
          {:keys [tenure-id swarm-id slice-id]} (nth entries
                                                      (mod target-index
                                                           (count entries)))
          before (mission/snapshot host)
          others-before (other-views before tenure-id)
          plan-before (plan-view before)
          result (mission-host/abandon-swarm!
                  host {:swarm-id swarm-id
                        :slice-id slice-id
                        :reason "property"
                        :at 42})
          after (:state result)
          others-after (other-views after tenure-id)]
      (and
       (is (:ok? result) (pr-str (:error result)))
       (is (:ok? (mission/check-invariants after))
           (pr-str (:violations (mission/check-invariants after))))
       (is (empty? (orphan-violations after))
           (pr-str (orphan-violations after)))
       (is (= others-before others-after)
           (pr-str {:before others-before :after others-after}))
       (is (= plan-before (plan-view after)))
       (is (= :abandoned
              (get-in after [:relay :lanes :execution tenure-id :status])))
       (is (= "property"
              (get-in after [:relay :lanes :execution tenure-id
                             :abandoned-reason])))
       (is (cleared-target? after tenure-id))))))

(defspec abandon-sequence-keeps-invariants 15
  (prop/for-all [specs gen-specs
                 targets (gen/vector gen/nat 0 5)]
    (let [{:keys [host entries]} (build-host specs)
          entry-count (count entries)]
      (loop [state (mission/snapshot host)
             remaining targets]
        (if-let [target-index (first remaining)]
          (let [{:keys [tenure-id swarm-id slice-id]}
                (nth entries (mod target-index entry-count))
                before-version (get-in state [:boards tenure-id
                                              :board-version])
                result (mission-host/abandon-swarm!
                        host {:swarm-id swarm-id
                              :slice-id slice-id
                              :reason "again"
                              :at 42})
                after (:state result)]
            (and
             (is (:ok? result) (pr-str (:error result)))
             (is (:ok? (mission/check-invariants after))
                 (pr-str (:violations (mission/check-invariants after))))
             (is (empty? (orphan-violations after))
                 (pr-str (orphan-violations after)))
             (if (:already? result)
               (is (= before-version
                      (get-in after [:boards tenure-id :board-version]))
                   "a repeated abandon must not advance the board")
               (is (> (long (get-in after [:boards tenure-id :board-version]))
                      (long before-version))))
             (recur after (rest remaining))))
          (and
           (is (:ok? (mission/check-invariants state)))
           (is (empty? (orphan-violations state)))))))))

;; ---------------------------------------------------------------------------
;; Focused rejections

(deftest plan-tenure-is-never-abandonable
  (let [state (relay/new-state {:session-id "abandon-plan"})
        plan-id (relay/current-tenure-id state :plan)
        result (relay/abandon-execution-tenure state plan-id "no")]
    (is (= :ABANDON_PLAN_TENURE_FORBIDDEN (get-in result [:error :code])))
    (is (= state (:state result)))
    (is (= :active (get-in (:state result)
                           [:lanes :plan :status])))))

(deftest completed-tenure-is-refused
  (let [base (relay/new-state {:session-id "abandon-completed"})
        completed (assoc (tenure/create-tenure
                          {:tenure-id "done"
                           :epoch 2
                           :lane :execution
                           :milestone :completed
                           :initial? false})
                         :status :completed)
        state (assoc-in base [:lanes :execution "done"] completed)
        result (relay/abandon-execution-tenure state "done" "no")]
    (is (= :TENURE_ALREADY_COMPLETED (get-in result [:error :code])))
    (is (= state (:state result)))))

(deftest abandon-demotes-the-holder-and-spares-other-tenures
  (let [{:keys [host entries]} (build-host [{:roots 1 :children 0
                                             :workers 1 :claims 1
                                             :action? false}
                                            {:roots 1 :children 0
                                             :workers 1 :claims 1
                                             :action? false}])
        [a b] entries
        a-worker (first (:workers a))
        b-worker (first (:workers b))
        a-task (:task-id (first (:claims a)))
        b-task (:task-id (first (:claims b)))
        token (:receipt (ok! (mission/take-or-die!
                              host {:tenure-id (:tenure-id a)
                                    :actor-id a-worker
                                    :request-id "holder-a"
                                    :confirm confirm-token})))
        _ token
        before (mission/snapshot host)
        b-before (tenure-view before (:tenure-id b))
        a-holder (get-in before [:boards (:tenure-id a)
                                 :token-slot :holder-id])
        result (mission-host/abandon-swarm!
                host {:swarm-id (:swarm-id a)
                      :slice-id (:slice-id a)
                      :reason "holder obsolete"
                      :at 42})
        after (:state result)
        a-board (get-in after [:boards (:tenure-id a)])
        b-board (get-in after [:boards (:tenure-id b)])]
    (testing "the targeted holder is demoted and the token is free"
      (is (= a-worker a-holder))
      (is (nil? (get-in a-board [:token-slot :holder-id])))
      (is (= :free (get-in a-board [:token-slot :state])))
      (is (not-any? #(= :holder (:role %)) (vals (:actors a-board))))
      (is (empty? (:claims a-board))))
    (testing "the other tenure keeps its work and actor"
      (is (= b-before (tenure-view after (:tenure-id b))))
      (is (contains? (:claims b-board)
                     (-> (get-in b-board [:tasks b-task :claim-id]))))
      (is (= :alive (get-in b-board [:actors b-worker :life-state])))
      (is (= :active (get-in after [:relay :lanes :execution
                                    (:tenure-id b) :status]))))
    (is (:ok? (mission/check-invariants after)))
    (is (empty? (orphan-violations after)))))
