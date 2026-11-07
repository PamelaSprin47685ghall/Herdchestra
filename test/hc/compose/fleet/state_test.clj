(ns hc.compose.fleet.state-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.state :as state]))

(defn- slice
  ([id] (slice id []))
  ([id deps]
   {:slice-id id
    :goal (str "goal " id)
    :inputs []
    :depends-on deps
    :done-when "done"}))

(defn- new-fleet
  []
  (state/new-state {:mission-id "mission"
                    :worktree-root "/tmp/hc-fleet-test"
                    :retention-ms 1000}))

(defn- forked
  [slices]
  (let [result (state/fork (new-fleet) slices)]
    (is (:ok? result) (str (:error result)))
    result))

(defn- started
  [slices]
  (let [forked (forked slices)
        ids (get-in forked [:receipt :swarm-ids])
        ready (:state (state/mark-worktree-ready (:state forked) ids))]
    (:state (state/start-ready ready))))

(deftest fork-assigns-one-distinct-swarm-and-worktree-per-slice
  (let [result (forked [(slice "a") (slice "b")])
        swarm-ids (get-in result [:receipt :swarm-ids])
        swarms (mapv #(get-in (:state result) [:swarms %]) swarm-ids)]
    (is (= ["swarm-1" "swarm-2"] swarm-ids))
    (is (= 2 (count (set (map :workspace swarms)))))
    (is (every? #(= :queued (:status %)) swarms)
        "a worktree is not ready until the caller verifies it")))

(deftest dependency-slices-wait-for-a-joined-result
  (let [state-value (started [(slice "a")
                              (slice "b" ["a"])
                              (slice "c")])
        status (into {} (map (juxt :slice-id :status)
                             (vals (:swarms state-value))))]
    (is (= {"a" :running "b" :queued "c" :running} status))))

(deftest completion-queue-is-fifo-not-race-order
  (let [state-value (started [(slice "a") (slice "b") (slice "c")])
        state-value (:state (state/complete-swarm state-value "swarm-3" "r3" {:board-version 1}))
        state-value (:state (state/complete-swarm state-value "swarm-1" "r1" {:board-version 1}))
        state-value (:state (state/complete-swarm state-value "swarm-2" "r2" {:board-version 1}))]
    (is (= ["swarm-3" "swarm-1" "swarm-2"] (:completion-queue state-value)))
    (is (= {:status :ready :swarm-id "swarm-3"} (state/join-state state-value)))
    (let [joined (:state (state/joined-swarm state-value "swarm-3"
                                             {:ff-commit "ff-3" :rebase-rounds 1}))]
      (is (= ["swarm-1" "swarm-2"] (:completion-queue joined)))
      (is (= "r3" (get-in joined [:swarms "swarm-3" :report]))))))

(deftest join-blocks-then-returns-none
  (let [state-value (started [(slice "a")])]
    (is (= :blocked (:status (state/join-state state-value))))
    (let [completed (:state (state/complete-swarm state-value "swarm-1" "report" {:board-version 7}))]
      (is (= :ready (:status (state/join-state completed))))
      (let [joined (:state (state/joined-swarm completed "swarm-1"
                                               {:ff-commit "ff" :rebase-rounds 0}))]
        (is (= :none (:status (state/join-state joined))))))))

(deftest a-head-held-by-an-integrator-is-blocked-not-ready
  (let [state-value (started [(slice "a")])
        completed (:state (state/complete-swarm state-value "swarm-1"
                                                "report" {:board-version 3}))
        held (:state (state/set-integrating completed "swarm-1" true))]
    (is (= {:status :blocked :integrating ["swarm-1"]}
           (state/join-state held)))
    (let [cleared (:state (state/set-integrating held "swarm-1" false))]
      (is (= {:status :ready :swarm-id "swarm-1"}
             (state/join-state cleared))))))

(deftest a-merged-result-whose-write-failed-stays-ready-for-retry
  (let [state-value (started [(slice "a") (slice "b" ["a"])])
        completed (:state (state/complete-swarm state-value "swarm-1"
                                                "report" {:board-version 3}))
        held (:state (state/set-integrating completed "swarm-1" true))
        recovered (state/mark-join-recovery held "swarm-1"
                                            {:ff-commit "ff-real"
                                             :rebase-rounds 1
                                             :persist-error {:code :DISK_FULL}
                                             :now 42})]
    (is (:ok? recovered))
    (is (= ["swarm-1"] (get-in recovered [:state :completion-queue]))
        "the queued result stays the FIFO head")
    (is (true? (get-in recovered [:state :swarms "swarm-1" :integrating?])))
    (is (= {:status :ready :swarm-id "swarm-1"}
           (state/join-state (:state recovered)))
        "a merged head is ready for the write-only retry")
    (is (= :SWARM_INTEGRATING
           (get-in (state/abandon-swarm (:state recovered) "swarm-1"
                                        {:reason :stale} {:now 43})
                   [:error :code])))
    (is (= :SWARM_INTEGRATING
           (get-in (state/fail-swarm (:state recovered) "swarm-1"
                                     {:code :STALE} {:now 43})
                   [:error :code])))
    (let [cleared (:state (state/set-integrating (:state recovered)
                                                 "swarm-1" false))]
      (is (= :SWARM_JOIN_PENDING
             (get-in (state/abandon-swarm cleared "swarm-1"
                                          {:reason :stale} {:now 44})
                     [:error :code])))
      (is (= :SWARM_JOIN_PENDING
             (get-in (state/fail-swarm cleared "swarm-1"
                                       {:code :STALE} {:now 44})
                     [:error :code])))
      (let [joined (:state (state/joined-swarm cleared "swarm-1"
                                               {:ff-commit "ff-real"
                                                :rebase-rounds 1}))]
        (is (= :joined (get-in joined [:swarms "swarm-1" :status])))
        (is (nil? (get-in joined [:swarms "swarm-1" :join-recovery])))
        (is (= :running (get-in joined [:swarms "swarm-2" :status])))))
    (let [forced (state/fail-swarm (:state recovered) "swarm-1"
                                   {:code :STALE} {:now 45 :force? true})]
      (is (:ok? forced))
      (is (nil? (get-in forced [:state :swarms "swarm-1" :join-recovery]))
          "a forced terminal transition ends the pending join")
      (is (= [] (get-in forced [:state :completion-queue]))))))

(deftest joining-a-dependency-starts-its-dependents
  (let [state-value (started [(slice "a") (slice "b" ["a"])])
        completed (:state (state/complete-swarm state-value "swarm-1" "report" {:board-version 1}))
        joined (state/joined-swarm completed "swarm-1"
                                   {:ff-commit "ff" :rebase-rounds 1})]
    (is (= ["swarm-2"] (get-in joined [:receipt :started])))
    (is (= :running (get-in joined [:state :swarms "swarm-2" :status])))))

(deftest abandon-fails-only-its-dependents-and-leaves-siblings-running
  (let [state-value (started [(slice "a")
                              (slice "b")
                              (slice "c" ["a"])])
        abandoned (state/abandon-swarm state-value "swarm-1"
                                       {:reason :unresolvable-conflict}
                                       {:now 100})]
    (is (:ok? abandoned))
    (is (= :abandoned (get-in abandoned [:state :swarms "swarm-1" :status])))
    (is (= :failed (get-in abandoned [:state :swarms "swarm-3" :status])))
    (is (= :running (get-in abandoned [:state :swarms "swarm-2" :status]))
        "an unrelated sibling is never cancelled")
    (is (= [] (:completion-queue (:state abandoned))))
    (is (= :blocked (:status (state/join-state (:state abandoned)))))
    (is (= ["swarm-3"] (get-in abandoned [:receipt :failed-dependents])))))

(deftest abandoning-a-queued-result-withdraws-it-without-a-fake-join
  (let [state-value (started [(slice "a") (slice "b")])
        completed (:state (state/complete-swarm state-value "swarm-1" "report" {:board-version 1}))
        abandoned (state/abandon-swarm completed "swarm-1" {:reason :stale} {:now 10})]
    (is (= [] (:completion-queue (:state abandoned))))
    (is (= :abandoned (get-in abandoned [:state :swarms "swarm-1" :status])))
    (is (nil? (state/swarm-result (:state abandoned) "swarm-1")))
    (is (= :blocked (:status (state/join-state (:state abandoned)))))))

(deftest a-later-fork-can-depend-on-a-joined-slice
  (let [state-value (started [(slice "a")])
        state-value (:state (state/complete-swarm state-value "swarm-1" "r" {:board-version 1}))
        state-value (:state (state/joined-swarm state-value "swarm-1"
                                                {:ff-commit "ff" :rebase-rounds 0}))
        forked (state/fork state-value [(slice "b" ["a"])])
        ids (get-in forked [:receipt :swarm-ids])
        ready (:state (state/mark-worktree-ready (:state forked) ids))
        started-state (:state (state/start-ready ready))]
    (is (:ok? forked))
    (is (= :running (get-in started-state [:swarms "swarm-2" :status])))))

(deftest a-fork-cannot-depend-on-an-abandoned-slice
  (let [state-value (started [(slice "a")])
        state-value (:state (state/abandon-swarm state-value "swarm-1"
                                                 {:reason :stale} {:now 1}))
        forked (state/fork state-value [(slice "b" ["a"])])]
    (is (false? (:ok? forked)))
    (is (= :DEPENDENCY_TERMINAL (get-in forked [:error :code])))))

(deftest all-abandoned-results-return-no-results
  (let [state-value (started [(slice "a") (slice "b")])
        state-value (:state (state/abandon-swarm state-value "swarm-1"
                                                 {:reason :x} {:now 1}))
        state-value (:state (state/abandon-swarm state-value "swarm-2"
                                                 {:reason :y} {:now 2}))]
    (is (= :no-results (:status (state/join-state state-value))))))

(deftest some-joined-results-end-with-none
  (let [state-value (started [(slice "a") (slice "b")])
        state-value (:state (state/complete-swarm state-value "swarm-1" "r" {:board-version 1}))
        state-value (:state (state/joined-swarm state-value "swarm-1"
                                                {:ff-commit "ff" :rebase-rounds 0}))
        state-value (:state (state/abandon-swarm state-value "swarm-2"
                                                 {:reason :x} {:now 1}))]
    (is (= :none (:status (state/join-state state-value))))))

(deftest complete-is-single-shot-and-requires-a-board-version
  (let [state-value (started [(slice "a")])
        once (:state (state/complete-swarm state-value "swarm-1" "report" {:board-version 1}))
        twice (state/complete-swarm once "swarm-1" "again" {:board-version 1})
        no-board (state/complete-swarm state-value "swarm-1" "report" {})]
    (is (= :ALREADY_COMPLETED (get-in twice [:error :code])))
    (is (= :INVALID_BOARD_VERSION (get-in no-board [:error :code])))))

(deftest joined-rows-inline-the-full-report
  (let [report (apply str (repeat 5000 "x"))
        state-value (started [(slice "a")])
        completed (:state (state/complete-swarm state-value "swarm-1" report {:board-version 1}))
        joined (:state (state/joined-swarm completed "swarm-1"
                                           {:ff-commit "ff" :rebase-rounds 2}))
        row (first (filter #(= "swarm-1" (:swarm-id %)) (state/fleet-rows joined)))]
    (is (= :joined (:status row)))
    (is (= report (:report row)))
    (is (= "ff" (:ff-commit row)))))

(deftest a-later-all-abandoned-fork-returns-no-results
  (let [first-fork (started [(slice "a")])
        joined (-> first-fork
                   (state/complete-swarm "swarm-1" "first" {:board-version 1})
                   :state
                   (state/joined-swarm "swarm-1"
                                       {:ff-commit "ff-1" :rebase-rounds 0})
                   :state)
        forked (state/fork joined [(slice "b")])
        ids (get-in forked [:receipt :swarm-ids])
        ready (:state (state/mark-worktree-ready (:state forked) ids))
        running (:state (state/start-ready ready))
        abandoned (:state (state/abandon-swarm running "swarm-2"
                                                {:reason :stale} {:now 2}))]
    (is (= :joined (get-in abandoned [:swarms "swarm-1" :status])))
    (is (= :abandoned (get-in abandoned [:swarms "swarm-2" :status])))
    (is (= :no-results (:status (state/join-state abandoned)))
        "the latest fork produced no joined result")))

(deftest terminal-abandon-clears-report-and-completion
  (let [completed (-> (started [(slice "a")])
                      (state/complete-swarm "swarm-1" "full report"
                                            {:board-version 3})
                      :state)
        abandoned (:state (state/abandon-swarm completed "swarm-1"
                                               {:reason :stale} {:now 4}))
        swarm (get-in abandoned [:swarms "swarm-1"])]
    (is (nil? (:report swarm)))
    (is (nil? (:board-version swarm)))
    (is (nil? (:completion-seq swarm)))
    (is (= [] (:completion-queue abandoned)))))

(deftest fleet-rows-distinguish-current-and-swarm-master-version
  (let [joined (-> (started [(slice "a") (slice "b")])
                   (state/complete-swarm "swarm-1" "first" {:board-version 1})
                   :state
                   (state/joined-swarm "swarm-1"
                                       {:ff-commit "ff-1" :rebase-rounds 0})
                   :state)
        rows (into {} (map (juxt :swarm-id identity))
                   (state/fleet-rows joined))]
    (is (= 1 (get-in rows ["swarm-1" :master-version])))
    (is (= 1 (get-in rows ["swarm-1" :swarm-master-version])))
    (is (= 1 (get-in rows ["swarm-2" :master-version])))
    (is (= 0 (get-in rows ["swarm-2" :swarm-master-version])))))

(deftest event-log-keeps-a-bounded-tail
  (with-redefs [state/default-max-events 3]
    (let [result (forked [(slice "a") (slice "b") (slice "c") (slice "d")])
          events (:events (:state result))]
      (is (= 3 (count events)))
      (is (= [2 3 4] (mapv :event-id events))))))

(deftest gc-collects-a-terminal-managed-path-without-a-ready-flag
  (let [abandoned (-> (started [(slice "a")])
                      (state/abandon-swarm "swarm-1" {:reason :stale} {:now 1})
                      :state
                      (assoc-in [:swarms "swarm-1" :worktree-ready?] false))]
    (is (= ["swarm-1"]
           (mapv :swarm-id (state/gc-candidates abandoned 2000))))))

(defspec fifo-join-follows-completion-order 50
  (prop/for-all [order (gen/shuffle (vec (range 1 8)))]
    (let [slices (mapv #(slice (str "s" %)) (range 1 8))
          state-value (started slices)
          completed (reduce (fn [s n]
                              (:state (state/complete-swarm
                                       s (str "swarm-" n)
                                       (str "report-" n)
                                       {:board-version n})))
                            state-value order)
          expected (mapv #(str "s" %) order)
          joined (reduce (fn [[s results] _]
                           (let [head (:swarm-id (state/join-state s))
                                 next-state (:state (state/joined-swarm
                                                     s head
                                                     {:ff-commit (str "ff-" head)
                                                      :rebase-rounds 1}))]
                             [next-state (conj results (:slice-id
                                                       (get-in next-state
                                                               [:swarms head])))]))
                         [completed []]
                         order)]
      (and (= expected (second joined))
           (= :none (:status (state/join-state (first joined))))))))

(defspec abandoning-one-independent-slice-never-touches-another 50
  (prop/for-all [n (gen/choose 1 8)
                 target (gen/choose 1 8)]
    (let [target (min target n)
          slices (mapv #(slice (str "s" %)) (range 1 (inc n)))
          state-value (started slices)
          abandoned (state/abandon-swarm state-value (str "swarm-" target)
                                         {:reason :test} {:now 1})
          statuses (into {} (map (juxt :swarm-id :status)
                                 (vals (get-in abandoned [:state :swarms]))))]
      (and (:ok? abandoned)
           (= :abandoned (get statuses (str "swarm-" target)))
           (every? (fn [[id status]]
                     (or (= id (str "swarm-" target))
                         (= status :running)))
                   statuses)
           (= [] (get-in abandoned [:state :completion-queue]))))))
