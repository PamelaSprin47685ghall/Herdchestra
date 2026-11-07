(ns hc.compose.fleet.control-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hc.compose.fleet.control :as control]
            [hc.compose.fleet.support :as support]))

(def ^:dynamic *worktree-root* nil)

(use-fixtures :each
  (fn [run-tests]
    (support/with-temp-dir [root "hc-fleet-control-"]
      (binding [*worktree-root* root]
        (run-tests)))))

(defn- slice
  ([id] (slice id []))
  ([id deps]
   {:slice-id id
    :goal (str "goal " id)
    :inputs []
    :depends-on deps
    :done-when "done"}))

(defn- make-control
  [fake & {:keys [launch resolve clock retention-ms persist]}]
  (control/new-control
   {:mission-id "mission"
    :worktree-root *worktree-root*
    :repo "/fake/repo"
    :git (:git fake)
    :launch-swarm! (or launch (fn [_] true))
    :resolve-conflict! resolve
    :clock (or clock (constantly 1000))
    :retention-ms (or retention-ms 1000)
    :max-rebase-rounds 4
    :persist! persist}))

(deftest fork-creates-worktrees-and-launches-only-independent-slices
  (let [fake (support/fake-git {:repo "/fake/repo"})
        launched (atom [])
        c (make-control fake :launch #(swap! launched conj %))
        result (control/fork! c [(slice "a") (slice "b" ["a"])])]
    (is (:ok? result))
    (is (= 2 (count (:swarms result))))
    (is (= ["swarm-1"] (mapv :swarm-id @launched)))
    (is (= :running (get-in result [:state :swarms "swarm-1" :status])))
    (is (= :queued (get-in result [:state :swarms "swarm-2" :status])))
    (is (some #(= [:create-worktree (str *worktree-root* "/mission/swarm-1")
                   "hc/mission/swarm-1" "master"]
                  %)
              @(:calls fake)))))

(deftest spec-shaped-fork-returns-the-fork-vector
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)
        result (control/fork c [(slice "a")])]
    (is (= [{:swarm-id "swarm-1"
             :slice-id "a"
             :workspace (str *worktree-root* "/mission/swarm-1")}]
           result))))

(deftest spec-shaped-fork-reports-insufficient-worktrees
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :create-results [{:status :failed
                                 :code :INSUFFICIENT_WORKTREES
                                 :exit 128
                                 :err "No space left on device"}]})
        c (make-control fake)]
    (is (= :INSUFFICIENT_WORKTREES (control/fork c [(slice "a")])))))

(deftest join-blocks-while-fork-allocation-is-in-progress
  (let [started (promise)
        release (promise)
        fake (support/fake-git
              {:repo "/fake/repo"
               :create-hook! (fn [_]
                               (deliver started true)
                               @release)})
        c (make-control fake)
        fork-f (future (control/fork! c [(slice "a")]))
        _ (is (= true (deref started 2000 ::timeout)))
        join-f (future (control/join! c {:timeout-ms 5000}))
        before (deref join-f 100 ::timeout)]
    (is (= ::timeout before)
        "join must not report :none while fork is still allocating")
    (deliver release true)
    (is (:ok? (deref fork-f 2000 ::timeout)))
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (is (= "report" (:report (deref join-f 2000 ::timeout))))))

(deftest join-is-fifo-and-returns-only-verified-fast-forwards
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)
        _ (is (:ok? (control/fork! c [(slice "a") (slice "b") (slice "c")])))
        _ (control/complete! c "swarm-3" "report-3" {:board-version 1})
        _ (control/complete! c "swarm-1" "report-1" {:board-version 1})
        _ (control/complete! c "swarm-2" "report-2" {:board-version 1})
        first-result (control/join! c)
        second-result (control/join! c)
        third-result (control/join! c)]
    (is (= "swarm-3" (:swarm-id first-result)))
    (is (= "report-3" (:report first-result)))
    (is (string? (:ff-commit first-result)))
    (is (= "swarm-1" (:swarm-id second-result)))
    (is (= "swarm-2" (:swarm-id third-result)))
    (is (= :none (control/join! c)))
    (is (= 3 (:master-version (control/snapshot c)))
        "three joins advance master three times")
    (is (= 1 (:rebase-rounds first-result)))))

(deftest join-refuses-a-worktree-on-the-wrong-branch
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)
        workspace (str *worktree-root* "/mission/swarm-1")]
    (is (:ok? (control/fork! c [(slice "a")])))
    (swap! (:heads fake) assoc workspace "foreign-head")
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (let [result (control/join! c)]
      (is (= :WORKTREE_BRANCH_MISMATCH
             (get-in result [:fleet/error :code])))
      (is (= :failed
             (get-in (control/snapshot c)
                     [:swarms "swarm-1" :status])))
      (is (not-any? #(= :ff-merge (first %)) @(:calls fake))))))

(deftest join-refuses-a-detached-worktree-at-the-same-head
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)
        workspace (str *worktree-root* "/mission/swarm-1")]
    (is (:ok? (control/fork! c [(slice "a")])))
    (is (= "hc/mission/swarm-1"
           (get-in @(:current-branches fake) [workspace])))
    ;; Keep HEAD and the branch head identical; only the checkout identity
    ;; changes.  A commit-only check would still pass here.
    (swap! (:current-branches fake) assoc workspace :detached)
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (let [result (control/join! c)]
      (is (= :WORKTREE_BRANCH_MISMATCH
             (get-in result [:fleet/error :code])))
      (is (true? (get-in result [:fleet/error :detached?])))
      (is (nil? (get-in result [:fleet/error :current-branch])))
      (is (= :failed
             (get-in (control/snapshot c)
                     [:swarms "swarm-1" :status])))
      (is (not-any? #(= :rebase-onto (first %)) @(:calls fake))
          "a detached worktree must not be rebased")
      (is (not-any? #(= :ff-merge (first %)) @(:calls fake))
          "a detached worktree must not be fast-forwarded"))))

(deftest join-refuses-a-foreign-branch-at-the-same-head
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)
        workspace (str *worktree-root* "/mission/swarm-1")]
    (is (:ok? (control/fork! c [(slice "a")])))
    ;; The other branch points at the same commit as the swarm branch.
    (swap! (:current-branches fake) assoc workspace "foreign-branch")
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (let [result (control/join! c)]
      (is (= :WORKTREE_BRANCH_MISMATCH
             (get-in result [:fleet/error :code])))
      (is (= "foreign-branch"
             (get-in result [:fleet/error :current-branch])))
      (is (false? (get-in result [:fleet/error :detached?])))
      (is (= :failed
             (get-in (control/snapshot c)
                     [:swarms "swarm-1" :status])))
      (is (not-any? #(= :rebase-onto (first %)) @(:calls fake)))
      (is (not-any? #(= :ff-merge (first %)) @(:calls fake))))))

(deftest join-refuses-an-unreadable-worktree-branch
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)
        workspace (str *worktree-root* "/mission/swarm-1")]
    (is (:ok? (control/fork! c [(slice "a")])))
    (swap! (:current-branches fake) dissoc workspace)
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (let [result (control/join! c)]
      (is (= :WORKTREE_BRANCH_UNREADABLE
             (get-in result [:fleet/error :code])))
      (is (= :failed
             (get-in (control/snapshot c)
                     [:swarms "swarm-1" :status])))
      (is (not-any? #(= :rebase-onto (first %)) @(:calls fake)))
      (is (not-any? #(= :ff-merge (first %)) @(:calls fake))))))

(deftest joining-a-dependency-launches-its-dependent
  (let [fake (support/fake-git {:repo "/fake/repo"})
        launched (atom [])
        c (make-control fake :launch #(swap! launched conj %))]
    (is (:ok? (control/fork! c [(slice "a") (slice "b" ["a"])])))
    (is (= 1 (count @launched)))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (let [result (control/join! c)]
      (is (= "swarm-1" (:swarm-id result)))
      (is (= ["swarm-1" "swarm-2"] (mapv :swarm-id @launched)))
      (is (= :running (get-in (control/snapshot c) [:swarms "swarm-2" :status]))))
    (control/complete! c "swarm-2" "report-b" {:board-version 2})
    (is (= "swarm-2" (:swarm-id (control/join! c))))
    (is (= :none (control/join! c)))))

(deftest a-later-fork-starts-after-its-joined-dependency
  (let [fake (support/fake-git {:repo "/fake/repo"})
        launched (atom [])
        c (make-control fake :launch #(swap! launched conj %))]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (is (= "swarm-1" (:swarm-id (control/join! c))))
    (is (:ok? (control/fork! c [(slice "b" ["a"])])))
    (is (= ["swarm-1" "swarm-2"] (mapv :swarm-id @launched)))
    (is (= :running (get-in (control/snapshot c) [:swarms "swarm-2" :status])))))

(deftest abandoning-one-swarm-does-not-cancel-its-siblings
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)]
    (is (:ok? (control/fork! c [(slice "a") (slice "b")])))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (let [abandoned (control/abandon! c "swarm-1" {:reason :stale})]
      (is (:ok? abandoned))
      (is (= :abandoned (get-in (control/snapshot c) [:swarms "swarm-1" :status])))
      (is (= :running (get-in (control/snapshot c) [:swarms "swarm-2" :status])))
      (is (= [] (:completion-queue (control/snapshot c)))))
    (control/complete! c "swarm-2" "report-b" {:board-version 2})
    (is (= "swarm-2" (:swarm-id (control/join! c)))
        "the unrelated sibling still joins")
    (is (= :none (control/join! c)))))

(deftest failing-one-swarm-does-not-cancel-its-siblings
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)]
    (is (:ok? (control/fork! c [(slice "a") (slice "b")])))
    (let [failed (control/fail! c "swarm-1" {:code :BOOT_FAILED})]
      (is (:ok? failed))
      (is (= :failed (get-in (control/snapshot c) [:swarms "swarm-1" :status])))
      (is (= :running (get-in (control/snapshot c) [:swarms "swarm-2" :status]))))
    (control/complete! c "swarm-2" "report-b" {:board-version 1})
    (is (= "swarm-2" (:swarm-id (control/join! c))))
    (is (= :none (control/join! c)))))

(deftest an-unresolvable-conflict-abandons-only-that-result-and-join-continues
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :rebase-results [{:status :conflict :workspace "/fake"}]})
        c (make-control fake :resolve (fn [_] :abandon))]
    (is (:ok? (control/fork! c [(slice "a") (slice "b")])))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (control/complete! c "swarm-2" "report-b" {:board-version 1})
    (let [result (control/join! c)]
      (is (= "swarm-2" (:swarm-id result))
          "the abandoned head is skipped, not returned as a fake result")
      (is (= :abandoned (get-in (control/snapshot c) [:swarms "swarm-1" :status])))
      (is (= :joined (get-in (control/snapshot c) [:swarms "swarm-2" :status])))
      (is (some #(= [:rebase-abort
                     (str *worktree-root* "/mission/swarm-1")] %)
                @(:calls fake))))))

(deftest join-returns-no-results-when-every-swarm-is-abandoned
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/abandon! c "swarm-1" {:reason :stale})
    (is (= :no-results (control/join! c)))))

(deftest a-resolved-conflict-continues-and-joins
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :rebase-results [{:status :conflict :workspace "/fake"}]
               :continue-results [{:status :ok :workspace "/fake"}]})
        c (make-control fake :resolve (fn [_] :resolved))]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (let [result (control/join! c)]
      (is (= "swarm-1" (:swarm-id result)))
      (is (= 1 (:rebase-rounds result)))
      (is (some #(= :stage-all (first %)) @(:calls fake))
          "control stages a resolved worktree even when the resolver did not")
      (is (some #(= :rebase-continue (first %)) @(:calls fake))))))

(deftest a-fresh-branch-at-master-does-not-shortcut-the-rebase
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :rebase-results [{:status :failed
                                 :code :REBASE_FAILED
                                 :exit 1
                                 :err "cannot rebase: You have unstaged changes"}]})
        c (make-control fake)
        workspace (str *worktree-root* "/mission/swarm-1")
        branch "hc/mission/swarm-1"]
    (is (:ok? (control/fork! c [(slice "a")])))
    ;; A fresh branch with no commits has the same head as master.  It is
    ;; not proof of a previous fast-forward.
    (swap! (:heads fake) assoc workspace "master-0")
    (swap! (:branch-heads fake) assoc branch "master-0")
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (let [result (control/join! c)
          swarm (get-in (control/snapshot c) [:swarms "swarm-1"])]
      (is (contains? result :fleet/error))
      (is (= :failed (:status swarm)))
      (is (some #(= :rebase-onto (first %)) @(:calls fake))
          "a fresh branch must go through rebase")
      (is (not-any? #(= :ff-merge (first %)) @(:calls fake))))))

(deftest a-join-interruption-keeps-the-result-queued
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :rebase-results [{:status :interrupted
                                 :exit nil
                                 :out ""
                                 :err "git command interrupted"}]})
        c (make-control fake)]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (let [result (control/join! c)
          state-value (control/snapshot c)
          swarm (get-in state-value [:swarms "swarm-1"])]
      (is (= :JOIN_INTERRUPTED (get-in result [:fleet/error :code])))
      (is (= :running (:status swarm)))
      (is (true? (:completed? swarm)))
      (is (= ["swarm-1"] (:completion-queue state-value)))
      (is (false? (:integrating? swarm)))
      (is (true? (:join-intent swarm)))
      (is (some #(= :rebase-abort (first %)) @(:calls fake))))))

(deftest a-master-move-during-conflict-resolution-retries
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :rebase-results [{:status :conflict :workspace "/fake"}
                                {:status :ok :workspace "/fake"}]
               :continue-results [{:status :ok :workspace "/fake"}]})
        moved? (atom false)
        resolver (fn [_]
                   (when-not @moved?
                     (reset! moved? true)
                     (reset! (:repo-head fake) "master-1"))
                   :resolved)
        c (make-control fake :resolve resolver)]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (let [result (control/join! c)]
      (is (= "swarm-1" (:swarm-id result)))
      (is (= 2 (:rebase-rounds result)))
      (is (= 2 (count (filter #(= :rebase-onto (first %))
                              @(:calls fake))))
          "master movement during resolution starts another rebase round"))))

(deftest a-conflict-stage-failure-abandons-only-that-swarm
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :rebase-results [{:status :conflict :workspace "/fake"}]
               :stage-results [{:status :failed
                                :code :STAGE_FAILED
                                :workspace "/fake"}]})
        c (make-control fake :resolve (fn [_] :resolved))]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (is (= :no-results (control/join! c)))
    (let [swarm (get-in (control/snapshot c) [:swarms "swarm-1"])]
      (is (= :abandoned (:status swarm)))
      (is (= :CONFLICT_STAGE_FAILED
             (get-in swarm [:abandon-reason :code]))))))

(deftest a-git-failure-is-reported-and-never-faked-as-joined
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :rebase-results [{:status :failed
                                 :code :REBASE_FAILED
                                 :exit 1
                                 :err "rebase failed"}]})
        c (make-control fake)]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (let [result (control/join! c)
          swarm (get-in (control/snapshot c) [:swarms "swarm-1"])]
      (is (contains? result :fleet/error))
      (is (= :failed (:status swarm)))
      (is (nil? (:ff-commit swarm)))
      (is (not-any? #(= :ff-merge (first %)) @(:calls fake))
          "join must not attempt a fast-forward after a failed rebase")
      (is (= :no-results (control/join! c))))))

(deftest worktree-allocation-failure-rolls-back-and-reports-insufficient-worktrees
  (let [fake (support/fake-git
              {:repo "/fake/repo"
               :create-results [{:status :ok}
                                {:status :failed
                                 :code :INSUFFICIENT_WORKTREES
                                 :exit 128
                                 :err "No space left on device"}]})
        launched (atom [])
        c (make-control fake :launch #(swap! launched conj %))
        result (control/fork! c [(slice "a") (slice "b")])]
    (is (false? (:ok? result)))
    (is (= :INSUFFICIENT_WORKTREES (get-in result [:error :code])))
    (is (= {} (get-in result [:state :swarms])))
    (is (empty? @launched))
    (is (= 1 (count (get-in result [:error :cleanup]))))
    (is (some #(= :remove-worktree (first %)) @(:calls fake)))
    (is (some #(= [:delete-branch "hc/mission/swarm-1"] %) @(:calls fake))
        "rollback also removes the branch created by the failed fork")))

(deftest a-synchronous-launch-failure-closes-only-that-swarm
  (let [fake (support/fake-git {:repo "/fake/repo"})
        calls (atom [])
        c (make-control fake
                        :launch (fn [{:keys [swarm-id]}]
                                  (swap! calls conj swarm-id)
                                  (when (= "swarm-1" swarm-id)
                                    (throw (ex-info "launcher exploded" {})))
                                  true))
        result (control/fork! c [(slice "a") (slice "b")])]
    (is (false? (:ok? result)))
    (is (= :SWARM_LAUNCH_FAILED (get-in result [:error :code])))
    (is (= ["swarm-2"] (:launched result)))
    (is (= :failed (get-in result [:state :swarms "swarm-1" :status])))
    (is (= :running (get-in result [:state :swarms "swarm-2" :status])))
    (is (= ["swarm-1" "swarm-2"] @calls))))

(deftest a-launcher-failure-map-closes-only-that-swarm
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake
                        :launch (fn [{:keys [swarm-id]}]
                                  (if (= "swarm-1" swarm-id)
                                    {:status :failed :error {:code :BOOT}}
                                    true)))
        result (control/fork! c [(slice "a") (slice "b")])]
    (is (false? (:ok? result)))
    (is (= :failed (get-in result [:state :swarms "swarm-1" :status])))
    (is (= :running (get-in result [:state :swarms "swarm-2" :status])))))

(deftest a-launch-that-closes-its-swarm-is-not-reported-as-success
  (let [fake (support/fake-git {:repo "/fake/repo"})
        ctl (atom nil)
        c (make-control fake
                        :launch (fn [{:keys [swarm-id]}]
                                  (control/abandon! @ctl swarm-id {:reason :immediate})
                                  true))]
    (reset! ctl c)
    (let [result (control/fork! c [(slice "a")])]
      (is (false? (:ok? result)))
      (is (= :abandoned (get-in result [:state :swarms "swarm-1" :status])))
      (is (= :SWARM_LAUNCH_CLOSED
             (get-in result [:error :failed 0 :error :code]))))))

(deftest join-blocks-until-a-result-is-completed
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake)]
    (is (:ok? (control/fork! c [(slice "a")])))
    (let [pending (future (control/join! c {:timeout-ms 5000}))]
      (Thread/sleep 50)
      (control/complete! c "swarm-1" "late report" {:board-version 1})
      (let [result (deref pending 3000 ::timeout)]
        (is (not= ::timeout result))
        (is (= "swarm-1" (:swarm-id result)))
        (is (= "late report" (:report result)))))))

(deftest join-timeout-is-explicit
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake :clock #(System/currentTimeMillis))]
    (is (:ok? (control/fork! c [(slice "a")])))
    (let [result (control/join! c {:timeout-ms 10})]
      (is (= :JOIN_TIMEOUT (get-in result [:fleet/error :code])))
      (is (nil? (:swarm-id result))))))

(deftest gc-respects-the-retention-window
  (let [clock (atom 1000)
        fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake :clock #(deref clock) :retention-ms 500)]
    (is (:ok? (control/fork! c [(slice "a")])))
    (control/complete! c "swarm-1" "report" {:board-version 1})
    (is (= "swarm-1" (:swarm-id (control/join! c))))
    (is (= [] (:removed (control/gc! c))))
    (reset! clock 2000)
    (let [result (control/gc! c)]
      (is (= ["swarm-1"] (:removed result)))
      (is (true? (get-in (control/snapshot c) [:swarms "swarm-1" :worktree-removed?]))))))

(deftest gc-never-touches-an-active-worktree
  (let [clock (atom 1000)
        fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake :clock #(deref clock) :retention-ms 0)]
    (is (:ok? (control/fork! c [(slice "a")])))
    ;; A corrupted deadline must not make a running swarm eligible.
    (swap! (:state c) assoc-in [:swarms "swarm-1" :gc-after] 0)
    (reset! clock 999999999)
    (let [result (control/gc! c)]
      (is (= [] (:removed result)))
      (is (not-any? #(= :remove-worktree (first %)) @(:calls fake)))
      (is (false? (get-in (control/snapshot c)
                          [:swarms "swarm-1" :worktree-removed?]))))))

(deftest gc-removes-only-expired-registered-managed-worktrees
  (let [clock (atom 1000)
        fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake :clock #(deref clock) :retention-ms 0)]
    (is (:ok? (control/fork! c [(slice "a") (slice "b")])))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (control/complete! c "swarm-2" "report-b" {:board-version 2})
    (is (= "swarm-1" (:swarm-id (control/join! c))))
    (is (= "swarm-2" (:swarm-id (control/join! c))))
    ;; A terminal swarm without a real deadline is not expired.
    (swap! (:state c) assoc-in [:swarms "swarm-1" :gc-after] nil)
    ;; A terminal swarm whose workspace is not the managed path is shared.
    (swap! (:state c) assoc-in [:swarms "swarm-2" :workspace] "/shared/master")
    (reset! clock 999999999)
    (let [result (control/gc! c)
          removals (filter #(= :remove-worktree (first %)) @(:calls fake))]
      (is (= [] (:removed result)))
      (is (empty? removals))
      (is (false? (get-in (control/snapshot c)
                          [:swarms "swarm-1" :worktree-removed?])))
      (is (false? (get-in (control/snapshot c)
                          [:swarms "swarm-2" :worktree-removed?]))))))

(deftest gc-stops-on-interruption
  (let [clock (atom 1000)
        fake (support/fake-git {:repo "/fake/repo"
                                :remove-results [{:status :interrupted}]})
        c (make-control fake :clock #(deref clock) :retention-ms 0)]
    (is (:ok? (control/fork! c [(slice "a") (slice "b")])))
    (control/complete! c "swarm-1" "report-a" {:board-version 1})
    (control/complete! c "swarm-2" "report-b" {:board-version 2})
    (is (= "swarm-1" (:swarm-id (control/join! c))))
    (is (= "swarm-2" (:swarm-id (control/join! c))))
    (reset! clock 999999999)
    (let [result (control/gc! c)
          removals (filter #(= :remove-worktree (first %)) @(:calls fake))
          state (control/snapshot c)]
      (is (false? (:ok? result)))
      (is (true? (:interrupted? result)))
      (is (= [] (:removed result)))
      (is (= 1 (count removals))
          "the sweep stops after the interrupted removal")
      (is (= :interrupted (get-in state [:swarms "swarm-1" :gc-error :status])))
      (is (false? (get-in state [:swarms "swarm-1" :worktree-removed?])))
      (is (nil? (get-in state [:swarms "swarm-2" :gc-error])))
      (is (false? (get-in state [:swarms "swarm-2" :worktree-removed?]))))))

(deftest fork-rolls-back-when-the-published-state-cannot-be-persisted
  (let [fake (support/fake-git {:repo "/fake/repo"})
        c (make-control fake
                        :persist (fn [_]
                                   {:ok? false
                                    :error {:code :DISK_FULL}}))
        result (control/fork! c [(slice "a")])]
    (is (false? (:ok? result)))
    (is (= :DISK_FULL (get-in result [:error :code])))
    (is (= {} (:swarms (control/snapshot c)))
        "a fork is never published before its state is durable")
    (is (some #(= :remove-worktree (first %)) @(:calls fake))
        "the created worktree is rolled back")))

(deftest a-transition-persistence-failure-keeps-the-live-state-visible
  (let [fake (support/fake-git {:repo "/fake/repo"})
        persist-calls (atom 0)
        c (make-control fake
                        :persist
                        (fn [_]
                          (if (= 1 (swap! persist-calls inc))
                            {:ok? true}
                            {:ok? false
                             :error {:code :DISK_FULL}})))]
    (is (:ok? (control/fork! c [(slice "a")])))
    (let [result (control/complete! c "swarm-1" "report" {:board-version 1})]
      (is (:ok? result))
      (is (false? (:persisted? result)))
      (is (= :DISK_FULL (get-in result [:persist-error :code])))
      (is (= "report" (get-in (control/snapshot c)
                              [:swarms "swarm-1" :report]))
          "the live fleet keeps serving while the write failure is visible"))))

(deftest join-keeps-a-merged-result-pending-when-the-join-write-fails
  (let [fake (support/fake-git {:repo "/fake/repo"})
        launched (atom [])
        persist-calls (atom 0)
        c (make-control
           fake
           :launch #(swap! launched conj %)
           :persist (fn [_]
                      (if (contains? #{4 5} (swap! persist-calls inc))
                        {:ok? false :error {:code :DISK_FULL}}
                        {:ok? true})))]
    (is (:ok? (control/fork! c [(slice "a") (slice "b" ["a"])])))
    (is (= ["swarm-1"] (mapv :swarm-id @launched)))
    (is (:ok? (control/complete! c "swarm-1" "report-a" {:board-version 1})))
    (let [failed (control/join! c)
          state (control/snapshot c)
          recovery (get-in state [:swarms "swarm-1" :join-recovery])
          pending-ff (:ff-commit recovery)]
      (is (= :FLEET_STATE_PERSIST_FAILED
             (get-in failed [:fleet/error :code])))
      (is (true? (get-in failed [:fleet/error :git-merged?])))
      (is (true? (get-in failed [:fleet/error :join-pending?])))
      (is (string? pending-ff))
      (is (= ["swarm-1"] (mapv :swarm-id @launched))
          "a failed join write must not start the dependent")
      (is (= :queued (get-in state [:swarms "swarm-2" :status])))
      (is (= :running (get-in state [:swarms "swarm-1" :status])))
      (is (true? (get-in state [:swarms "swarm-1" :completed?])))
      (is (true? (get-in state [:swarms "swarm-1" :integrating?])))
      (is (= ["swarm-1"] (:completion-queue state))
          "the queued result stays the FIFO head")
      (is (true? (:git-merged? recovery)))
      (is (= 1 (count (filter #(= :ff-merge (first %)) @(:calls fake)))))
      (is (= 1 (count (filter #(= :rebase-onto (first %)) @(:calls fake)))))
      (is (= :SWARM_INTEGRATING
             (get-in (control/abandon! c "swarm-1" {:reason :stale})
                     [:error :code]))
          "the pending merge cannot be abandoned while the claim is live")
      (swap! (:state c) assoc-in [:swarms "swarm-1" :integrating?] false)
      (is (= :SWARM_JOIN_PENDING
             (get-in (control/fail! c "swarm-1" {:code :STALE})
                     [:error :code]))
          "a recovered pending merge still refuses to discard the result")
      (let [retry-failed (control/join! c)]
        (is (= :FLEET_STATE_PERSIST_FAILED
               (get-in retry-failed [:fleet/error :code])))
        (is (= ["swarm-1"] (mapv :swarm-id @launched))
            "a repeated write failure still must not start the dependent")
        (is (= ["swarm-1"] (:completion-queue (control/snapshot c))))
        (is (= 1 (count (filter #(= :ff-merge (first %)) @(:calls fake))))
            "a repeated retry still does not repeat the fast-forward"))
      (let [result (control/join! c)
            state (control/snapshot c)]
        (is (= "swarm-1" (:swarm-id result)))
        (is (= "report-a" (:report result)))
        (is (= pending-ff (:ff-commit result))
            "the retry returns the same verified fast-forward")
        (is (= :joined (get-in state [:swarms "swarm-1" :status])))
        (is (nil? (get-in state [:swarms "swarm-1" :join-recovery])))
        (is (= [] (:completion-queue state)))
        (is (= :running (get-in state [:swarms "swarm-2" :status])))
        (is (= ["swarm-1" "swarm-2"] (mapv :swarm-id @launched))
            "the dependent starts exactly once, after the durable join")
        (is (= 1 (count (filter #(= :ff-merge (first %)) @(:calls fake))))
            "the retry does not repeat the fast-forward")
        (is (= 1 (count (filter #(= :rebase-onto (first %)) @(:calls fake))))
            "the retry does not repeat the rebase")))))

(deftest join-finalizes-an-already-merged-branch-after-restart-without-repeating-git
  (let [fake (support/fake-git {:repo "/fake/repo"})
        launched (atom [])
        persist-calls (atom 0)
        c (make-control
           fake
           :launch #(swap! launched conj %)
           :persist (fn [_]
                      (if (= 4 (swap! persist-calls inc))
                        {:ok? false :error {:code :DISK_FULL}}
                        {:ok? true})))]
    (is (:ok? (control/fork! c [(slice "a") (slice "b" ["a"])])))
    (is (:ok? (control/complete! c "swarm-1" "report-a" {:board-version 1})))
    (is (= :FLEET_STATE_PERSIST_FAILED
           (get-in (control/join! c) [:fleet/error :code])))
    ;; Simulate a restart that did not keep the live-only marker: the
    ;; durable snapshot still has the completed queue head and no claim.
    (swap! (:state c)
           (fn [state]
             (-> state
                 (assoc-in [:swarms "swarm-1" :join-recovery] nil)
                 (assoc-in [:swarms "swarm-1" :integrating?] false))))
    (let [result (control/join! c)]
      (is (= "swarm-1" (:swarm-id result)))
      (is (= 0 (:rebase-rounds result))
          "no rebase round runs when master already holds the branch")
      (is (= ["swarm-1" "swarm-2"] (mapv :swarm-id @launched)))
      (is (= 1 (count (filter #(= :ff-merge (first %)) @(:calls fake))))
          "master already at the branch head proves the merge")
      (is (= 1 (count (filter #(= :rebase-onto (first %)) @(:calls fake))))
          "restart recovery does not rebase again"))))
