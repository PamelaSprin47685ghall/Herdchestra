(ns hc.compose.fleet.git-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.support :as support]))

(deftest real-git-is-available
  (let [adapter (git/git-cli ".")
        version (git/git-version adapter)]
    (is (= :ok (:status version)))
    (is (git/available? adapter))))

(deftest creates-rebases-and-fast-forwards-a-real-worktree
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"
          created (git/create-worktree! adapter
                                         {:workspace workspace
                                          :branch branch
                                          :base "master"})]
      (is (= :ok (:status created)))
      (is (.isDirectory (io/file workspace)))
      (let [branch-head (support/commit-file! workspace "work.txt" "work\n" "swarm work")
            rebased (git/rebase-onto! adapter workspace "master")]
        (is (= :ok (:status rebased)))
        (let [merged (git/ff-merge! adapter {:branch branch})
              master-head (git/head adapter repo)]
          (is (= :ok (:status merged)))
          (is (= branch-head (:head merged)))
          (is (= branch-head (:head master-head)))
          (is (.exists (io/file repo "work.txt"))))))))

(deftest refuses-a-non-fast-forward-instead-of-faking-success
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch branch
                                                 :base "master"}))))
      (support/commit-file! workspace "work.txt" "work\n" "swarm work")
      (is (= :ok (:status (git/rebase-onto! adapter workspace "master"))))
      ;; Move master after the swarm rebased.  The branch no longer contains
      ;; master, so --ff-only must fail.
      (support/commit-file! repo "later.txt" "later\n" "master moves")
      (let [master-before (:head (git/head adapter repo))
            merged (git/ff-merge! adapter {:branch branch})
            master-after (:head (git/head adapter repo))]
        (is (= :failed (:status merged)))
        (is (= master-before master-after)
            "a failed ff must not move master")
        (is (not= :ok (:status merged)))))))

(deftest detects-a-real-rebase-conflict
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch branch
                                                 :base "master"}))))
      (support/commit-file! workspace "base.txt" "branch\n" "branch edits base")
      (support/commit-file! repo "base.txt" "master\n" "master edits base")
      (let [rebased (git/rebase-onto! adapter workspace "master")
            aborted (git/rebase-abort! adapter workspace)
            status (support/git! workspace "status" "--porcelain")]
        (is (= :conflict (:status rebased)))
        (is (= :ok (:status aborted)))
        (is (zero? (count (str/trim (:out status)))))))))

(deftest continues-a-rebase-after-the-holder-resolves-it
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch branch
                                                 :base "master"}))))
      (support/commit-file! workspace "base.txt" "branch\n" "branch edits base")
      (support/commit-file! repo "base.txt" "master\n" "master edits base")
      (is (= :conflict (:status (git/rebase-onto! adapter workspace "master"))))
      (spit (io/file workspace "base.txt") "resolved\n")
      (support/git! workspace "add" "base.txt")
      (let [continued (git/rebase-continue! adapter workspace)]
        (is (= :ok (:status continued)))
        (is (= "resolved\n" (slurp (io/file workspace "base.txt"))))))))

(deftest refuses-to-merge-when-master-is-not-checked-out
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (support/git! repo "branch" "other")
    (support/git! repo "checkout" "other")
    (support/commit-file! repo "other.txt" "other\n" "other moves")
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch branch
                                                 :base "master"}))))
      (let [master-before (:head (git/repo-head adapter "master"))
            merged (git/ff-merge! adapter {:branch branch :ref "master"})
            master-after (:head (git/repo-head adapter "master"))]
        (is (= :failed (:status merged)))
        (is (= :MASTER_NOT_CHECKED_OUT (:code merged)))
        (is (= master-before master-after))))))

(deftest reports-the-real-worktree-branch
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch branch
                                                 :base "master"}))))
      (let [attached (git/current-branch adapter workspace)]
        (is (= :ok (:status attached)))
        (is (= branch (:branch attached)))
        (is (nil? (:detached? attached))))
      (support/git! workspace "checkout" "--detach")
      (let [detached (git/current-branch adapter workspace)]
        (is (= :ok (:status detached)))
        (is (nil? (:branch detached)))
        (is (true? (:detached? detached))))
      (support/git! workspace "branch" "foreign")
      (support/git! workspace "checkout" "foreign")
      (let [foreign (git/current-branch adapter workspace)]
        (is (= :ok (:status foreign)))
        (is (= "foreign" (:branch foreign)))
        (is (nil? (:detached? foreign))))
      (let [missing (git/current-branch adapter (str repo "/missing"))]
        (is (= :failed (:status missing)))
        (is (= :BRANCH_NAME_UNREADABLE (:code missing)))))))

(deftest refuses-a-same-commit-different-branch
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (support/git! repo "branch" "other")
    (support/git! repo "checkout" "other")
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch branch
                                                 :base "master"}))))
      (let [merged (git/ff-merge! adapter {:branch branch :ref "master"})]
        (is (= :failed (:status merged)))
        (is (= :MASTER_NOT_CHECKED_OUT (:code merged)))
        (is (= "other" (:current-branch merged)))))))

(deftest never-deletes-a-preexisting-branch-on-create-failure
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (support/git! repo "branch" "hc/mission/swarm-1")
    (let [adapter (git/git-cli repo)
          created (git/create-worktree! adapter
                                         {:workspace (str repo "/swarm-1")
                                          :branch "hc/mission/swarm-1"
                                          :base "master"})
          branch (git/branch-head adapter "hc/mission/swarm-1")]
      (is (= :failed (:status created)))
      (is (= :ok (:status branch))
          "a branch that existed before the attempt must survive cleanup"))))

(deftest reports-missing-base-and-branch-honestly
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          created (git/create-worktree! adapter
                                         {:workspace (str repo "/bad")
                                          :branch "hc/mission/bad"
                                          :base "does-not-exist"})
          missing (git/branch-head adapter "does-not-exist")]
      (is (= :failed (:status created)))
      (is (= :failed (:status missing)))
      (is (not (git/available? (reify git/GitPort
                                 (git-version [_] {:status :unavailable})
                                 (repo-head [_ _] {:status :failed})
                                 (branch-head [_ _] {:status :failed})
                                 (head [_ _] {:status :failed})
                                 (current-branch [_ _] {:status :failed})
                                 (ancestor? [_ _ _] {:status :failed})
                                 (worktree-clean? [_ _] false)
                                 (ff-ready? [_ _ _] {:ready? false
                                                      :reason :GIT_CHECK_FAILED})
                                 (create-worktree! [_ _] {:status :failed})
                                 (remove-worktree! [_ _] {:status :failed})
                                 (delete-branch! [_ _] {:status :failed})
                                 (rebase-onto! [_ _ _] {:status :failed})
                                 (rebase-continue! [_ _] {:status :failed})
                                 (stage-all! [_ _] {:status :failed})
                                 (rebase-abort! [_ _] {:status :failed})
                                 (ff-merge! [_ _] {:status :failed}))))))))

(deftest resource-exhaustion-is-classified-as-insufficient-worktrees
  (is (true? (#'git/resource-exhausted? "fatal: No space left on device")))
  (is (true? (#'git/resource-exhausted? "fatal: Disk quota exceeded")))
  (is (false? (#'git/resource-exhausted? "fatal: already exists")))
  (is (= :INSUFFICIENT_WORKTREES
         (#'git/classify-worktree-failure
          {:status :failed
           :out ""
           :err "fatal: unable to allocate memory"}))))

(deftest reports-whether-a-real-worktree-is-clean
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch "hc/mission/swarm-1"
                                                 :base "master"}))))
      (testing "a fresh worktree is clean"
        (is (true? (git/worktree-clean? adapter workspace))))
      (testing "an untracked file makes it dirty"
        (spit (io/file workspace "wip.txt") "wip\n")
        (is (false? (git/worktree-clean? adapter workspace))))
      (testing "a missing directory is never optimistically clean"
        (is (false? (git/worktree-clean? adapter (str repo "/missing"))))))))

(deftest fake-git-exposes-configurable-ff-readiness
  (testing "the default fake stays clean and ready"
    (let [fake (support/fake-git {})]
      (is (true? (git/worktree-clean? (:git fake) "/worktree")))
      (is (= {:ready? true
              :head "master-0"
              :target-head "master-0"
              :clean? true
              :reason :ready}
             (git/ff-ready? (:git fake) "/worktree" "master")))))
  (testing "directory and functional overrides expose the failure branches"
    (let [fake (support/fake-git
                {:clean? {"/dirty" false}
                 :ff-ready
                 (fn [directory target-ref]
                   {:ready? false
                    :head "head-1"
                    :target-head "head-0"
                    :clean? false
                    :reason :TARGET_NOT_ANCESTOR
                    :directory directory
                    :target-ref target-ref})})
          adapter (:git fake)]
      (is (false? (git/worktree-clean? adapter "/dirty")))
      (is (true? (git/worktree-clean? adapter "/clean")))
      (is (= {:ready? false
              :head "head-1"
              :target-head "head-0"
              :clean? false
              :reason :TARGET_NOT_ANCESTOR
              :directory "/dirty"
              :target-ref "master"}
             (git/ff-ready? adapter "/dirty" "master")))
      (is (= [[:worktree-clean? "/dirty"]
              [:worktree-clean? "/clean"]
              [:ff-ready? "/dirty" "master"]]
             @(:calls fake))))))

(deftest ff-ready-requires-a-clean-descendant-worktree
  (support/with-temp-dir [repo "hc-fleet-git-"]
    (support/init-repo! repo)
    (let [adapter (git/git-cli repo)
          workspace (str repo "/swarm-1")
          branch "hc/mission/swarm-1"]
      (is (= :ok (:status (git/create-worktree! adapter
                                                {:workspace workspace
                                                 :branch branch
                                                 :base "master"}))))
      (testing "a fresh worktree contains master and is clean"
        (let [ready (git/ff-ready? adapter workspace "master")]
          (is (true? (:ready? ready)) (pr-str ready))
          (is (true? (:clean? ready)))
          (is (= (:head ready) (:target-head ready)))
          (is (= :ready (:reason ready)))))
      (testing "uncommitted work refuses the check"
        (spit (io/file workspace "wip.txt") "wip\n")
        (let [ready (git/ff-ready? adapter workspace "master")]
          (is (false? (:ready? ready)))
          (is (false? (:clean? ready)))
          (is (= :WORKTREE_DIRTY (:reason ready)))))
      (support/git! workspace "clean" "-fd")
      (support/commit-file! workspace "work.txt" "work\n" "swarm work")
      (testing "a clean descendant of master is ff-ready"
        (let [ready (git/ff-ready? adapter workspace "master")]
          (is (true? (:ready? ready)) (pr-str ready))
          (is (true? (:clean? ready)))))
      (support/commit-file! repo "later.txt" "later\n" "master moves")
      (testing "an outdated worktree is not ff-ready"
        (let [ready (git/ff-ready? adapter workspace "master")]
          (is (false? (:ready? ready)))
          (is (= :TARGET_NOT_ANCESTOR (:reason ready)))))
      (testing "an unreadable target ref is reported, not guessed"
        (let [ready (git/ff-ready? adapter workspace "does-not-exist")]
          (is (false? (:ready? ready)))
          (is (nil? (:target-head ready)))
          (is (= :TARGET_HEAD_UNREADABLE (:reason ready)))))
      (testing "a missing worktree is reported, not guessed"
        (let [ready (git/ff-ready? adapter (str repo "/missing") "master")]
          (is (false? (:ready? ready)))
          (is (false? (:clean? ready)))
          (is (= :HEAD_UNREADABLE (:reason ready))))))))
