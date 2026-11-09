(ns hc.compose.fleet.git-real-test
  "Real Git integration tests for the fleet's narrow GitPort.

  The fake-based suite already covers orchestration.  These tests exercise
  the adapter against the real executable because `ff-ready?` and
  `worktree-clean?` feed the closing `:FF_NOT_READY` payload, and porcelain
  output details (ignored files, staged files, linked worktrees) must match
  what the fake promises.

  Every test uses its own `mkdtemp` repository and cleans it in `finally`.
  Without a Git executable the test body prints a skip reason instead of
  passing silently."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.support :as support]))

(defn- with-real-git
  [label body]
  (if (git/available? (git/git-cli "."))
    (body)
    (println (str "SKIP hc.compose.fleet.git-real-test/" label
                  ": git executable unavailable"))))

(deftest worktree-clean-tracks-untracked-index-head-and-ignored-files
  (with-real-git
    "worktree-clean-tracks-untracked-index-head-and-ignored-files"
    (fn []
      (support/with-temp-dir [repo "hc-git-real-clean-"]
        (support/init-repo! repo)
        (let [adapter (git/git-cli repo)]
          (testing "a fresh repository is clean"
            (is (true? (git/worktree-clean? adapter repo))))
          (testing "an untracked file is dirty"
            (spit (io/file repo "f.txt") "x\n")
            (is (false? (git/worktree-clean? adapter repo))))
          (testing "a staged file is dirty"
            (support/git! repo "add" "f.txt")
            (is (false? (git/worktree-clean? adapter repo))))
          (testing "a commit makes the worktree clean again"
            (support/git! repo "commit" "-m" "add f")
            (is (true? (git/worktree-clean? adapter repo))))
          (testing "ignored files never make the worktree dirty"
            (support/commit-file! repo ".gitignore" "ignored.txt\n" "ignore")
            (spit (io/file repo "ignored.txt") "ignored\n")
            (is (true? (git/worktree-clean? adapter repo)))
            (is (str/blank?
                 (:out (support/git! repo "status" "--porcelain"))))))))))

(deftest ff-ready-two-by-two-matrix
  (with-real-git
    "ff-ready-two-by-two-matrix"
    (fn []
      (support/with-temp-dir [repo "hc-git-real-ff-ready-"]
        (support/init-repo! repo)
        (support/commit-file! repo "second.txt" "second\n" "second")
        (let [adapter (git/git-cli repo)
              workspace (str repo "/swarm-1")]
          (is (= :ok (:status (git/create-worktree! adapter
                                                    {:workspace workspace
                                                     :branch "hc/mission/swarm-1"
                                                     :base "master"}))))
          (testing "clean + target is an ancestor"
            (let [result (git/ff-ready? adapter workspace "master")]
              (is (true? (:ready? result)) (pr-str result))
              (is (true? (:clean? result)))
              (is (= :ready (:reason result)))))
          (testing "dirty + target is an ancestor"
            (spit (io/file workspace "dirty.txt") "dirty\n")
            (let [result (git/ff-ready? adapter workspace "master")]
              (is (false? (:ready? result)))
              (is (false? (:clean? result)))
              (is (= :WORKTREE_DIRTY (:reason result)))))
          (support/git! workspace "clean" "-fd")
          (testing "clean + target is not an ancestor"
            (support/commit-file! repo "third.txt" "third\n" "master moves")
            (let [result (git/ff-ready? adapter workspace "master")]
              (is (false? (:ready? result)))
              (is (true? (:clean? result)))
              (is (= :TARGET_NOT_ANCESTOR (:reason result)))))
          (testing "a missing directory has a concrete unreadable-head code"
            (let [result (git/ff-ready? adapter (str repo "/missing")
                                        "master")]
              (is (false? (:ready? result)))
              (is (false? (:clean? result)))
              (is (= :HEAD_UNREADABLE (:reason result))))))))))

(deftest ff-ready-and-ancestor-agree-on-the-same-inputs
  (with-real-git
    "ff-ready-and-ancestor-agree-on-the-same-inputs"
    (fn []
      (support/with-temp-dir [repo "hc-git-real-consistency-"]
        (support/init-repo! repo)
        (support/commit-file! repo "second.txt" "second\n" "second")
        (let [adapter (git/git-cli repo)
              workspace (str repo "/swarm-1")]
          (is (= :ok (:status (git/create-worktree! adapter
                                                    {:workspace workspace
                                                     :branch "hc/mission/swarm-1"
                                                     :base "master"}))))
          (let [ready (git/ff-ready? adapter workspace "master")
                ancestor (git/ancestor? adapter workspace "master")]
            (is (true? (:ready? ready)))
            (is (= :ok (:status ancestor)))
            (is (true? (:ancestor? ancestor))
                "ready? implies ancestor? for the same directory and ref"))
          (spit (io/file workspace "dirty.txt") "dirty\n")
          (let [dirty (git/ff-ready? adapter workspace "master")]
            (is (false? (git/worktree-clean? adapter workspace)))
            (is (false? (:ready? dirty))
                "a dirty worktree can never be ff-ready"))
          (support/git! workspace "clean" "-fd")
          (support/commit-file! repo "third.txt" "third\n" "master moves")
          (let [stale (git/ff-ready? adapter workspace "master")
                ancestor (git/ancestor? adapter workspace "master")]
            (is (false? (:ready? stale)))
            (is (= :ok (:status ancestor)))
            (is (false? (:ancestor? ancestor)))
            (is (= :TARGET_NOT_ANCESTOR (:reason stale)))))))))

(deftest real-ff-merge-advances-only-when-fast-forwardable
  (with-real-git
    "real-ff-merge-advances-only-when-fast-forwardable"
    (fn []
      (support/with-temp-dir [repo "hc-git-real-ff-merge-"]
        (support/init-repo! repo)
        (let [adapter (git/git-cli repo)
              workspace (str repo "/swarm-1")
              branch "hc/mission/swarm-1"]
          (is (= :ok (:status (git/create-worktree! adapter
                                                    {:workspace workspace
                                                     :branch branch
                                                     :base "master"}))))
          (let [branch-head (support/commit-file!
                             workspace "feature.txt" "feature\n" "feature work")
                merged (git/ff-merge! adapter {:branch branch :ref "master"})
                master (git/repo-head adapter "master")]
            (is (= :ok (:status merged)) (pr-str merged))
            (is (= branch-head (:head merged)))
            (is (= branch-head (:head master)))
            (is (.exists (io/file repo "feature.txt"))))
          (testing "a diverged branch fails without resetting master"
            (let [feature-head (support/commit-file!
                                workspace "feature-2.txt" "feature\n"
                                "feature moves")
                  master-before (support/commit-file!
                                 repo "master-2.txt" "master\n" "master moves")
                  failed (git/ff-merge! adapter {:branch branch :ref "master"})
                  master-after (:head (git/head adapter repo))]
              (is (= :failed (:status failed)) (pr-str failed))
              (is (= :FF_MERGE_FAILED (:code failed)))
              (is (= master-before master-after))
              (is (not= feature-head master-after))
              (is (false? (.exists (io/file repo "feature-2.txt")))))))))))

(deftest create-and-remove-worktree-report-explicit-states
  (with-real-git
    "create-and-remove-worktree-report-explicit-states"
    (fn []
      (support/with-temp-dir [repo "hc-git-real-worktree-lifecycle-"]
        (support/init-repo! repo)
        (let [adapter (git/git-cli repo)
              workspace (str repo "/swarm-1")
              request {:workspace workspace
                       :branch "hc/mission/swarm-1"
                       :base "master"}
              first-create (git/create-worktree! adapter request)
              second-create (git/create-worktree! adapter request)
              first-remove (git/remove-worktree! adapter workspace)
              second-remove (git/remove-worktree! adapter workspace)]
          (is (= :ok (:status first-create)) (pr-str first-create))
          (is (= :failed (:status second-create)) (pr-str second-create))
          (is (= :WORKTREE_ALREADY_EXISTS (:code second-create)))
          (is (= :ok (:status first-remove)) (pr-str first-remove))
          (is (= :ok (:status second-remove)) (pr-str second-remove))
          (is (true? (:already-absent? second-remove)))
          (is (false? (.exists (io/file workspace)))))))))

(deftest real-rebase-conflict-needs-an-explicit-abort
  (with-real-git
    "real-rebase-conflict-needs-an-explicit-abort"
    (fn []
      (support/with-temp-dir [repo "hc-git-real-rebase-"]
        (support/init-repo! repo)
        (let [adapter (git/git-cli repo)
              workspace (str repo "/swarm-1")
              branch "hc/mission/swarm-1"]
          (is (= :ok (:status (git/create-worktree! adapter
                                                    {:workspace workspace
                                                     :branch branch
                                                     :base "master"}))))
          (support/commit-file! repo "base.txt" "master\n" "master edits base")
          (support/commit-file! workspace "base.txt" "feature\n"
                                 "feature edits base")
          (let [rebased (git/rebase-onto! adapter workspace "master")]
            (is (= :conflict (:status rebased)) (pr-str rebased))
            (let [conflicted (slurp (io/file workspace "base.txt"))]
              (is (str/includes? conflicted "<<<<<<<"))
              (is (str/includes? conflicted "master"))
              (is (str/includes? conflicted "feature")))
            (let [aborted (git/rebase-abort! adapter workspace)
                  status (support/git! workspace "status" "--porcelain")]
              (is (= :ok (:status aborted)) (pr-str aborted))
              (is (= "feature\n" (slurp (io/file workspace "base.txt"))))
              (is (str/blank? (:out status))
                  "the worktree is clean and usable after the abort"))))))))
