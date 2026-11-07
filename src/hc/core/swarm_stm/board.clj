(ns hc.core.swarm-stm.board
  "Immutable public board for the phase-one swarm control kernel.

  The board holds authoritative control records only: tasks, actors, claims,
  actions, the token slot, wait registrations, raw inputs, the ledger and the
  append-only event log.  Derived indexes are recomputed from those records in
  the same immutable value; they are never independent authority.

  All functions here are pure.  The transactional boundary lives in
  hc.core.swarm-stm.commands and the CAS loop lives in
  hc.core.swarm-stm.kernel."
  (:refer-clojure :exclude [derive]))

(def task-states #{:ready :waiting :done})
(def actor-life-states #{:alive :dead :terminated})
(def action-phases #{:claimed :preparing :waiting-gate :applying :running
                     :finished :uncertain})

;; Actions in these phases are unfinished side-effect responsibility.
(def active-action-phases #{:applying :running :uncertain})

;; Actions in these phases have not reached a side effect yet.  They still
;; need a live claim and a registered active action; finish/release/retire
;; remove them instead of leaving an orphan.
(def pre-side-effect-action-phases #{:claimed :preparing :waiting-gate})

;; Actions in these phases mean a legal next step still exists.  :waiting-gate,
;; :applying and :running are tool-blocked; they do not create a new global
;; step even though the actor may still be responsible.
(def progressable-action-phases #{:claimed :preparing :finished :uncertain})

(def token-confirm :i-hereby-confirm-i-die-without-regret)

(defn empty-board
  "A fresh board with no tasks, actors, inputs or obligations."
  []
  {:board-version 0
   :counters {:task 0 :actor 0 :claim 0 :action 0 :input 0}
   :tasks {}
   :actors {}
   :claims {}
   :actions {}
   :token-slot {:holder-id nil :term-id 0 :state :free}
   :waiters {}
   :inputs {}
   :ledger {}
   :comments {}
   :requests {}
   :events []
   :event-seq 0
   :last-wake nil
   :derived nil})

(defn alloc-id
  "Return [board' id] with a fresh, never-reused id for KIND.
  PREFIX names the printed id; KIND names the counter."
  [board kind prefix]
  (let [n (inc (get-in board [:counters kind] 0))]
    [(assoc-in board [:counters kind] n)
     (keyword (str (name prefix) "-" n))]))

(defn unfinished?
  [task]
  (not= :done (:state task)))

(defn task-progressable?
  "True when T itself has a legal next non-blocked step.

  A ready task is progressable when it has no live action, or when its live
  action is in a phase that still permits preparation/result handling.  A
  ready task whose only action is blocked in :waiting-gate/:applying/:running
  contributes no new global step."
  [board task]
  (and (= :ready (:state task))
       (let [actions (filter #(= (:task-id task) (:task-id %))
                             (vals (:actions board)))
             live (remove #(= :finished (:phase %)) actions)]
         (cond
           (empty? live) true
           (some #(contains? #{:waiting-gate :applying :running} (:phase %)) live) false
           :else true))))

(defn progressable-reasons
  "Set of reasons why the global non-blocking progress set is non-empty.
  This is computed from authoritative records, never from the derived index."
  [board]
  (let [tasks (vals (:tasks board))]
    (cond-> #{}
      (some #(task-progressable? board %) tasks) (conj :ready-task)
      (some #(and (= :waiting (:state %)) (empty? (:wait-for %))) tasks)
      (conj :broken-wait)
      (some #(= :unhandled (:state %)) (vals (:inputs board))) (conj :unhandled-input))))

(defn global-progressable?
  "True iff at least one legal non-blocking step exists anywhere on the board.
  A parked slot is only legal while this is false."
  [board]
  (boolean (seq (progressable-reasons board))))

(defn responsibility
  "R(a): unfinished ordinary tasks owned by A, all unfinished token tasks when
  A is the current holder, and A's actions in :applying/:running/:uncertain."
  [board agent-id]
  (let [holder (get-in board [:token-slot :holder-id])
        tasks (vals (:tasks board))
        ordinary (for [t tasks
                       :when (and (unfinished? t)
                                  (not (:needs-token t))
                                  (= agent-id (:owner-id t)))]
                   (:task-id t))
        token (when (= holder agent-id)
                (for [t tasks
                      :when (and (unfinished? t) (:needs-token t))]
                  (:task-id t)))
        actions (for [a (vals (:actions board))
                      :when (and (= agent-id (:agent-id a))
                                 (contains? active-action-phases (:phase a)))]
                  (:action-id a))]
    (into #{} (concat ordinary token actions))))

(defn derive
  "Recompute all non-authoritative indexes from the authoritative records.
  The result is stored in the same immutable board value."
  [board]
  (let [tasks (vals (:tasks board))
        ready (into #{} (comp (filter #(= :ready (:state %))) (map :task-id)) tasks)
        waiting (into #{} (comp (filter #(= :waiting (:state %))) (map :task-id)) tasks)
        done (into #{} (comp (filter #(= :done (:state %))) (map :task-id)) tasks)
        token-tasks (into #{} (comp (filter #(and (unfinished? %) (:needs-token %)))
                                    (map :task-id)) tasks)
        public-ready (into #{} (comp (filter #(and (= :ready (:state %))
                                                   (not (:needs-token %))
                                                   (nil? (:owner-id %))))
                                     (map :task-id)) tasks)
        owned (reduce (fn [m t]
                        (if (and (unfinished? t) (:owner-id t))
                          (update m (:owner-id t) (fnil conj #{}) (:task-id t))
                          m))
                      {}
                      tasks)
        actor-ids (keys (:actors board))
        resp (into {} (map (fn [id] [id (responsibility board id)]) actor-ids))
        holder (get-in board [:token-slot :holder-id])
        roots (into #{} (comp (filter #(nil? (:parent-id %))) (map :task-id)) tasks)]
    (assoc board :derived
           {:ready-tasks ready
            :waiting-tasks waiting
            :done-tasks done
            :public-ready-tasks public-ready
            :token-tasks token-tasks
            :owned-tasks owned
            :responsibility resp
            :holder-responsibility (when holder (get resp holder))
            :global-progressable? (global-progressable? board)
            :progressable-reasons (progressable-reasons board)
            :parked-agents (set (keys (:waiters board)))
            :root-tasks roots
            :all-roots-done? (and (seq roots)
                                  (every? #(= :done (get-in board [:tasks % :state])) roots))
            :ledger-clear? (empty? (:ledger board))})))

(defn alive?
  [board agent-id]
  (= :alive (get-in board [:actors agent-id :life-state])))

(defn holder?
  [board agent-id]
  (= agent-id (get-in board [:token-slot :holder-id])))

(defn responsible-for-task?
  "Who may finish or maintain an unclaimed task?"
  [board agent-id task]
  (if (:needs-token task)
    (holder? board agent-id)
    (= agent-id (:owner-id task))))

(defn reaches?
  "True when FROM waits, directly or transitively, on TARGET.
  Used to reject explicit dependency cycles before commit."
  [board from target]
  (loop [stack [from]
         seen #{}]
    (cond
      (empty? stack) false
      (contains? seen (peek stack)) (recur (pop stack) seen)
      :else
      (let [n (peek stack)]
        (if (= n target)
          true
          (recur (into (pop stack) (get-in board [:tasks n :wait-for] #{}))
                 (conj seen n)))))))

(defn- cycle-violations
  [tasks]
  (letfn [(visit [id path]
            (if (contains? path id)
              [{:invariant :dependency-cycle
                :task-id id
                :path (vec path)}]
              (mapcat #(visit % (conj path id))
                      (get-in tasks [id :child-ids] []))))]
    (mapcat #(visit % #{}) (keys tasks))))

(defn- wait-for-cycle-violations
  "Detect cycles in the explicit :wait-for graph.  Command handlers reject
  new cycles, but a recovered or injected board must also fail this check."
  [tasks]
  (letfn [(visit [id path]
            (if (contains? path id)
              [{:invariant :wait-for-cycle
                :task-id id
                :path (vec (conj path id))}]
              (mapcat #(visit % (conj path id))
                      (get-in tasks [id :wait-for] #{}))))]
    (distinct (mapcat #(visit % #{}) (keys tasks)))))

(defn check-invariants
  "Return a vector of violations.  Empty means the board is structurally legal.

  These checks are intentionally stronger than the minimum needed by the
  command handlers: property tests run them after every successful transition."
  [board]
  (let [{:keys [tasks actors claims actions token-slot waiters inputs ledger
                board-version]} board
        holder (:holder-id token-slot)
        holder-count (count (filter #(= :holder (:role %)) (vals actors)))
        derived (:derived board)]
    (vec
     (concat
      ;; ---- tasks -----------------------------------------------------------
      (for [[id t] tasks
            :when (not= id (:task-id t))]
        {:invariant :task-id-key-mismatch :task-id id})
      (for [[id t] tasks
            :when (not (contains? task-states (:state t)))]
        {:invariant :task-state :task-id id :state (:state t)})
      (for [[id t] tasks
            :when (not (and (string? (:goal t)) (seq (:goal t))))]
        {:invariant :task-goal-missing :task-id id})
      (for [[id t] tasks
            :when (not (and (string? (:remaining-work t))
                            (seq (:remaining-work t))))]
        {:invariant :task-remaining-work-missing :task-id id})
      (for [[id t] tasks
            :when (not (and (string? (:done-when t)) (seq (:done-when t))))]
        {:invariant :task-done-when-missing :task-id id})
      (for [[id t] tasks
            :when (not (boolean? (:needs-token t)))]
        {:invariant :task-needs-token-not-boolean :task-id id})
      (for [[id t] tasks
            :when (not (set? (:wait-for t)))]
        {:invariant :task-wait-for-not-set :task-id id})
      (for [[id t] tasks
            wid (:wait-for t)
            :when (not (contains? tasks wid))]
        {:invariant :wait-for-unknown-task :task-id id :wait-for wid})
      (for [[id t] tasks
            wid (:wait-for t)
            :when (= wid id)]
        {:invariant :self-wait :task-id id})
      (for [[id t] tasks
            wid (:wait-for t)
            :when (= :done (get-in tasks [wid :state]))]
        {:invariant :wait-for-done-task :task-id id :wait-for wid})
      (for [[id t] tasks
            :when (and (= :waiting (:state t)) (empty? (:wait-for t)))]
        {:invariant :waiting-without-wait-for :task-id id})
      (for [[id t] tasks
            :when (and (= :ready (:state t)) (seq (:wait-for t)))]
        {:invariant :ready-with-wait-for :task-id id})
      (for [[id t] tasks
            :when (and (= :done (:state t)) (seq (:wait-for t)))]
        {:invariant :done-with-wait-for :task-id id})
      (for [[id t] tasks
            :when (and (= :done (:state t)) (:claim-id t))]
        {:invariant :done-with-claim :task-id id :claim-id (:claim-id t)})
      (for [[id t] tasks
            :when (and (= :done (:state t)) (nil? (:done-at-version t)))]
        {:invariant :done-without-version :task-id id})
      (for [[id t] tasks
            :when (and (not= :done (:state t)) (:done-at-version t))]
        {:invariant :unfinished-with-done-version :task-id id})
      (for [[id t] tasks
            :let [cid (:claim-id t)]
            :when cid
            :when (not (contains? claims cid))]
        {:invariant :task-claim-missing :task-id id :claim-id cid})
      (for [[id t] tasks
            :let [c (get claims (:claim-id t))]
            :when (and c (not= id (:task-id c)))]
        {:invariant :claim-task-mismatch :task-id id :claim-id (:claim-id c)})
      (for [[id t] tasks
            :when (and (:owner-id t) (not (contains? actors (:owner-id t))))]
        {:invariant :task-owner-unknown :task-id id :owner-id (:owner-id t)})
      (for [[id t] tasks
            :when (and (unfinished? t)
                       (:owner-id t)
                       (not= :alive (get-in actors [(:owner-id t) :life-state])))]
        {:invariant :unfinished-task-owned-by-dead :task-id id
         :owner-id (:owner-id t)})
      (for [[id t] tasks
            :when (and (:parent-id t) (not (contains? tasks (:parent-id t))))]
        {:invariant :parent-missing :task-id id :parent-id (:parent-id t)})
      (for [[id t] tasks
            cid (:child-ids t)
            :when (not (contains? tasks cid))]
        {:invariant :child-missing :task-id id :child-id cid})
      (for [[id t] tasks
            cid (:child-ids t)
            :when (not= id (get-in tasks [cid :parent-id]))]
        {:invariant :child-parent-mismatch :task-id id :child-id cid
         :actual-parent (get-in tasks [cid :parent-id])})
      (for [[id t] tasks
            :when (and (:ledger-key t)
                       (contains? ledger (:ledger-key t))
                       (not= id (get-in ledger [(:ledger-key t) :task-id])))]
        {:invariant :ledger-key-task-mismatch :task-id id
         :ledger-key (:ledger-key t)})
      (for [[id t] tasks
            :when (and (nil? (:ledger-key t))
                       (some #(= id (:task-id %)) (vals ledger)))]
        {:invariant :ledger-task-without-key :task-id id})
      (cycle-violations tasks)
      (wait-for-cycle-violations tasks)

      ;; ---- claims ----------------------------------------------------------
      (for [[cid c] claims
            :when (not= cid (:claim-id c))]
        {:invariant :claim-id-key-mismatch :claim-id cid})
      (for [[cid c] claims
            :when (not (contains? tasks (:task-id c)))]
        {:invariant :claim-task-unknown :claim-id cid :task-id (:task-id c)})
      (for [[cid c] claims
            :when (not= cid (get-in tasks [(:task-id c) :claim-id]))]
        {:invariant :claim-not-attached :claim-id cid :task-id (:task-id c)})
      (for [[cid c] claims
            :when (not (alive? board (:agent-id c)))]
        {:invariant :claim-by-dead-actor :claim-id cid :agent-id (:agent-id c)})
      (for [[cid c] claims
            :when (not (and (string? (:atomicity-reason c))
                            (seq (:atomicity-reason c))))]
        {:invariant :claim-without-atomicity-reason :claim-id cid})

      ;; ---- actions ---------------------------------------------------------
      (for [[aid a] actions
            :when (not= aid (:action-id a))]
        {:invariant :action-id-key-mismatch :action-id aid})
      (for [[aid a] actions
            :when (not (contains? action-phases (:phase a)))]
        {:invariant :action-phase :action-id aid :phase (:phase a)})
      (for [[aid a] actions
            :when (and (contains? active-action-phases (:phase a))
                       (not (contains? tasks (:task-id a))))]
        {:invariant :active-action-task-missing :action-id aid :task-id (:task-id a)})
      (for [[aid a] actions
            :when (and (contains? active-action-phases (:phase a))
                       (not (contains? claims (:claim-id a))))]
        {:invariant :active-action-claim-missing :action-id aid :claim-id (:claim-id a)})
      (for [[aid a] actions
            :when (and (contains? active-action-phases (:phase a))
                       (not (alive? board (:agent-id a))))]
        {:invariant :active-action-by-dead-actor :action-id aid :agent-id (:agent-id a)})
      (for [[aid a] actions
            :let [t (get tasks (:task-id a))]
            :when (and (contains? active-action-phases (:phase a))
                       t
                       (not= (:claim-id a) (:claim-id t)))]
        {:invariant :action-claim-mismatch :action-id aid
         :claim-id (:claim-id a) :task-claim-id (:claim-id t)})
      (for [[aid a] actions
            :let [c (get claims (:claim-id a))]
            :when (and c (not= (:agent-id a) (:agent-id c)))]
        {:invariant :action-agent-claim-mismatch :action-id aid})
      (for [[aid a] actions
            :when (and (contains? active-action-phases (:phase a))
                       (not= aid (get-in actors [(:agent-id a) :active-action-id])))]
        {:invariant :active-action-not-registered :action-id aid})
      (for [[id actor] actors
            :let [aid (:active-action-id actor)]
            :when aid
            :let [a (get actions aid)]
            :when (or (nil? a)
                      (not= id (:agent-id a))
                      (= :finished (:phase a)))]
        {:invariant :actor-active-action-invalid :agent-id id :action-id aid})
      (for [[id actor] actors
            :let [active (filter #(and (= id (:agent-id %))
                                       (not= :finished (:phase %)))
                                 (vals actions))]
            :when (> (count active) 1)]
        {:invariant :multiple-active-actions :agent-id id
         :action-ids (mapv :action-id active)})

      ;; ---- pre-side-effect action cleanup ---------------------------------
      (for [[aid a] actions
            :when (and (contains? pre-side-effect-action-phases (:phase a))
                       (not (contains? tasks (:task-id a))))]
        {:invariant :pre-side-effect-action-task-missing
         :action-id aid :task-id (:task-id a)})
      (for [[aid a] actions
            :when (and (contains? pre-side-effect-action-phases (:phase a))
                       (not (contains? claims (:claim-id a))))]
        {:invariant :pre-side-effect-action-claim-missing
         :action-id aid :claim-id (:claim-id a)})
      (for [[aid a] actions
            :when (and (contains? pre-side-effect-action-phases (:phase a))
                       (not (alive? board (:agent-id a))))]
        {:invariant :pre-side-effect-action-by-dead-actor
         :action-id aid :agent-id (:agent-id a)})
      (for [[aid a] actions
            :let [t (get tasks (:task-id a))]
            :when (and (contains? pre-side-effect-action-phases (:phase a))
                       t
                       (not= (:claim-id a) (:claim-id t)))]
        {:invariant :pre-side-effect-action-claim-mismatch
         :action-id aid :claim-id (:claim-id a)
         :task-claim-id (:claim-id t)})
      (for [[aid a] actions
            :when (and (contains? pre-side-effect-action-phases (:phase a))
                       (not= aid (get-in actors [(:agent-id a)
                                                 :active-action-id])))]
        {:invariant :pre-side-effect-action-not-registered
         :action-id aid :agent-id (:agent-id a)})

      ;; ---- actors and token slot ------------------------------------------
      (for [[id a] actors
            :when (not= id (:agent-id a))]
        {:invariant :actor-id-key-mismatch :agent-id id})
      (for [[id a] actors
            :when (not (contains? actor-life-states (:life-state a)))]
        {:invariant :actor-life-state :agent-id id :life-state (:life-state a)})
      (for [[id a] actors
            :when (not (contains? #{:normal :holder} (:role a)))]
        {:invariant :actor-role :agent-id id :role (:role a)})
      (for [[id a] actors
            :when (not= (boolean (holder? board id)) (= :holder (:role a)))]
        {:invariant :holder-role-mismatch :agent-id id :role (:role a)})
      (for [[id a] actors
            :when (and (not= :alive (:life-state a))
                       (:active-action-id a))]
        {:invariant :dead-actor-with-active-action :agent-id id})
      (for [[id a] actors
            :when (and (not= :alive (:life-state a))
                       (some #(= id (:agent-id %)) (vals claims)))]
        {:invariant :dead-actor-with-claim :agent-id id})
      (for [[id a] actors
            :when (and (not= :alive (:life-state a))
                       (contains? waiters id))]
        {:invariant :dead-actor-parked :agent-id id})
      (for [[id a] actors
            :when (and (not= :alive (:life-state a))
                       (seq (responsibility board id)))]
        {:invariant :dead-actor-with-responsibility :agent-id id
         :responsibility (responsibility board id)})
      (for [[id a] actors
            :when (and (not= :alive (:life-state a)) (holder? board id))]
        {:invariant :dead-holder :agent-id id})
      (when (> holder-count 1)
        [{:invariant :multiple-holders :count holder-count}])
      (when holder
        (when-not (alive? board holder)
          [{:invariant :holder-not-alive :holder-id holder}])
        (when (empty? (responsibility board holder))
          [{:invariant :holder-with-empty-responsibility :holder-id holder}]))
      (when (and (nil? holder) (not= :free (:state token-slot)))
        [{:invariant :free-token-slot-state :state (:state token-slot)}])
      (when (and holder (not= :held (:state token-slot)))
        [{:invariant :held-token-slot-state :state (:state token-slot)}])

      ;; ---- waiters, inputs, ledger ----------------------------------------
      (for [[id reg] waiters
            :when (not (contains? actors id))]
        {:invariant :parked-unknown-actor :agent-id id})
      (for [[id reg] waiters
            :when (and (contains? actors id)
                       (not= :alive (get-in actors [id :life-state])))]
        {:invariant :parked-dead-actor :agent-id id})
      (for [[id reg] waiters
            :when (and (integer? (:cursor reg))
                       (> (:cursor reg) board-version))]
        {:invariant :park-cursor-in-future :agent-id id :cursor (:cursor reg)})
      (when (and (global-progressable? board) (seq waiters))
        [{:invariant :parked-while-progressable :waiters (set (keys waiters))}])
      (for [[iid input] inputs
            :when (not (contains? #{:unhandled :handled} (:state input)))]
        {:invariant :input-state :input-id iid :state (:state input)})
      (for [[iid input] inputs
            :when (not (and (string? (:text input)) (seq (:text input))))]
        {:invariant :input-text-missing :input-id iid})
      (for [[key entry] ledger
            :when (not (contains? tasks (:task-id entry)))]
        {:invariant :ledger-task-unknown :ledger-key key :task-id (:task-id entry)})
      (for [[key entry] ledger
            :let [t (get tasks (:task-id entry))]
            :when (and t (not= key (:ledger-key t)))]
        {:invariant :ledger-entry-key-mismatch :ledger-key key})
      (for [[key entry] ledger
            :let [t (get tasks (:task-id entry))]
            :when (and t (= :done (:state t)))]
        {:invariant :ledger-open-on-done-task :ledger-key key})
      (for [[id t] tasks
            :let [key (:ledger-key t)]
            :when (and key (unfinished? t) (not (contains? ledger key)))]
        {:invariant :task-ledger-key-unregistered :task-id id :ledger-key key})

      ;; ---- derived index consistency --------------------------------------
      (when derived
        (let [expected (:derived (derive (assoc board :derived nil)))
              indexes [:ready-tasks :waiting-tasks :done-tasks
                       :public-ready-tasks :token-tasks :owned-tasks
                       :responsibility :holder-responsibility
                       :global-progressable? :progressable-reasons
                       :parked-agents :root-tasks :all-roots-done?
                       :ledger-clear?]]
          (for [index indexes
                :when (not= (get derived index) (get expected index))]
            {:invariant :derived-index-mismatch
             :index index
             :actual (get derived index)
             :expected (get expected index)})))
      ))))


