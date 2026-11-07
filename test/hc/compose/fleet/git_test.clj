(ns hc.compose.fleet.git-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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
