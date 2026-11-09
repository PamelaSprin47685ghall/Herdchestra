(ns hc.compose.fleet.master-integration-test
  "Real Git tests for master integration serialization.

  `hc.compose.fleet.control` serializes every rebase + fast-forward under its
  master integration lock.  The fake-based control tests cannot prove that
  concurrent `join!` calls produce one linear master history, so these tests
  drive real worktrees and real Git from independent threads.

  Every test uses its own `mkdtemp` root and deletes it in `finally`.  All
  waits are bounded; a broken lock fails a test instead of hanging the JVM."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.control :as control]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.support :as support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- slice
  [id]
  {:slice-id id
   :goal (str "goal " id)
   :inputs []
   :depends-on []
   :done-when "done"})

(defn- make-control
  [repo worktree-root opts]
  (control/new-control
   (merge {:mission-id "master-integration-test"
           :worktree-root worktree-root
           :git (git/git-cli repo)
           :launch-swarm! (fn [_] true)
           :resolve-conflict! nil
           :clock #(System/currentTimeMillis)
           :retention-ms 60000
           :max-rebase-rounds 16}
          opts)))

(defn- fork-swarms!
  "Fork SLICES and return `slice-id -> swarm`, or throw after a clear failure."
  [c slices]
  (let [result (control/fork! c slices)]
    (is (:ok? result) (pr-str result))
    (when-not (:ok? result)
      (throw (ex-info "fleet fork failed" result)))
    (into {} (map (juxt :slice-id identity)) (:swarms result))))

(defn- rev-count
  [repo & options]
  (Long/parseLong
   (str/trim (:out (apply support/git! repo "rev-list" "--count" options)))))

(defn- ancestor-commit?
  [repo ancestor descendant]
  (let [result (git/run-command
                ["git" "merge-base" "--is-ancestor" ancestor descendant]
                {:dir repo})]
    (and (= :ok (:status result))
         (zero? (long (or (:exit result) 1))))))

(defn- commit!
  [workspace file slice-id]
  (support/commit-file! workspace file (str slice-id "\n")
                        (str "work from " slice-id)))

(defn- join-bounded
  [c]
  (control/join! c {:timeout-ms 10000}))

(deftest concurrent-joins-produce-one-linear-master
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-master-integration-concurrent-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")
            _ (.mkdirs (io/file repo))
            _ (support/init-repo! repo)
            c (make-control repo worktree-root {})
            swarms (fork-swarms! c [(slice "a") (slice "b") (slice "c")])
            start (CountDownLatch. 3)
            futures (atom [])]
        (try
          (doseq [slice-id ["a" "b" "c"]]
            (swap! futures conj
                   (future
                     (let [swarm (get swarms slice-id)
                           workspace (:workspace swarm)]
                       (commit! workspace (str slice-id ".txt") slice-id)
                       (control/complete! c (:swarm-id swarm)
                                          (str "report-" slice-id)
                                          {:board-version 1})
                       (.countDown start)
                       (.await start 5 TimeUnit/SECONDS)
                       (join-bounded c)))))
          (let [results (mapv #(deref % 15000 ::timeout) @futures)]
            (is (not-any? #{::timeout} results))
            (doseq [result results]
              (is (string? (:ff-commit result)) (pr-str result)))
            (let [joined (filterv #(string? (:ff-commit %)) results)
                  state (control/snapshot c)
                  by-version (sort-by #(get-in state [:swarms (:swarm-id %)
                                                       :master-version])
                                      joined)
                  versions (mapv #(get-in state [:swarms (:swarm-id %)
                                                :master-version])
                                 by-version)
                  ff-commits (mapv :ff-commit by-version)]
              (is (= 3 (count joined)))
              (is (= 3 (count (set (map :swarm-id joined))))
                  "each concurrent join consumes one distinct result")
              (is (= [1 2 3] versions)
                  "the integration lock assigns master versions 1,2,3")
              (testing "ff commits are a strict ancestor chain"
                (doseq [[previous next] (partition 2 1 ff-commits)]
                  (is (ancestor-commit? repo previous next)
                      (str previous " must be an ancestor of " next))
                  (is (= 1 (rev-count repo (str previous ".." next)))
                      "each integration adds exactly one commit")))
              (testing "master is linear"
                (let [all (rev-count repo "master")
                      first-parent (rev-count repo "--first-parent" "master")
                      merges (rev-count repo "--merges" "master")]
                  (is (= 4 all) "base commit plus three integrated commits")
                  (is (= all first-parent)
                      "first-parent count equals total count: no side branches")
                  (is (zero? merges) "no merge commit was created")
                  (println "master-integration/concurrent evidence"
                           {:versions versions
                            :ff-commits ff-commits
                            :rev-list all
                            :first-parent first-parent
                            :merges merges})))
              (doseq [slice-id ["a" "b" "c"]]
                (is (.exists (io/file repo (str slice-id ".txt")))))
              (is (= (:ff-commit (last by-version))
                     (:head (git/repo-head (git/git-cli repo) "master"))))))
          (finally
            (doseq [f @futures] (future-cancel f))))))))

(deftest a-slice-based-on-old-master-rebases-before-ff
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-master-integration-rebase-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")
            _ (.mkdirs (io/file repo))
            _ (support/init-repo! repo)
            c (make-control repo worktree-root {})
            swarms (fork-swarms! c [(slice "a") (slice "b")])
            swarm-a (get swarms "a")
            swarm-b (get swarms "b")]
        (commit! (:workspace swarm-a) "a.txt" "a")
        (control/complete! c (:swarm-id swarm-a) "report-a" {:board-version 1})
        (let [a-result (join-bounded c)]
          (is (= (:swarm-id swarm-a) (:swarm-id a-result)) (pr-str a-result))
          (is (string? (:ff-commit a-result)))
          ;; B was forked from the old master and commits only now.
          (let [b-before (commit! (:workspace swarm-b) "b.txt" "b")]
            (control/complete! c (:swarm-id swarm-b) "report-b"
                               {:board-version 1})
            (let [b-result (join-bounded c)
                  state (control/snapshot c)]
              (is (= (:swarm-id swarm-b) (:swarm-id b-result))
                  (pr-str b-result))
              (is (string? (:ff-commit b-result)))
              (is (>= (long (:rebase-rounds b-result)) 1))
              (is (not= b-before (:ff-commit b-result))
                  "the old-master commit was rewritten by the rebase")
              (is (ancestor-commit? repo (:ff-commit a-result)
                                    (:ff-commit b-result)))
              (is (= 1 (rev-count repo (str (:ff-commit a-result) ".."
                                            (:ff-commit b-result)))))
              (is (= (:ff-commit a-result)
                     (str/trim
                      (:out (support/git! repo "rev-parse"
                                          (str (:ff-commit b-result) "^")))))
                  "B's parent is A in the linear master history")
              (is (= 3 (rev-count repo "--first-parent" "master")))
              (is (zero? (rev-count repo "--merges" "master")))
              (println "master-integration/rebase evidence"
                       {:a (:ff-commit a-result)
                        :a-rebase-rounds (:rebase-rounds a-result)
                        :b (:ff-commit b-result)
                        :b-rebase-rounds (:rebase-rounds b-result)
                        :b-pre-rebase b-before
                        :master-version (:master-version state)}))))))))

(deftest a-conflict-is-abandoned-without-blocking-the-next-result
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-master-integration-conflict-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")
            _ (.mkdirs (io/file repo))
            _ (support/init-repo! repo)
            resolver-calls (atom [])
            c (make-control repo worktree-root
                            {:resolve-conflict!
                             (fn [request]
                               (swap! resolver-calls conj request)
                               :abandon)})
            swarms (fork-swarms! c [(slice "a") (slice "b") (slice "c")])
            swarm-a (get swarms "a")
            swarm-b (get swarms "b")
            swarm-c (get swarms "c")]
        (commit! (:workspace swarm-a) "base.txt" "a")
        (commit! (:workspace swarm-c) "base.txt" "c")
        (commit! (:workspace swarm-b) "other.txt" "b")
        ;; Completion order is the queue order: A, then the conflicting C,
        ;; then B.  A joins first; C must abandon; B still joins.
        (control/complete! c (:swarm-id swarm-a) "report-a" {:board-version 1})
        (control/complete! c (:swarm-id swarm-c) "report-c" {:board-version 1})
        (control/complete! c (:swarm-id swarm-b) "report-b" {:board-version 1})
        (let [a-result (join-bounded c)]
          (is (= (:swarm-id swarm-a) (:swarm-id a-result)) (pr-str a-result))
          (let [b-result (join-bounded c)
                state (control/snapshot c)
                c-state (get-in state [:swarms (:swarm-id swarm-c)])]
            (is (= (:swarm-id swarm-b) (:swarm-id b-result)) (pr-str b-result))
            (is (= 1 (count @resolver-calls))
                "C entered the conflict resolver exactly once")
            (is (= (:swarm-id swarm-c) (:swarm-id (first @resolver-calls))))
            (is (= :abandoned (:status c-state)))
            (is (some? (:abandon-reason c-state)))
            (is (number? (:gc-after c-state))
                "the abandoned worktree is retained until its GC deadline")
            (is (false? (boolean (:worktree-removed? c-state))))
            (is (nil? (:ff-commit c-state)))
            (is (not-any? #{(:swarm-id swarm-c)} (:completion-queue state))
                "C's result never enters the join queue")
            (is (= "a\n" (slurp (io/file repo "base.txt"))))
            (is (= 3 (rev-count repo "--first-parent" "master"))
                "base, A, and B only; C never reaches master")
            (is (zero? (rev-count repo "--merges" "master")))
            (is (= :none (join-bounded c)))
            (println "master-integration/conflict evidence"
                     {:a (:ff-commit a-result)
                      :c-status (:status c-state)
                      :c-gc-after (:gc-after c-state)
                      :c-in-queue? false
                      :b (:ff-commit b-result)
                      :master-count (rev-count repo "--first-parent" "master")
                      :resolver-calls (count @resolver-calls)})))))))

(deftest join-blocks-while-a-swarm-is-running-and-then-returns
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-master-integration-blocking-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")
            _ (.mkdirs (io/file repo))
            _ (support/init-repo! repo)
            c (make-control repo worktree-root {})
            swarms (fork-swarms! c [(slice "a")])
            swarm (get swarms "a")
            waiting (future (control/join! c {:timeout-ms 5000}))]
        (try
          (testing "an empty queue with a running swarm blocks"
            (is (= ::blocked (deref waiting 500 ::blocked))))
          (commit! (:workspace swarm) "a.txt" "a")
          (control/complete! c (:swarm-id swarm) "report-a" {:board-version 1})
          (let [result (deref waiting 2000 ::timeout)]
            (is (not= ::timeout result)
                "the completed result wakes the blocked join")
            (is (= (:swarm-id swarm) (:swarm-id result)) (pr-str result))
            (is (string? (:ff-commit result))))
          (testing "the queue is empty after that one result"
            (is (= :none (join-bounded c))))
          (finally
            (future-cancel waiting)))))))

(deftest one-single-thread-join-succeeds-after-a-concurrent-burst
  (when (git/available? (git/git-cli "."))
    (support/with-temp-dir [root "hc-master-integration-no-leak-"]
      (let [repo (str root "/repo")
            worktree-root (str root "/worktrees")
            _ (.mkdirs (io/file repo))
            _ (support/init-repo! repo)
            c (make-control repo worktree-root {})
            swarms (fork-swarms! c [(slice "a") (slice "b") (slice "c")])
            start (CountDownLatch. 2)
            futures (atom [])]
        (try
          (doseq [slice-id ["a" "b" "c"]]
            (let [swarm (get swarms slice-id)]
              (commit! (:workspace swarm) (str slice-id ".txt") slice-id)
              (control/complete! c (:swarm-id swarm) (str "report-" slice-id)
                                 {:board-version 1})))
          (dotimes [_ 2]
            (swap! futures conj
                   (future
                     (.countDown start)
                     (.await start 5 TimeUnit/SECONDS)
                     (join-bounded c))))
          (let [concurrent (mapv #(deref % 15000 ::timeout) @futures)]
            (is (not-any? #{::timeout} concurrent))
            (doseq [result concurrent]
              (is (string? (:ff-commit result)) (pr-str result)))
            (is (= 2 (count (set (map :swarm-id concurrent)))))
            (let [third (join-bounded c)]
              (is (string? (:ff-commit third))
                  "a later single-thread join still acquires the lock")
              (is (not (contains? (set (map :swarm-id concurrent))
                                  (:swarm-id third))))
              (is (= 3 (count (set (concat (map :swarm-id concurrent)
                                           [(:swarm-id third)])))))
              (is (= 4 (rev-count repo "master")))
              (is (= 4 (rev-count repo "--first-parent" "master")))
              (is (zero? (rev-count repo "--merges" "master")))
              (doseq [result (conj concurrent third)]
                (is (ancestor-commit? repo (:ff-commit result)
                                      (:head (git/repo-head
                                              (git/git-cli repo) "master")))))))
          (finally
            (doseq [f @futures] (future-cancel f))))))))
