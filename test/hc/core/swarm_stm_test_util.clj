(ns hc.core.swarm-stm-test-util
  "Small scripted-agent helpers shared by unit, property and smoke tests."
  (:require [clojure.test :as t]
            [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.kernel :as kernel]))

(defn new-kernel
  ([] (new-kernel {}))
  ([opts] (kernel/create opts)))

(defn exec!
  [k cmd]
  (kernel/execute! k cmd))

(def test-write-gate
  "In-process W stand-in for kernel tests that only need the under-W proof."
  {:acquire (fn [_owner] ::permit)
   :release (fn [_permit] nil)})

(defn exec-under-write!
  [k cmd]
  (kernel/execute-under-write-gate! k test-write-gate :test-owner cmd))

(defn exec-ok!
  [k cmd]
  (let [r (kernel/execute! k cmd)]
    (when-not (:ok? r)
      (throw (ex-info (str "command failed: " (:error r))
                      {:command cmd :result r})))
    r))

(defn spawn!
  [k slot]
  (:agent-id (exec-ok! k {:op :register-actor :slot-id slot})))

(defn create-task!
  [k actor-id opts]
  (exec-ok! k (merge {:op :create-task :actor-id actor-id} opts)))

(defn split!
  [k actor-id task-id opts]
  (exec-ok! k (merge {:op :split :actor-id actor-id :task-id task-id} opts)))

(defn claim!
  [k actor-id task-id]
  (exec-ok! k {:op :claim :actor-id actor-id :task-id task-id
               :atomicity-reason "scripted atomic step"}))

(defn finish!
  ([k actor-id task-id claim-id]
   (finish! k actor-id task-id claim-id "scripted result"))
  ([k actor-id task-id claim-id summary]
   (exec-ok! k (cond-> {:op :finish :actor-id actor-id :task-id task-id
                        :result-summary summary}
                 claim-id (assoc :claim-id claim-id)))))

(defn take!
  [k actor-id request-id]
  (exec! k {:op :take-or-die :actor-id actor-id :request-id request-id
            :confirm :i-hereby-confirm-i-die-without-regret}))

(defn park!
  [k actor-id]
  (exec! k {:op :park-slot :actor-id actor-id
            :observed-cursor (kernel/board-version k)}))

(defn assert-invariants!
  "Throw with a compact violation report instead of silently continuing."
  [k]
  (let [violations (kernel/check-invariants k)]
    (when (seq violations)
      (throw (ex-info "board invariant violation"
                      {:violations violations
                       :board (kernel/snapshot k)}))))
  k)

(defn assert-invariants-test!
  [k]
  (let [violations (kernel/check-invariants k)]
    (t/is (empty? violations) (pr-str violations))
    k))

(defn alive-actors
  [k]
  (->> (get (kernel/snapshot k) :actors)
       vals
       (filter #(= :alive (:life-state %)))
       (mapv :agent-id)))

(defn ready-tasks
  [k]
  (->> (get (kernel/snapshot k) :tasks)
       vals
       (filter #(= :ready (:state %)))
       (mapv :task-id)))

(defn claimed-tasks
  [k]
  (->> (get (kernel/snapshot k) :tasks)
       vals
       (filter #(and (:claim-id %) (not= :done (:state %))))
       (mapv :task-id)))

(defn child-spec
  [n]
  {:goal (str "child " n)
   :remaining-work (str "do child " n)
   :done-when (str "child " n " has a result")})
