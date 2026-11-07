(ns hc.compose.guards.scheduler-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.guards.priority :as priority]
            [hc.compose.guards.scheduler :as scheduler]))

;; ---------------------------------------------------------------------------
;; Test helpers

(defn- counter-guard
  "A pure guard that increments :n.  LIMIT disables the guard at that count."
  ([id priority] (counter-guard id priority nil))
  ([id priority limit]
   (scheduler/guard
    id
    priority
    (fn [state _]
      (when (or (nil? limit) (< (:n state) limit))
        {:n (:n state)}))
    (fn [state _ _ _]
      {:state (update state :n inc)}))))

(defn- accept
  "Complete the commit and dispatch handshake.  Return the scheduler or nil."
  [scheduler transition-id]
  (let [dispatched (scheduler/after-commit scheduler transition-id)
        acknowledged (scheduler/ack-dispatch (:scheduler dispatched)
                                             transition-id)]
    (when (and (= :dispatch (:status dispatched))
               (= :acked (:status acknowledged)))
      (:scheduler acknowledged))))

(defn- claim-guard
  "A guard that claims a ready call and dispatches one model attempt."
  []
  (scheduler/guard
   :claim-call
   :p3-progress
   (fn [state _]
     (when (= :ready (get-in state [:calls "c1" :state]))
       {:work-id "c1"}))
   (fn [state _ _ attempt-id]
     {:state (-> state
                 (assoc-in [:calls "c1" :state] :dispatching)
                 (assoc-in [:calls "c1" :attempt-id] attempt-id))
      :claim {:path [:calls "c1" :state]
              :work-id "c1"
              :attempt-path [:calls "c1" :attempt-id]
              :from :ready
              :to :dispatching}
      :dispatch [{:kind :model :payload {:call-id "c1"}}]})))

(defn- rejecting-guard
  [prepare]
  (scheduler/guard :bad :p3-progress (fn [_ _] true) prepare))

(defn- plain-counter-guard
  [id]
  (scheduler/guard id
                   (fn [state _] {:n (:n state)})
                   (fn [state _ _ _] {:state (update state :n inc)})))

(defn- simple-claim-guard
  "A canonical guard with one-argument enabled? and prepare functions."
  []
  {:id :simple-claim
   :enabled? (fn [state]
               (when (= :ready (get-in state [:work :state])) true))
   :prepare (fn [state]
              {:state (assoc-in state [:work :state] :dispatching)
               :claim {:path [:work :state]
                       :work-id :work
                       :attempt-path [:work :attempt-id]
                       :from :ready
                       :to :dispatching}
               :dispatch [{:kind :test}]})})

(defn- error-code
  [result]
  (get-in result [:error :code]))

;; ---------------------------------------------------------------------------
;; Priority and fairness

(deftest priority-ranks
  (is (= 0 (priority/rank :p0)))
  (is (= 0 (priority/rank :p0-control)))
  (is (= 4 (priority/rank 4)))
  (is (nil? (priority/rank :p5)))
  (is (nil? (priority/rank 5))))

(deftest rotation-edge-cases
  (let [candidates [{:guard-id :a} {:guard-id :b} {:guard-id :c}]
        ids (fn [last-id]
              (mapv :guard-id (priority/rotate-after candidates last-id)))]
    (is (= [:a :b :c] (ids nil)))
    (is (= [:a :b :c] (ids :missing)))
    (is (= [:b :c :a] (ids :a)))
    (is (= [:c :a :b] (ids :b)))
    (is (= [:a :b :c] (ids :c)))))

(deftest priority-preempts-lower-groups
  (let [catalog (scheduler/catalog [(counter-guard :low :p3-progress)
                                    (counter-guard :high :p1-closure)])
        result (scheduler/step catalog {:n 0} {}
                               (scheduler/new-scheduler {:quota 4}))]
    (is (= :prepared (:status result)))
    (is (= :high (get-in result [:transition :guard-id])))))

(deftest lower-ranks-are-not-evaluated
  (let [high (scheduler/guard
              :high
              :p1-closure
              (fn [_ _] true)
              (fn [state _ _ _] {:state (assoc state :high? true)}))
        low (scheduler/guard
             :low
             :p3-progress
             (fn [_ _]
               (throw (ex-info "lower rank was evaluated" {})))
             (fn [state _ _ _] {:state (assoc state :low? true)}))
        result (scheduler/step (scheduler/catalog [low high]) {} {}
                               (scheduler/new-scheduler {:quota 4}))]
    (is (= :prepared (:status result)))
    (is (= :high (get-in result [:transition :guard-id])))))

(deftest catalog-map-assigns-priority
  (let [catalog (scheduler/catalog {:p1-closure [(plain-counter-guard :high)]
                                    :p3-progress [(plain-counter-guard :low)]})
        result (scheduler/step catalog {:n 0} {}
                               (scheduler/new-scheduler {:quota 4}))]
    (is (= :prepared (:status result)))
    (is (= :high (get-in result [:transition :guard-id])))
    (is (= :p1-closure (get-in result [:transition :priority])))))

(deftest flat-catalog-defaults-to-p3
  (let [catalog (scheduler/catalog [(plain-counter-guard :only)])
        result (scheduler/step catalog {:n 0} {}
                               (scheduler/new-scheduler {:quota 4}))]
    (is (= :prepared (:status result)))
    (is (= :p3-progress (get-in result [:transition :priority])))))

(deftest same-priority-guards-rotate
  (let [catalog (scheduler/catalog [(counter-guard :a :p3-progress)
                                    (counter-guard :b :p3-progress)])
        s0 (scheduler/new-scheduler {:quota 4})]
    (loop [s s0 state {:n 0} seen []]
      (if (= 4 (count seen))
        (is (= [:a :b :a :b] seen))
        (let [result (scheduler/step catalog state {} s)]
          (is (= :prepared (:status result)))
          (let [transition-id (get-in result [:transition :transition-id])
                s' (accept (:scheduler result) transition-id)]
            (is (some? s'))
            (recur s'
                   (get-in result [:transition :state])
                   (conj seen (get-in result [:transition :guard-id])))))))))

;; ---------------------------------------------------------------------------
;; Quota

(deftest quota-requeues-enabled-work
  (let [catalog (scheduler/catalog [(counter-guard :work :p3-progress)])
        s0 (scheduler/new-scheduler {:quota 2})]
    (loop [s s0 state {:n 0} prepared 0]
      (if (< prepared 2)
        (let [result (scheduler/step catalog state {} s)]
          (is (= :prepared (:status result)))
          (let [transition-id (get-in result [:transition :transition-id])
                s' (accept (:scheduler result) transition-id)]
            (is (some? s'))
            (recur s' (get-in result [:transition :state]) (inc prepared))))
        (let [exhausted (scheduler/step catalog state {} s)]
          (is (= :quota-exhausted (:status exhausted)))
          (is (true? (:continue? exhausted)))
          (is (= :quota-requeue (get-in exhausted [:scheduler :wakeup :reason])))
          (is (= 2 (:n state)))
          (let [next-round (scheduler/finish-round (:scheduler exhausted))
                resumed (scheduler/step catalog state {} next-round)]
            (is (= :prepared (:status resumed)))
            (is (= 3 (get-in resumed [:transition :state :n])))))))))

(deftest quota-drains-when-no-guard-remains
  (let [catalog (scheduler/catalog [(counter-guard :work :p3-progress 2)])
        s0 (scheduler/new-scheduler {:quota 10})]
    (loop [s s0 state {:n 0}]
      (let [result (scheduler/step catalog state {} s)]
        (if (= :prepared (:status result))
          (let [transition-id (get-in result [:transition :transition-id])
                s' (accept (:scheduler result) transition-id)]
            (is (some? s'))
            (recur s' (get-in result [:transition :state])))
          (do
            (is (= :drained (:status result)))
            (is (nil? (:continue? result)))
            (is (= 2 (:n state)))))))))

(deftest quota-remaining-counts-down
  (let [catalog (scheduler/catalog [(counter-guard :work :p3-progress)])
        s0 (scheduler/new-scheduler {:quota 3})]
    (is (= 3 (scheduler/quota-remaining s0)))
    (let [result (scheduler/step catalog {:n 0} {} s0)]
      (is (= 2 (scheduler/quota-remaining (:scheduler result)))))))

;; ---------------------------------------------------------------------------
;; Claim before dispatch

(deftest dispatch-waits-for-commit
  (let [catalog (scheduler/catalog [(claim-guard)])
        s0 (scheduler/new-scheduler {:quota 4})
        state {:calls {"c1" {:state :ready :attempt-id nil}}}
        prepared (scheduler/step catalog state {} s0)
        transition (:transition prepared)
        transition-id (:transition-id transition)
        attempt-id (:attempt-id transition)]
    (is (= :prepared (:status prepared)))
    (is (not (contains? transition :dispatch)))
    (is (= 1 (:dispatch-count transition)))
    (is (= :dispatching (get-in transition [:state :calls "c1" :state])))
    (is (= attempt-id (get-in transition [:state :calls "c1" :attempt-id])))
    (is (= (:state transition)
           (get-in (:scheduler prepared) [:pending-dispatch :state]))
        "the host must be able to recover an uncommitted candidate")
    (is (= :awaiting-commit
           (:status (scheduler/step catalog state {} (:scheduler prepared)))))
    (let [dispatched (scheduler/after-commit (:scheduler prepared)
                                             transition-id)]
      (is (= :dispatch (:status dispatched)))
      (is (= 1 (count (:dispatch dispatched))))
      (is (every? #(= attempt-id (:attempt-id %)) (:dispatch dispatched)))
      (is (= :awaiting-dispatch
             (:status (scheduler/step catalog (:state transition) {}
                                      (:scheduler dispatched)))))
      (let [replay (scheduler/after-commit (:scheduler dispatched)
                                           transition-id)]
        (is (true? (:replayed? replay)))
        (is (= (:dispatch dispatched) (:dispatch replay))))
      (let [acked (scheduler/ack-dispatch (:scheduler dispatched)
                                          transition-id)]
        (is (= :acked (:status acked)))
        (is (= :drained
               (:status (scheduler/step catalog (:state transition) {}
                                        (:scheduler acked)))))))))

(deftest abandon-pending-clears-one-handshake
  (let [catalog (scheduler/catalog [(claim-guard)])
        prepared (scheduler/step catalog
                                 {:calls {"c1" {:state :ready :attempt-id nil}}}
                                 {}
                                 (scheduler/new-scheduler {:quota 4}))
        scheduler' (:scheduler prepared)
        abandoned (scheduler/abandon-pending! scheduler')]
    (is (some? (get-in scheduler' [:pending-dispatch :state])))
    (is (nil? (:pending-dispatch abandoned)))
    (is (zero? (:used abandoned)))
    (is (= (inc (:round scheduler')) (:round abandoned)))
    (is (= :prepared
           (:status (scheduler/step catalog
                                    {:calls {"c1" {:state :ready
                                                   :attempt-id nil}}}
                                    {}
                                    abandoned))))))

(deftest state-only-micro-action-is-allowed
  (let [g (scheduler/guard
           :mark
           :p3-progress
           (fn [state _] (when-not (:marked? state) true))
           (fn [state _ _ _] {:state (assoc state :marked? true)}))
        result (scheduler/step (scheduler/catalog [g]) {} {}
                               (scheduler/new-scheduler {}))
        transition-id (get-in result [:transition :transition-id])
        dispatched (scheduler/after-commit (:scheduler result)
                                           transition-id)
        acknowledged (scheduler/ack-dispatch (:scheduler dispatched)
                                             transition-id)]
    (is (= :prepared (:status result)))
    (is (nil? (get-in result [:transition :claim])))
    (is (= 0 (get-in result [:transition :dispatch-count])))
    (is (= [] (:dispatch dispatched)))
    (is (= :acked (:status acknowledged)))))

(deftest prepare-must-progress
  (let [g (rejecting-guard (fn [state _ _ _] {:state state}))
        result (scheduler/step (scheduler/catalog [g]) {} {}
                               (scheduler/new-scheduler {}))]
    (is (= :rejected (:status result)))
    (is (= :NO_PROGRESS (error-code result)))))

(deftest dispatch-without-claim-is-rejected
  (let [g (rejecting-guard
           (fn [state _ _ _]
             {:state (assoc state :changed? true)
              :dispatch [{:kind :test}]}))
        result (scheduler/step (scheduler/catalog [g]) {} {}
                               (scheduler/new-scheduler {}))]
    (is (= :rejected (:status result)))
    (is (= :DISPATCH_WITHOUT_CLAIM (error-code result)))))

(deftest claim-target-must-be-in-prepared-state
  (let [g (rejecting-guard
           (fn [state _ _ attempt-id]
             {:state (assoc-in state [:work :attempt-id] attempt-id)
              :claim {:path [:work :state]
                      :work-id :work
                      :attempt-path [:work :attempt-id]
                      :from :ready
                      :to :dispatching}
              :dispatch [{:kind :test}]}))
        result (scheduler/step (scheduler/catalog [g])
                               {:work {:state :ready}} {}
                               (scheduler/new-scheduler {}))]
    (is (= :rejected (:status result)))
    (is (= :CLAIM_TARGET_MISMATCH (error-code result)))))

(deftest claim-must-not-record-a-different-attempt-id
  (let [g (rejecting-guard
           (fn [state _ _ _]
             {:state (-> state
                         (assoc-in [:work :state] :dispatching)
                         (assoc-in [:work :attempt-id] :wrong))
              :claim {:path [:work :state]
                      :work-id :work
                      :attempt-path [:work :attempt-id]
                      :from :ready
                      :to :dispatching}
              :dispatch [{:kind :test}]}))
        result (scheduler/step (scheduler/catalog [g])
                               {:work {:state :ready}} {}
                               (scheduler/new-scheduler {}))]
    (is (= :rejected (:status result)))
    (is (= :CLAIM_ATTEMPT_NOT_RECORDED (error-code result)))))

(deftest one-argument-guards-work
  (let [catalog (scheduler/catalog {:p3-progress [(simple-claim-guard)]})
        result (scheduler/step catalog {:work {:state :ready}} {}
                               (scheduler/new-scheduler {}))
        transition (:transition result)
        attempt-id (:attempt-id transition)
        dispatched (scheduler/after-commit (:scheduler result)
                                           (:transition-id transition))]
    (is (= :prepared (:status result)))
    (is (= :dispatching (get-in transition [:state :work :state])))
    (is (= attempt-id (get-in transition [:state :work :attempt-id])))
    (is (= attempt-id (:attempt-id (first (:dispatch dispatched)))))))

(deftest internal-arity-errors-are-not-retried
  (let [g (scheduler/guard
           :broken
           :p3-progress
           (fn [_ _]
             (throw (clojure.lang.ArityException. 2 "inner")))
           (fn [state _ _ _] {:state (assoc state :x 1)}))]
    (is (thrown? clojure.lang.ArityException
                 (scheduler/step (scheduler/catalog [g]) {} {}
                                 (scheduler/new-scheduler {}))))))

(deftest dispatch-attempt-must-match-claim
  (let [g (rejecting-guard
           (fn [state _ _ attempt-id]
             {:state (-> state
                         (assoc-in [:work :state] :dispatching)
                         (assoc-in [:work :attempt-id] attempt-id))
              :claim {:path [:work :state]
                      :work-id :work
                      :attempt-path [:work :attempt-id]
                      :from :ready
                      :to :dispatching}
              :dispatch [{:kind :test :attempt-id :wrong}]}))
        result (scheduler/step (scheduler/catalog [g])
                               {:work {:state :ready}} {}
                               (scheduler/new-scheduler {}))]
    (is (= :rejected (:status result)))
    (is (= :DISPATCH_ATTEMPT_MISMATCH (error-code result)))))

(deftest claim-source-must-match
  (let [g (rejecting-guard
           (fn [state _ _ attempt-id]
             {:state (-> state
                         (assoc-in [:work :state] :dispatching)
                         (assoc-in [:work :attempt-id] attempt-id))
              :claim {:path [:work :state]
                      :work-id :work
                      :attempt-path [:work :attempt-id]
                      :from :pending
                      :to :dispatching}
              :dispatch [{:kind :test}]}))
        result (scheduler/step (scheduler/catalog [g])
                               {:work {:state :ready}} {}
                               (scheduler/new-scheduler {}))]
    (is (= :rejected (:status result)))
    (is (= :CLAIM_SOURCE_MISMATCH (error-code result)))))

(deftest claim-from-must-be-ready-or-pending
  (let [g (rejecting-guard
           (fn [state _ _ attempt-id]
             {:state (-> state
                         (assoc-in [:work :state] :dispatching)
                         (assoc-in [:work :attempt-id] attempt-id))
              :claim {:path [:work :state]
                      :work-id :work
                      :attempt-path [:work :attempt-id]
                      :from :done
                      :to :dispatching}
              :dispatch [{:kind :test}]}))
        result (scheduler/step (scheduler/catalog [g])
                               {:work {:state :done}} {}
                               (scheduler/new-scheduler {}))]
    (is (= :rejected (:status result)))
    (is (= :CLAIM_FROM_INVALID (error-code result)))))

;; ---------------------------------------------------------------------------
;; Wakeups, blocked assertion, and round lifecycle

(deftest wake-coalesces
  (let [s (-> (scheduler/new-scheduler {:quota 1})
              (scheduler/wake :first)
              (scheduler/wake :second)
              (scheduler/wake :third))]
    (is (true? (get-in s [:wakeup :pending?])))
    (is (= :first (get-in s [:wakeup :reason])))
    (is (= 2 (get-in s [:wakeup :coalesced])))
    (let [result (scheduler/step (scheduler/catalog [(counter-guard :g :p3-progress)])
                                 {:n 0} {} s)]
      (is (= :prepared (:status result)))
      (is (false? (get-in result [:scheduler :wakeup :pending?]))))))

(deftest blocked-assertion-is-explicit
  (let [empty-catalog (scheduler/catalog [])
        s (scheduler/new-scheduler {})
        active {:tenure-terminal? false
                :inflight? false
                :timed-wait? false}]
    (let [blocked (scheduler/step empty-catalog {} active s)]
      (is (= :blocked (:status blocked)))
      (is (= :BLOCKED (get-in blocked [:diagnostic :code])))
      (is (= :no-enabled-guard (get-in blocked [:diagnostic :reason])))
      (is (= :p4-blocked (get-in blocked [:diagnostic :priority]))))
    (is (= :idle (:status (scheduler/step empty-catalog {}
                                         (assoc active :inflight? true) s))))
    (is (= :idle (:status (scheduler/step empty-catalog {}
                                         (assoc active :timed-wait? true) s))))
    (is (= :idle (:status (scheduler/step empty-catalog {}
                                         (assoc active :tenure-terminal? true) s))))
    (is (= :idle (:status (scheduler/step empty-catalog {} {} s))))))

(deftest all-rejected-guards-block
  (let [g (rejecting-guard (fn [state _ _ _] {:state state}))
        catalog (scheduler/catalog [g])
        first-result (scheduler/step catalog {} {} (scheduler/new-scheduler {:quota 4}))]
    (is (= :rejected (:status first-result)))
    (let [blocked (scheduler/step catalog {}
                                 {:tenure-terminal? false
                                  :inflight? false
                                  :timed-wait? false}
                                 (:scheduler first-result))]
      (is (= :blocked (:status blocked)))
      (is (= :all-guards-rejected (get-in blocked [:diagnostic :reason])))
      (is (= [:bad] (get-in blocked [:diagnostic :rejected-guards]))))))

(deftest rejected-plan-consumes-quota
  (let [g (rejecting-guard (fn [state _ _ _] {:state state}))
        catalog (scheduler/catalog [g])
        first-result (scheduler/step catalog {} {}
                                     (scheduler/new-scheduler {:quota 1}))]
    (is (= :rejected (:status first-result)))
    (is (= 0 (scheduler/quota-remaining (:scheduler first-result))))
    (let [drained (scheduler/step catalog {} {} (:scheduler first-result))]
      (is (= :drained (:status drained)))
      (let [next-round (scheduler/finish-round (:scheduler drained))]
        (is (= :rejected
               (:status (scheduler/step catalog {} {} next-round))))))))

(deftest finish-round-refuses-pending-dispatch
  (let [catalog (scheduler/catalog [(claim-guard)])
        result (scheduler/step catalog
                               {:calls {"c1" {:state :ready :attempt-id nil}}}
                               {}
                               (scheduler/new-scheduler {}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pending dispatch"
                          (scheduler/finish-round (:scheduler result))))))

(deftest after-commit-and-ack-reject-unknown-transitions
  (let [s (scheduler/new-scheduler {})]
    (is (= :unknown-transition
           (:status (scheduler/after-commit s :transition-1))))
    (is (= :unknown-transition
           (:status (scheduler/ack-dispatch s :transition-1))))))

(deftest ack-requires-commit
  (let [catalog (scheduler/catalog [(claim-guard)])
        result (scheduler/step catalog
                               {:calls {"c1" {:state :ready :attempt-id nil}}}
                               {}
                               (scheduler/new-scheduler {}))
        transition-id (get-in result [:transition :transition-id])]
    (is (= :not-committed
           (:status (scheduler/ack-dispatch (:scheduler result)
                                            transition-id))))))

(deftest catalog-validation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unique"
                        (scheduler/catalog
                         [(counter-guard :same :p3-progress)
                          (counter-guard :same :p3-progress)])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"P0 through P4"
                        (scheduler/catalog
                         [(scheduler/guard :bad :p9 (fn [_ _] true)
                                           (fn [state _ _ _]
                                             {:state (assoc state :x 1)}))])))
  (is (thrown? clojure.lang.ExceptionInfo
               (scheduler/new-scheduler {:quota 0}))))

;; ---------------------------------------------------------------------------
;; Properties

(defspec attempt-ids-are-unique 50
  (prop/for-all [steps (gen/choose 1 20)]
    (let [catalog (scheduler/catalog [(counter-guard :work :p3-progress)])
          s0 (scheduler/new-scheduler {:quota steps})]
      (loop [s s0 state {:n 0} ids #{} remaining steps]
        (if (zero? remaining)
          (= (count ids) steps)
          (let [result (scheduler/step catalog state {} s)]
            (if (= :prepared (:status result))
              (let [attempt-id (get-in result [:transition :attempt-id])
                    transition-id (get-in result [:transition :transition-id])
                    s' (accept (:scheduler result) transition-id)]
                (if s'
                  (recur s'
                         (get-in result [:transition :state])
                         (conj ids attempt-id)
                         (dec remaining))
                  false))
              false)))))))

(defspec quota-bounds-one-round 50
  (prop/for-all [quota (gen/choose 1 10)]
    (let [catalog (scheduler/catalog [(counter-guard :work :p3-progress)])
          s0 (scheduler/new-scheduler {:quota quota})]
      (loop [s s0 state {:n 0} prepared 0]
        (if (= prepared quota)
          (let [exhausted (scheduler/step catalog state {} s)
                next-round (scheduler/finish-round (:scheduler exhausted))
                resumed (scheduler/step catalog state {} next-round)]
            (and (= :quota-exhausted (:status exhausted))
                 (true? (:continue? exhausted))
                 (= :prepared (:status resumed))
                 (= (inc quota) (get-in resumed [:transition :state :n]))))
          (let [result (scheduler/step catalog state {} s)]
            (if (= :prepared (:status result))
              (let [transition-id (get-in result [:transition :transition-id])
                    s' (accept (:scheduler result) transition-id)]
                (if s'
                  (recur s'
                         (get-in result [:transition :state])
                         (inc prepared))
                  false))
              false)))))))

(defspec wake-notifications-coalesce 50
  (prop/for-all [reasons (gen/vector (gen/elements [:state :timer :message])
                                     1 20)]
    (let [s (reduce scheduler/wake (scheduler/new-scheduler {}) reasons)]
      (and (true? (get-in s [:wakeup :pending?]))
           (= (first reasons) (get-in s [:wakeup :reason]))
           (= (dec (count reasons)) (get-in s [:wakeup :coalesced]))))))

(defspec dispatch-effects-carry-the-claimed-attempt 50
  (prop/for-all [effect-count (gen/choose 1 5)]
    (let [g (scheduler/guard
             :claim
             :p3-progress
             (fn [state _]
               (when (= :ready (get-in state [:w :state])) true))
             (fn [state _ _ attempt-id]
               {:state (-> state
                           (assoc-in [:w :state] :dispatching)
                           (assoc-in [:w :attempt-id] attempt-id))
                :claim {:path [:w :state]
                        :work-id :w
                        :attempt-path [:w :attempt-id]
                        :from :ready
                        :to :dispatching}
                :dispatch (repeat effect-count {:kind :test})}))
          catalog (scheduler/catalog [g])
          result (scheduler/step catalog {:w {:state :ready}} {}
                                 (scheduler/new-scheduler {:quota 1}))
          transition (:transition result)
          attempt-id (:attempt-id transition)
          dispatched (scheduler/after-commit
                      (:scheduler result)
                      (:transition-id transition))]
      (and (= :prepared (:status result))
           (= effect-count (:dispatch-count transition))
           (= effect-count (count (:dispatch dispatched)))
           (every? #(= attempt-id (:attempt-id %)) (:dispatch dispatched))
           (= attempt-id (get-in transition [:state :w :attempt-id]))
           (= :dispatching (get-in transition [:state :w :state]))))))

(defspec selected-candidate-has-lowest-enabled-rank 50
  (prop/for-all [ranks (gen/vector (gen/elements [:p0 :p1 :p2 :p3 :p4])
                                   1 8)]
    (let [guards (map-indexed
                  (fn [i p]
                    (counter-guard (keyword (str "g" i)) p))
                  ranks)
          catalog (scheduler/catalog guards)
          s (scheduler/new-scheduler {:quota 10})
          enabled (scheduler/enabled-candidates catalog {:n 0} {})
          candidate (scheduler/next-candidate catalog {:n 0} {} s)]
      (and (= (count guards) (count enabled))
           (= (apply min (map :rank enabled)) (:rank candidate))))))
