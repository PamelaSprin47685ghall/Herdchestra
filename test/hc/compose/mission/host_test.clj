(ns hc.compose.mission.host-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.host :as mission-host]
            [hc.compose.mission.proof :as proof]
            [hc.compose.mission.support :as support]
            [hc.core.relay.barrier :as barrier]))

(defn- ok!
  [result]
  (is (:ok? result) (pr-str (:error result)))
  result)

(deftest plan-write-lock-is-tenure-scoped
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)]
    (testing "an unreviewed Plan tenure is read-only"
      (is (= :WRITE_LOCKED_BEFORE_FIRST_REVIEW
             (get-in (mission/set-draft! h {:path "plans/a.md"
                                            :content "draft"
                                            :tenure-id plan-id})
                     [:error :code]))))
    (testing "the initial S1 ask is a legal human wait"
      (let [asked (support/review! h :plan plan-id [])]
        (is (= :ask (get-in asked [:receipt :outcome])))
        (is (= :AWAITING_USER
               (get-in (mission/set-draft! h {:path "plans/a.md"
                                              :content "draft"
                                              :tenure-id plan-id})
                       [:error :code])))))
    (mission/resume! h {:lane :plan :tenure-id plan-id})
    (testing "resume does not count as the first review"
      (is (= :WRITE_LOCKED_BEFORE_FIRST_REVIEW
             (get-in (mission/set-draft! h {:path "plans/a.md"
                                            :content "draft"
                                            :tenure-id plan-id})
                     [:error :code]))))
    (testing "the first accepted review unlocks writing for this tenure"
      (is (= :ledger-updated
             (get-in (support/review! h :plan plan-id
                                      [{:key "k" :value "v"}])
                     [:receipt :outcome])))
      (is (:ok? (mission/set-draft! h {:path "plans/a.md"
                                       :content "draft body"
                                       :tenure-id plan-id})))
      (is (= :DRAFT_PATH_LOCKED
             (get-in (mission/set-draft! h {:path "plans/b.md"
                                            :content "other"
                                            :tenure-id plan-id})
                     [:error :code]))))
    (testing "a relay opens a fresh read-only Plan tenure"
      (is (= :relayed
             (get-in (support/review! h :plan plan-id
                                      [{:key "k" :value nil}])
                     [:receipt :outcome])))
      (is (= :TENURE_RETIRED
             (get-in (mission/set-draft! h {:path "plans/a.md"
                                            :content "late draft"
                                            :tenure-id plan-id})
                     [:error :code]))
          "a late draft write cannot attach to the successor")
      (is (= :WRITE_LOCKED_BEFORE_FIRST_REVIEW
             (get-in (mission/set-draft! h {:path "plans/a.md"
                                            :content "new tenure"
                                            :tenure-id (support/plan-tenure-id h)})
                     [:error :code]))
          "the successor is read-only until its own first review"))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest draft-first-write-claim-is-transactional
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)]
    (support/review! h :plan plan-id [{:key "k" :value "v"}])
    (let [first (mission/apply-command!
                 h {:op :claim-draft
                    :path "plans/a.md"
                    :request-id "claim-1"
                    :call-id "call-1"
                    :tenure-id plan-id})]
      (is (:ok? first))
      (is (= :draft-claimed (get-in first [:receipt :outcome]))))
    (testing "a concurrent first writer cannot take another name"
      (is (= :DRAFT_NAME_TAKEN
             (get-in (mission/apply-command!
                      h {:op :claim-draft
                         :path "plans/b.md"
                         :request-id "claim-2"
                         :tenure-id plan-id})
                     [:error :code]))))
    (testing "the same request and the same path are idempotent"
      (is (= :draft-claim-replayed
             (get-in (mission/apply-command!
                      h {:op :claim-draft
                         :path "plans/a.md"
                         :request-id "claim-1"
                         :tenure-id plan-id})
                     [:receipt :outcome])))
      (is (:ok? (mission/apply-command!
                 h {:op :claim-draft
                    :path "plans/a.md"
                    :request-id "claim-same-path"
                    :tenure-id plan-id}))))
    (testing "a failed writer releases its claim"
      (is (:ok? (mission/apply-command!
                 h {:op :release-draft
                    :path "plans/a.md"
                    :request-id "claim-1"})))
      (is (:ok? (mission/apply-command!
                 h {:op :claim-draft
                    :path "plans/b.md"
                    :request-id "claim-2"
                    :tenure-id plan-id}))))
    (testing "the final draft promotes the claim and fixes the path"
      (is (:ok? (mission/set-draft! h {:path "plans/b.md"
                                       :content "body"
                                       :tenure-id plan-id})))
      (is (nil? (:draft-claim (mission/snapshot h))))
      (is (= :DRAFT_PATH_LOCKED
             (get-in (mission/apply-command!
                      h {:op :claim-draft
                         :path "plans/a.md"
                         :request-id "claim-3"
                         :tenure-id plan-id})
                     [:error :code]))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest check-draft-is-read-only-and-tenure-scoped
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)]
    (testing "an unreviewed tenure refuses without creating a claim"
      (let [before (mission/snapshot h)
            result (mission/apply-command! h {:op :check-draft
                                              :tenure-id plan-id
                                              :path "plans/a.md"})]
        (is (= :WRITE_LOCKED_BEFORE_FIRST_REVIEW
               (get-in result [:error :code])))
        (is (nil? (:draft-claim (mission/snapshot h))))
        (is (= before (mission/snapshot h)))))
    (support/review! h :plan plan-id [{:key "k" :value "v"}])
    (testing "a reviewed tenure checks the path without claiming"
      (let [before (mission/snapshot h)
            result (mission/apply-command! h {:op :check-draft
                                              :tenure-id plan-id
                                              :path "plans/a.md"})]
        (is (:ok? result) (pr-str (:error result)))
        (is (= :draft-checked (get-in result [:receipt :outcome])))
        (is (= plan-id (get-in result [:receipt :tenure-id])))
        (is (true? (get-in result [:receipt :reviewed-in-tenure?])))
        (is (= "plans/a.md" (get-in result [:receipt :draft-path])))
        (is (false? (get-in result [:receipt :handoff?])))
        (is (nil? (:draft-claim (mission/snapshot h))))
        (is (= before (mission/snapshot h)))))
    (testing "a fixed draft path is reported as already fixed"
      (is (:ok? (mission/set-draft! h {:path "plans/a.md"
                                       :content "body"
                                       :tenure-id plan-id})))
      (let [result (mission/apply-command! h {:op :check-draft
                                              :tenure-id plan-id
                                              :path "plans/a.md"})]
        (is (= :draft-already-fixed (get-in result [:receipt :outcome])))
        (is (= "plans/a.md" (get-in result [:receipt :draft-path])))))
    (testing "a retired tenure is refused before any path or lock check"
      (support/review! h :plan plan-id [{:key "k" :value nil}])
      (is (= :TENURE_RETIRED
             (get-in (mission/apply-command! h {:op :check-draft
                                                :tenure-id plan-id
                                                :path "plans/a.md"})
                     [:error :code]))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest draft-claim-restart-is-conservative
  (let [h (support/host {:owner-instance-id "instance-1"})
        plan-id (support/plan-tenure-id h)]
    (support/review! h :plan plan-id [{:key "k" :value "v"}])
    (is (:ok? (mission/apply-command!
               h {:op :claim-draft
                  :path "plans/a.md"
                  :request-id "claim-1"
                  :call-id "call-1"
                  :tenure-id plan-id})))
    (let [state (mission/snapshot h)
          same (mission/restore state {:owner-instance-id "instance-1"})
          dead (mission/restore state {:owner-instance-id "instance-2"})
          legacy-state (update state :draft-claim dissoc :owner-instance-id)
          legacy (mission/restore legacy-state
                                  {:owner-instance-id "instance-3"})]
      (testing "same Owner instance keeps the in-flight claim"
        (is (:ok? same))
        (is (= "plans/a.md" (get-in same [:state :draft-claim :path]))))
      (testing "a different Owner instance proves the old continuation dead"
        (is (:ok? dead))
        (is (nil? (get-in dead [:state :draft-claim])))
        (is (= "instance-2" (get-in dead [:state :owner-instance-id])))
        (let [host (mission/restore-host
                    (:state dead) {:owner-instance-id "instance-2"})
              claimed (mission/apply-command!
                       host {:op :claim-draft
                             :path "plans/b.md"
                             :request-id "claim-2"
                             :tenure-id (support/plan-tenure-id host)})]
          (is (:ok? claimed))))
      (testing "a legacy claim without an instance keeps uncertain responsibility"
        (is (:ok? legacy))
        (is (= "plans/a.md" (get-in legacy [:state :draft-claim :path])))
        (let [host (mission/restore-host
                    (:state legacy) {:owner-instance-id "instance-3"})
              refused (mission/apply-command!
                       host {:op :claim-draft
                             :path "plans/b.md"
                             :request-id "claim-3"
                             :tenure-id (support/plan-tenure-id host)})]
          (is (= :DRAFT_NAME_TAKEN (get-in refused [:error :code]))))))))

(deftest review-call-id-is-idempotent
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)
        request {:lane :plan
                 :tenure-id plan-id
                 :call-id "review-call"
                 :findings []}
        first-result (ok! (mission/review! h request))
        replay (ok! (mission/review! h request))
        conflict (mission/review! h (assoc request
                                           :findings [{:key "k" :value "v"}]))]
    (is (= :ask (get-in first-result [:receipt :outcome])))
    (is (true? (get-in replay [:receipt :replayed])))
    (is (empty? (:effects replay)))
    (is (= :CALL_ID_PAYLOAD_CONFLICT (get-in conflict [:error :code])))))

(deftest execution-handoff-requires-a-draft-and-creates-nothing-on-rejection
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)]
    (support/review! h :plan plan-id [])
    (mission/resume! h {:lane :plan :tenure-id plan-id})
    (support/review! h :plan plan-id [])
    (support/review! h :plan (support/plan-tenure-id h) [])
    (support/review! h :plan (support/plan-tenure-id h) [])
    (support/review! h :plan (support/plan-tenure-id h) [])
    (is (= :pending (get-in (mission/snapshot h)
                            [:relay :lanes :plan :milestone])))
    (let [rejected (support/review! h :plan (support/plan-tenure-id h) [])]
      (is (false? (:ok? rejected)))
      (is (= :DRAFT_MISSING_OR_EMPTY (get-in rejected [:error :code])))
      (is (empty? (support/execution-tenure-ids h)))
      (is (= :pending (get-in (mission/snapshot h)
                              [:relay :lanes :plan :milestone])))
      (is (empty? (get-in (mission/snapshot h) [:boards]))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest swarm-start-creates-real-board-roots-and-model-bindings
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        started (support/start-swarm! h execution-id
                                     [{:key "alpha" :value "Do alpha"}
                                      {:key "beta" :value "Do beta"}])
        roots (support/root-task-ids h execution-id)
        state (mission/snapshot h)]
    (is (= :swarm-started (get-in started [:receipt :outcome])))
    (is (= 2 (count roots)))
    (is (= #{"alpha" "beta"}
           (set (map :ledger-key (support/board-tasks h execution-id)))))
    (is (every? #(= :ready (:state %))
                (support/board-tasks h execution-id)))
    (is (= :main (get-in state [:bindings :execution execution-id
                                :reviewer :model-role])))
    (is (= "main-model" (get-in state [:bindings :execution execution-id
                                       :reviewer :model-id])))
    (is (= :main (get-in state [:bindings :plan :model-role])))
    (is (= :swarm (get-in state [:bindings :execution execution-id
                                 :reviewer :phase])))
    (testing "the swarm-start effect is the wake-all dispatch"
      (let [effect (first (:effects started))]
        (is (= :swarm/start (:type effect)))
        (is (= :all (:wake effect)))
        (is (= (set roots) (set (keys (:board-root-tasks effect)))))))
    (testing "a registered worker is bound to the task model"
      (let [worker-id (support/register-worker! h execution-id :slot-a)
            binding (mission/role-binding (mission/snapshot h)
                                          {:role :worker
                                           :tenure-id execution-id
                                           :agent-id worker-id})]
        (is (= :task (:model-role binding)))
        (is (= "task-model" (:model-id binding)))
        (is (= :worker (:role binding)))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest finish-rejection-does-not-partially-commit
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        worker-id (support/register-worker! h execution-id :slot-a)
        task-id (first (support/root-task-ids h execution-id))
        claim (ok! (mission/claim-task! h {:tenure-id execution-id
                                           :actor-id worker-id
                                           :task-id task-id
                                           :atomicity-reason "atomic root"}))
        before (mission/snapshot h)
        rejected (mission/finish-root-task! h
                                           {:tenure-id execution-id
                                            :actor-id worker-id
                                            :task-id task-id
                                            :claim-id :wrong-claim
                                            :result-summary "should not commit"
                                            :board-facts {:ff-ready? true}})
        after (mission/snapshot h)]
    (is (false? (:ok? rejected)))
    (is (= :CLAIM_CHANGED (get-in rejected [:error :code])))
    (is (= before after)
        "a rejected board finish must not advance the board or the relay")
    (is (= :ready (get-in after [:boards execution-id :tasks task-id :state])))
    (is (= #{"k"} (set (keys (get-in after [:relay :lanes :execution
                                            execution-id :ledger])))))
    (is (= :active (get-in after [:relay :lanes :execution
                                  execution-id :swarm-phase])))
    (testing "the correct claim finishes and raises the atomic barrier"
      (let [finished (ok! (support/finish-task! h execution-id worker-id
                                                task-id
                                                (get-in claim [:receipt :claim-id])
                                                "done"))]
        (is (= :relayed (get-in finished [:receipt :outcome])))
        (is (true? (get-in finished [:receipt :relayed?])))
        (is (string? (get-in finished [:receipt :barrier-id])))))))

(deftest unhandled-user-input-defers-root-barrier-and-survives-test
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        worker-id (support/register-worker! h execution-id :slot-a)
        task-id (first (support/root-task-ids h execution-id))
        claim (ok! (mission/claim-task! h {:tenure-id execution-id
                                           :actor-id worker-id
                                           :task-id task-id
                                           :atomicity-reason "atomic root"}))
        input (ok! (mission/apply-command!
                    h
                    {:op :board-command
                     :tenure-id execution-id
                     :command {:op :user-input
                               :text "Also check the startup path."
                               :message-id "message-1"
                               :attachments
                               [{:attachment-id "a1"
                                 :blob-id "blob-1"
                                 :name "spec.txt"
                                 :mime "text/plain"
                                 :size 12
                                 :content "secret body"
                                 :resolution "agent summary"}]}}))
        input-id (get-in input [:receipt :input-id])
        finished (ok! (support/finish-task! h execution-id worker-id
                                            task-id
                                            (get-in claim [:receipt :claim-id])
                                            "done"))
        state (mission/snapshot h)]
    (is (= :relay-pending (get-in finished [:receipt :outcome])))
    (is (some #{:unhandled-inputs}
              (get-in finished [:receipt :reasons])))
    (is (= 1 (:unhandled-inputs
              (mission/board-summary state execution-id
                                     {:ff-ready? true}))))
    (testing "the board and its public input survive the deferred barrier"
      (is (= :unhandled (get-in state [:boards execution-id
                                       :inputs input-id :state])))
      (is (= execution-id (-> state :relay :lanes :execution keys first)))
      (is (true? (get-in state [:relay :lanes :execution execution-id
                                :relay-pending?])))
      (is (not-any? #(= :execution (:lane %))
                    (get-in state [:relay :barriers]))))

    (testing "handling the input lets the barrier retire the tenure"
      (ok! (mission/apply-command!
            h
            {:op :board-command
             :tenure-id execution-id
             :command {:op :resolve-input
                       :actor-id worker-id
                       :input-id input-id
                       :summary "Startup path included in the root result."}}))
      (let [relayed (ok! (mission/maybe-relay!
                          h
                          {:tenure-id execution-id
                           :board-facts {:ff-ready? true}}))
            final (mission/snapshot h)
            successor-id (-> final :relay :lanes :execution keys first)
            barrier-record (last (get-in final [:relay :barriers]))
            context (:context barrier-record)]
        (is (= :relayed (get-in relayed [:receipt :outcome])))
        (is (not= execution-id successor-id))
        (is (not (contains? (:boards final) execution-id)))
        (is (= :main (get-in final [:bindings :execution successor-id
                                    :reviewer :model-role])))
        (is (str/includes? (:user-input context)
                           "Build the requested feature."))
        (is (str/includes? (:user-input context)
                           "Also check the startup path."))
        (is (not (str/includes? (:user-input context)
                                "Startup path included in the root result.")))
        (is (not (contains? context :resolution)))
        (is (= [{:attachment-id "a1"
                 :blob-id "blob-1"
                 :name "spec.txt"
                 :mime "text/plain"
                 :size 12
                 :message-id "message-1"}]
               (:user-attachments context)))
        (is (not (contains? (first (:user-attachments context)) :content)))
        (is (= 2 (count (get-in final [:relay :user-inputs]))))
        (is (= "message-1"
               (get-in final [:relay :user-inputs 1 :message-id])))
        (is (= [{:attachment-id "a1"
                 :blob-id "blob-1"
                 :name "spec.txt"
                 :mime "text/plain"
                 :size 12}]
               (get-in final [:relay :user-inputs 1 :attachments])))
        (is (:ok? (mission/check-invariants final)))))))

(deftest root-reopen-restores-board-and-relay-obligation-test
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id
                                [{:key "a" :value "Do a"}
                                 {:key "b" :value "Do b"}])
        worker-id (support/register-worker! h execution-id :slot-a)
        board-tasks (support/board-tasks h execution-id)
        task-a (some #(when (= "a" (:ledger-key %)) (:task-id %))
                     board-tasks)
        task-b (some #(when (= "b" (:ledger-key %)) (:task-id %))
                     board-tasks)
        first-finish (ok! (support/claim-and-finish-root!
                           h execution-id worker-id task-a))
        state-1 (mission/snapshot h)
        reopened (ok! (mission/apply-command!
                       h
                       {:op :board-command
                        :tenure-id execution-id
                        :command {:op :reopen
                                  :actor-id worker-id
                                  :task-id task-a
                                  :reason "The first result is insufficient."}}))
        state-2 (mission/snapshot h)
        tenure (get-in state-2 [:relay :lanes :execution execution-id])
        mirror (some #(when (= "a" (:ledger-key %)) %)
                     (:root-tasks tenure))]
    (is (= :root-finished (get-in first-finish [:receipt :outcome])))
    (is (= #{"b"} (set (keys (get-in state-1
                                     [:relay :lanes :execution
                                      execution-id :ledger])))))
    (is (= :root-reopened (get-in reopened [:receipt :outcome])))
    (testing "board, relay ledger, relay mirror, and host binding reopen together"
      (is (= :ready (get-in state-2 [:boards execution-id
                                     :tasks task-a :state])))
      (is (= #{"a" "b"}
             (set (keys (get-in state-2 [:relay :lanes :execution
                                         execution-id :ledger])))))
      (is (= "Do a" (get-in tenure [:ledger "a"])))
      (is (= :ready (:state mirror)))
      (is (= :ready (get-in state-2 [:bindings :execution execution-id
                                     :root-tasks task-a :state])))
      (is (false? (:relay-pending? tenure)))
      (is (= #{"a" "b"} (set (keys (get-in state-2 [:boards execution-id
                                                     :ledger])))))
      (is (:ok? (mission/check-invariants state-2))))

    (testing "finishing both roots again raises the barrier"
      (ok! (support/claim-and-finish-root! h execution-id worker-id task-a))
      (let [final-finish (ok! (support/claim-and-finish-root!
                               h execution-id worker-id task-b))
            final (mission/snapshot h)]
        (is (= :relayed (get-in final-finish [:receipt :outcome])))
        (is (not (contains? (:boards final) execution-id)))
        (is (:ok? (mission/check-invariants final)))))))

(deftest root-reopen-clears-a-pending-barrier-test
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        worker-id (support/register-worker! h execution-id :slot-a)
        task-id (first (support/root-task-ids h execution-id))
        claim (ok! (mission/claim-task! h {:tenure-id execution-id
                                           :actor-id worker-id
                                           :task-id task-id
                                           :atomicity-reason "atomic root"}))
        input (ok! (mission/apply-command!
                    h
                    {:op :board-command
                     :tenure-id execution-id
                     :command {:op :user-input
                               :text "Check the startup path."
                               :message-id "pending-message"}}))
        input-id (get-in input [:receipt :input-id])
        pending (ok! (support/finish-task! h execution-id worker-id
                                           task-id
                                           (get-in claim [:receipt :claim-id])
                                           "done"))
        reopened (ok! (mission/apply-command!
                       h
                       {:op :board-command
                        :tenure-id execution-id
                        :command {:op :reopen
                                  :actor-id worker-id
                                  :task-id task-id
                                  :reason "Need another pass."}}))
        state (mission/snapshot h)]
    (is (= :relay-pending (get-in pending [:receipt :outcome])))
    (is (true? (get-in pending [:state :relay :lanes :execution
                                execution-id :relay-pending?])))
    (is (= :root-reopened (get-in reopened [:receipt :outcome])))
    (is (false? (get-in state [:relay :lanes :execution execution-id
                               :relay-pending?])))
    (is (= #{"k"} (set (keys (get-in state [:relay :lanes :execution
                                            execution-id :ledger])))))
    (is (= :ready (get-in state [:boards execution-id :tasks task-id
                                 :state])))
    (testing "resolving the input and finishing the root raises the barrier"
      (ok! (mission/apply-command!
            h
            {:op :board-command
             :tenure-id execution-id
             :command {:op :resolve-input
                       :actor-id worker-id
                       :input-id input-id
                       :summary "Startup path checked."}}))
      (let [finished (ok! (support/claim-and-finish-root!
                           h execution-id worker-id task-id))
            final (mission/snapshot h)]
        (is (= :relayed (get-in finished [:receipt :outcome])))
        (is (not (contains? (:boards final) execution-id)))
        (is (:ok? (mission/check-invariants final)))))))

(deftest finish-root-preserves-verified-read-set-evidence
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        worker-id (support/register-worker! h execution-id :slot-a)
        task-id (first (support/root-task-ids h execution-id))
        claim (ok! (mission/claim-task! h {:tenure-id execution-id
                                           :actor-id worker-id
                                           :task-id task-id
                                           :atomicity-reason "verified root"}))
        read-set [{:path "plans/demo.md"
                   :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}]
        finished (ok! (mission/finish-root-task!
                       h
                       {:tenure-id execution-id
                        :actor-id worker-id
                        :task-id task-id
                        :claim-id (get-in claim [:receipt :claim-id])
                        :result-summary "verified finish"
                        :verified-read-set read-set
                        :env {:write-gate-proof (proof/token)}
                        :board-facts (support/quiescent-facts)}))
        result-ref (get-in finished [:receipt :result-ref])]
    (is (= :relayed (get-in finished [:receipt :outcome])))
    (is (= read-set (:read-set result-ref)))
    (is (= read-set (:verified-read-set result-ref)))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest finish-root-rejects-a-forged-write-gate-proof
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        worker-id (support/register-worker! h execution-id :slot-a)
        task-id (first (support/root-task-ids h execution-id))
        claim (ok! (mission/claim-task! h {:tenure-id execution-id
                                           :actor-id worker-id
                                           :task-id task-id
                                           :atomicity-reason "forged root"}))
        barriers-before (count (get-in (mission/snapshot h)
                                       [:relay :barriers]))
        read-set [{:path "plans/demo.md"
                   :stamp {:mtime-sec 1 :mtime-nsec 2 :size 3}}]]
    (doseq [forged [true "forged" :forged {:token "forged"} (Object.)]]
      (let [result (mission/finish-root-task!
                    h
                    {:tenure-id execution-id
                     :actor-id worker-id
                     :task-id task-id
                     :claim-id (get-in claim [:receipt :claim-id])
                     :result-summary "forged finish"
                     :verified-read-set read-set
                     :env {:write-gate-proof forged
                           :under-write-gate? true}
                     :board-facts (support/quiescent-facts)})]
        (is (false? (:ok? result)))
        (is (= :WRITE_GATE_REQUIRED (get-in result [:error :code])))
        (is (= barriers-before
               (count (get-in (mission/snapshot h)
                              [:relay :barriers]))))))
    (is (= :ready
           (get-in (mission/snapshot h)
                   [:boards execution-id :tasks task-id :state])))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest atomic-barrier-swaps-tenure-board-and-binding-together
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        old-id (support/sole-execution-id h)
        _ (support/start-swarm! h old-id [{:key "k" :value "Do it"}])
        worker-id (support/register-worker! h old-id :slot-a)
        task-id (first (support/root-task-ids h old-id))
        finished (ok! (support/claim-and-finish-root! h old-id worker-id task-id))
        state (mission/snapshot h)
        new-ids (set (support/execution-tenure-ids h))
        new-id (first (disj new-ids old-id))
        barrier-record (last (get-in state [:relay :barriers]))]
    (is (= :relayed (get-in finished [:receipt :outcome])))
    (is (true? (get-in finished [:receipt :relayed?])))
    (is (= #{new-id} new-ids))
    (is (contains? (get-in state [:relay :retired-tenure-ids]) old-id))
    (testing "the old board and role bindings are gone"
      (is (not (contains? (get-in state [:boards]) old-id)))
      (is (not (contains? (get-in state [:bindings :execution]) old-id))))
    (testing "the successor Reviewer is bound in the same committed value"
      (is (= :reviewer (get-in state [:bindings :execution new-id
                                      :reviewer :role])))
      (is (= :main (get-in state [:bindings :execution new-id
                                  :reviewer :model-role])))
      (is (= "main-model" (get-in state [:bindings :execution new-id
                                         :reviewer :model-id])))
      (is (map? (get-in state [:boards new-id])))
      (is (= :open (get-in state [:relay :lanes :execution new-id
                                 :review-window]))))
    (testing "the barrier context is narrow and predecessor-private state is hidden"
      (is (= old-id (:from-tenure-id barrier-record)))
      (is (= new-id (:to-tenure-id barrier-record)))
      (is (true? (:predecessor-hidden? barrier-record)))
      (is (every? barrier/visible-context-keys
                  (keys (:context barrier-record))))
      (is (not (contains? (:context barrier-record) :ledger)))
      (is (not (contains? (:context barrier-record) :findings)))
      (is (not (contains? (:context barrier-record) :board))))
    (testing "a late request from the retired tenure is rejected"
      (is (= :TENURE_RETIRED
             (get-in (mission/finish-root-task!
                      h
                      {:tenure-id old-id
                       :actor-id worker-id
                       :task-id task-id
                       :result-summary "late"})
                     [:error :code]))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest multiple-execution-tenures-have-independent-boards-and-barriers
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        plan-id (support/plan-tenure-id h)
        first-handoff (ok! (mission/review! h {:lane :plan
                                               :tenure-id plan-id
                                               :call-id "handoff-1"
                                               :findings []}))
        second-handoff (ok! (mission/review! h {:lane :plan
                                                :tenure-id plan-id
                                                :call-id "handoff-2"
                                                :findings []}))
        first-id (get-in first-handoff [:receipt :execution-tenure-id])
        second-id (get-in second-handoff [:receipt :execution-tenure-id])]
    (is (not= first-id second-id))
    (is (= #{first-id second-id}
           (set (support/execution-tenure-ids h))))
    (support/start-swarm! h first-id [{:key "first" :value "First"}])
    (support/start-swarm! h second-id [{:key "second" :value "Second"}])
    (is (= #{"first"}
           (set (map :ledger-key (support/board-tasks h first-id)))))
    (is (= #{"second"}
           (set (map :ledger-key (support/board-tasks h second-id)))))
    (let [worker-id (support/register-worker! h first-id :slot-a)
          task-id (first (support/root-task-ids h first-id))]
      (ok! (support/claim-and-finish-root! h first-id worker-id task-id)))
    (is (contains? (get-in (mission/snapshot h) [:relay :retired-tenure-ids])
                   first-id))
    (is (= :active (get-in (mission/snapshot h)
                           [:relay :lanes :execution second-id :swarm-phase])))
    (is (= :ready (get-in (mission/snapshot h)
                          [:boards second-id :tasks
                           (first (support/root-task-ids h second-id))
                           :state])))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest abandon-swarm-marks-the-tenure-and-clears-its-board
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        tenure-id (support/sole-execution-id h)
        _ (support/start-swarm! h tenure-id [{:key "slice-a" :value "work"}])
        _ (support/register-worker! h tenure-id :slot-a)
        result (mission-host/abandon-swarm! h {:swarm-id "swarm-1"
                                               :slice-id "slice-a"
                                               :reason "master moved"
                                               :at 4242})]
    (is (:ok? result) (pr-str (:error result)))
    (is (= tenure-id (:tenure-id result)))
    (let [state (:state result)
          tenure (get-in state [:relay :lanes :execution tenure-id])
          board* (get-in state [:boards tenure-id])]
      (is (= :abandoned (:status tenure)))
      (is (= "master moved" (:abandoned-reason tenure)))
      (is (= 4242 (:retired-at tenure)))
      (is (empty? (:tasks board*)))
      (is (empty? (:claims board*)))
      (is (empty? (:actions board*)))
      (is (= :free (get-in board* [:token-slot :state])))
      (is (some #(= :swarm-abandoned (:type %)) (:events board*)))
      (is (= :active (get-in state [:relay :lanes :plan :status])))
      (is (= :abandoned
             (get-in state [:bindings :execution tenure-id
                            :reviewer :status])))
      (is (= tenure-id (get-in state [:swarm-abandonments "swarm-1"]))))
    (is (:ok? (mission/check-invariants (:state result)))
        (pr-str (:violations (mission/check-invariants (:state result)))))
    (testing "a repeated abandon is idempotent and keeps the original record"
      (let [again (mission-host/abandon-swarm! h {:swarm-id "swarm-1"
                                                  :slice-id "slice-a"
                                                  :reason "again"})]
        (is (:ok? again) (pr-str (:error again)))
        (is (true? (:already? again)))
        (is (= "master moved"
               (get-in again
                       [:state :relay :lanes :execution tenure-id
                        :abandoned-reason])))))))

(deftest plan-dedicated-worker-reuses-and-resets-per-tenure
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)
        _ (support/review! h :plan plan-id [{:key "k" :value "v"}])
        first (ok! (mission/devops-request! h {:tenure-id plan-id
                                               :call-id "devops-1"
                                               :verbose-intent "inspect"}))
        first-id (get-in first [:receipt :worker-id])
        completed (ok! (mission/devops-complete! h
                                                 {:tenure-id plan-id
                                                  :worker-id first-id
                                                  :call-id "devops-1"
                                                  :status :ok
                                                  :report "verbatim\nreport"}))
        second (ok! (mission/devops-request! h {:tenure-id plan-id
                                                :call-id "devops-2"
                                                :verbose-intent "inspect again"}))]
    (is (= :task (get-in first [:receipt :model-role])))
    (is (= "task-model" (get-in first [:receipt :model-id])))
    (is (= :plan-worker (get-in first [:receipt :contract :role])))
    (is (false? (get-in first [:receipt :contract :board?])))
    (is (false? (get-in first [:receipt :contract :token?])))
    (is (false? (get-in first [:receipt :contract :write?])))
    (is (= "verbatim\nreport" (get-in completed [:receipt :report])))
    (is (= first-id (get-in second [:receipt :worker-id])))
    (is (true? (get-in second [:receipt :reused?])))
    (testing "clearing the Plan ledger retires the worker with the tenure"
      (ok! (mission/review! h {:lane :plan
                               :tenure-id plan-id
                               :call-id "clear-plan"
                               :findings [{:key "k" :value nil}]}))
      (is (nil? (mission/role-binding (mission/snapshot h)
                                      {:role :plan-worker})))
      (let [new-plan-id (support/plan-tenure-id h)
            third (ok! (mission/devops-request! h
                                                {:tenure-id new-plan-id
                                                 :call-id "devops-3"
                                                 :verbose-intent "fresh tenure"}))]
        (is (not= first-id (get-in third [:receipt :worker-id])))
        (is (false? (get-in third [:receipt :reused?])))))
    (testing "devops call ids replay without steering again"
      (let [replay (ok! (mission/devops-request! h
                                                 {:tenure-id (support/plan-tenure-id h)
                                                  :call-id "devops-3"
                                                  :verbose-intent "fresh tenure"}))]
        (is (true? (get-in replay [:receipt :replayed])))
        (is (empty? (:effects replay)))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest holder-lifecycle-and-board-command-idempotency
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
        holder-id (support/register-worker! h execution-id :slot-holder)
        worker-id (support/register-worker! h execution-id :slot-worker)
        _ (swap! (:state h)
                 update-in [:boards execution-id :tasks]
                 assoc :token-task
                 {:task-id :token-task
                  :state :ready
                  :needs-token true
                  :owner-id nil
                  :claim-id nil
                  :rev 0
                  :wait-for #{}
                  :child-ids []
                  :result-refs []})
        token-request {:tenure-id execution-id
                       :actor-id holder-id
                       :request-id "token-request-1"
                       :confirm :i-hereby-confirm-i-die-without-regret}
        won (ok! (mission/take-or-die! h token-request))
        _ (swap! (:state h)
                 (fn [s]
                   (:state
                    (mission/apply-command
                     s
                     {:op :board-command
                      :tenure-id execution-id
                      :command {:op :claim
                                :actor-id holder-id
                                :task-id :token-task
                                :atomicity-reason "test"}}))))
        _ (swap! (:state h)
                 (fn [s]
                   (:state
                    (mission/apply-command
                     s
                     {:op :board-command
                      :tenure-id execution-id
                      :command {:op :finish
                                :actor-id holder-id
                                :task-id :token-task
                                :claim-id (get-in s [:boards execution-id
                                                     :tasks :token-task :claim-id])
                                :result-summary "done"}}))))
        replayed (ok! (mission/take-or-die! h token-request))
        conflict (mission/take-or-die! h (assoc token-request
                                                :confirm :wrong))]
    (is (= :attempt-won (get-in won [:receipt :result])))
    (is (false? (get-in won [:receipt :retired?]))
        "a holder with a token task stays alive after winning")
    (is (= :dead (get-in (mission/snapshot h)
                         [:bindings :execution execution-id
                          :swarm holder-id :status])))
    (is (true? (get-in replayed [:receipt :replayed])))
    (is (empty? (:effects replayed)))
    (is (= :REQUEST_ID_CONFLICT (get-in conflict [:error :code])))
    (is (= "token-request-1"
           (get-in conflict [:error :details :request-id])))
    (testing "finish-root-task request-id replays after the barrier"
      (let [task-id (first (support/root-task-ids h execution-id))
            claim (ok! (mission/claim-task! h
                                            {:tenure-id execution-id
                                             :actor-id worker-id
                                             :task-id task-id
                                             :atomicity-reason "atomic root"}))
            request {:tenure-id execution-id
                     :actor-id worker-id
                     :task-id task-id
                     :claim-id (get-in claim [:receipt :claim-id])
                     :result-summary "done"
                     :request-id "finish-request-1"
                     :board-facts {:ff-ready? true}}
            finished (ok! (mission/finish-root-task! h request))
            replay (ok! (mission/finish-root-task! h request))
            changed (mission/finish-root-task! h (assoc request
                                                         :result-summary
                                                         "different"))]
        (is (= :relayed (get-in finished [:receipt :outcome])))
        (is (true? (get-in replay [:receipt :replayed])))
        (is (empty? (:effects replay)))
        (is (= :REQUEST_ID_CONFLICT (get-in changed [:error :code])))
        (is (= "finish-request-1"
               (get-in changed [:error :details :request-id])))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest plan-worker-survives-milestone-promotion
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)]
    (support/review! h :plan plan-id [])
    (mission/resume! h {:lane :plan :tenure-id plan-id})
    (support/review! h :plan plan-id [])
    (let [successor-id (support/plan-tenure-id h)
          first (ok! (mission/devops-request! h
                                              {:tenure-id successor-id
                                               :call-id "promote-worker-1"
                                               :verbose-intent "inspect S1"}))
          worker-id (get-in first [:receipt :worker-id])]
      (ok! (mission/devops-complete! h
                                     {:tenure-id successor-id
                                      :worker-id worker-id
                                      :call-id "promote-worker-1"
                                      :status :ok
                                      :report "S1 report"}))
      (let [promoted (ok! (support/review! h :plan successor-id []))
            second (ok! (mission/devops-request! h
                                                 {:tenure-id successor-id
                                                  :call-id "promote-worker-2"
                                                  :verbose-intent "inspect S2"}))]
        (is (= :S2 (get-in promoted [:receipt :to-milestone])))
        (is (= worker-id (get-in second [:receipt :worker-id])))
        (is (true? (get-in second [:receipt :reused?])))))
    (is (:ok? (mission/check-invariants (mission/snapshot h))))))

(deftest devops-convenience-returns-the-worker-report
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)
        _ (support/review! h :plan plan-id [{:key "k" :value "v"}])
        result (mission/devops! h
                                {:tenure-id plan-id
                                 :call-id "devops-run"
                                 :verbose-intent "inspect"
                                 :run (fn [effect]
                                        (is (= :worker/spawn (:type effect)))
                                        (is (= :task (:model-role effect)))
                                        {:status :ok
                                         :report "raw worker report"})})]
    (is (:ok? result))
    (is (= "raw worker report" (get-in result [:receipt :report])))
    (is (= :devops-completed (get-in result [:receipt :outcome])))
    (let [runs (atom 0)
          replay (mission/devops! h
                                  {:tenure-id plan-id
                                   :call-id "devops-run"
                                   :verbose-intent "inspect"
                                   :run (fn [_]
                                          (swap! runs inc)
                                          {:status :ok
                                           :report "must not run"})})]
      (is (:ok? replay))
      (is (zero? @runs))
      (is (true? (get-in replay [:receipt :replayed])))
      (is (empty? (:effects replay))))))

(deftest nudge-policy-sees-role-lifecycle-state
  (let [plan-host (support/host)
        plan-id (support/plan-tenure-id plan-host)]
    (ok! (support/review! plan-host :plan plan-id [{:key "k" :value "v"}]))
    (let [plan-decision (mission/decide-nudge plan-host {:role :plan})]
      (is (true? (:enabled? plan-decision)))
      (is (= :central (:kind plan-decision)))
      (is (= [{:key "k" :value "v"}] (:ledger plan-decision))))
    (let [h (support/host)
          _ (support/drive-plan-to-pending! h)
          _ (support/start-execution! h)]
    (let [execution-id (support/sole-execution-id h)]
      (support/start-swarm! h execution-id [{:key "work" :value "Do work"}])
      (let [reviewer (mission/decide-nudge h {:role :reviewer
                                              :tenure-id execution-id})]
        (is (false? (:enabled? reviewer)))
        (is (= :reviewer-suspended-in-swarm (:reason reviewer))))
      (let [worker-id (support/register-worker! h execution-id :slot-a)
            worker (mission/decide-nudge h {:role :worker
                                            :tenure-id execution-id
                                            :agent-id worker-id})]
        (is (true? (:enabled? worker)))
        (is (= :worker (:kind worker)))
        (is (not (re-find #"review 账本" (:message worker))))
        (is (re-find #"无人值守" (:message worker))))))))

(deftest closing-requires-quiescence-then-completes
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        old-id (support/sole-execution-id h)
        _ (support/start-swarm! h old-id [{:key "k" :value "Do it"}])
        worker-id (support/register-worker! h old-id :slot-a)
        task-id (first (support/root-task-ids h old-id))]
    (ok! (support/claim-and-finish-root! h old-id worker-id task-id))
    (let [reviewer-id (support/sole-execution-id h)
          promoted (ok! (support/review! h :execution reviewer-id []))]
      (is (= :closing (get-in promoted [:receipt :to-milestone])))
      (testing "ff dry-run is a hard precondition"
        (let [rejected (support/review! h :execution reviewer-id []
                                        {:ff-ready? false})]
          (is (false? (:ok? rejected)))
          (is (= :FF_NOT_READY (get-in rejected [:error :code])))))
      (testing "an open report also blocks closing"
        (let [rejected (support/review! h :execution reviewer-id []
                                        {:ff-ready? true
                                         :report-pending 1})]
          (is (false? (:ok? rejected)))
          (is (= :RESOURCES_NOT_QUIESCENT (get-in rejected [:error :code])))))
      (let [completed (ok! (support/review! h :execution reviewer-id []
                                            (support/quiescent-facts)))
            tenure (get-in (mission/snapshot h)
                           [:relay :lanes :execution reviewer-id])
            terminal (mission/decide-nudge h {:role :reviewer
                                              :tenure-id reviewer-id})]
        (is (= :completed (get-in completed [:receipt :outcome])))
        (is (= :completed (:milestone tenure)))
        (is (= :completed (:status tenure)))
        (is (= :completed (get-in (mission/snapshot h)
                                  [:bindings :execution reviewer-id
                                   :reviewer :phase])))
        (is (false? (:enabled? terminal)))
        (is (= :terminal-completed (get-in terminal [:exit :kind])))
        (is (:ok? (mission/check-invariants (mission/snapshot h))))))))

(deftest review-uses-the-sole-execution-tenure
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        asked (mission/review! h {:lane :execution
                                  :call-id "sole-execution-review"
                                  :findings []})]
    (is (:ok? asked) (pr-str (:error asked)))
    (is (= :ask (get-in asked [:receipt :outcome])))
    (is (= execution-id (get-in asked [:receipt :tenure-id]))
        "a single execution tenure is resolved without an explicit id")))

(deftest devops-call-id-does-not-collide-with-a-board-request-id
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)
        requested (mission/devops-request! h {:tenure-id plan-id
                                              :call-id "shared-id"
                                              :verbose-intent "inspect"})]
    (is (:ok? requested) (pr-str (:error requested)))
    (is (:ok? (mission/devops-complete!
               h {:tenure-id plan-id
                  :worker-id (get-in requested [:receipt :worker-id])
                  :call-id "shared-id"
                  :status :ok
                  :report "done"})))
    (support/drive-plan-to-pending! h)
    (support/start-execution! h)
    (let [execution-id (support/sole-execution-id h)]
      (support/start-swarm! h execution-id [{:key "k" :value "Do it"}])
      (let [worker (support/register-worker! h execution-id :slot-a)
            task-id (first (support/root-task-ids h execution-id))
            claimed (mission/claim-task! h {:tenure-id execution-id
                                            :actor-id worker
                                            :task-id task-id
                                            :atomicity-reason
                                            "one root task"})
            finished (mission/finish-root-task!
                      h {:tenure-id execution-id
                         :actor-id worker
                         :task-id task-id
                         :claim-id (get-in claimed [:receipt :claim-id])
                         :result-summary "done"
                         :board-facts {:ff-ready? true}
                         :request-id "shared-id"})]
        (is (:ok? finished) (pr-str (:error finished)))
        (is (not= :REQUEST_ID_CONFLICT
                  (get-in finished [:error :code]))
            "the devops call-id namespace is separate from board requests")))))

(deftest reopen-root-after-replace-restores-the-original-obligation
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        _ (support/start-execution! h)
        execution-id (support/sole-execution-id h)
        _ (support/start-swarm! h execution-id
                                [{:key "k1" :value "Original obligation"}
                                 {:key "k2" :value "Keep this open"}])
        worker (support/register-worker! h execution-id :slot-a)
        task-id (first (support/root-task-ids h execution-id))
        _ (ok! (mission/claim-task! h {:tenure-id execution-id
                                       :actor-id worker
                                       :task-id task-id
                                       :atomicity-reason "replace one root"}))
        replaced (ok! (mission/replace-root-task!
                       h {:tenure-id execution-id
                          :actor-id worker
                          :task-id task-id
                          :replacement {:goal "Replacement goal"
                                        :remaining-work "Replacement work"
                                        :done-when "Replacement done"}
                          :reason "the original approach changed"}))
        new-task-id (get-in replaced [:receipt :new-task-id])
        claimed (ok! (mission/claim-task! h {:tenure-id execution-id
                                             :actor-id worker
                                             :task-id new-task-id
                                             :atomicity-reason "one root"}))
        _ (ok! (mission/finish-root-task!
                h {:tenure-id execution-id
                   :actor-id worker
                   :task-id new-task-id
                   :claim-id (get-in claimed [:receipt :claim-id])
                   :result-summary "replacement done"
                   :board-facts {:ff-ready? true}}))
        reopened (ok! (mission/reopen-root-task!
                       h {:tenure-id execution-id
                          :actor-id worker
                          :task-id new-task-id
                          :reason "restore the original obligation"}))
        ledger (get-in reopened
                       [:state :relay :lanes :execution execution-id
                        :ledger])]
    (is (= "Original obligation" (get ledger "k1"))
        "reopen restores the Reviewer's obligation, not the replacement goal")))

(deftest draft-reconcile-adopts-disk-content-test
  (let [h (support/host)
        plan-id (support/plan-tenure-id h)
        asked (support/review! h :plan plan-id [])
        resumed (mission/resume! h {:lane :plan :tenure-id plan-id})
        r1 (support/review! h :plan (support/plan-tenure-id h) [])
        r2 (support/review! h :plan (support/plan-tenure-id h) [])
        written (mission/set-draft! h {:path "plans/reconcile.md"
                                       :content "Program draft."
                                       :tenure-id (support/plan-tenure-id h)})
        r3 (support/review! h :plan (support/plan-tenure-id h) [])
        r4 (support/review! h :plan (support/plan-tenure-id h) [])
        state (mission/snapshot h)
        draft (get-in state [:relay :draft])]
    (is (= :ask (get-in asked [:receipt :outcome])))
    (is (:ok? resumed))
    (is (:ok? written) (pr-str written))
    (is (= :pending (get-in state [:relay :lanes :plan :milestone])))
    (testing "divergent disk content wins and marks the draft user-edited"
      (let [result (ok! (mission/review!
                         h {:lane :plan
                            :tenure-id (support/plan-tenure-id h)
                            :call-id "reconcile-1"
                            :findings []
                            :draft-disk-content "User edited on disk."}))
            effect (first (:effects result))
            draft' (get-in (mission/snapshot h) [:relay :draft])]
        (is (= :execution-started (get-in result [:receipt :outcome])))
        (is (= "User edited on disk." (:draft effect))
            "the execution/start effect carries the disk version")
        (is (= :user (:source draft')))
        (is (true? (:user-edited? draft')))
        (is (= (inc (:version draft)) (:version draft')))))
    (testing "identical disk content is a no-op"
      (let [state2 (mission/snapshot h)
            reconciled (mission/draft-reconcile-state
                        state2 {:disk-content "User edited on disk."})]
        (is (false? (:changed? reconciled)))
        (is (= (:version (get-in state2 [:relay :draft]))
               (:version (get-in (:state reconciled) [:relay :draft]))))))
    (testing "a missing file keeps the relay draft"
      (let [reconciled (mission/draft-reconcile-state
                        (mission/snapshot h) {:disk-content nil})]
        (is (false? (:changed? reconciled)))
        (is (true? (:missing? reconciled)))))))

(deftest review-without-disk-content-skips-reconcile-test
  (let [h (support/host)
        _ (support/drive-plan-to-pending! h)
        plan-id (support/plan-tenure-id h)
        ;; No :draft-disk-content key: the review must not touch the draft.
        result (ok! (mission/review! h {:lane :plan
                                        :tenure-id plan-id
                                        :call-id "no-disk-1"
                                        :findings []}))]
    (is (= :execution-started (get-in result [:receipt :outcome])))
    (is (= "Draft body for the smoke test."
           (get-in (mission/snapshot h) [:relay :draft :content])))))
