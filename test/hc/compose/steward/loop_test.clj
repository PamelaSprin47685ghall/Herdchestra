(ns hc.compose.steward.loop-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.steward :as steward]
            [hc.compose.steward.loop :as loop]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def sha-1 (apply str (repeat 40 "a")))
(def sha-2 (apply str (repeat 40 "b")))

(defn- sha-of
  [n]
  (format "%040x" (long (max 0 n))))

(defn- git-commit
  ([commit parents]
   (git-commit commit parents ["src/app.clj"]))
  ([commit parents changed-paths]
   {:kind :git-commit
    :commit commit
    :parents parents
    :changed-paths changed-paths
    :subject (str "commit " commit)}))

(defn- state-with-intent
  []
  (let [result (steward/observe-world
                (steward/initial-state)
                {:now 1
                 :intent {:revision 1
                          :text "Improve reliability"
                          :received-at 1}
                 :capacity {:slots-total 2
                            :slots-allocated 0
                            :min-spare 1
                            :missions []}
                 :git-commits [(git-commit sha-1 [])]})]
    (when-not (:ok? result)
      (throw (ex-info "observation fixture was refused"
                      {:error (:error result)})))
    (:state result)))

(defn- world
  [& {:as overrides}]
  (merge {:now 1000
          :git {:head sha-1 :new-commits [] :master-version 1}
          :intent nil
          :capacity {:rows [] :slots-total 2 :min-spare 1}
          :stale? false}
         overrides))

(defn- effect-types
  [result]
  (mapv :type (:effects result)))

(defn- effects-of
  [result type]
  (filterv #(= type (:type %)) (:effects result)))

(defn- candidate
  [evidence-id]
  {:candidate-id "cand-1"
   :title "Harden startup"
   :consequence 0.9
   :evidence-strength 0.9
   :dependency-cost 0.1
   :reversibility 0.8
   :opportunity-cost 0.1
   :demand 1
   :evidence-ids [evidence-id]
   :operator-aligned? true})

(defn- pulled-state
  []
  (:state (steward/evaluate-frontier (state-with-intent))))

(defn- admitted-state
  "A state with exactly one admitted Mission and its id."
  []
  (let [decided (steward/decide
                 (pulled-state)
                 {:request-id "req-admit"
                  :candidates [(candidate (str "git:" sha-1))]})]
    (when-not (:ok? decided)
      (throw (ex-info "fixture decision was refused"
                      {:error (:error decided)})))
    {:state (:state decided)
     :mission-id (first (get-in decided [:decision :admitted]))}))

(defn- plan-started-state
  "An admitted mission with a Plan session and a first plan-start timestamp."
  [started-at]
  (let [{:keys [state mission-id]} (admitted-state)
        linked (steward/link-mission state mission-id
                                     {:session-id "plan-1"
                                      :plan-started-at started-at})]
    (when-not (:ok? linked)
      (throw (ex-info "plan-started fixture was refused"
                      {:error (:error linked)})))
    {:state (:state linked)
     :mission-id mission-id}))

;; ---------------------------------------------------------------------------
;; Purity and boundaries

(deftest same-world-produces-the-same-tick
  (let [state (state-with-intent)
        w (world :git {:head sha-2
                       :new-commits [(git-commit sha-2 [sha-1]
                                                 ["src/next.clj"])]
                       :master-version 2})]
    (is (= (loop/tick state w) (loop/tick state w)))))

(deftest empty-intent-never-decides
  (let [result (loop/tick (steward/initial-state) (world))]
    (is (empty? (effects-of result :model/decide)))
    (is (empty? (effects-of result :model/meditate)))))

(deftest stale-world-still-ingests-evidence
  (let [state (state-with-intent)
        before (get-in state [:cognition :evidence-seq])
        result (loop/tick
                state
                (world :stale? true
                       :git {:head sha-2
                             :new-commits [(git-commit sha-2 [sha-1]
                                                       ["src/x.clj"])]
                             :master-version 2}))]
    (is (> (get-in result [:state :cognition :evidence-seq]) before))
    (is (= 1 (count (effects-of result :model/meditate))))
    (is (empty? (effects-of result :model/decide)))))

(deftest unchanged-evidence-never-meditates
  (let [result (loop/tick (state-with-intent) (world))]
    (is (empty? (effects-of result :model/meditate)))))

(deftest persist-is-last-and-only-for-a-change
  (let [first-result (loop/tick (state-with-intent) (world))
        settled (:state first-result)
        second-result (loop/tick settled (world))]
    (is (= :persist (peek (effect-types first-result))))
    (is (empty? (effects-of second-result :persist)))
    (is (= settled (:state second-result)))))

(deftest duplicate-evidence-is-a-no-op
  (let [state (state-with-intent)
        again (loop/tick
               state
               (world :git {:head sha-1
                            :new-commits [(git-commit sha-1 [])]
                            :master-version 2}))]
    (is (empty? (effects-of again :model/meditate)))
    (is (= (get-in state [:cognition :evidence-seq])
           (get-in again [:state :cognition :evidence-seq])))))

(deftest report-fingerprints-include-the-join-identity
  (let [base {:type :report/emit
              :mission-id "mission-1"
              :outcome :completed
              :swarm-id "sw1"
              :ff-commit "commit-a"}
        same (assoc base :at 999)
        other-swarm (assoc base :swarm-id "sw2")
        other-commit (assoc base :ff-commit "commit-b")]
    (is (= (loop/effect-fingerprint base)
           (loop/effect-fingerprint same)))
    (is (not= (loop/effect-fingerprint base)
              (loop/effect-fingerprint other-swarm)))
    (is (not= (loop/effect-fingerprint base)
              (loop/effect-fingerprint other-commit)))))

(deftest plan-stall-boundary-and-idempotence
  (let [{:keys [state mission-id]} (plan-started-state 0)
        not-yet (loop/tick state (world :now 599999))
        stalled (loop/tick state (world :now 600001))
        again (loop/tick (:state stalled) (world :now 600001))
        stall-effects
        (filterv #(= :MISSION_PLAN_STALLED (:code %)) (:effects stalled))
        diagnostic (first stall-effects)]
    (is (not-any? #(= :MISSION_PLAN_STALLED (:code %)) (:effects not-yet)))
    (is (= 1 (count stall-effects)))
    (is (= :diagnostic (:type diagnostic)))
    (is (= mission-id (:mission-id diagnostic)))
    (is (= 600001 (:stalled-ms diagnostic)))
    (is (= 600001 (:at diagnostic)))
    (is (= 600001 (get-in stalled [:state :stall-reported mission-id])))
    (is (= :admitted
           (get-in stalled [:state :docket :missions mission-id :status])))
    (is (nil? (get-in stalled [:state :links mission-id :plan-status])))
    (is (not-any? #(= :MISSION_PLAN_STALLED (:code %)) (:effects again)))
    (is (not-any? #(= :plan/start (:type %)) (:effects stalled)))
    (is (not-any? #(= :fleet/fork (:type %)) (:effects stalled)))))

(deftest plan-start-stamps-only-the-first-attempt
  (let [{:keys [state mission-id]} (admitted-state)
        linked (:state (steward/link-mission state mission-id
                                             {:plan-started-at 5}))
        result (loop/tick linked (world :now 1000))]
    (is (some #(= :plan/start (:type %)) (:effects result)))
    (is (= 5 (get-in result [:state :links mission-id
                            :plan-started-at])))))

;; ---------------------------------------------------------------------------
;; Plan and fork

(deftest admitted-mission-emits-exactly-one-plan-start
  (let [{:keys [state mission-id]} (admitted-state)
        result (loop/tick state (world))
        starts (effects-of result :plan/start)]
    (is (string? mission-id))
    (is (= 1 (count starts)))
    (is (= mission-id (:mission-id (first starts))))
    (let [linked (:state (steward/link-mission state mission-id
                                               {:session-id "session-1"}))
          again (loop/tick linked (world))]
      (is (empty? (effects-of again :plan/start))))))

(deftest plan-ready-link-emits-one-fork
  (let [{:keys [state mission-id]} (admitted-state)
        slices [{:slice-id "s1" :goal "a" :done-when "green"}
                {:slice-id "s2" :goal "b" :done-when "green"}]
        ready (:state (steward/link-mission state mission-id
                                            {:plan-status :plan-ready
                                             :session-id "session-1"
                                             :slices slices}))
        result (loop/tick ready (world))
        forks (effects-of result :fleet/fork)]
    (is (= 1 (count forks)))
    (is (= mission-id (:mission-id (first forks))))
    (is (= slices (:slices (first forks)))))
  (let [{:keys [state mission-id]} (admitted-state)
        forked (:state (steward/link-mission state mission-id
                                             {:plan-status :plan-ready
                                              :slices [{:slice-id "s1"}]
                                              :swarm-ids ["swarm-1"]}))
        result (loop/tick forked (world))]
    (is (empty? (effects-of result :fleet/fork)))))

(deftest plan-ready-without-slices-is-diagnosed
  (let [{:keys [state mission-id]} (admitted-state)
        ready (:state (steward/link-mission state mission-id
                                            {:plan-status :plan-ready}))
        result (loop/tick ready (world))
        diagnostics (effects-of result :diagnostic)]
    (is (empty? (effects-of result :fleet/fork)))
    (is (some #(= :MISSION_SLICES_MISSING (:code %)) diagnostics))
    (is (some #(= :fork (:stage %)) (:skipped result)))))

;; ---------------------------------------------------------------------------
;; Join results

(deftest join-results-map-to-terminal-statuses
  (testing "joined becomes completed and emits the report"
    (let [{:keys [state mission-id]} (admitted-state)
          active (:state (steward/transition-mission state mission-id
                                                     :active "started"))
          result (loop/tick
                  active
                  (world :fleet
                         {:results
                          [{:mission-id mission-id
                            :result {:swarm-id "sw1"
                                     :slice-id "s1"
                                     :report "done"
                                     :ff-commit sha-2}}]}))
          reports (effects-of result :report/emit)]
      (is (= :completed
             (get-in result [:state :docket :missions mission-id :status])))
      (is (= [:completed] (mapv :outcome reports)))
      (is (= "done" (:report (first reports))))
      (is (= sha-2 (:ff-commit (first reports))))))

  (testing "fleet/error becomes failed"
    (let [{:keys [state mission-id]} (admitted-state)
          planning (:state (steward/transition-mission state mission-id
                                                       :planning "planned"))
          result (loop/tick
                  planning
                  (world :fleet
                         {:results
                          [{:mission-id mission-id
                            :result {:fleet/error {:code :BOOM
                                                   :swarm-id "sw1"}}}]}))
          reports (effects-of result :report/emit)]
      (is (= :failed
             (get-in result [:state :docket :missions mission-id :status])))
      (is (= [:failed] (mapv :outcome reports)))
      (is (= :BOOM (get-in (first reports) [:error :code])))))

  (testing "abandoned becomes abandoned"
    (let [{:keys [state mission-id]} (admitted-state)
          result (loop/tick
                  state
                  (world :fleet
                         {:results
                          [{:mission-id mission-id
                            :result {:status :abandoned}}]}))
          reports (effects-of result :report/emit)]
      (is (= :abandoned
             (get-in result [:state :docket :missions mission-id :status])))
      (is (= [:abandoned] (mapv :outcome reports)))))

  (testing "an unknown mission is diagnosed, never invented"
    (let [result (loop/tick (state-with-intent)
                            (world :fleet
                                   {:results
                                    [{:mission-id "mission-ghost"
                                      :result {:ff-commit sha-2}}]}))]
      (is (some #(= :UNKNOWN_MISSION (:reason %))
                (:skipped result)))
      (is (not (contains? (get-in result [:state :docket :missions])
                          "mission-ghost"))))))

;; ---------------------------------------------------------------------------
;; Invariants

(deftest invariants-reject-a-link-to-a-missing-mission
  (let [{:keys [state]} (admitted-state)
        valid (loop/check-invariants state)
        bad (loop/check-invariants
             (assoc-in state [:links "mission-ghost"]
                       {:mission-id "mission-ghost" :status :admitted}))
        bad-schema (loop/check-invariants
                    (assoc-in state [:docket :schema-version] 2))]
    (is (:ok? valid))
    (is (false? (:ok? bad)))
    (is (some #(= :LINKS_UNKNOWN_MISSION (:code %)) (:errors bad)))
    (is (= ["mission-ghost"]
           (get-in (first (filter #(= :LINKS_UNKNOWN_MISSION (:code %))
                                  (:errors bad)))
                   [:details :mission-ids])))
    (is (some #(= :INVALID_DOCKET_SCHEMA (:code %)) (:errors bad-schema)))))

;; ---------------------------------------------------------------------------
;; Properties

(def gen-commit
  (gen/fmap (fn [[n paths]]
              {:kind :git-commit
               :commit (sha-of n)
               :parents []
               :changed-paths (if (seq paths)
                                (mapv #(str "src/f" (mod % 9) ".clj") paths)
                                ["src/app.clj"])
               :subject (str "commit " n)})
            (gen/tuple gen/nat (gen/vector gen/nat 0 3))))

(def gen-fleet-row
  (gen/fmap (fn [[status mission-id]]
              (cond-> {:status status}
                mission-id (assoc :mission-id (str "mission-" mission-id))))
            (gen/tuple (gen/elements [:running :queued :joined :abandoned
                                      :failed :unknown nil])
                       (gen/one-of [(gen/return nil)
                                    (gen/choose 1 4)]))))

(def gen-world
  (gen/fmap
   (fn [[commits rows stale? intent?]]
     (cond-> {:now 1000
              :git {:head (sha-of 99)
                    :new-commits commits
                    :master-version 2}
              :capacity {:rows rows :slots-total 4 :min-spare 1}
              :stale? stale?}
       intent? (assoc :intent {:revision 1
                               :text "Improve reliability"
                               :received-at 1})))
   (gen/tuple (gen/vector gen-commit 0 6)
              (gen/vector gen-fleet-row 0 6)
              gen/boolean
              gen/boolean)))

(def ^:private valid-effect-types
  #{:model/meditate :model/decide :plan/start :fleet/fork
    :report/emit :diagnostic :persist})

(defn- index-of
  [coll value]
  (first (keep-indexed (fn [index item]
                         (when (= item value) index))
                       coll)))

(defspec tick-keeps-invariants-and-orders-effects 60
  (prop/for-all [world gen-world]
    (let [result (loop/tick (steward/initial-state) world)
          state (:state result)
          effects (:effects result)
          types (mapv :type effects)
          persists (filterv #(= :persist (:type %)) effects)
          meditate-at (index-of types :model/meditate)
          decide-at (index-of types :model/decide)]
      (and (:ok? (loop/check-invariants state))
           (<= (count persists) 1)
           (or (empty? persists) (= :persist (peek types)))
           (every? valid-effect-types types)
           (or (nil? meditate-at)
               (nil? decide-at)
               (< meditate-at decide-at))
           (every? (fn [effect]
                     (or (not= :persist (:type effect))
                         (map? (:state effect))))
                   effects)))))

(defspec sequential-ticks-keep-invariants 40
  (prop/for-all [seeds (gen/vector gen/nat 1 8)
                 rows (gen/vector gen-fleet-row 0 4)]
    (let [states
          (reductions
           (fn [state [index seed]]
             (:state
              (loop/tick
               state
               {:now (* 1000 (inc index))
                :git {:head (sha-of seed)
                      :new-commits
                      [(git-commit
                        (sha-of seed)
                        (if (zero? index)
                          []
                          [(sha-of (nth seeds (dec index)))]))]
                      :master-version (inc index)}
                :capacity {:rows rows :slots-total 4 :min-spare 1}
                :stale? false})))
           (steward/initial-state)
           (map-indexed vector seeds))]
      (every? #(:ok? (loop/check-invariants %)) states))))

(defspec stall-ledger-stays-bounded 30
  (prop/for-all [extra (gen/choose 0 44)]
    (let [{:keys [state mission-id]} (plan-started-state 0)
          old-count (+ 256 extra)
          reported (into {}
                         (map (fn [index]
                                [(str "old-" index) index])
                              (range old-count)))
          prepared (assoc state
                          :stall-reported reported
                          :stall-ledger-dropped 0)
          result (loop/tick prepared (world :now 1000000))
          ledger (get-in result [:state :stall-reported])
          effects (:effects result)]
      (and (= 1 (count (filter #(= :MISSION_PLAN_STALLED (:code %))
                               effects)))
           (= 256 (count ledger))
           (contains? ledger mission-id)
           (not (contains? ledger "old-0"))
           (= (inc extra)
              (get-in result [:state :stall-ledger-dropped]))
           (not-any? #(= :plan/start (:type %)) effects)
           (not-any? #(= :fleet/fork (:type %)) effects)))))
