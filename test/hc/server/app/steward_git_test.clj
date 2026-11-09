(ns hc.server.app.steward-git-test
  "Real Git observations for the Steward.

  Every test builds its own temporary repository and calls the adapter the
  same way `hc.server.app.steward-runtime` does.  Tests that need the real
  executable are guarded by `git/available?`; the failure-shape tests use an
  injected runner and therefore run everywhere."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.support :as support]
            [hc.core.steward-sphinx :as sphinx]
            [hc.server.app.steward-git :as steward-git]))

(defn- git-available?
  []
  (git/available? (git/git-cli ".")))

(defn- current-head
  [repo]
  (str/trim (:out (support/git! repo "rev-parse" "HEAD"))))

(defn- write-bytes!
  [repo name value]
  (with-open [out (io/output-stream (io/file repo name))]
    (.write out ^bytes value)))

(defn- log-fn
  ([repo] (log-fn repo {}))
  ([repo opts]
   (steward-git/git-log-since-fn (git/git-cli repo) opts)))

(deftest parses-real-commits-as-git-evidence
  (when (git-available?)
    (support/with-temp-dir [repo "hc-steward-git-"]
      (support/init-repo! repo)
      (let [base (current-head repo)
            text-sha (support/commit-file!
                      repo "中 文\"引号\".txt" "one line\n" "chinese and quotes")
            _ (write-bytes! repo "bin.dat" (byte-array [0 1 2 3]))
            _ (support/git! repo "add" "bin.dat")
            _ (support/git! repo "commit" "-m" "binary file")
            binary-sha (current-head repo)
            run (log-fn repo {:ref "master" :max-commits 10})
            result (run {:since nil :ref "master" :now 0})]
        (is (:ok? result) (pr-str result))
        (is (= binary-sha (:head result)))
        (is (= 3 (:master-version result)))
        (is (false? (:truncated? result)))
        (is (= 3 (:scanned result)))
        (is (= 3 (count (:commits result))))
        (let [commits (:commits result)
              text-commit (first (filter #(= text-sha (:commit %)) commits))
              binary-commit (first (filter #(= binary-sha (:commit %))
                                           commits))]
          (is text-commit)
          (is binary-commit)
          (testing "a text commit carries the exact evidence shape"
            (is (= (str "git:" text-sha) (:evidence-id text-commit)))
            (is (= :git-commit (:kind text-commit)))
            (is (= [base] (:parents text-commit)))
            (is (= "chinese and quotes" (:subject text-commit)))
            (is (= "Fleet Test" (:author text-commit)))
            (is (re-matches #"\d+" (:committed-at text-commit)))
            (is (string? (:tree text-commit)))
            (is (= #{"中 文\"引号\".txt"}
                   (set (:changed-paths text-commit))))
            (is (= 1 (:insertions text-commit)))
            (is (= 0 (:deletions text-commit))))
          (testing "a binary numstat line keeps its path and counts zero"
            (is (= #{"bin.dat"} (set (:changed-paths binary-commit))))
            (is (= 0 (:insertions binary-commit)))
            (is (= 0 (:deletions binary-commit))))
          (testing "every commit is accepted by the Sphinx evidence validator"
            (is (every? :ok? (map sphinx/normalize-evidence commits))))
          (testing "order is commit time ascending, then sha ascending"
            (is (= (vec commits)
                   (vec (sort-by (juxt #(Long/parseLong (:committed-at %))
                                       :commit)
                                 commits))))))))))

(deftest since-filter-and-default-ref
  (when (git-available?)
    (support/with-temp-dir [repo "hc-steward-git-"]
      (support/init-repo! repo)
      (let [base (current-head repo)
            first-sha (support/commit-file! repo "a.txt" "a\n" "first")
            second-sha (support/commit-file! repo "b.txt" "b\n" "second")
            head (current-head repo)
            run (log-fn repo {:max-commits 10})
            result (run {:since base :now 0})]
        (is (:ok? result) (pr-str result))
        (is (= head (:head result)) "the factory default ref is HEAD")
        (is (= #{first-sha second-sha}
               (set (map :commit (:commits result)))))
        (is (= 2 (count (:commits result))))
        (is (= 3 (:master-version result))
            "master-version counts the whole ref, not the since window")
        (testing "an empty since window is a success, not a failure"
          (let [again (run {:since head :now 0})]
            (is (:ok? again) (pr-str again))
            (is (= head (:head again)))
            (is (empty? (:commits again)))
            (is (false? (:truncated? again)))
            (is (zero? (:scanned again)))
            (is (= 3 (:master-version again)))))))))

(deftest max-commits-truncates-without-failing
  (when (git-available?)
    (support/with-temp-dir [repo "hc-steward-git-"]
      (support/init-repo! repo)
      (let [base (current-head repo)
            first-sha (support/commit-file! repo "a.txt" "a\n" "first")
            second-sha (support/commit-file! repo "b.txt" "b\n" "second")
            run (log-fn repo {:max-commits 2})
            result (run {:since nil :now 0})
            commits (:commits result)]
        (is (:ok? result) (pr-str result))
        (is (= 2 (count commits)))
        (is (= #{first-sha second-sha}
               (set (map :commit commits)))
            "the newest two commits are kept")
        (is (not (contains? (set (map :commit commits)) base)))
        (is (true? (:truncated? result)))
        (is (= 3 (:scanned result))
            "git reads one sentinel commit past the limit")
        (is (= 3 (:master-version result)))))))

(deftest byte-limit-truncates-the-evidence-vector
  (when (git-available?)
    (support/with-temp-dir [repo "hc-steward-git-"]
      (support/init-repo! repo)
      (let [message-file (io/file repo "commit-message.txt")]
        (spit message-file (apply str (repeat 120000 "x")))
        (dotimes [_ 25]
          (support/git! repo "commit" "--allow-empty" "-F" (str message-file)))
        (let [run (log-fn repo {:max-commits 50})
              result (run {:since nil :now 0})
              commits (:commits result)
              bytes (count (.getBytes ^String (pr-str commits) "UTF-8"))]
          (is (:ok? result) (pr-str (dissoc result :commits)))
          (is (true? (:truncated? result)))
          (is (pos? (count commits)))
          (is (< (count commits) 26))
          (is (<= bytes steward-git/max-commit-bytes)
              "the serialized commit vector stays within the 2 MiB budget")
          (is (= 26 (:scanned result))
              "all bounded commits were scanned before the byte cap"))))))

(deftest empty-repository-is-a-success
  (when (git-available?)
    (support/with-temp-dir [repo "hc-steward-git-"]
      (support/git! repo "init" "-q" "-b" "master")
      (let [result ((log-fn repo) {:since nil :now 0})]
        (is (:ok? result) (pr-str result))
        (is (nil? (:head result)))
        (is (= [] (:commits result)))
        (is (= 0 (:master-version result)))
        (is (false? (:truncated? result)))
        (is (= 0 (:scanned result)))))))

(deftest non-repository-is-reported-not-thrown
  (when (git-available?)
    (support/with-temp-dir [dir "hc-steward-not-a-repo-"]
      (let [result ((log-fn dir) {:since nil :now 0})]
        (is (false? (:ok? result)))
        (is (= :STEWARD_GIT_UNAVAILABLE (get-in result [:error :code])))
        (is (string? (get-in result [:error :message])))
        (is (map? (get-in result [:error :details])))))))

(deftest injected-runner-failures-map-to-codes
  (let [make (fn [result]
               (steward-git/git-log-since-fn
                "/fake/repo"
                {:run-command (fn [_ _] result)}))
        unavailable ((make {:status :unavailable :err "git: command not found"})
                     {:since nil :now 0})
        timeout ((make {:status :timeout :err "slow"})
                 {:since nil :now 0})
        failed ((make {:status :failed :exit 1 :err "boom"})
                {:since nil :now 0})
        thrown-runner (steward-git/git-log-since-fn
                       "/fake/repo"
                       {:run-command (fn [_ _]
                                       (throw (ex-info "runner died" {})))})]
    (is (= :STEWARD_GIT_UNAVAILABLE (get-in unavailable [:error :code])))
    (is (= :STEWARD_GIT_TIMEOUT (get-in timeout [:error :code])))
    (is (= :STEWARD_GIT_FAILED (get-in failed [:error :code])))
    (is (= :STEWARD_GIT_FAILED
           (get-in (thrown-runner {:since nil :now 0}) [:error :code])))
    (doseq [result [unavailable timeout failed
                    (thrown-runner {:since nil :now 0})]]
      (is (false? (:ok? result)))
      (is (string? (get-in result [:error :message])))
      (is (map? (get-in result [:error :details]))))))

(deftest blocking-runner-maps-to-timeout-deterministically
  (let [captured (atom nil)
        run (steward-git/git-log-since-fn
             "/fake/repo"
             {:timeout-ms 50
              :run-command (fn [argv opts]
                             (reset! captured {:argv argv :opts opts})
                             ;; Simulate a command that outlives its bound.
                             ;; The assertion is on the structured result,
                             ;; never on elapsed milliseconds.
                             (Thread/sleep 200)
                             {:status :timeout :err "too slow"})})
        result (run {:since nil :now 0})]
    (is (= :STEWARD_GIT_TIMEOUT (get-in result [:error :code])))
    (is (= 50 (get-in @captured [:opts :timeout-ms])))
    (is (= "/fake/repo" (get-in @captured [:opts :dir])))
    (is (= "git" (first (get-in @captured [:argv]))))))

(deftest c-quoted-paths-decode-without-guessing
  (is (= "plain.txt" (#'steward-git/unquote-path "plain.txt")))
  (is (= "a b\"c\".txt"
         (#'steward-git/unquote-path "\"a b\\\"c\\\".txt\"")))
  (is (= "tab\there"
         (#'steward-git/unquote-path "\"tab\\there\""))))
