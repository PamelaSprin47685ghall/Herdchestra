(ns hc.compose.fleet.support
  "Test-only helpers.  Nothing here is on the production path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.compose.fleet.git :as git])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn delete-tree!
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (.delete file)))))

(defmacro with-temp-dir
  [[binding prefix] & body]
  `(let [~binding (temp-dir ~prefix)]
     (try
       ~@body
       (finally
         (delete-tree! ~binding)))))

(defn git!
  [dir & args]
  (let [result (git/run-command (into ["git"] args) {:dir dir})]
    (when-not (= :ok (:status result))
      (throw (ex-info "test git command failed"
                      {:dir dir :args args :result result})))
    result))

(defn init-repo!
  [dir]
  (git! dir "init" "-b" "master")
  (git! dir "config" "user.email" "fleet@example.test")
  (git! dir "config" "user.name" "Fleet Test")
  (git! dir "config" "commit.gpgsign" "false")
  (spit (io/file dir "base.txt") "base\n")
  (git! dir "add" "base.txt")
  (git! dir "commit" "-m" "base")
  dir)

(defn commit-file!
  [dir name content message]
  (spit (io/file dir name) content)
  (git! dir "add" name)
  (git! dir "commit" "-m" message)
  (str/trim (:out (git! dir "rev-parse" "HEAD"))))

(defn fake-git
  "A deliberately explicit test double.

  `:create-results`, `:remove-results`, `:delete-results`, `:rebase-results`,
  `:continue-results`, and `:ff-results` are queues of adapter results.  An
  empty queue falls back to the corresponding `:default-*` result.  The double
  records every call so a test can prove that a failure did not fabricate a
  fast-forward.  `:create-hook!` runs before each worktree result and is used
  by the allocation-blocking test to stall fork.

  `:clean?` and `:ff-ready` configure the `worktree-clean?` / `ff-ready?`
  answers.  `:clean?` accepts a literal boolean, a map keyed by directory, or
  a function of the directory.  `:ff-ready` accepts a literal result map or a
  function of `[directory target-ref]`.  The defaults keep every existing
  test unchanged: clean is true and ff-ready reports
  `{:ready? true :reason :ready}`."
  [opts]
  (let [calls (atom [])
        heads (atom (or (:heads opts) {}))
        current-branches (atom (or (:current-branches opts) {}))
        repo-head (atom (or (:repo-head opts) "master-0"))
        branch-heads (atom (or (:branch-heads opts) {}))
        create-results (atom (vec (or (:create-results opts) [])))
        rebase-results (atom (vec (or (:rebase-results opts) [])))
        continue-results (atom (vec (or (:continue-results opts) [])))
        stage-results (atom (vec (or (:stage-results opts) [])))
        ff-results (atom (vec (or (:ff-results opts) [])))
        remove-results (atom (vec (or (:remove-results opts) [])))
        delete-results (atom (vec (or (:delete-results opts) [])))
        create-hook! (or (:create-hook! opts) (fn [_] nil))
        head-seq (atom 0)
        clean? (fn [directory]
                 (let [configured (:clean? opts true)]
                   (cond
                     (fn? configured) (boolean (configured directory))
                     (map? configured) (boolean (get configured directory true))
                     :else (boolean configured))))
        ff-ready (fn [directory target-ref]
                   (let [configured (:ff-ready opts)]
                     (cond
                       (fn? configured) (configured directory target-ref)
                       (map? configured) configured
                       (some? configured) configured
                       :else
                       {:ready? true
                        :head (get @heads directory @repo-head)
                        :target-head (get @branch-heads target-ref @repo-head)
                        :clean? true
                        :reason :ready})))
        next-head! (fn []
                     (str "head-" (swap! head-seq inc)))
        pop! (fn [q default]
               (if (and q (seq @q))
                 (let [v (first @q)]
                   (swap! q subvec 1)
                   v)
                 default))]
    {:calls calls
     :repo-head repo-head
     :branch-heads branch-heads
     :heads heads
     :current-branches current-branches
     :git
     (reify git/GitPort
       (git-version [_]
         (swap! calls conj [:git-version])
         {:status :ok :out "git version fake"})

       (repo-head [_ ref]
         (swap! calls conj [:repo-head ref])
         {:status :ok :ref ref :head @repo-head})

       (branch-head [_ branch]
         (swap! calls conj [:branch-head branch])
         (if-let [head (get @branch-heads branch)]
           {:status :ok :branch branch :head head}
           {:status :failed :code :BRANCH_NOT_FOUND :branch branch}))

       (head [_ directory]
         (swap! calls conj [:head directory])
         (if-let [head (get @heads directory)]
           {:status :ok :head head}
           (if (= directory (:repo opts))
             {:status :ok :head @repo-head}
             {:status :failed :code :HEAD_NOT_FOUND :directory directory})))

       (current-branch [_ directory]
         (swap! calls conj [:current-branch directory])
         (let [branch (get @current-branches directory)]
           (cond
             (= :detached branch)
             {:status :ok :directory directory :branch nil :detached? true}

             (string? branch)
             {:status :ok :directory directory :branch branch}

             (= directory (:repo opts))
             {:status :ok :directory directory :branch "master"}

             :else
             {:status :failed
              :code :BRANCH_NAME_UNREADABLE
              :directory directory})))

       (ancestor? [_ directory ref]
         (swap! calls conj [:ancestor? directory ref])
         {:status :ok :ancestor? true :ref ref})

       (worktree-clean? [_ directory]
         (swap! calls conj [:worktree-clean? directory])
         (clean? directory))

       (ff-ready? [_ directory target-ref]
         (swap! calls conj [:ff-ready? directory target-ref])
         (ff-ready directory target-ref))

       (create-worktree! [_ {:keys [workspace branch base]}]
         (swap! calls conj [:create-worktree workspace branch base])
         (create-hook! {:workspace workspace :branch branch :base base})
         (let [result (pop! create-results
                            (or (:default-create opts)
                                {:status :ok}))]
           (if (= :ok (:status result))
             (let [head (or (:head result) (next-head!))]
               (swap! heads assoc workspace head)
               (swap! branch-heads assoc branch head)
               (swap! current-branches assoc workspace branch)
               (assoc result :workspace workspace :branch branch :head head))
             result)))

       (remove-worktree! [_ workspace]
         (swap! calls conj [:remove-worktree workspace])
         (pop! remove-results {:status :ok :workspace workspace}))

       (delete-branch! [_ branch]
         (swap! calls conj [:delete-branch branch])
         (swap! branch-heads dissoc branch)
         (pop! delete-results {:status :ok :branch branch}))

       (rebase-onto! [_ workspace ref]
         (swap! calls conj [:rebase-onto workspace ref])
         (pop! rebase-results {:status :ok :workspace workspace :ref ref}))

       (rebase-continue! [_ workspace]
         (swap! calls conj [:rebase-continue workspace])
         (pop! continue-results {:status :ok :workspace workspace}))

       (stage-all! [_ workspace]
         (swap! calls conj [:stage-all workspace])
         (pop! stage-results {:status :ok :workspace workspace}))

       (rebase-abort! [_ workspace]
         (swap! calls conj [:rebase-abort workspace])
         {:status :ok :workspace workspace})

       (ff-merge! [_ {:keys [branch]}]
         (swap! calls conj [:ff-merge branch])
         (let [result (pop! ff-results
                            (or (:default-ff opts)
                                {:status :ok :branch branch
                                 :head (get @branch-heads branch)}))]
           (when (= :ok (:status result))
             (reset! repo-head (:head result)))
           result)))}))
