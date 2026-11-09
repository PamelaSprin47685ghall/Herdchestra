(ns hc.server.app.steward-closed-loop-test
  "One end-to-end Steward stewardship closed loop over injected I/O.

  The chain under test is:

    Git evidence -> Sphinx cognition -> Decision Frontier -> model decision
    -> Docket admission -> Plan handoff -> fleet fork -> join results
    -> reports -> mission completed

  Every external dependency is injected.  The model executor runs inline so
  each tick is deterministic; scheduled ticks are disabled with a no-op
  executor and the test calls `tick!` explicitly."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.steward-runtime :as runtime]
            [hc.server.app.steward-store :as steward-store]
            [hc.server.storage.support :as storage-support])
  (:import (java.util.concurrent Executor)))

;; ---------------------------------------------------------------------------
;; Fixtures

(def sha-1 (apply str (repeat 40 "a")))
(def sha-2 (apply str (repeat 40 "b")))
(def sha-3 (apply str (repeat 40 "c")))
(def sha-4 (apply str (repeat 40 "d")))

(defn- commit
  [commit-id parents subject path]
  {:kind :git-commit
   :commit commit-id
   :parents parents
   :changed-paths [path]
   :subject subject})

(def ^:private noop-executor
  (reify Executor
    (execute [_ _] nil)))

(def ^:private inline-executor
  (reify Executor
    (execute [_ runnable]
      (.run ^Runnable runnable))))

(defn- stored-state
  [store]
  (:state (steward-store/load-state store)))

(defn- mission-of
  [state]
  (val (first (:missions (:docket state)))))

(defn- decision-value
  "The first decision the scripted model returned."
  [model-state]
  (first (:decisions @model-state)))

;; ---------------------------------------------------------------------------
;; The closed loop

(deftest closed-loop-from-git-to-completed
  (storage-support/with-temp-store [engine {}]
    (let [store (steward-store/steward-store engine)
          commits [(commit sha-1 [] "first" "src/a.clj")
                   (commit sha-2 [sha-1] "second" "src/b.clj")]
          git-calls (atom 0)
          model-calls (atom [])
          model-state (atom {:decide-calls 0 :decisions []})
          plan-calls (atom [])
          fork-calls (atom [])
          reports (atom [])
          slices [{:slice-id "s1"
                   :goal "parse config"
                   :inputs ["src/a.clj"]
                   :depends-on []
                   :done-when "tests green"}
                  {:slice-id "s2"
                   :goal "compat config"
                   :inputs ["src/b.clj"]
                   :depends-on []
                   :done-when "tests green"}]
          candidate {:candidate-id "c1"
                     :title "Harden startup"
                     :goal "make startup deterministic"
                     :summary "Harden startup"
                     :consequence 0.9
                     :evidence-strength 0.9
                     :dependency-cost 0.1
                     :reversibility 0.8
                     :opportunity-cost 0.1
                     :demand 1
                     :evidence-ids [(str "git:" sha-1)]
                     :assumption-ids []
                     :depends-on []
                     :operator-aligned? true}
          git-fn (fn [_]
                   (let [n (swap! git-calls inc)]
                     {:ok? true
                      :head sha-2
                      :master-version 1
                      :commits (if (= 1 n) commits [])}))
          model-fn
          (fn [effect]
            (swap! model-calls conj effect)
            (case (:type effect)
              :model/meditate
              {:ok? true
               :value {:revision {:summary "Two commits observed"
                                  :frontier-of-thought
                                  "Startup hardening is the next focus"}}}

              :model/decide
              (let [[before _] (swap-vals! model-state update
                                           :decide-calls inc)
                    n (inc (:decide-calls before))
                    value (if (= n 1)
                            {:request-id "r1"
                             :frontier-id (:frontier-id effect)
                             :candidates [candidate]}
                            {:request-id (str "r" (inc n))
                             :frontier-id (:frontier-id effect)
                             :candidates []})]
                (swap! model-state update :decisions conj value)
                {:ok? true :value value})

              nil))
          started
          (runtime/start!
           {:store store
            :git git-fn
            :model-fn model-fn
            :model-executor inline-executor
            :tick-executor noop-executor
            :plan-start-fn
            (fn [effect]
              (swap! plan-calls conj effect)
              {:ok? true :session-id "plan-1"})
            :fork-fn
            (fn [requested-slices]
              (swap! fork-calls conj (vec requested-slices))
              {:ok? true
               :swarms [{:swarm-id "sw1"} {:swarm-id "sw2"}]})
            :on-report-fn (fn [report] (swap! reports conj report))
            :clock (constantly 1000)
            :config {:slots-total 4 :min-spare 1}})]
      (is (:ok? started))
      (let [rt (:runtime started)]
        (try
          (testing "tick 1: Git evidence enters the Sphinx"
            (let [result (runtime/tick! rt)
                  state (stored-state store)
                  health (runtime/health rt)
                  snap (runtime/snapshot rt)]
              (is (:ok? result))
              (is (some? (:last-tick-at health)))
              (is (= 2 (get-in state [:cognition :evidence-seq])))
              (is (= 2 (get-in snap [:steward :cognition-version])))
              (is (zero? (:persist-conflicts snap)))
              (is (= :started (:status health)))
              (is (some #(and (= :model/meditate (:type %))
                              (= :meditator (:role %)))
                        @model-calls))))

          (testing "tick 2: the meditator revision reaches the best answer"
            (let [before (get-in (runtime/snapshot rt)
                                 [:steward :cognition-version])
                  result (runtime/tick! rt)
                  after (get-in (runtime/snapshot rt)
                                [:steward :cognition-version])
                  best-answer (get-in (runtime/snapshot rt)
                                      [:steward :best-answer])]
              (is (:ok? result))
              (is (> after before))
              (is (= 3 after))
              (is (= 1 (:revision best-answer)))
              (is (= "Two commits observed" (:summary best-answer)))))

          (testing "tick 3: a frontier wakes the Steward model"
            (is (:ok? (runtime/submit-intent!
                       rt {:revision 1
                           :text "Improve reliability"
                           :received-at 1})))
            (let [result (runtime/tick! rt)]
              (is (:ok? result))
              (is (some #(and (= :model/decide (:type %))
                              (= :steward (:role %)))
                        @model-calls))
              (is (empty? (:missions (:docket (stored-state store)))))))

          (testing "tick 4: the decision admits a mission and Plan starts once"
            (let [result (runtime/tick! rt)
                  state (stored-state store)
                  mission (mission-of state)
                  mission-id (:mission-id mission)
                  snap (runtime/snapshot rt)]
              (is (:ok? result))
              (is (= 1 (count (:missions (:docket state)))))
              (is (= 1 (count (get-in snap
                                      [:steward :docket-summary :missions]))))
              (is (string? mission-id))
              (is (= :planning (:status mission)))
              (is (= "plan-1"
                     (get-in state [:links mission-id :session-id])))
              (is (= 1000
                     (get-in state [:links mission-id :plan-started-at])))
              (is (= 1 (count @plan-calls)))))

          (testing "replayed request-id never creates a second mission"
            (let [decision (decision-value model-state)
                  replay {:request-id (:request-id decision)
                          :frontier-id (:frontier-id decision)
                          :candidates (:candidates decision)}
                  before-missions (count (:missions
                                          (:docket (stored-state store))))
                  before-plans (count @plan-calls)
                  before-forks (count @fork-calls)
                  result (do
                           (runtime/submit-decision! rt replay)
                           (runtime/tick! rt))
                  after-state (stored-state store)]
              (is (:ok? result))
              (is (= before-missions
                     (count (:missions (:docket after-state)))))
              (is (= before-plans (count @plan-calls)))
              (is (= before-forks (count @fork-calls)))))

          (testing "Plan handoff: slices become one fork"
            (let [mission-id (:mission-id (mission-of (stored-state store)))
                  handoff (runtime/on-plan-ready!
                           rt mission-id
                           {:session-id "plan-1"
                            :slices slices
                            :plan-version 1})
                  result (runtime/tick! rt)
                  state (stored-state store)
                  link (get-in state [:links mission-id])]
              (is (:ok? handoff))
              (is (:ok? result))
              (is (= 1 (count @fork-calls)))
              (is (= slices (first @fork-calls)))
              (is (= ["sw1" "sw2"] (:swarm-ids link)))
              (is (= :active
                     (get-in state [:docket :missions mission-id :status])))))

          (testing "join results: two reports and a completed mission"
            (let [mission-id (:mission-id (mission-of (stored-state store)))
                  j1 (runtime/on-join-result!
                      rt nil {:swarm-id "sw1"
                              :slice-id "s1"
                              :report "done-1"
                              :ff-commit sha-3
                              :rebase-rounds 0}
                      nil)
                  j2 (runtime/on-join-result!
                      rt nil {:swarm-id "sw2"
                              :slice-id "s2"
                              :report "done-2"
                              :ff-commit sha-4
                              :rebase-rounds 1}
                      nil)
                  result (runtime/tick! rt)
                  state (stored-state store)]
              (is (:ok? j1))
              (is (:ok? j2))
              (is (:ok? result))
              (is (= :completed
                     (get-in state [:docket :missions mission-id :status])))
              ;; The loop emitted two reports, one per join result.
              (is (= 2 (:reports (:observations result))))
              ;; The runtime must deliver both to the report sink.
              (is (= 2 (count @reports)))
              (is (= ["sw1" "sw2"] (mapv :swarm-id @reports)))))

          (testing "a repeated result for the same swarm is delivered once"
            (let [result {:swarm-id "sw1"
                          :slice-id "s1"
                          :report "done-1"
                          :ff-commit sha-3
                          :rebase-rounds 0}
                  before (count @reports)]
              (runtime/on-join-result! rt nil result nil)
              (runtime/on-join-result! rt nil result nil)
              (let [tick-result (runtime/tick! rt)]
                (is (:ok? tick-result))
                ;; The loop still emits one effect per queued result.
                (is (= 2 (:reports (:observations tick-result))))
                ;; The permanent report ledger suppresses both duplicates.
                (is (= before (count @reports)))
                (is (= 1 (count (filter #(= "sw1" (:swarm-id %))
                                        @reports)))))))

          (testing "a later frontier with no candidates changes no counts"
            (is (:ok? (runtime/submit-intent!
                       rt {:revision 2
                           :text "Second look"
                           :received-at 2})))
            (let [r1 (runtime/tick! rt)
                  r2 (runtime/tick! rt)
                  state (stored-state store)
                  mission (mission-of state)]
              (is (:ok? r1))
              (is (:ok? r2))
              (is (= 2 (:decide-calls @model-state)))
              (is (= 1 (count (:missions (:docket state)))))
              (is (= :completed (:status mission)))
              (is (= 1 (count @plan-calls)))
              (is (= 1 (count @fork-calls)))))

          (finally
            (runtime/stop! rt)
            (steward-store/close! store)))))))

;; ---------------------------------------------------------------------------
;; Negative checks

(deftest on-join-result-does-not-block-on-a-slow-tick
  (storage-support/with-temp-store [engine {}]
    (let [store (steward-store/steward-store engine)
          started
          (runtime/start!
           {:store store
            :git (fn [_]
                   (Thread/sleep 1000)
                   {:ok? true :head sha-1 :commits []
                    :master-version 1})
            :clock (constantly 1000)
            :config {:slots-total 4 :min-spare 1}})]
      (is (:ok? started))
      (let [rt (:runtime started)]
        (try
          (let [started-at (System/nanoTime)
                result (runtime/on-join-result!
                        rt nil {:swarm-id "sw1" :ff-commit sha-2} nil)
                elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
            (is (:ok? result))
            (is (< elapsed-ms 100.0)))
          (finally
            (runtime/stop! rt)
            (steward-store/close! store)))))))

(deftest git-failure-preserves-docket-and-links
  (storage-support/with-temp-store [engine {}]
    (let [store (steward-store/steward-store engine)
          model-calls (atom [])
          started
          (runtime/start!
           {:store store
            :git (fn [_]
                   (throw (ex-info "git down" {:code :GIT_DOWN})))
            :model-fn (fn [effect]
                        (swap! model-calls conj effect)
                        nil)
            :model-executor inline-executor
            :tick-executor noop-executor
            :clock (constantly 1000)
            :config {:slots-total 4 :min-spare 1}})]
      (is (:ok? started))
      (let [rt (:runtime started)]
        (try
          ;; Settle the capacity/last-observation baseline.
          (runtime/tick! rt)
          (let [before (stored-state store)]
            (is (:ok? (runtime/submit-intent!
                       rt {:revision 1
                           :text "Improve reliability"
                           :received-at 1})))
            (let [result (runtime/tick! rt)
                  after (stored-state store)
                  snap (runtime/snapshot rt)]
              (is (= (:docket before) (:docket after)))
              (is (= (:links before) (:links after)))
              (is (= :STEWARD_GIT_OBSERVATION_FAILED
                     (get-in snap [:last-error :code])))
              (is (some #(= :STALE_OBSERVATION (:reason %))
                        (:skipped result)))
              (is (not-any? #(= :model/decide (:type %)) @model-calls))))
          (finally
            (runtime/stop! rt)
            (steward-store/close! store)))))))
