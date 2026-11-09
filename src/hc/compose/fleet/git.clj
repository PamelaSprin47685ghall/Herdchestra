(ns hc.compose.fleet.git
  "Explicit Git integration for the fork/join fleet.

  Every operation runs the real `git` executable through `ProcessBuilder`.
  A non-zero exit, a missing executable, a timeout, a missing worktree, or an
  unverifiable head is a structured failure.  This namespace never invents a
  commit, a merge, a branch, or a worktree, and it has no in-memory fallback.

  The adapter is a protocol so tests can exercise control logic with a
  deliberately failing fake.  Production code uses `git-cli`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(defprotocol GitPort
  "The narrow Git operations the fleet needs."
  (git-version [this])
  (repo-head [this ref])
  (branch-head [this branch])
  (head [this directory])
  (current-branch [this directory])
  (ancestor? [this directory ref])
  (worktree-clean? [this directory])
  (ff-ready? [this directory target-ref])
  (create-worktree! [this {:keys [workspace branch base]}])
  (remove-worktree! [this workspace])
  (delete-branch! [this branch])
  (rebase-onto! [this workspace ref])
  (rebase-continue! [this workspace])
  (stage-all! [this workspace])
  (rebase-abort! [this workspace])
  (ff-merge! [this {:keys [branch ref]}]))

(defn- process-result
  [status exit out err argv]
  {:status status
   :exit exit
   :out out
   :err err
   :argv argv})

(defn- interrupted-result?
  [result]
  (= :interrupted (:status result)))

(defn run-command
  "Run ARGV without a shell.

  The result is `{:status :ok|:failed|:timeout|:unavailable|:interrupted
  ...}`.  Output is captured concurrently so a chatty Git command cannot
  deadlock on a pipe.  An interrupted wait destroys the child process before
  returning, so a cancelled fleet sweep does not leave Git running."
  [argv {:keys [dir env timeout-ms]
         :or {timeout-ms 120000}}]
  (let [pb (ProcessBuilder. (into-array String (mapv str argv)))]
    (when dir
      (.directory pb (io/file (str dir))))
    (let [environment (.environment pb)]
      (doseq [[k v] (merge {"GIT_TERMINAL_PROMPT" "0"
                            "GIT_EDITOR" "true"
                            "GIT_SEQUENCE_EDITOR" "true"
                            "LC_ALL" "C"}
                           env)]
        (.put environment (str k) (str v))))
    (try
      (let [process (.start pb)
            stdout (future (slurp (.getInputStream process)))
            stderr (future (slurp (.getErrorStream process)))]
        (try
          (let [finished? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)]
            (if finished?
              (let [exit (.exitValue process)]
                (process-result (if (zero? exit) :ok :failed)
                                exit @stdout @stderr argv))
              (do
                (.destroyForcibly process)
                (.waitFor process)
                (process-result :timeout nil @stdout @stderr argv))))
          (catch InterruptedException _
            (.destroyForcibly process)
            (try
              (.waitFor process)
              (catch InterruptedException _
                (.interrupt (Thread/currentThread))))
            (.interrupt (Thread/currentThread))
            (process-result :interrupted nil "" "git command interrupted"
                            argv))))
      (catch java.io.IOException e
        (process-result :unavailable nil "" (.getMessage e) argv)))))

(defn- git
  [dir & args]
  (run-command (into ["git"] args) {:dir dir}))

(defn- trimmed
  [text]
  (str/trim (or text "")))

(defn- resource-exhausted?
  [text]
  (boolean
   (re-find #"(?i)no space left|not enough space|disk quota|quota exceeded|too many open files|resource temporarily unavailable|cannot allocate|unable to allocate|out of memory"
            (or text ""))))

(defn- already-exists?
  [text]
  (boolean (re-find #"(?i)already exists|already registered|is a missing but already registered"
                    (or text ""))))

(defn- conflict-output?
  [out err]
  (boolean
   (or (re-find #"(?i)CONFLICT|could not apply|Automatic merge failed|fix conflicts"
                (str out "\n" err))
       (re-find #"(?i)unmerged paths|You are currently rebasing" (str err)))))

(defn- unmerged-files?
  [directory]
  (let [result (git directory "diff" "--name-only" "--diff-filter=U")]
    (and (= :ok (:status result))
         (not (str/blank? (:out result))))))

(defn- branch-ref?
  [ref]
  (and (string? ref)
       (not= "HEAD" ref)
       (not (re-matches #"[0-9a-fA-F]{7,40}" ref))))

(defn- ancestor-check
  "Run one ancestor test of REF against HEAD in DIRECTORY.

  The result keeps the historical `ancestor?` shape."
  [directory ref]
  (let [result (run-command ["git" "merge-base" "--is-ancestor" ref "HEAD"]
                            {:dir directory})]
    (cond
      (interrupted-result? result)
      {:status :interrupted :ref ref
       :exit (:exit result) :out (:out result) :err (:err result)}

      (zero? (long (or (:exit result) 1)))
      {:status :ok :ancestor? true :ref ref}

      (= 1 (:exit result))
      {:status :ok :ancestor? false :ref ref}

      :else
      {:status :failed
       :code :ANCESTOR_CHECK_FAILED
       :ref ref
       :exit (:exit result)
       :out (:out result)
       :err (:err result)})))

(defn- worktree-status
  "Return `{:clean? bool :result git-result}` for DIRECTORY.

  A worktree is clean only when `git status --porcelain` exits zero with
  blank output.  A missing directory, timeout, or unreadable status is not
  clean; it is never optimistically treated as clean."
  [directory]
  (try
    (let [result (run-command ["git" "status" "--porcelain"]
                              {:dir directory})]
      {:clean? (boolean (and (= :ok (:status result))
                             (str/blank? (:out result))))
       :result result})
    (catch Throwable t
      {:clean? false
       :result {:status :failed
                :code :WORKTREE_STATUS_FAILED
                :err (or (.getMessage ^Throwable t) (str t))}})))

(defn- classify-worktree-failure
  [result]
  (let [text (str (:out result) "\n" (:err result))]
    (cond
      (already-exists? text) :WORKTREE_ALREADY_EXISTS
      (resource-exhausted? text) :INSUFFICIENT_WORKTREES
      :else :WORKTREE_CREATE_FAILED)))

(defn- rebase-failure
  [directory result]
  (cond
    (interrupted-result? result)
    {:status :interrupted
     :exit (:exit result)
     :out (:out result)
     :err (:err result)}

    (or (conflict-output? (:out result) (:err result))
        (unmerged-files? directory))
    {:status :conflict
     :exit (:exit result)
     :out (:out result)
     :err (:err result)}

    :else
    {:status :failed
     :code :REBASE_FAILED
     :exit (:exit result)
     :out (:out result)
     :err (:err result)}))

(defn- cleanup-worktree-attempt
  "Remove only what this failed attempt created.

  PATH-EXISTED? and BRANCH-EXISTED? were sampled before `git worktree add`.
  A pre-existing path or branch is never touched."
  [repo workspace branch path-existed? branch-existed?]
  (let [path-now? (.exists (io/file workspace))
        branch-now (when-not branch-existed?
                     (git repo "rev-parse" "--verify" (str branch "^{commit}")))]
    {:worktree (when (and (not path-existed?) path-now?)
                 (git repo "worktree" "remove" "--force" workspace))
     :branch (when (and (not branch-existed?)
                        (= :ok (:status branch-now)))
               (git repo "branch" "-D" branch))}))

(defrecord GitCli [repo timeout-ms]
  GitPort
  (git-version [_]
    (run-command ["git" "--version"] {:timeout-ms timeout-ms}))

  (repo-head [_ ref]
    (let [result (git repo "rev-parse" "--verify" (str ref "^{commit}"))]
      (cond
        (interrupted-result? result)
        {:status :interrupted :ref ref
         :exit (:exit result) :out (:out result) :err (:err result)}

        (= :ok (:status result))
        {:status :ok :ref ref :head (trimmed (:out result))}

        :else
        {:status :failed
         :code :REF_NOT_FOUND
         :ref ref
         :exit (:exit result)
         :out (:out result)
         :err (:err result)})))

  (branch-head [_ branch]
    (let [result (git repo "rev-parse" "--verify" (str branch "^{commit}"))]
      (cond
        (interrupted-result? result)
        {:status :interrupted :branch branch
         :exit (:exit result) :out (:out result) :err (:err result)}

        (= :ok (:status result))
        {:status :ok :branch branch :head (trimmed (:out result))}

        :else
        {:status :failed
         :code :BRANCH_NOT_FOUND
         :branch branch
         :exit (:exit result)
         :out (:out result)
         :err (:err result)})))

  (head [_ directory]
    (let [result (git directory "rev-parse" "--verify" "HEAD")]
      (cond
        (interrupted-result? result)
        {:status :interrupted :directory directory
         :exit (:exit result) :out (:out result) :err (:err result)}

        (= :ok (:status result))
        {:status :ok :head (trimmed (:out result))}

        :else
        {:status :failed
         :code :HEAD_NOT_FOUND
         :directory directory
         :exit (:exit result)
         :out (:out result)
         :err (:err result)})))

  (current-branch [_ directory]
    (let [result (git directory "symbolic-ref" "--short" "-q" "HEAD")]
      (cond
        (interrupted-result? result)
        {:status :interrupted :directory directory
         :exit (:exit result) :out (:out result) :err (:err result)}

        (= :ok (:status result))
        (let [branch (trimmed (:out result))]
          (if (str/blank? branch)
            {:status :failed
             :code :BRANCH_NAME_UNREADABLE
             :directory directory
             :exit (:exit result)
             :out (:out result)
             :err (:err result)}
            {:status :ok :directory directory :branch branch}))

        ;; `symbolic-ref -q` exits 1, with no output, for a detached HEAD.
        (= 1 (:exit result))
        {:status :ok :directory directory :branch nil :detached? true}

        :else
        {:status :failed
         :code :BRANCH_NAME_UNREADABLE
         :directory directory
         :exit (:exit result)
         :out (:out result)
         :err (:err result)})))

  (ancestor? [_ directory ref]
    (ancestor-check directory ref))

  (worktree-clean? [_ directory]
    (:clean? (worktree-status directory)))

  (ff-ready? [this directory target-ref]
    (try
      (let [head-result (head this directory)
            target-result (repo-head this target-ref)
            status (worktree-status directory)
            clean? (:clean? status)
            head-ok? (= :ok (:status head-result))
            target-ok? (= :ok (:status target-result))
            ancestor (when (and head-ok? target-ok? clean?)
                       (ancestor-check directory target-ref))
            base {:head (when head-ok? (:head head-result))
                  :target-head (when target-ok? (:head target-result))
                  :clean? clean?}]
        (cond
          (or (interrupted-result? head-result)
              (interrupted-result? target-result)
              (interrupted-result? (:result status))
              (interrupted-result? ancestor))
          (assoc base :ready? false :reason :interrupted)

          (not head-ok?)
          (assoc base :ready? false :reason :HEAD_UNREADABLE
                 :head-result head-result)

          (not target-ok?)
          (assoc base :ready? false :reason :TARGET_HEAD_UNREADABLE
                 :target-ref target-ref
                 :target-result target-result)

          (not= :ok (:status (:result status)))
          (assoc base :ready? false :reason :WORKTREE_STATUS_FAILED
                 :status-result (:result status))

          (not clean?)
          (assoc base :ready? false :reason :WORKTREE_DIRTY)

          (= :failed (:status ancestor))
          (assoc base :ready? false :reason :ANCESTOR_CHECK_FAILED
                 :ancestor ancestor)

          (:ancestor? ancestor)
          (assoc base :ready? true :reason :ready
                 :target-ref target-ref)

          :else
          (assoc base :ready? false :reason :TARGET_NOT_ANCESTOR
                 :target-ref target-ref)))
      (catch Throwable t
        {:ready? false
         :head nil
         :target-head nil
         :clean? false
         :reason :GIT_CHECK_FAILED
         :target-ref target-ref
         :error {:code :GIT_CHECK_FAILED
                 :message (or (.getMessage ^Throwable t) (str t))}})))

  (create-worktree! [this {:keys [workspace branch base]}]
    (let [path-existed? (.exists (io/file workspace))
          branch-existed? (= :ok (:status (branch-head this branch)))
          result (git repo "worktree" "add" "--no-track" "-b" branch workspace base)]
      (cond
        (interrupted-result? result)
        {:status :interrupted
         :workspace workspace
         :branch branch
         :exit (:exit result)
         :out (:out result)
         :err (:err result)}

        (= :ok (:status result))
        (let [verified (head this workspace)]
          (cond
            (interrupted-result? verified)
            {:status :interrupted
             :workspace workspace
             :branch branch
             :result verified}

            (= :ok (:status verified))
            {:status :ok
             :workspace workspace
             :branch branch
             :head (:head verified)}

            :else
            {:status :failed
             :code :WORKTREE_UNVERIFIED
             :workspace workspace
             :branch branch
             :error verified
             :cleanup (cleanup-worktree-attempt
                       repo workspace branch path-existed? branch-existed?)}))

        :else
        {:status :failed
         :code (classify-worktree-failure result)
         :workspace workspace
         :branch branch
         :exit (:exit result)
         :out (:out result)
         :err (:err result)
         :cleanup (cleanup-worktree-attempt
                   repo workspace branch path-existed? branch-existed?)})))

  (delete-branch! [_ branch]
    (let [result (git repo "branch" "-D" branch)]
      (cond
        (interrupted-result? result)
        {:status :interrupted :branch branch
         :exit (:exit result) :out (:out result) :err (:err result)}

        (= :ok (:status result))
        {:status :ok :branch branch}

        (re-find #"(?i)not found|no such branch" (str (:out result) (:err result)))
        {:status :ok :branch branch :already-absent? true}

        :else
        {:status :failed
         :code :BRANCH_DELETE_FAILED
         :branch branch
         :exit (:exit result)
         :out (:out result)
         :err (:err result)})))

  (remove-worktree! [_ workspace]
    (if-not (.exists (io/file workspace))
      (let [prune (git repo "worktree" "prune")]
        (if (interrupted-result? prune)
          {:status :interrupted :workspace workspace :prune-status prune}
          {:status :ok
           :workspace workspace
           :already-absent? true
           :prune-status (:status prune)}))
      (let [result (git repo "worktree" "remove" "--force" workspace)]
        (cond
          (interrupted-result? result)
          {:status :interrupted :workspace workspace
           :exit (:exit result) :out (:out result) :err (:err result)}

          (= :ok (:status result))
          {:status :ok :workspace workspace}

          :else
          {:status :failed
           :code :WORKTREE_REMOVE_FAILED
           :workspace workspace
           :exit (:exit result)
           :out (:out result)
           :err (:err result)}))))

  (rebase-onto! [_ workspace ref]
    (let [result (git workspace "rebase" ref)]
      (if (= :ok (:status result))
        {:status :ok :workspace workspace :ref ref}
        (rebase-failure workspace result))))

  (rebase-continue! [_ workspace]
    (let [result (git workspace "rebase" "--continue")]
      (if (= :ok (:status result))
        {:status :ok :workspace workspace}
        (rebase-failure workspace result))))

  (stage-all! [_ workspace]
    (let [result (git workspace "add" "-A")]
      (cond
        (interrupted-result? result)
        {:status :interrupted :workspace workspace
         :exit (:exit result) :out (:out result) :err (:err result)}

        (= :ok (:status result))
        {:status :ok :workspace workspace}

        :else
        {:status :failed
         :code :STAGE_FAILED
         :workspace workspace
         :exit (:exit result)
         :out (:out result)
         :err (:err result)})))

  (rebase-abort! [_ workspace]
    (let [result (git workspace "rebase" "--abort")]
      (cond
        (interrupted-result? result)
        {:status :interrupted :workspace workspace
         :exit (:exit result) :out (:out result) :err (:err result)}

        (= :ok (:status result))
        {:status :ok :workspace workspace}

        (re-find #"(?i)no rebase in progress" (str (:out result) (:err result)))
        {:status :ok :workspace workspace :already-aborted? true}

        :else
        {:status :failed
         :code :REBASE_ABORT_FAILED
         :workspace workspace
         :exit (:exit result)
         :out (:out result)
         :err (:err result)})))

  (ff-merge! [this {:keys [branch ref]}]
    (let [ref (or ref "HEAD")
          expected-branch (when (branch-ref? ref)
                            (str/replace ref #"^refs/heads/" ""))
          branch-check (when expected-branch
                         (git repo "symbolic-ref" "--short" "-q" "HEAD"))
          current-branch (when (and branch-check (= :ok (:status branch-check)))
                           (trimmed (:out branch-check)))
          master (repo-head this ref)
          checked-out (head this repo)
          branch-head (branch-head this branch)]
      (cond
        (and branch-check (interrupted-result? branch-check))
        {:status :interrupted
         :ref ref
         :expected-branch expected-branch
         :branch-check branch-check}

        (and branch-check (not= :ok (:status branch-check)))
        {:status :failed
         :code :MASTER_NOT_CHECKED_OUT
         :ref ref
         :expected-branch expected-branch
         :branch-check branch-check}

        (and expected-branch (not= current-branch expected-branch))
        {:status :failed
         :code :MASTER_NOT_CHECKED_OUT
         :ref ref
         :expected-branch expected-branch
         :current-branch current-branch}

        (interrupted-result? master)
        {:status :interrupted :ref ref :result master}

        (not= :ok (:status master))
        {:status :failed :code :MASTER_HEAD_UNREADABLE :ref ref :error master}

        (interrupted-result? checked-out)
        {:status :interrupted :ref ref :result checked-out}

        (not= :ok (:status checked-out))
        {:status :failed :code :MASTER_HEAD_UNREADABLE :error checked-out}

        (not= (:head master) (:head checked-out))
        {:status :failed
         :code :MASTER_NOT_CHECKED_OUT
         :ref ref
         :master-head (:head master)
         :checked-out (:head checked-out)}

        (interrupted-result? branch-head)
        {:status :interrupted :branch branch :result branch-head}

        (not= :ok (:status branch-head))
        {:status :failed :code :BRANCH_HEAD_UNREADABLE :error branch-head}

        :else
        (let [result (git repo "merge" "--ff-only" branch)]
          (cond
            (interrupted-result? result)
            {:status :interrupted
             :branch branch
             :ref ref
             :exit (:exit result)
             :out (:out result)
             :err (:err result)}

            (= :ok (:status result))
            (let [after (repo-head this ref)
                  ancestor (when (= :ok (:status after))
                             (git repo "merge-base" "--is-ancestor"
                                  (:head branch-head) (:head after)))]
              (cond
                (interrupted-result? after)
                {:status :interrupted
                 :ref ref
                 :result after}

                (not= :ok (:status after))
                {:status :failed
                 :code :MASTER_HEAD_UNREADABLE
                 :ref ref
                 :error after}

                (interrupted-result? ancestor)
                {:status :interrupted
                 :ref ref
                 :result ancestor}

                (and ancestor
                     (zero? (long (or (:exit ancestor) 1))))
                {:status :ok
                 :branch branch
                 :ref ref
                 :previous-head (:head master)
                 :head (:head after)}

                :else
                {:status :failed
                 :code :FF_HEAD_MISMATCH
                 :branch branch
                 :ref ref
                 :expected (:head branch-head)
                 :actual (:head after)
                 :ancestor ancestor}))

            :else
            {:status :failed
             :code :FF_MERGE_FAILED
             :branch branch
             :ref ref
             :exit (:exit result)
             :out (:out result)
             :err (:err result)}))))))

(defn git-cli
  "Construct the real Git adapter for REPO."
  ([repo] (git-cli repo {}))
  ([repo {:keys [timeout-ms] :or {timeout-ms 120000}}]
   (when-not (and (string? repo) (not (str/blank? repo)))
     (throw (ex-info "fleet: git repo must be a non-blank path"
                     {:error :INVALID_GIT_REPO :repo repo})))
   (->GitCli repo timeout-ms)))

(defn available?
  "True when the real Git executable answers."
  [git]
  (let [result (git-version git)]
    (and (= :ok (:status result))
         (re-find #"(?i)git version" (str (:out result) (:err result))))))
