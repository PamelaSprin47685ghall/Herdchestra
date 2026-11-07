(ns hc.compose.mission.worker-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.mission.worker :as worker]))

(def ^:private empty-state
  {:worker nil :next-seq 1})

(defn- ensure!
  ([state call-id] (ensure! state call-id "inspect"))
  ([state call-id intent]
   (let [result (worker/ensure state
                               {:tenure-id "tenure-1"
                                :model-id "task-model"
                                :call-id call-id
                                :verbose-intent intent})]
     (is (:ok? result) (pr-str (:error result)))
     result)))

(defn- complete!
  [state worker-id call-id]
  (let [result (worker/complete (:worker state)
                                {:call-id call-id
                                 :status :ok
                                 :report (str "report for " call-id)})]
    (is (:ok? result) (pr-str (:error result)))
    (assoc state :worker (:worker result))))

(deftest contract-is-read-only-and-task-model-only
  (is (= :task (get-in worker/contract [:model-role])))
  (is (false? (get-in worker/contract [:board?])))
  (is (false? (get-in worker/contract [:token?])))
  (is (false? (get-in worker/contract [:write?])))
  (is (= worker/capabilities (get-in worker/contract [:capabilities])))
  (is (contains? worker/capabilities :call))
  (is (contains? worker/capabilities :whereAll))
  (is (not-any? worker/capabilities worker/forbidden-capabilities))
  (is (empty? (worker/check-worker
               (worker/new-worker {:worker-id "w1"
                                   :tenure-id "t1"
                                   :model-id "task-model"})))))

(deftest one-live-worker-per-tenure-is-reused-and-steered
  (let [first (ensure! empty-state "call-1" "first intent")
        state (:state first)
        second (ensure! state "call-2" "second intent")
        state (:state second)]
    (is (false? (:reused? first)))
    (is (= :worker/spawn (get-in first [:effect :type])))
    (is (true? (:reused? second)))
    (is (= :worker/steer (get-in second [:effect :type])))
    (is (= (:worker-id first) (:worker-id second)))
    (is (= 2 (get-in state [:worker :steer-seq])))
    (is (= "second intent" (get-in state [:worker :last-intent])))
    (is (= "call-2" (get-in state [:worker :pending-call-id])))))

(deftest report-is-returned-verbatim
  (let [first (ensure! empty-state "call-1")
        state (:state first)
        report "line one\n  line two\n\"quoted\" and \\slashes"
        result (worker/complete (:worker state)
                                {:call-id "call-1"
                                 :status :ok
                                 :report report})]
    (is (:ok? result))
    (is (= report (get-in result [:result :report])))
    (is (= report (get-in result [:worker :last-report])))
    (is (= :idle (get-in result [:worker :status])))
    (is (nil? (get-in result [:worker :pending-call-id])))
    (is (= 1 (get-in result [:worker :report-count])))))

(deftest stale-report-does-not-kill-the-worker
  (let [first (ensure! empty-state "call-1")
        state (:state first)
        stale (worker/complete (:worker state)
                               {:call-id "other-call"
                                :status :ok
                                :report "late"})]
    (is (false? (:ok? stale)))
    (is (= :STALE_WORKER_CALL (get-in stale [:error :code])))
    (is (= :running (get-in state [:worker :status])))))

(deftest death-recreates-one-fresh-worker
  (let [first (ensure! empty-state "call-1")
        dead (assoc (:state first)
                    :worker (worker/mark-dead (get-in first [:state :worker])
                                              :test-death))
        second (ensure! dead "call-2")]
    (is (false? (:reused? second)))
    (is (= :worker/spawn (get-in second [:effect :type])))
    (is (not= (:worker-id first) (:worker-id second)))
    (is (= "plan-worker-2" (:worker-id second)))
    (is (= 2 (get-in second [:state :worker :generation])))
    (is (worker/live? (get-in second [:state :worker])))
    (testing "a dead worker cannot publish a late report"
      (let [late (worker/complete (get-in dead [:worker])
                                  {:call-id "call-1"
                                   :status :ok
                                   :report "late"})]
        (is (false? (:ok? late)))
        (is (= :WORKER_DEAD (get-in late [:error :code])))))))

(defspec worker-lifecycle-keeps-one-task-model-identity
  40
  (prop/for-all [ops (gen/vector (gen/elements [:ensure :complete :die]) 1 25)]
    (loop [ops ops
           state empty-state
           pending nil
           index 0]
      (if-let [op (first ops)]
        (let [index (inc index)
              call-id (str "call-" index)]
          (case op
            :ensure
            (let [result (worker/ensure state
                                        {:tenure-id "tenure-1"
                                         :model-id "task-model"
                                         :call-id call-id
                                         :verbose-intent (str "intent " index)})]
              (if-not (:ok? result)
                false
                (recur (next ops) (:state result) call-id (inc index))))

            :complete
            (if-let [worker (when (worker/live? (:worker state))
                              (:worker state))]
              (let [result (worker/complete
                            worker
                            {:call-id (or pending call-id)
                             :status :ok
                             :report "report"})]
                (cond
                  (:ok? result)
                  (recur (next ops)
                         (assoc state :worker (:worker result))
                         nil
                         (inc index))

                  ;; An idle worker has no pending call, so a late report is
                  ;; a legal stale rejection, not a lifecycle failure.
                  (and (nil? pending)
                       (= :STALE_WORKER_CALL (get-in result [:error :code])))
                  (recur (next ops) state pending (inc index))

                  :else false))
              (recur (next ops) state pending (inc index)))

            :die
            (recur (next ops)
                   (assoc state :worker (worker/mark-dead (:worker state)
                                                          :property-death))
                   pending
                   (inc index))))
        (let [worker (:worker state)]
          (and (map? state)
               (integer? (:next-seq state))
               (pos? (:next-seq state))
               (or (nil? worker)
                   (and (= "tenure-1" (:tenure-id worker))
                        (= :task (:model-role worker))
                        (= "task-model" (:model-id worker))
                        (empty? (worker/check-worker worker))))))))))

(deftest idle-worker-rejects-a-late-report
  (let [idle (worker/new-worker {:worker-id "w-idle"
                                 :tenure-id "t1"
                                 :model-id "task-model"})
        late (worker/complete idle
                              {:call-id "call-late"
                               :status :ok
                               :report "late"})]
    (is (false? (:ok? late)))
    (is (= :STALE_WORKER_CALL (get-in late [:error :code])))
    (is (nil? (:last-report idle)))))

(deftest running-worker-must-name-a-pending-call
  (let [idle (worker/new-worker {:worker-id "w-pending"
                                 :tenure-id "t1"
                                 :model-id "task-model"})
        running (assoc idle :status :running)
        violations (worker/check-worker running)]
    (is (some #(= :WORKER_PENDING_STATE_INVALID (:code %)) violations)
        "a running worker without a pending call is a broken lifecycle")
    (is (empty? (worker/check-worker idle)))))
