(ns hc.compose.fleet.smoke-test
  "End-to-end smoke tests with the real Git executable.

  These tests do not replace the focused unit tests.  They prove that the
  control path really creates worktrees, rebases, fast-forwards master, and
  abandons a conflicting result without inventing a commit."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hc.compose.fleet.control :as control]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.support :as support]))

(defn- slice
  ([id] (slice id []))
  ([id deps]
   {:slice-id id
    :goal (str "goal " id)
    :inputs []
    :depends-on deps
    :done-when "done"}))

(defn- make-control
  [repo worktree-root launch resolve]
  (control/new-control
   {:mission-id "smoke"
    :worktree-root worktree-root
    :git (git/git-cli repo)
    :launch-swarm! launch
    :resolve-conflict! resolve
    :clock #(System/currentTimeMillis)
    :retention-ms 60000}))

(deftest real-worktrees-rebase-and-fast-forward-in-fifo-order
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-fleet-smoke-"]
    (let [repo (str root "/repo")
          worktree-root (str root "/worktrees")]
      (.mkdirs (io/file repo))
      (support/init-repo! repo)
      (let [launched (atom [])
            c (make-control
               repo worktree-root
               (fn [{:keys [swarm-id workspace]}]
                 (support/commit-file! workspace (str swarm-id ".txt")
                                       (str swarm-id "\n")
                                       (str "work from " swarm-id))
                 (swap! launched conj swarm-id))
               nil)
            result (control/fork! c [(slice "a") (slice "b")])]
        (is (:ok? result))
        (is (= #{"swarm-1" "swarm-2"} (set @launched)))
        (control/complete! c "swarm-2" "report-b" {:board-version 1})
        (control/complete! c "swarm-1" "report-a" {:board-version 1})
        (let [first-result (control/join! c)
              second-result (control/join! c)]
          (is (= "swarm-2" (:swarm-id first-result)))
          (is (= "swarm-1" (:swarm-id second-result)))
          (is (string? (:ff-commit first-result)))
          (is (string? (:ff-commit second-result)))
          (is (= :none (control/join! c)))
          (is (= 2 (:master-version (control/snapshot c)))))
        (is (.exists (io/file repo "swarm-1.txt")))
        (is (.exists (io/file repo "swarm-2.txt"))))))))

(deftest a-detached-worktree-at-the-same-head-does-not-join
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-fleet-smoke-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")
            workspace (str worktree-root "/smoke/swarm-1")]
        (.mkdirs (io/file repo))
        (support/init-repo! repo)
        (let [c (make-control repo worktree-root (fn [_] true) nil)]
          (is (:ok? (control/fork! c [(slice "a")])))
          ;; Detach without moving HEAD: the commit-only check would pass and
          ;; report a fake join.
          (support/git! workspace "checkout" "--detach")
          (control/complete! c "swarm-1" "report-a" {:board-version 1})
          (let [result (control/join! c)
                swarm (get-in (control/snapshot c) [:swarms "swarm-1"])]
            (is (= :WORKTREE_BRANCH_MISMATCH
                   (get-in result [:fleet/error :code])))
            (is (true? (get-in result [:fleet/error :detached?])))
            (is (= :failed (:status swarm)))
            (is (nil? (:ff-commit swarm)))
            (is (zero? (:master-version (control/snapshot c))))))))))

(deftest a-dirty-no-commit-worktree-is-not-silently-joined
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-fleet-smoke-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")
            workspace (str worktree-root "/smoke/swarm-1")]
        (.mkdirs (io/file repo))
        (support/init-repo! repo)
        (let [c (make-control repo worktree-root (fn [_] true) nil)]
          (is (:ok? (control/fork! c [(slice "a")])))
          ;; No commit: the branch head still equals master.  A dirty tracked
          ;; file must not be accepted as an already-merged result.
          (spit (io/file workspace "base.txt") "dirty\n")
          (control/complete! c "swarm-1" "report-a" {:board-version 1})
          (let [result (control/join! c)
                swarm (get-in (control/snapshot c) [:swarms "swarm-1"])]
            (is (= :REBASE_FAILED
                   (get-in result [:fleet/error :code])))
            (is (= :failed (:status swarm)))
            (is (nil? (:ff-commit swarm)))
            (is (zero? (:master-version (control/snapshot c))))
            (is (= "dirty\n" (slurp (io/file workspace "base.txt"))))))))))

(deftest a-failed-fork-rolls-back-worktrees-and-branches
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-fleet-smoke-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")]
        (.mkdirs (io/file repo))
        (support/init-repo! repo)
        (.mkdirs (io/file worktree-root "smoke"))
        (spit (io/file worktree-root "smoke" "swarm-2") "collision")
        (let [c (make-control repo worktree-root (fn [_] true) nil)
              failed (control/fork! c [(slice "a") (slice "b")])]
          (is (false? (:ok? failed)))
          (is (= :WORKTREE_CREATION_FAILED (get-in failed [:error :code])))
          (is (not (.exists (io/file worktree-root "smoke" "swarm-1"))))
          (.delete (io/file worktree-root "smoke" "swarm-2"))
          (let [retry (control/fork! c [(slice "a") (slice "b")])]
            (is (:ok? retry))
            (is (= 2 (count (:swarms retry))))))))))

(deftest real-conflict-abandons-one-result-and-lets-the-other-join
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-fleet-smoke-"]
    (let [repo (str root "/repo")
          worktree-root (str root "/worktrees")]
      (.mkdirs (io/file repo))
      (support/init-repo! repo)
      (let [c (make-control
               repo worktree-root
               (fn [{:keys [swarm-id workspace]}]
                 (if (= "swarm-1" swarm-id)
                   (support/commit-file! workspace "base.txt"
                                         "swarm-1\n" "edit base from swarm-1")
                   (support/commit-file! workspace "other.txt"
                                         "swarm-2\n" "add other from swarm-2")))
               (fn [_] :abandon))
            _ (is (:ok? (control/fork! c [(slice "a") (slice "b")])))
            ;; Advance master so swarm-1's base.txt edit genuinely conflicts.
            _ (support/commit-file! repo "base.txt" "master\n" "master edits base")
            _ (control/complete! c "swarm-1" "report-a" {:board-version 1})
            _ (control/complete! c "swarm-2" "report-b" {:board-version 1})
            result (control/join! c)]
        (is (= "swarm-2" (:swarm-id result))
            "the conflicting head is abandoned; the next queued result joins")
        (is (= :abandoned (get-in (control/snapshot c) [:swarms "swarm-1" :status])))
        (is (= :joined (get-in (control/snapshot c) [:swarms "swarm-2" :status])))
        (is (= "master\n" (slurp (io/file repo "base.txt"))))
        (is (= "swarm-2\n" (slurp (io/file repo "other.txt")))))))))
