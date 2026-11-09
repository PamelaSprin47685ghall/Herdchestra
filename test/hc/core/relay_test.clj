(ns hc.core.relay-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.relay.barrier :as barrier]
            [hc.core.relay.core :as relay]
            [hc.core.relay.ledger :as ledger]
            [hc.core.relay.tenure :as tenure]))

;; ---------------------------------------------------------------------------
;; Fixtures and helpers

(def ^:private call-counter (atom 0))

(defn- call-id []
  (str "call-" (swap! call-counter inc)))

(defn- fresh
  ([] (fresh {}))
  ([opts]
   (relay/new-state
    (merge {:session-id "session-test"
            :user-input "Build the requested feature."
            :draft {:path "plans/test.md"
                    :content "Draft body."
                    :version 1
                    :source :program
                    :user-edited? false}
            :successor-hint "Independently inspect the workspace."}
           opts))))

(defn- review!
  ([state lane tenure-id findings]
   (review! state lane tenure-id findings {}))
  ([state lane tenure-id findings extra]
   (relay/review state
                 (merge {:call-id (call-id)
                         :lane lane
                         :tenure-id tenure-id
                         :findings findings}
                        extra))))

(defn- ok!
  [result]
  (is (:ok? result) (pr-str (:error result)))
  result)

(defn- error-code
  [result]
  (get-in result [:error :code]))

(defn- outcome
  [result]
  (get-in result [:receipt :outcome]))

(defn- quiescent-board
  []
  {:unresolved-tasks 0
   :unhandled-inputs 0
   :user-inputs []
   :inflight-actions 0
   :holder-retired? true
   :all-root-tasks-done? true
   :ff-ready? true
   :applying 0
   :running 0
   :uncertain 0
   :call-children 0
   :report-pending 0
   :pending-rebase 0
   :holds-write-lock? false
   :unresolved-token-tasks 0})

(defn- drive-plan-to-pending!
  [state]
  (let [plan-id (relay/current-tenure-id state :plan)
        r1 (ok! (review! state :plan plan-id [{:key "obligation" :value "work"}]))
        r2 (ok! (review! (:state r1) :plan plan-id
                         [{:key "obligation" :value nil}]))
        plan-id' (relay/current-tenure-id (:state r2) :plan)
        r3 (ok! (review! (:state r2) :plan plan-id' []))
        r4 (ok! (review! (:state r3) :plan plan-id' []))
        r5 (ok! (review! (:state r4) :plan plan-id' []))
        state' (:state r5)]
    (is (= :pending (get-in state' [:lanes :plan :milestone])))
    state'))

(defn- start-execution!
  [state]
  (let [plan-id (relay/current-tenure-id state :plan)
        result (ok! (review! state :plan plan-id []))]
    (is (= :execution-started (outcome result)))
    result))

(defn- start-swarm!
  [state findings]
  (let [execution-id (relay/current-tenure-id state :execution)
        result (ok! (review! state :execution execution-id findings))]
    (is (= :swarm-started (outcome result)))
    result))

(defn- start-swarm-on!
  [state tenure-id findings]
  (ok! (relay/review state
                     {:call-id (call-id)
                      :lane :execution
                      :tenure-id tenure-id
                      :findings findings})))

(defn- root-tasks
  ([state]
   (root-tasks state (relay/current-tenure-id state :execution)))
  ([state tenure-id]
   (:root-tasks (relay/tenure-by-id state tenure-id))))

(defn- sole-exec
  [state]
  (relay/current-tenure state :execution))

(defn- exec
  [state tenure-id]
  (relay/tenure-by-id state tenure-id))

(defn- execution-barriers
  [state]
  (filter #(= :execution (:lane %)) (:barriers state)))

(defn- finish-root!
  ([state task]
   (finish-root! state task nil))
  ([state task board]
   (relay/finish-root-task
    state
    {:lane :execution
     :tenure-id (relay/current-tenure-id state :execution)
     :task-id (:task-id task)
     :board board})))

(defn- assert-valid
  [state]
  (let [check (relay/check-invariants state)]
    (is (:ok? check) (pr-str (:violations check))))
  state)

;; ---------------------------------------------------------------------------
;; Ledger

(deftest ledger-semantics-test
  (testing "empty vector keeps an existing ledger unchanged"
    (let [before {"a" "1" "b" "2"}
          after (ledger/apply-findings before [])]
      (is (= before after))))

  (testing "upsert, overwrite, and release"
    (let [findings (ledger/normalize-findings
                    [{:key "a" :value "new"}
                     {:key "b" :value nil}
                     {:key "c" :value "added"}])
          after (ledger/apply-findings {"a" "old" "b" "gone"} (:findings findings))]
      (is (:ok? findings))
      (is (= {"a" "new" "c" "added"} after))))

  (testing "duplicate keys apply in order"
    (let [findings (ledger/normalize-findings
                    [{:key "a" :value "first"}
                     {:key "a" :value "last"}])
          after (ledger/apply-findings {} (:findings findings))]
      (is (= {"a" "last"} after))))

  (testing "invalid findings are rejected with the offending index"
    (doseq [bad [[{:key "" :value "v"}]
                 [{:key "k"}]
                 [{:value "v"}]
                 [{:key "k" :value ""}]
                 [{:key "k" :value 42}]
                 [{:key 7 :value "v"}]
                 :not-a-collection]]
      (let [result (ledger/normalize-findings bad)]
        (is (false? (:ok? result)) (pr-str bad))
        (is (= :INVALID_FINDINGS (get-in result [:error :code]))))))

  (testing "changes report additions and removals"
    (is (= {:added {"b" "2"} :removed {"a" "1"}}
           (ledger/changes {"a" "1" "b" "old"} {"b" "2"})))))

;; ---------------------------------------------------------------------------
;; Plan lane

(deftest plan-ledger-updates-do-not-start-a-swarm-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        updated (ok! (review! state :plan plan-id
                              [{:key "a" :value "alpha"}
                               {:key "b" :value "beta"}]))
        state' (:state updated)]
    (is (= :ledger-updated (outcome updated)))
    (is (= {"a" "alpha" "b" "beta"} (relay/plan-ledger state')))
    (is (nil? (relay/current-tenure state' :execution)))
    (is (= :open (get-in state' [:lanes :plan :review-window])))
    (is (empty? (:effects updated)))
    (assert-valid state')

    (testing "an empty review keeps the non-empty ledger"
      (let [unchanged (ok! (review! state' :plan plan-id []))]
        (is (= :ledger-unchanged (outcome unchanged)))
        (is (= {"a" "alpha" "b" "beta"} (relay/plan-ledger (:state unchanged))))))

    (testing "releasing the last key relays the Plan tenure"
      (let [cleared (ok! (review! state' :plan plan-id
                                  [{:key "a" :value nil}
                                   {:key "b" :value nil}]))
            state'' (:state cleared)
            successor-id (relay/current-tenure-id state'' :plan)]
        (is (= :relayed (outcome cleared)))
        (is (not= plan-id successor-id))
        (is (contains? (:retired-tenure-ids state'') plan-id))
        (is (= {} (relay/plan-ledger state'')))
        (is (nil? (relay/current-tenure state'' :execution)))
        (is (= :S1 (get-in state'' [:lanes :plan :milestone])))
        (is (= :open (get-in state'' [:lanes :plan :review-window])))
        (is (= 1 (count (:barriers state''))))
        (is (barrier/context-keys-valid?
             (get-in state'' [:barriers 0 :context])))
        (is (not (contains? (get-in state'' [:barriers 0 :context]) :ledger)))
        (assert-valid state'')))))

(deftest plan-initial-empty-review-asks-once-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        asked (ok! (review! state :plan plan-id []))
        state' (:state asked)]
    (is (= :ask (outcome asked)))
    (is (= "我需要干什么？" (get-in asked [:receipt :question])))
    (is (false? (get-in asked [:receipt :reviewed?])))
    (is (:awaiting-user? (relay/current-tenure state' :plan)))
    (is (:first-review? (relay/current-tenure state' :plan)))
    (is (nil? (relay/nudge-message state' :plan)))

    (testing "a second review before resume is refused"
      (is (= :AWAITING_USER
             (error-code (review! state' :plan plan-id [])))))

    (testing "resume then a second empty review hands off to a successor"
      (let [resumed (ok! (relay/resume state' {:lane :plan :tenure-id plan-id}))
            relayed (ok! (review! (:state resumed) :plan plan-id []))
            state'' (:state relayed)
            successor-id (relay/current-tenure-id state'' :plan)]
        (is (= :resumed (outcome resumed)))
        (is (= :relayed (outcome relayed)))
        (is (not= plan-id successor-id))
        (is (contains? (:retired-tenure-ids state'') plan-id))

        (testing "the successor's empty first review promotes S1 to S2"
          (let [promoted (ok! (review! state'' :plan successor-id []))
                state''' (:state promoted)]
            (is (= :promoted (outcome promoted)))
            (is (= :S1 (get-in promoted [:receipt :from-milestone])))
            (is (= :S2 (get-in promoted [:receipt :to-milestone])))
            (is (= :S2 (get-in state''' [:lanes :plan :milestone])))
            (is (= successor-id (relay/current-tenure-id state''' :plan)))
            (is (:first-review? (relay/current-tenure state''' :plan)))
            (assert-valid state''')))))))

(deftest plan-promotes-through-pending-and-hands-off-test
  (let [state (drive-plan-to-pending! (fresh))
        plan-id (relay/current-tenure-id state :plan)
        started (start-execution! state)
        state' (:state started)
        execution-id (relay/current-tenure-id state' :execution)]
    (is (= :execution-started (outcome started)))
    (is (= :pending (get-in state' [:lanes :plan :milestone])))
    (is (= plan-id (relay/current-tenure-id state' :plan)))
    (is (= :active (get-in state' [:lanes :plan :status])))
    (is (= :execution (:milestone (sole-exec state'))))
    (is (:initial? (relay/current-tenure state' :execution)))
    (is (= {} (relay/execution-ledger state')))
    (is (= "Draft body." (get-in state' [:draft :content])))
    (is (= "Draft body." (get-in started [:effects 0 :draft])))
    (is (= 11 (get-in started [:receipt :draft-length])))
    (assert-valid state')

    (testing "pending Plan refuses non-empty findings"
      (is (= :NONEMPTY_FINDINGS_AT_PENDING
             (error-code (review! state' :plan plan-id
                                  [{:key "late" :value "work"}])))))

    (testing "a second distinct handoff creates an independent execution tenure"
      (let [second (ok! (review! state' :plan plan-id []))
            state'' (:state second)
            execution-id-2 (:execution-tenure-id (:receipt second))]
        (is (= :execution-started (outcome second)))
        (is (not= execution-id execution-id-2))
        (is (= 2 (count (relay/execution-tenures state''))))
        (is (:initial? (exec state'' execution-id)))
        (is (:initial? (exec state'' execution-id-2)))
        (is (= {} (relay/ledger-for state'' execution-id)))
        (is (= {} (relay/ledger-for state'' execution-id-2)))
        (is (= plan-id (relay/current-tenure-id state'' :plan)))
        (assert-valid state'')

        (testing "the first handoff replay still returns its original receipt"
          (let [replay (relay/review state'' {:call-id (get-in started [:receipt :call-id])
                                              :lane :plan
                                              :tenure-id plan-id
                                              :findings []})]
            (is (:ok? replay))
            (is (true? (get-in replay [:receipt :replayed])))
            (is (empty? (:effects replay)))
            (is (some? (exec (:state replay) execution-id)))
            (is (some? (exec (:state replay) execution-id-2)))
            (is (= 2 (count (relay/execution-tenures (:state replay)))))))))
    ))

(deftest plan-pending-requires-a-draft-test
  (testing "missing draft"
    (let [state (fresh {:plan-milestone :pending :draft nil})
          plan-id (relay/current-tenure-id state :plan)]
      (is (= :DRAFT_MISSING_OR_EMPTY
             (error-code (review! state :plan plan-id []))))
      (is (nil? (relay/current-tenure state :execution)))))

  (testing "blank draft content"
    (let [state (fresh {:plan-milestone :pending
                        :draft {:path "plans/blank.md" :content "   "}})
          plan-id (relay/current-tenure-id state :plan)]
      (is (= :DRAFT_MISSING_OR_EMPTY
             (error-code (review! state :plan plan-id []))))))

  (testing "draft path is fixed once written"
    (let [state (fresh {:plan-milestone :pending})
          updated (relay/set-draft state
                                   {:path "plans/test.md" :content "Updated body."})
          locked (relay/set-draft state {:path "plans/other.md" :content "x"})
          invalid (relay/set-draft state {:path "../escape.md" :content "x"})]
      (is (:ok? updated))
      (is (= "Updated body." (get-in (:state updated) [:draft :content])))
      (is (= :DRAFT_PATH_LOCKED (error-code locked)))
      (is (= :INVALID_DRAFT_PATH (error-code invalid)))))

  (testing "draft freezes after the execution tenure starts"
    (let [state (-> (fresh {:plan-milestone :pending})
                    start-execution!
                    :state)
          frozen (relay/set-draft state
                                  {:path "plans/test.md" :content "changed"})]
      (is (= :DRAFT_LOCKED_AFTER_HANDOFF (error-code frozen)))
      (is (= "Draft body." (get-in (:state frozen) [:draft :content]))))))

(deftest multiple-independent-handoffs-test
  (let [pending (-> (fresh) drive-plan-to-pending!)
        started (start-execution! pending)
        state (:state started)
        first-call-id (get-in started [:receipt :call-id])
        plan-id (relay/current-tenure-id state :plan)
        execution-1 (relay/current-tenure-id state :execution)
        second-handoff (ok! (review! state :plan plan-id []))
        state-2 (:state second-handoff)
        second-call-id (get-in second-handoff [:receipt :call-id])
        execution-2 (:execution-tenure-id (:receipt second-handoff))]
    (is (not= execution-1 execution-2))
    (is (= 2 (count (relay/execution-tenures state-2))))
    (is (nil? (relay/current-tenure state-2 :execution))
        "the two-lane convenience accessor refuses to guess")
    (is (nil? (relay/current-tenure-id state-2 :execution)))
    (is (nil? (relay/nudge-message state-2 :execution))
        "lane-level Nudge is ambiguous with two execution tenures")
    (is (some? (relay/nudge-message state-2 :execution execution-1)))
    (is (some? (relay/nudge-message state-2 :execution execution-2)))

    (let [swarm-1 (ok! (review! state-2 :execution execution-1
                                [{:key "a" :value "first handoff"}]))
          state-3 (:state swarm-1)]
      (is (= :swarm-started (outcome swarm-1)))
      (is (= :closed (:review-window (exec state-3 execution-1))))
      (is (= :open (:review-window (exec state-3 execution-2))))
      (is (= {} (relay/ledger-for state-3 execution-2)))
      (is (nil? (relay/nudge-message state-3 :execution execution-1)))
      (is (some? (relay/nudge-message state-3 :execution execution-2)))

      (let [swarm-2 (ok! (review! state-3 :execution execution-2
                                  [{:key "b" :value "second handoff"}]))
            state-4 (:state swarm-2)
            task-1 (first (root-tasks state-4 execution-1))
            task-2 (first (root-tasks state-4 execution-2))
            finish-1 (ok! (relay/finish-root-task
                           state-4
                           {:lane :execution
                            :tenure-id execution-1
                            :task-id (:task-id task-1)
                            :board (quiescent-board)}))
            state-5 (:state finish-1)
            successor-1 (:successor-tenure-id (:receipt finish-1))]
        (is (= :swarm-started (outcome swarm-2)))
        (is (= {"a" "first handoff"} (relay/ledger-for state-4 execution-1)))
        (is (= {"b" "second handoff"} (relay/ledger-for state-4 execution-2)))
        (is (= :relayed (outcome finish-1)))
        (is (not= execution-1 successor-1))
        (is (contains? (:retired-tenure-ids state-5) execution-1))
        (is (some? (exec state-5 successor-1)))
        (is (some? (exec state-5 execution-2))
            "the second handoff is unaffected by the first relay")
        (is (= {"b" "second handoff"} (relay/ledger-for state-5 execution-2)))
        (is (= :active (:swarm-phase (exec state-5 execution-2))))
        (is (= :TENURE_RETIRED
               (error-code (review! state-5 :execution execution-1 []))))
        (is (= :REVIEW_WINDOW_CLOSED
               (error-code (review! state-5 :execution execution-2 []))))

        (testing "replaying either handoff after a relay does not create another tenure"
          (doseq [call-id [first-call-id second-call-id]]
            (let [replay (relay/review state-5 {:call-id call-id
                                                :lane :plan
                                                :tenure-id plan-id
                                                :findings []})]
              (is (:ok? replay))
              (is (true? (get-in replay [:receipt :replayed])))
              (is (empty? (:effects replay)))
              (is (= 2 (count (relay/execution-tenures (:state replay)))))
              (is (some? (exec (:state replay) successor-1)))
              (is (some? (exec (:state replay) execution-2))))))

        (let [finish-2 (ok! (relay/finish-root-task
                             state-5
                             {:lane :execution
                              :tenure-id execution-2
                              :task-id (:task-id task-2)
                              :board (quiescent-board)}))
              state-6 (:state finish-2)
              successor-2 (:successor-tenure-id (:receipt finish-2))]
          (is (= :relayed (outcome finish-2)))
          (is (not= execution-2 successor-2))
          (is (contains? (:retired-tenure-ids state-6) execution-2))
          (is (= #{successor-1 successor-2}
                 (set (keys (relay/execution-tenures state-6)))))
          (is (= 2 (count (execution-barriers state-6))))
          (is (every? empty? (vals (relay/execution-ledgers state-6))))
          (is (= plan-id (relay/current-tenure-id state-6 :plan)))
          (is (= :pending (get-in state-6 [:lanes :plan :milestone])))
          (is (:ok? (relay/check-invariants state-6)))

          (doseq [call-id [first-call-id second-call-id]]
            (let [replay (relay/review state-6 {:call-id call-id
                                                :lane :plan
                                                :tenure-id plan-id
                                                :findings []})]
              (is (:ok? replay))
              (is (true? (get-in replay [:receipt :replayed])))
              (is (= 2 (count (relay/execution-tenures (:state replay)))))
              (is (= #{successor-1 successor-2}
                     (set (keys (relay/execution-tenures
                                 (:state replay))))))))
          (doseq [[call-id tenure-id findings]
                  [[(get-in swarm-1 [:receipt :call-id]) execution-1
                    [{:key "a" :value "first handoff"}]]
                   [(get-in swarm-2 [:receipt :call-id]) execution-2
                    [{:key "b" :value "second handoff"}]]]]
            (let [replay (relay/review state-6 {:call-id call-id
                                                :lane :execution
                                                :tenure-id tenure-id
                                                :findings findings})]
              (is (:ok? replay))
              (is (true? (get-in replay [:receipt :replayed])))
              (is (= 2 (count (relay/execution-tenures (:state replay)))))))
          )))))

;; ---------------------------------------------------------------------------
;; Execution lane: review window and relay

(deftest execution-initial-empty-review-asks-then-relays-test
  (let [state (drive-plan-to-pending! (fresh))
        plan-id (relay/current-tenure-id state :plan)
        started (start-execution! state)
        state' (:state started)
        execution-id (relay/current-tenure-id state' :execution)
        asked (ok! (review! state' :execution execution-id []))
        state'' (:state asked)]
    (is (= :ask (outcome asked)))
    (is (= "底稿已满足，接下来做什么？" (get-in asked [:receipt :question])))
    (is (:awaiting-user? (relay/current-tenure state'' :execution)))
    (is (nil? (relay/nudge-message state'' :execution)))

    (let [resumed (ok! (relay/resume state'' {:lane :execution
                                              :tenure-id execution-id}))
          relayed (ok! (review! (:state resumed) :execution execution-id []
                                {:board (quiescent-board)}))
          state''' (:state relayed)
          successor-id (relay/current-tenure-id state''' :execution)]
      (is (= :relayed (outcome relayed)))
      (is (not= execution-id successor-id))
      (is (contains? (:retired-tenure-ids state''') execution-id))
      (is (= :execution (:milestone (exec state''' successor-id))))
      (is (= plan-id (relay/current-tenure-id state''' :plan)))
      (is (= :pending (get-in state''' [:lanes :plan :milestone])))
      (assert-valid state''')

      (testing "the old tenure cannot review after retirement"
        (is (= :TENURE_RETIRED
               (error-code (review! state''' :execution execution-id [])))))

      (testing "successor empty review promotes execution to closing"
        (let [promoted (ok! (review! state''' :execution successor-id []))
              state'''' (:state promoted)]
          (is (= :promoted (outcome promoted)))
          (is (= :execution (get-in promoted [:receipt :from-milestone])))
          (is (= :closing (get-in promoted [:receipt :to-milestone])))
          (is (= successor-id (relay/current-tenure-id state'''' :execution)))
          (assert-valid state'''')

          (testing "closing refuses to complete with live resources"
            (let [busy (assoc (quiescent-board) :running 1)
                  refused (review! state'''' :execution successor-id []
                                   {:board busy})]
              (is (= :RESOURCES_NOT_QUIESCENT (error-code refused)))
              (is (= :closing (:milestone (exec (:state refused)
                                               successor-id))))))

          (testing "closing refuses when the fast-forward dry-run is not ready"
            (let [not-ready (assoc (quiescent-board) :ff-ready? false)]
              (is (= :FF_NOT_READY
                     (error-code (review! state'''' :execution successor-id []
                                          {:board not-ready}))))))

          (testing "closing completes with a quiescent board and ff ready"
            (let [completed (ok! (review! state'''' :execution successor-id []
                                          {:board (quiescent-board)}))
                  final (:state completed)]
              (is (= :completed (outcome completed)))
              (is (= :completed (:milestone (exec final successor-id))))
              (is (= :completed (:status (exec final successor-id))))
              (is (= :closed (:review-window (exec final successor-id))))
              (is (= :active (get-in final [:lanes :plan :status])))
              (is (= :pending (get-in final [:lanes :plan :milestone])))
              (assert-valid final)

              (testing "a completed execution rejects its own new review but Plan may hand off again"
                (is (= :MISSION_COMPLETED
                       (error-code (review! final :execution successor-id []))))
                (let [new-handoff (ok! (review! final :plan plan-id []))]
                  (is (= :execution-started (outcome new-handoff)))
                  (is (= 2 (count (relay/execution-tenures
                                   (:state new-handoff)))))
                  (assert-valid (:state new-handoff)))))))))))

(deftest execution-initial-nonempty-review-starts-swarm-test
  (let [state (drive-plan-to-pending! (fresh))
        plan-id (relay/current-tenure-id state :plan)
        started (start-execution! state)
        state' (:state started)
        execution-id (relay/current-tenure-id state' :execution)
        findings [{:key "api" :value "define the API"}
                  {:key "tests" :value "add focused tests"}]
        swarm (start-swarm! state' findings)
        state'' (:state swarm)
        tasks (root-tasks state'')]
    (is (= :swarm-started (outcome swarm)))
    (is (= :closed (:review-window (exec state'' execution-id))))
    (is (= :active (:swarm-phase (exec state'' execution-id))))
    (is (false? (:first-review? (exec state'' execution-id))))
    (is (= {"api" "define the API" "tests" "add focused tests"}
           (relay/execution-ledger state'')))
    (is (= 2 (count tasks)))
    (is (= #{"api" "tests"} (set (map :ledger-key tasks))))
    (is (every? #(= :ready (:state %)) tasks))
    (is (every? #(= (:goal %) (:remaining-work %)) tasks))
    (is (every? #(= (:goal %) (:done-when %)) tasks))
    (is (= :swarm/start (:type (first (:effects swarm)))))
    (is (= :all (:wake (first (:effects swarm)))))
    (is (= {} (relay/plan-ledger state'')))
    (is (= plan-id (relay/current-tenure-id state'' :plan)))
    (assert-valid state'')

    (testing "the suspended Reviewer cannot submit another review"
      (is (= :REVIEW_WINDOW_CLOSED
             (error-code (review! state'' :execution execution-id
                                  [{:key "more" :value "work"}])))))))

(deftest empty-effective-findings-do-not-start-swarm-test
  (let [state (-> (fresh {:plan-milestone :pending})
                  start-execution!
                  :state)
        execution-id (relay/current-tenure-id state :execution)
        noop (ok! (review! state :execution execution-id
                           [{:key "missing" :value nil}]))
        state' (:state noop)]
    (is (= :noop (outcome noop)))
    (is (= {} (relay/execution-ledger state')))
    (is (= :none (:swarm-phase (exec state' execution-id))))
    (is (= :open (:review-window (exec state' execution-id))))
    (is (:first-review? (relay/current-tenure state' :execution)))
    (is (= :ask (outcome (ok! (review! state' :execution execution-id [])))))
    (assert-valid state')))

(deftest root-finish-releases-ledger-and-relays-only-when-quiescent-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state
                  (start-swarm! [{:key "a" :value "first"}
                                 {:key "b" :value "second"}])
                  :state)
        state (assert-valid state)
        execution-id (relay/current-tenure-id state :execution)
        tasks (root-tasks state)
        [task-a task-b] tasks
        first-finish (ok! (finish-root! state task-a (quiescent-board)))
        state-1 (:state first-finish)]
    (is (= :root-finished (outcome first-finish)))
    (is (= {"b" "second"} (relay/execution-ledger state-1)))
    (is (= 0 (count (execution-barriers state-1))))
    (assert-valid state-1)

    (testing "an in-flight action defers the barrier"
      (let [busy (assoc (quiescent-board) :inflight-actions 1)
            second-finish (ok! (finish-root! state-1 task-b busy))
            state-2 (:state second-finish)]
        (is (= :relay-pending (outcome second-finish)))
        (is (= {} (relay/execution-ledger state-2)))
        (is (true? (:relay-pending? (exec state-2 execution-id))))
        (is (= 0 (count (execution-barriers state-2))))
        (assert-valid state-2)

        (testing "maybe-relay still defers while the board is busy"
          (let [retry (ok! (relay/maybe-relay state-2
                                              {:lane :execution
                                               :tenure-id execution-id
                                               :board busy}))]
            (is (= :relay-pending (outcome retry)))
            (is (= 0 (count (execution-barriers (:state retry)))))))

        (testing "a partial snapshot is not treated as quiescent"
          (let [partial {:unresolved-tasks 0
                         :inflight-actions 0
                         :holder-retired? true}
                retry (ok! (relay/maybe-relay state-2
                                              {:lane :execution
                                               :tenure-id execution-id
                                               :board partial}))]
            (is (= :relay-pending (outcome retry)))
            (is (some #{:board-roots-not-done}
                      (get-in retry [:receipt :reasons])))
            (is (= 0 (count (execution-barriers (:state retry)))))))

        (testing "maybe-relay fires the barrier once the board is quiescent"
          (let [relay-result (ok! (relay/maybe-relay
                                   state-2
                                   {:lane :execution
                                    :tenure-id execution-id
                                    :board (quiescent-board)}))
                final (:state relay-result)
                successor-id (relay/current-tenure-id final :execution)
                barrier-record (last (execution-barriers final))]
            (is (= :relayed (outcome relay-result)))
            (is (= 1 (count (execution-barriers final))))
            (is (not= execution-id successor-id))
            (is (= execution-id (get-in barrier-record [:from-tenure-id])))
            (is (= successor-id (get-in barrier-record [:to-tenure-id])))
            (is (= :execution (:milestone (exec final successor-id))))
            (is (= {} (relay/execution-ledger final)))
            (is (empty? (root-tasks final)))
            (is (barrier/context-keys-valid? (:context barrier-record)))
            (is (not-any? #(contains? (:context barrier-record) %)
                          [:ledger :findings :board :predecessor]))
            (assert-valid final)

            (testing "the retired tenure and its root tasks are inert"
              (is (= :TENURE_RETIRED
                     (error-code (review! final :execution execution-id []))))
              (is (= :TENURE_RETIRED
                     (error-code (relay/finish-root-task
                                  final
                                  {:lane :execution
                                   :tenure-id execution-id
                                   :task-id (:task-id task-b)
                                   :board (quiescent-board)})))))))))))

(deftest unhandled-board-input-defers-relay-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state
                  (start-swarm! [{:key "only" :value "one"}])
                  :state)
        execution-id (relay/current-tenure-id state :execution)
        task (first (root-tasks state))
        with-input (assoc (quiescent-board) :unhandled-inputs 1)
        finished (ok! (finish-root! state task with-input))
        state-1 (:state finished)]
    (testing "an unhandled public input defers the barrier"
      (is (= :relay-pending (outcome finished)))
      (is (some #{:unhandled-inputs}
                (get-in finished [:receipt :reasons])))
      (is (= {} (relay/execution-ledger state-1)))
      (is (true? (:relay-pending? (exec state-1 execution-id))))
      (is (= 0 (count (execution-barriers state-1))))
      (assert-valid state-1))

    (testing "a missing unhandled-input count is not consent"
      (let [partial (dissoc with-input :unhandled-inputs)
            retry (ok! (relay/maybe-relay state-1
                                          {:lane :execution
                                           :tenure-id execution-id
                                           :board partial}))]
        (is (= :relay-pending (outcome retry)))
        (is (some #{:unhandled-inputs-missing}
                  (get-in retry [:receipt :reasons])))))

    (testing "handling the input lets maybe-relay fire"
      (let [relayed (ok! (relay/maybe-relay
                          state-1
                          {:lane :execution
                           :tenure-id execution-id
                           :board (quiescent-board)}))
            final (:state relayed)]
        (is (= :relayed (outcome relayed)))
        (is (= 1 (count (execution-barriers final))))
        (is (not= execution-id
                  (relay/current-tenure-id final :execution)))
        (assert-valid final)))))

(deftest handled-user-input-crosses-barrier-as-raw-text-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state
                  (start-swarm! [{:key "only" :value "one"}])
                  :state)
        task (first (root-tasks state))
        board (assoc (quiescent-board)
                     :user-inputs
                     [{:text "Also check the startup path."
                       :message-id "message-1"
                       :attachments [{:attachment-id "a1"
                                      :blob-id "blob-1"
                                      :name "spec.txt"
                                      :mime "text/plain"
                                      :size 12
                                      :content "secret body"
                                      :resolution "agent summary"}]}])
        finished (ok! (finish-root! state task board))
        final (:state finished)
        barrier-record (last (execution-barriers final))
        context (:context barrier-record)
        inputs (:user-inputs final)]
    (is (= :relayed (outcome finished)))
    (is (str/includes? (:user-input context)
                       "Build the requested feature."))
    (is (str/includes? (:user-input context)
                       "Also check the startup path."))
    (is (not (contains? context :resolution)))
    (is (not (contains? context :message-id)))
    (is (= [{:attachment-id "a1"
             :blob-id "blob-1"
             :name "spec.txt"
             :mime "text/plain"
             :size 12
             :message-id "message-1"}]
           (:user-attachments context)))
    (is (not (contains? (first (:user-attachments context)) :content)))
    (is (not (contains? (first (:user-attachments context)) :resolution)))
    (is (= 2 (count inputs)))
    (is (= "message-1" (:message-id (second inputs))))
    (assert-valid final)))

(deftest user-input-history-rejects-non-raw-fields-test
  (let [state (fresh)
        contaminated (assoc state
                            :user-inputs
                            [{:text "Build the requested feature."
                              :resolution "agent summary"}])]
    (is (false? (:ok? (relay/check-invariants contaminated))))))

(deftest initial-user-attachments-cross-barrier-as-references-test
  (let [state (fresh {:user-attachments
                      [{:attachment-id "a1"
                        :blob-id "blob-1"
                        :name "spec.txt"
                        :mime "text/plain"
                        :size 12
                        :content "secret body"
                        :resolution "agent summary"}]})
        context (barrier/successor-context
                 state (relay/current-tenure state :plan))]
    (is (= [{:attachment-id "a1"
             :blob-id "blob-1"
             :name "spec.txt"
             :mime "text/plain"
             :size 12}]
           (:user-attachments context)))
    (is (not (contains? (first (:user-attachments context)) :content)))
    (is (not (contains? (first (:user-attachments context)) :resolution)))
    (assert-valid state)))

(deftest finish-without-board-defers-then-retries-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state
                  (start-swarm! [{:key "only" :value "one"}])
                  :state)
        execution-id (relay/current-tenure-id state :execution)
        task (first (root-tasks state))
        finished (ok! (relay/finish-root-task
                       state
                       {:lane :execution
                        :tenure-id execution-id
                        :ledger-key (:ledger-key task)}))]
    (is (= :root-finished (outcome finished)))
    (is (= {} (relay/execution-ledger (:state finished))))
    (is (= 0 (count (execution-barriers (:state finished)))))
    (is (false? (:relay-pending? (exec (:state finished) execution-id))))

    (testing "maybe-relay sees the active swarm and fires"
      (let [relayed (ok! (relay/maybe-relay
                          (:state finished)
                          {:lane :execution
                           :tenure-id execution-id
                           :board (quiescent-board)}))
            final (:state relayed)]
        (is (= :relayed (outcome relayed)))
        (is (= 1 (count (execution-barriers final))))
        (assert-valid final)))))

(deftest closing-phase-swarm-relay-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)
        resumed (ok! (relay/resume (:state (ok! (review! state :execution
                                                          execution-id [])))
                                   {:lane :execution :tenure-id execution-id}))
        relayed (ok! (review! (:state resumed) :execution execution-id []
                              {:board (quiescent-board)}))
        state-1 (:state relayed)
        successor-id (relay/current-tenure-id state-1 :execution)
        promoted (ok! (review! state-1 :execution successor-id []))
        state-2 (:state promoted)
        swarm (start-swarm! state-2 [{:key "finish" :value "finish the slice"}])
        state-3 (:state swarm)
        task (first (root-tasks state-3))
        relay-result (ok! (finish-root! state-3 task (quiescent-board)))
        state-4 (:state relay-result)
        successor-2 (relay/current-tenure-id state-4 :execution)
        completed (ok! (review! state-4 :execution successor-2 []
                                {:board (quiescent-board)}))
        final (:state completed)]
    (is (= :closing (:milestone (exec state-2 successor-id))))
    (is (= :swarm-started (outcome swarm)))
    (is (= :closing (:milestone (exec state-3 successor-id))))
    (is (= :relayed (outcome relay-result)))
    (is (= :closing (:milestone (exec state-4 successor-2))))
    (is (= :completed (outcome completed)))
    (is (= :completed (:status (exec final successor-2))))
    (assert-valid final)))

;; ---------------------------------------------------------------------------
;; Idempotency, Nudge, and separation

(deftest review-idempotency-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        cid (call-id)
        first-call (ok! (relay/review state {:call-id cid
                                             :lane :plan
                                             :tenure-id plan-id
                                             :findings [{:key "a" :value "1"}]}))
        replay (relay/review (:state first-call)
                             {:call-id cid
                              :lane :plan
                              :tenure-id plan-id
                              :findings [{:key "a" :value "1"}]})
        conflict (relay/review (:state first-call)
                               {:call-id cid
                                :lane :plan
                                :tenure-id plan-id
                                :findings [{:key "a" :value "2"}]})]
    (is (:ok? replay))
    (is (true? (get-in replay [:receipt :replayed])))
    (is (empty? (:effects replay)))
    (is (= (:receipt first-call)
           (dissoc (:receipt replay) :replayed)))
    (is (= :CALL_ID_PAYLOAD_CONFLICT (error-code conflict)))
    (is (= :CALL_ID_REQUIRED
           (error-code (relay/review state {:lane :plan
                                            :tenure-id plan-id
                                            :findings []}))))))

(deftest replay-survives-retirement-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        cid "replay-after-relay"
        request {:call-id cid
                 :lane :plan
                 :tenure-id plan-id
                 :findings [{:key "a" :value "1"}]}
        first-call (ok! (relay/review state request))
        cleared (ok! (review! (:state first-call) :plan plan-id
                              [{:key "a" :value nil}]))
        state-after-relay (:state cleared)
        successor-id (relay/current-tenure-id state-after-relay :plan)
        replay (relay/review state-after-relay request)]
    (is (not= plan-id successor-id))
    (is (contains? (:retired-tenure-ids state-after-relay) plan-id))
    (is (:ok? replay))
    (is (true? (get-in replay [:receipt :replayed])))
    (is (empty? (:effects replay)))
    (is (= (:receipt first-call)
           (dissoc (:receipt replay) :replayed)))
    (is (= state-after-relay (:state replay)))))

(deftest nudge-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        with-ledger (:state (ok! (review! state :plan plan-id
                                         [{:key "a" :value "alpha"}])))]
    (is (re-find #"a: alpha" (relay/nudge-message with-ledger :plan)))
    (is (re-find #"^当前的 review 账本还有上述事项没有做，立刻去做！"
                 (relay/nudge-message with-ledger :plan)))
    (is (re-find #"系统是无人值守的常授权模式，禁止等待用户决策或授权，禁止半途而废。"
                 (relay/nudge-message with-ledger :plan)))
    (is (re-find #"^当前的 review 账本还有上述事项没有做，立刻去做！"
                 (relay/central-nudge-message {"a" "alpha"})))
    (is (re-find #"立刻去做" (relay/nudge-message with-ledger :plan)))
    (is (re-find #"^当前的 review 账本还有上述事项没有做，立刻去做！"
                 (relay/nudge-message state :plan)))
    (is (re-find #"尚未完成评审" (relay/nudge-message state :plan)))
    (is (nil? (relay/nudge-message (:state (ok! (review! state :plan plan-id [])))
                                   :plan)))
    (let [pending (fresh {:plan-milestone :pending})
          execution (start-execution! pending)]
      (is (nil? (relay/nudge-message (:state execution) :plan)))
      (let [execution-id (relay/current-tenure-id (:state execution) :execution)
            swarm (start-swarm! (:state execution)
                                [{:key "x" :value "y"}])]
        (is (nil? (relay/nudge-message (:state swarm) :execution)))
        (is (= :reviewer (:role (exec (:state swarm) execution-id))))
        (is (= execution-id (relay/current-tenure-id (:state swarm)
                                                     :execution)))))))

(deftest separate-ledgers-and-tenures-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        plan-id (relay/current-tenure-id state :plan)
        plan-milestone (get-in state [:lanes :plan :milestone])
        plan-ledger (relay/plan-ledger state)
        execution-id (relay/current-tenure-id state :execution)
        swarm (start-swarm! state [{:key "only" :value "work"}])
        state-1 (:state swarm)
        task (first (root-tasks state-1))
        relayed (ok! (finish-root! state-1 task (quiescent-board)))
        state-2 (:state relayed)]
    (is (= {} plan-ledger))
    (is (= plan-id (relay/current-tenure-id state-2 :plan)))
    (is (= plan-milestone (get-in state-2 [:lanes :plan :milestone])))
    (is (= plan-ledger (relay/plan-ledger state-2)))
    (is (not= execution-id (relay/current-tenure-id state-2 :execution)))
    (is (= 1 (count (filter #(= :execution (:lane %))
                            (:barriers state-2)))))
    (is (= :active (get-in state-2 [:lanes :plan :status])))
    (assert-valid state-2)

    (testing "barrier history can be compacted without changing the live lanes"
      (is (= :INVALID_PRUNE_COUNT
             (error-code (relay/prune-barriers state-2 -1))))
      (let [pruned (ok! (relay/prune-barriers state-2 1))
            state-3 (:state pruned)]
        (is (= 1 (count (:barriers state-3))))
        (is (= (:barrier-seq state-2) (:barrier-seq state-3)))
        (is (:ok? (relay/check-invariants state-3)))
        (let [emptied (ok! (relay/prune-barriers state-3 0))]
          (is (empty? (:barriers (:state emptied))))
          (is (:ok? (relay/check-invariants (:state emptied)))))))))

;; ---------------------------------------------------------------------------
;; Snapshot validation, message-id normalization, and boundary errors

(deftest barrier-snapshot-validation-test
  (testing "nil, string, and negative counters are not zero"
    (doseq [[k v expected] [[:applying nil :applying-invalid]
                            [:running -1 :running-invalid]
                            [:unhandled-inputs nil :unhandled-inputs-invalid]
                            [:call-children "0" :call-children-invalid]
                            [:inflight-actions nil :inflight-actions-invalid]
                            [:unresolved-tasks nil :unresolved-tasks-invalid]
                            [:unresolved-token-tasks nil
                             :unresolved-token-tasks-invalid]]]
      (let [board (assoc (quiescent-board) k v)
            reasons (barrier/closing-reasons board)]
        (is (false? (barrier/closing-quiescent? board)) (pr-str k v))
        (is (some #{expected} reasons) (pr-str k v)))))

  (testing "holds-write-lock? passes only on explicit false"
    (is (true? (barrier/closing-quiescent? (quiescent-board))))
    (doseq [v [nil true "false"]]
      (let [board (assoc (quiescent-board) :holds-write-lock? v)]
        (is (false? (barrier/closing-quiescent? board)) (pr-str v))))
    (is (some #{:write-lock-state-invalid}
              (barrier/closing-reasons
               (assoc (quiescent-board) :holds-write-lock? nil))))
    (is (some #{:holds-write-lock}
              (barrier/closing-reasons
               (assoc (quiescent-board) :holds-write-lock? true)))))

  (testing "all-root-tasks-done? nil is invalid, not false"
    (let [board (assoc (quiescent-board) :all-root-tasks-done? nil)
          reasons (barrier/closing-reasons board)]
      (is (false? (barrier/closing-quiescent? board)))
      (is (some #{:board-roots-invalid} reasons))))

  (testing "execution relay reasons mark invalid counters"
    (let [tenure {:ledger {} :root-tasks []}
          board (assoc (quiescent-board) :unresolved-tasks nil)]
      (is (false? (barrier/execution-relay-ready? tenure board)))
      (is (some #{:unresolved-tasks-invalid}
                (barrier/execution-relay-reasons tenure board))))))

(deftest message-id-normalization-and-dedupe-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)
        swarm (start-swarm-on! state execution-id [{:key "a" :value "work"}])
        state-1 (:state swarm)
        task (first (root-tasks state-1 execution-id))
        board (assoc (quiescent-board)
                     :user-inputs [{:text "first" :message-id "m1"}
                                   {:text "blank" :message-id ""}])
        finished (ok! (relay/finish-root-task
                       state-1
                       {:lane :execution :tenure-id execution-id
                        :task-id (:task-id task) :board board}))
        state-2 (:state finished)
        inputs (:user-inputs state-2)]
    (is (= :relayed (outcome finished)))
    (is (= 1 (count (filter #(= "m1" (:message-id %)) inputs))))
    (is (some #(= "blank" (:text %)) inputs))
    (is (not-any? #(= "" (:message-id %)) inputs))
    (assert-valid state-2)

    (testing "a later report of the same id merges attachments without a second entry"
      (let [successor-id (relay/current-tenure-id state-2 :execution)
            swarm-2 (start-swarm-on! state-2 successor-id
                                     [{:key "b" :value "more"}])
            state-3 (:state swarm-2)
            task-2 (first (root-tasks state-3 successor-id))
            board-2 (assoc (quiescent-board)
                           :user-inputs
                           [{:text "first again" :message-id "m1"
                             :attachments [{:attachment-id "a1"
                                            :blob-id "b1"}]}])
            finished-2 (ok! (relay/finish-root-task
                             state-3
                             {:lane :execution :tenure-id successor-id
                              :task-id (:task-id task-2) :board board-2}))
            state-4 (:state finished-2)
            entry (some #(when (= "m1" (:message-id %)) %)
                        (:user-inputs state-4))]
        (is (= :relayed (outcome finished-2)))
        (is (= 3 (count (:user-inputs state-4))))
        (is (= [{:attachment-id "a1" :blob-id "b1"}]
               (:attachments entry)))
        (assert-valid state-4)))))

(deftest initial-message-id-dedupes-test
  (let [state (fresh {:message-id "m0"})
        initial (first (:user-inputs state))]
    (is (= "m0" (:message-id initial)))
    (let [pending (drive-plan-to-pending! state)
          started (start-execution! pending)
          state-1 (:state started)
          execution-id (relay/current-tenure-id state-1 :execution)
          swarm (start-swarm-on! state-1 execution-id
                                 [{:key "a" :value "work"}])
          state-2 (:state swarm)
          task (first (root-tasks state-2 execution-id))
          board (assoc (quiescent-board)
                       :user-inputs
                       [{:text "Build the requested feature."
                         :message-id "m0"}])
          finished (ok! (relay/finish-root-task
                         state-2
                         {:lane :execution :tenure-id execution-id
                          :task-id (:task-id task) :board board}))
          state-3 (:state finished)]
      (is (= :relayed (outcome finished)))
      (is (= 1 (count (filter #(= "m0" (:message-id %))
                              (:user-inputs state-3)))))
      (assert-valid state-3))))

(deftest board-snapshot-required-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)
        asked (ok! (review! state :execution execution-id []))
        resumed (ok! (relay/resume (:state asked)
                                   {:lane :execution :tenure-id execution-id}))
        result (review! (:state resumed) :execution execution-id [])]
    (is (= :BOARD_SNAPSHOT_REQUIRED (error-code result)))
    (is (= :execution (:milestone (exec (:state result) execution-id)))))

  (testing "a board without :user-inputs stays pending with a reason"
    (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
          execution-id (relay/current-tenure-id state :execution)
          swarm (start-swarm-on! state execution-id
                                 [{:key "a" :value "work"}])
          state-1 (:state swarm)
          task (first (root-tasks state-1 execution-id))
          board (dissoc (quiescent-board) :user-inputs)
          finished (ok! (relay/finish-root-task
                         state-1
                         {:lane :execution :tenure-id execution-id
                          :task-id (:task-id task) :board board}))]
      (is (= :relay-pending (outcome finished)))
      (is (some #{:user-inputs-missing}
                (get-in finished [:receipt :reasons])))
      (is (true? (:relay-pending? (exec (:state finished) execution-id)))))))

(deftest maybe-relay-ledger-and-phase-gates-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)]
    (testing "Phase A has no pending relay"
      (is (= :NOT_IN_SWARM
             (error-code (relay/maybe-relay
                          state
                          {:lane :execution
                           :tenure-id execution-id
                           :board (quiescent-board)})))))
    (let [swarm (start-swarm-on! state execution-id
                                 [{:key "a" :value "work"}])
          state-1 (:state swarm)
          retry (ok! (relay/maybe-relay
                      state-1
                      {:lane :execution
                       :tenure-id execution-id
                       :board (quiescent-board)}))]
      (testing "Phase B with a ledger stays pending instead of hard-failing"
        (is (= :relay-pending (outcome retry)))
        (is (some #{:ledger-not-empty}
                  (get-in retry [:receipt :reasons])))
        (is (= {"a" "work"}
               (relay/ledger-for (:state retry) execution-id)))
        (is (false? (:relay-pending? (exec (:state retry) execution-id)))
            "a mid-swarm retry is not a pending barrier")
        (assert-valid (:state retry))))))

(deftest review-boundary-errors-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)]
    (is (= :INVALID_LANE
           (error-code (review! state :bogus plan-id []))))
    (is (= :UNKNOWN_TENURE
           (error-code (review! state :plan "missing" []))))
    (is (= :TENURE_LANE_MISMATCH
           (error-code (review! state :execution plan-id []))))
    (is (= :INVALID_REQUEST
           (error-code (relay/review state
                                     {:call-id (call-id)
                                      :lane :plan
                                      :tenure-id 42
                                      :findings []}))))
    (is (= :NOT_AWAITING_USER
           (error-code (relay/resume state
                                     {:lane :plan :tenure-id plan-id}))))
    (is (= :INVALID_DRAFT_CONTENT
           (error-code (relay/set-draft state
                                        {:path "plans/x.md" :content 42}))))
    (let [bad-role (assoc-in state [:lanes :plan :role] :reviewer)]
      (is (= :ROLE_FORBIDDEN
             (error-code (review! bad-role :plan plan-id []))))))

  (testing "pending Plan with a non-empty ledger refuses to start execution"
    (let [state (assoc-in (fresh {:plan-milestone :pending})
                          [:lanes :plan :ledger] {"x" "y"})
          plan-id (relay/current-tenure-id state :plan)]
      (is (= :NONEMPTY_LEDGER_AT_PENDING
             (error-code (review! state :plan plan-id []))))))

  (testing "unknown root task and duplicate finish"
    (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
          execution-id (relay/current-tenure-id state :execution)
          swarm (start-swarm-on! state execution-id
                                 [{:key "a" :value "work"}])
          state-1 (:state swarm)
          task (first (root-tasks state-1 execution-id))]
      (is (= :UNKNOWN_ROOT_TASK
             (error-code (relay/finish-root-task
                          state-1
                          {:lane :execution :tenure-id execution-id
                           :task-id "nope"}))))
      (is (= :UNKNOWN_ROOT_TASK
             (error-code (relay/finish-root-task
                          state-1
                          {:lane :execution :tenure-id execution-id
                           :task-id (:task-id task)
                           :ledger-key "other"}))))
      (let [first-finish (ok! (relay/finish-root-task
                               state-1
                               {:lane :execution :tenure-id execution-id
                                :task-id (:task-id task)}))
            second-finish (ok! (relay/finish-root-task
                                (:state first-finish)
                                {:lane :execution :tenure-id execution-id
                                 :task-id (:task-id task)}))]
        (is (= :root-finished (outcome first-finish)))
        (is (= :already-finished (outcome second-finish)))
        (is (= {} (relay/ledger-for (:state second-finish) execution-id)))))))

(deftest successor-first-review-test
  (testing "an execution successor's non-empty first review stays in :execution"
    (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
          execution-id (relay/current-tenure-id state :execution)
          asked (ok! (review! state :execution execution-id []))
          resumed (ok! (relay/resume (:state asked)
                                     {:lane :execution :tenure-id execution-id}))
          relayed (ok! (review! (:state resumed) :execution execution-id []
                                {:board (quiescent-board)}))
          state-1 (:state relayed)
          successor-id (relay/current-tenure-id state-1 :execution)
          swarm (start-swarm-on! state-1 successor-id
                                 [{:key "s" :value "work"}])
          state-2 (:state swarm)]
      (is (= :relayed (outcome relayed)))
      (is (= :execution (:milestone (exec state-2 successor-id))))
      (is (= :active (:swarm-phase (exec state-2 successor-id))))
      (assert-valid state-2)))

  (testing "a Plan successor's non-empty first review records the ledger only"
    (let [state (fresh)
          plan-id (relay/current-tenure-id state :plan)
          with-ledger (ok! (review! state :plan plan-id
                                    [{:key "a" :value "1"}]))
          cleared (ok! (review! (:state with-ledger) :plan plan-id
                                [{:key "a" :value nil}]))
          state-1 (:state cleared)
          successor-id (relay/current-tenure-id state-1 :plan)
          promoted (ok! (review! state-1 :plan successor-id []))
          state-2 (:state promoted)
          recorded (ok! (review! state-2 :plan successor-id
                                 [{:key "b" :value "2"}]))
          state-3 (:state recorded)]
      (is (= :S2 (get-in state-3 [:lanes :plan :milestone])))
      (is (= {"b" "2"} (relay/plan-ledger state-3)))
      (is (nil? (relay/current-tenure state-3 :execution)))
      (is (= :open (get-in state-3 [:lanes :plan :review-window])))
      (assert-valid state-3))))

(deftest closing-refusal-preserves-first-review-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)
        asked (ok! (review! state :execution execution-id []))
        _ (is (= :AWAITING_USER
                 (error-code (review! (:state asked)
                                      :execution execution-id []))))
        resumed (ok! (relay/resume (:state asked)
                                   {:lane :execution :tenure-id execution-id}))
        relayed (ok! (review! (:state resumed) :execution execution-id []
                              {:board (quiescent-board)}))
        state-1 (:state relayed)
        successor-id (relay/current-tenure-id state-1 :execution)
        promoted (ok! (review! state-1 :execution successor-id []))
        state-2 (:state promoted)
        busy (assoc (quiescent-board) :running 1)
        refused (review! state-2 :execution successor-id [] {:board busy})
        nil-running (review! (:state refused) :execution successor-id []
                             {:board (assoc (quiescent-board) :running nil)})
        not-ready (review! (:state nil-running) :execution successor-id []
                           {:board (assoc (quiescent-board) :ff-ready? false)})
        completed (ok! (review! (:state not-ready) :execution successor-id []
                                {:board (quiescent-board)}))]
    (is (= :closing (:milestone (exec state-2 successor-id))))
    (is (= :RESOURCES_NOT_QUIESCENT (error-code refused)))
    (is (:first-review? (exec (:state refused) successor-id)))
    (is (= :RESOURCES_NOT_QUIESCENT (error-code nil-running)))
    (is (some #{:running-invalid}
              (get-in nil-running [:error :details :reasons])))
    (is (= :FF_NOT_READY (error-code not-ready)))
    (is (:first-review? (exec (:state not-ready) successor-id)))
    (is (= :completed (outcome completed)))
    (is (nil? (relay/nudge-message (:state completed)
                                   :execution successor-id)))
    (assert-valid (:state completed))))

(deftest relay-receipt-and-milestone-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        with-ledger (ok! (review! state :plan plan-id
                                  [{:key "a" :value "1"}]))
        cleared (ok! (review! (:state with-ledger) :plan plan-id
                              [{:key "a" :value nil}]))
        receipt (:receipt cleared)
        barrier (last (:barriers (:state cleared)))]
    (is (= :relayed (outcome cleared)))
    (is (not (contains? receipt :context)))
    (is (some? (:barrier-id receipt)))
    (is (some? (:successor-tenure-id receipt)))
    (is (contains? (first (:effects cleared)) :context))
    (is (= (:milestone barrier) (:from-milestone barrier)))
    (is (= 1 (:from-epoch barrier)))
    (is (= 2 (:to-epoch barrier)))
    (is (= :plan (:lane barrier)))
    (is (true? (:predecessor-hidden? barrier)))
    (is (= (:milestone barrier)
           (:milestone (exec (:state cleared)
                             (:successor-tenure-id receipt)))))
    (is (not (contains? (get-in (:state cleared)
                                [:receipts (:call-id receipt) :receipt])
                        :context)))))

(deftest prune-and-nudge-boundaries-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)]
    (is (= :INVALID_PRUNE_COUNT
           (error-code (relay/prune-barriers state 1.5))))
    (let [pruned (ok! (relay/prune-barriers state 99))]
      (is (= 1 (count (:barriers state))))
      (is (= 1 (count (:barriers (:state pruned))))))
    (is (some? (relay/nudge-message state :execution execution-id)))
    (let [fresh-state (fresh)
          plan-id (relay/current-tenure-id fresh-state :plan)]
      (is (nil? (relay/nudge-message fresh-state :plan "stale-plan")))
      (is (some? (relay/nudge-message fresh-state :plan plan-id))))))

(deftest tenure-terminal-milestone-test
  (let [tenure (assoc (tenure/create-tenure
                       {:tenure-id "terminal"
                        :epoch 9
                        :lane :execution
                        :milestone :completed
                        :initial? false})
                      :status :completed)
        result (tenure/promote-tenure tenure)]
    (is (false? (:ok? result)))
    (is (= :TERMINAL_MILESTONE (get-in result [:error :code])))))

(deftest abandon-execution-tenure-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        first-id (relay/current-tenure-id state :execution)
        plan-id (relay/current-tenure-id state :plan)
        second (ok! (review! state :plan plan-id []))
        second-state (:state second)
        second-id (get-in second [:receipt :execution-tenure-id])
        abandoned (ok! (relay/abandon-execution-tenure
                        second-state first-id "master moved" 4242))
        state' (:state abandoned)
        first' (exec state' first-id)]
    (testing "the tenure is a terminal record with the reason and time"
      (is (= :abandoned (:status first')))
      (is (= "master moved" (:abandoned-reason first')))
      (is (= 4242 (:retired-at first')))
      (is (false? (tenure/active? first')))
      (is (false? (tenure/completed? first')))
      (is (tenure/terminal? first'))
      (is (empty? (:ledger first')))
      (is (empty? (:root-tasks first')))
      (is (= :none (:swarm-phase first'))))
    (testing "another execution tenure is untouched"
      (is (= :active (get-in state' [:lanes :execution second-id :status])))
      (is (nil? (get-in state' [:lanes :execution second-id
                                :abandoned-reason]))))
    (assert-valid state')
    (testing "a repeated abandon is an idempotent success"
      (let [again (ok! (relay/abandon-execution-tenure
                        state' first-id "again"))]
        (is (= :already-abandoned (outcome again)))
        (is (= state' (:state again)))
        (is (= "master moved"
               (get-in again [:receipt :reason])))))
    (testing "the Plan tenure can never be abandoned"
      (let [refused (relay/abandon-execution-tenure state' plan-id "no")]
        (is (= :ABANDON_PLAN_TENURE_FORBIDDEN (error-code refused)))
        (is (= state' (:state refused)))))
    (testing "reviews and relay retries see the abandoned tenure"
      (is (= :TENURE_ABANDONED
             (error-code (review! state' :execution first-id []))))
      (is (= :TENURE_ABANDONED
             (error-code (relay/maybe-relay
                          state'
                          {:lane :execution
                           :tenure-id first-id
                           :board (quiescent-board)})))))))

(deftest board-is-not-part-of-call-payload-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)
        asked (ok! (review! state :execution execution-id []))
        resumed (ok! (relay/resume (:state asked)
                                   {:lane :execution :tenure-id execution-id}))
        cid "board-payload"
        busy (assoc (quiescent-board) :running 1)
        first-call (ok! (relay/review (:state resumed)
                                      {:call-id cid
                                       :lane :execution
                                       :tenure-id execution-id
                                       :findings []
                                       :board busy}))
        replay (relay/review (:state first-call)
                             {:call-id cid
                              :lane :execution
                              :tenure-id execution-id
                              :findings []
                              :board (quiescent-board)})]
    (is (= :relay-pending (outcome first-call)))
    (is (:ok? replay))
    (is (true? (get-in replay [:receipt :replayed])))
    (is (empty? (:effects replay)))))

(deftest invariant-bite-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        execution-id (relay/current-tenure-id state :execution)
        ledger-state (assoc-in state
                               [:lanes :execution execution-id :ledger]
                               {"x" "y"})
        pending-state (assoc-in ledger-state
                                [:lanes :execution execution-id
                                 :relay-pending?]
                                true)]
    (is (some #{:EXECUTION_LEDGER_OUTSIDE_SWARM}
              (map :code (:violations
                          (relay/check-invariants ledger-state)))))
    (is (some #{:RELAY_PENDING_WITH_LEDGER}
              (map :code (:violations
                          (relay/check-invariants pending-state))))))

  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        with-ledger (ok! (review! state :plan plan-id
                                  [{:key "a" :value "1"}]))
        cleared (ok! (review! (:state with-ledger) :plan plan-id
                              [{:key "a" :value nil}]))
        state-1 (:state cleared)
        index (dec (count (:barriers state-1)))
        tampered (assoc-in state-1
                           [:barriers index :from-milestone]
                           :S3)]
    (is (some #{:BARRIER_SUCCESSOR_MISMATCH}
              (map :code (:violations
                          (relay/check-invariants tampered)))))))

(deftest new-state-validation-test
  (is (thrown? clojure.lang.ExceptionInfo
               (relay/new-state {:session-id nil})))
  (is (thrown? clojure.lang.ExceptionInfo
               (relay/new-state {:user-input 42})))
  (is (thrown? clojure.lang.ExceptionInfo
               (relay/new-state {:successor-hint 42})))
  (is (thrown? clojure.lang.ExceptionInfo
               (relay/new-state {:message-id ""})))
  (is (= "m0"
         (:message-id
          (first (:user-inputs
                  (relay/new-state {:user-input "x"
                                    :message-id "m0"})))))))

(deftest noop-reviewed-semantics-test
  (let [state (fresh)
        plan-id (relay/current-tenure-id state :plan)
        noop (ok! (review! state :plan plan-id
                           [{:key "x" :value nil}]))]
    (is (= :noop (outcome noop)))
    (is (true? (get-in noop [:receipt :reviewed?])))
    (is (:first-review? (relay/current-tenure (:state noop) :plan)))
    (let [asked (ok! (review! (:state noop) :plan plan-id []))]
      (is (= :ask (outcome asked)))
      (is (:awaiting-user? (relay/current-tenure (:state asked) :plan))))))

;; ---------------------------------------------------------------------------
;; Property tests

(def ^:private key-gen
  (gen/elements ["a" "b" "c" "d" "e"]))

(def ^:private value-gen
  (gen/fmap #(str "v" %) gen/string-alphanumeric))

(def ^:private finding-gen
  (gen/let [k key-gen
            v (gen/one-of [value-gen
                           (gen/return nil)])]
    {:key k :value v}))

(def ^:private findings-gen
  (gen/vector finding-gen 0 6))

(def ^:private sequence-op-gen
  (gen/one-of [(gen/fmap (fn [findings] {:op :review :findings findings})
                         findings-gen)
               (gen/return {:op :resume})]))

(defn- awaiting-target
  [state]
  (let [plan (get-in state [:lanes :plan])
        executions (relay/execution-tenures state)]
    (cond
      (:awaiting-user? plan)
      [:plan (:tenure-id plan)]

      :else
      (when-let [[id _] (first (filter (fn [[_ t]] (:awaiting-user? t))
                                       (sort-by key executions)))]
        [:execution id]))))

(defn- sequence-target
  [state]
  (let [executions (relay/execution-tenures state)
        ids (sort (keys executions))
        open (filter (fn [id]
                       (let [tenure (get executions id)]
                         (and (= :active (:status tenure))
                              (= :open (:review-window tenure)))))
                     ids)
        active (filter #(= :active (:status (get executions %))) ids)]
    (cond
      (seq open) [:execution (first open)]
      (= :pending (get-in state [:lanes :plan :milestone]))
      [:plan (relay/current-tenure-id state :plan)]
      (seq active) [:execution (first active)]
      :else [:plan (relay/current-tenure-id state :plan)])))

(defspec ledger-model-property 100
  (prop/for-all [initial (gen/map key-gen gen/string-alphanumeric)
                 findings findings-gen]
    (let [normalized (ledger/normalize-findings findings)]
      (and (:ok? normalized)
           (let [actual (ledger/apply-findings initial (:findings normalized))
                 expected (reduce (fn [l {:keys [key value]}]
                                    (if (nil? value)
                                      (dissoc l key)
                                      (assoc l key value)))
                                  initial
                                  (:findings normalized))]
             (= expected actual))))))

(defspec review-idempotency-property 50
  (prop/for-all [findings findings-gen]
    (let [state (fresh)
          plan-id (relay/current-tenure-id state :plan)
          cid (str "property-" (swap! call-counter inc))
          request {:call-id cid :lane :plan :tenure-id plan-id
                   :findings findings}
          first-call (relay/review state request)
          replay (relay/review (:state first-call) request)]
      (and (:ok? first-call)
           (:ok? replay)
           (true? (get-in replay [:receipt :replayed]))
           (empty? (:effects replay))
           (= (:state first-call) (:state replay))))))

(defspec review-sequence-invariants-property 50
  (prop/for-all [ops (gen/vector sequence-op-gen 0 12)]
    (loop [state (fresh)
           ops ops]
      (let [check (relay/check-invariants state)]
        (if-not (:ok? check)
          false
          (if-let [op (first ops)]
            (let [target (if (= :resume (:op op))
                           (or (awaiting-target state)
                               (sequence-target state))
                           (sequence-target state))
                  [lane tenure-id] target
                  result (if (= :resume (:op op))
                           (relay/resume state
                                         {:lane lane :tenure-id tenure-id})
                           (relay/review
                            state
                            {:call-id (str "seq-" (swap! call-counter inc))
                             :lane lane
                             :tenure-id tenure-id
                             :findings (:findings op)
                             :board (quiescent-board)}))]
              (recur (if (:ok? result) (:state result) state)
                     (rest ops)))
            true))))))

(defspec multiple-handoff-property 50
  (prop/for-all [handoffs (gen/choose 1 6)]
    (let [state (fresh {:plan-milestone :pending})
          plan-id (relay/current-tenure-id state :plan)
          result
          (loop [state state
                 remaining handoffs
                 ids #{}]
            (if (zero? remaining)
              {:state state :ids ids}
              (let [review-result
                    (relay/review
                     state
                     {:call-id (str "handoff-" (swap! call-counter inc))
                      :lane :plan
                      :tenure-id plan-id
                      :findings []})]
                (if (:ok? review-result)
                  (recur (:state review-result)
                         (dec remaining)
                         (conj ids (get-in review-result
                                           [:receipt :execution-tenure-id])))
                  {:state state :ids ids :error review-result}))))]
      (and (nil? (:error result))
           (= handoffs (count (:ids result)))
           (= handoffs (count (relay/execution-tenures (:state result))))
           (every? (fn [tenure]
                     (and (:initial? tenure)
                          (= {} (:ledger tenure))
                          (= :open (:review-window tenure))
                          (= :none (:swarm-phase tenure))))
                   (vals (relay/execution-tenures (:state result))))
           (:ok? (relay/check-invariants (:state result)))))))

(defspec swarm-root-order-property 50
  (prop/for-all [entries (gen/vector (gen/tuple key-gen value-gen) 1 5)]
    (let [findings (mapv (fn [[k v]] {:key k :value v}) entries)
          state (-> (fresh) drive-plan-to-pending! start-execution! :state
                    (start-swarm! findings)
                    :state)
          execution-id (relay/current-tenure-id state :execution)
          tasks (shuffle (root-tasks state))
          board (quiescent-board)
          final (reduce (fn [{:keys [state barrier-count]} task]
                          (let [result (finish-root! state task board)
                                state' (:state result)]
                            (if (and (:ok? result)
                                     (= :relayed (outcome result)))
                              {:state state'
                               :barrier-count (inc barrier-count)}
                              {:state state'
                               :barrier-count barrier-count})))
                        {:state state :barrier-count 0}
                        tasks)]
      (and (= (count (set (map first entries))) (count tasks))
           (= 1 (:barrier-count final))
           (= 1 (count (execution-barriers (:state final))))
           (= :execution (:milestone (sole-exec (:state final))))
           (not= execution-id
                 (relay/current-tenure-id (:state final) :execution))
           (:ok? (relay/check-invariants (:state final)))))))

(defspec closing-quiescence-property 100
  (prop/for-all [applying (gen/choose 0 2)
                 running (gen/choose 0 2)
                 uncertain (gen/choose 0 2)
                 unhandled-inputs (gen/choose 0 2)
                 call-children (gen/choose 0 2)
                 report-pending (gen/choose 0 2)
                 pending-rebase (gen/choose 0 2)
                 holds-lock gen/boolean
                 unresolved-token (gen/choose 0 2)
                 ff-ready gen/boolean]
    (let [board (merge (quiescent-board)
                       {:applying applying
                        :running running
                        :uncertain uncertain
                        :unhandled-inputs unhandled-inputs
                        :call-children call-children
                        :report-pending report-pending
                        :pending-rebase pending-rebase
                        :holds-write-lock? holds-lock
                        :unresolved-token-tasks unresolved-token
                        :ff-ready? ff-ready})
          quiescent? (and (zero? applying)
                          (zero? running)
                          (zero? uncertain)
                          (zero? unhandled-inputs)
                          (zero? call-children)
                          (zero? report-pending)
                          (zero? pending-rebase)
                          (not holds-lock)
                          (zero? unresolved-token))]
      (and (= quiescent? (barrier/closing-quiescent? board))
           (= ff-ready (barrier/ff-ready? board))))))

(deftest draft-version-and-source-markers-test
  (testing "set-draft stamps version, source, and user-edited flag"
    (let [state (fresh)
          written (ok! (relay/set-draft state
                                        {:path "plans/test.md"
                                         :content "Program body."}))
          draft (get-in (:state written) [:draft])]
      (is (= 2 (:version draft)))
      (is (= :program (:source draft)))
      (is (false? (:user-edited? draft)))
      (is (= 2 (get-in written [:receipt :draft-version])))
      (testing "a second program write keeps the version monotonic"
        (let [again (ok! (relay/set-draft (:state written)
                                          {:path "plans/test.md"
                                           :content "Program body v2."}))]
          (is (= 3 (:version (get-in (:state again) [:draft]))))
          (is (= :program
                 (:source (get-in (:state again) [:draft]))))))))

  (testing "execution/start effect carries draft source markers"
    (let [pending (drive-plan-to-pending! (fresh))
          plan-id (relay/current-tenure-id pending :plan)
          ;; Simulate the reconcile outcome: the draft was user-edited on
          ;; disk and adopted by the host before review.
          pending* (assoc-in pending [:draft]
                              (assoc (get-in pending [:draft])
                                     :source :user
                                     :user-edited? true
                                     :version 3))
          started (ok! (review! pending* :plan plan-id []))
          effect (first (:effects started))]
      (is (= :execution/start (:type effect)))
      (is (= :user (:draft-source effect)))
      (is (true? (:draft-user-edited? effect)))
      (is (= "Draft body." (:draft effect))))))

(deftest draft-freeze-after-handoff-still-holds-test
  (let [state (-> (fresh) drive-plan-to-pending! start-execution! :state)
        frozen (relay/set-draft state
                                {:path "plans/test.md" :content "changed"})]
    (is (= :DRAFT_LOCKED_AFTER_HANDOFF (error-code frozen)))
    (is (= "Draft body." (get-in (:state frozen) [:draft :content])))
    (is (= :program (get-in (:state frozen) [:draft :source])))))
