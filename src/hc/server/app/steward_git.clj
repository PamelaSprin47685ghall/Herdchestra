(ns hc.server.app.steward-git
  "Real Git evidence reader for the Standing Sphinx Steward.

  The Steward's runtime asks one question per world tick: which commits are
  reachable from its configured ref since the last observed head?  This
  namespace answers that question with the real `git` executable and returns
  raw `:git-commit` evidence maps in the shape
  `hc.core.steward-sphinx.evidence/normalize-evidence` accepts.

  The reader reuses `hc.compose.fleet.git/run-command` instead of introducing
  a second process abstraction.  `GitPort` has no generic log operation, and
  `run-command` already provides argv-only execution, explicit `:dir`, a
  bounded timeout, and captured stdout/stderr.  The adapter never throws:
  every Git, timeout, parse, or runner failure is returned as data.

  See the local `AGENTS.md` for the evidence key table, cursor semantics,
  the `:master-version` definition, and the truncation policy."
  (:require [clojure.string :as str]
            [hc.compose.fleet.git :as fleet-git]))

(def default-max-commits
  "Default number of commits one observation may read."
  50)

(def max-commits-limit
  "Hard upper bound for one observation's commit count."
  500)

(def max-commit-bytes
  "Hard upper bound for the serialized `:commits` value, in UTF-8 bytes."
  (* 2 1024 1024))

(def default-timeout-ms
  "Default bound for one Git command."
  30000)

(def log-format
  "NUL-delimited commit header: sha, parents, tree, author, epoch seconds,
  subject.  Numstat lines follow each header."
  "%H%x00%P%x00%T%x00%an%x00%ct%x00%s")

;; ---------------------------------------------------------------------------
;; Errors

(defn- error
  [code message details]
  {:ok? false
   :error {:code code
           :message message
           :details (or details {})}})

(defn- bounded-text
  [value limit]
  (let [text (str (or value ""))]
    (if (> (count text) limit)
      (str (subs text 0 limit) "...")
      text)))

(defn- result-text
  [result]
  (str (or (:out result) "") "\n" (or (:err result) "")))

(defn- not-repository?
  [result]
  (boolean (re-find #"(?i)not a git repository|not a working tree"
                    (result-text result))))

(defn- failure
  [result operation repository]
  (let [details {:operation operation
                 :repo (str repository)
                 :status (:status result)
                 :exit (:exit result)
                 :stderr (bounded-text (:err result) 4000)}]
    (cond
      (= :unavailable (:status result))
      (error :STEWARD_GIT_UNAVAILABLE
             "the git executable is not available"
             details)

      (= :timeout (:status result))
      (error :STEWARD_GIT_TIMEOUT
             "git did not finish before the timeout"
             details)

      (not-repository? result)
      (error :STEWARD_GIT_UNAVAILABLE
             "the configured path is not a Git worktree"
             details)

      :else
      (error :STEWARD_GIT_FAILED
             (str "git " (name operation) " failed")
             (assoc details :stdout (bounded-text (:out result) 2000))))))

;; ---------------------------------------------------------------------------
;; Path decoding

(defn- decode-c-quoted
  "Decode the C-style body Git uses when it quotes a path."
  [text]
  (let [length (count text)
        out (StringBuilder.)]
    (loop [index 0]
      (if (>= index length)
        (str out)
        (let [ch (.charAt text index)]
          (if (not= ch \\)
            (do (.append out ch)
                (recur (inc index)))
            (if (>= (inc index) length)
              (do (.append out ch)
                  (recur (inc index)))
              (let [next-ch (.charAt text (inc index))]
                (cond
                  (= next-ch \n)
                  (do (.append out \newline)
                      (recur (+ index 2)))

                  (= next-ch \t)
                  (do (.append out \tab)
                      (recur (+ index 2)))

                  (= next-ch \r)
                  (do (.append out \return)
                      (recur (+ index 2)))

                  (= next-ch \")
                  (do (.append out \")
                      (recur (+ index 2)))

                  (= next-ch \\)
                  (do (.append out \\)
                      (recur (+ index 2)))

                  (= next-ch \a)
                  (do (.append out (char 7))
                      (recur (+ index 2)))

                  (= next-ch \b)
                  (do (.append out (char 8))
                      (recur (+ index 2)))

                  (= next-ch \f)
                  (do (.append out (char 12))
                      (recur (+ index 2)))

                  (= next-ch \v)
                  (do (.append out (char 11))
                      (recur (+ index 2)))

                  (<= (int \0) (int next-ch) (int \7))
                  (let [end (loop [cursor (inc index)
                                   digits 0]
                              (if (and (< cursor length)
                                       (< digits 3)
                                       (<= (int \0)
                                           (int (.charAt text cursor))
                                           (int \7)))
                                (recur (inc cursor) (inc digits))
                                cursor))
                        octal (subs text (inc index) end)
                        value (Integer/parseInt octal 8)]
                    (.append out (char value))
                    (recur end))

                  :else
                  (do (.append out next-ch)
                      (recur (+ index 2))))))))))))

(defn- unquote-path
  [path]
  (if (and (> (count path) 1)
           (str/starts-with? path "\"")
           (str/ends-with? path "\""))
    (decode-c-quoted (subs path 1 (dec (count path))))
    path))

;; ---------------------------------------------------------------------------
;; Log parsing

(defn- split-header
  [line]
  (let [parts (vec (.split ^String line (str (char 0)) -1))]
    (when-not (= 6 (count parts))
      (throw (ex-info "malformed git log header"
                      {:parts (count parts)
                       :line (bounded-text line 200)})))
    (let [[commit parents tree author committed-at subject] parts]
      (when (str/blank? commit)
        (throw (ex-info "empty commit id in git log header" {})))
      {:commit commit
       :parents (if (str/blank? parents)
                  []
                  (vec (remove str/blank? (str/split parents #" "))))
       :tree tree
       :author author
       :committed-at committed-at
       :subject subject
       :changed-paths []
       :insertions 0
       :deletions 0})))

(defn- numstat
  [line]
  (when-let [[_ added deleted path]
             (re-matches #"^(\d+|-)\t(\d+|-)\t(.*)$" line)]
    {:insertions (if (= "-" added) 0 (Long/parseLong added))
     :deletions (if (= "-" deleted) 0 (Long/parseLong deleted))
     :path (unquote-path path)}))

(defn- finalize-commit
  [commit]
  {:evidence-id (str "git:" (:commit commit))
   :kind :git-commit
   :commit (:commit commit)
   :parents (vec (:parents commit))
   ;; The evidence validator rejects blank paths; an empty path entry can
   ;; only come from a malformed numstat line.
   :changed-paths (vec (distinct (sort (remove str/blank?
                                               (:changed-paths commit)))))
   :subject (:subject commit)
   :tree (:tree commit)
   :author (:author commit)
   :committed-at (:committed-at commit)
   :insertions (long (:insertions commit))
   :deletions (long (:deletions commit))})

(defn- parse-log
  "Parse `git log --numstat` output into git-commit evidence maps.

  Git emits commits newest-first.  Numstat lines attach to the preceding
  header.  Binary numstat entries (`-`) contribute zero insertions and
  deletions but keep their path."
  [text]
  (loop [lines (str/split-lines (or text ""))
         commits []
         current nil]
    (if-let [line (first lines)]
      (let [line (str/replace line #"\r$" "")]
        (cond
          (str/blank? line)
          (recur (rest lines) commits current)

          (str/includes? line (str (char 0)))
          (let [commit (split-header line)]
            (recur (rest lines) (conj commits commit) commit))

          :else
          (if-let [stat (numstat line)]
            (if (nil? current)
              (throw (ex-info "numstat line before a git log header"
                              {:line (bounded-text line 200)}))
              (let [commit (-> current
                               (update :changed-paths conj (:path stat))
                               (update :insertions + (:insertions stat))
                               (update :deletions + (:deletions stat)))
                    index (dec (count commits))
                    commits (assoc commits index commit)]
                (recur (rest lines) commits commit)))
            ;; Unknown non-blank lines are not evidence.  Ignoring them keeps
            ;; a future Git decoration from breaking the whole observation.
            (recur (rest lines) commits current))))
      (mapv finalize-commit commits))))

;; ---------------------------------------------------------------------------
;; Ordering and bounds

(defn- commit-time
  [commit]
  (try
    (Long/parseLong (str (:committed-at commit)))
    (catch Throwable _
      0)))

(defn- sort-commits
  "Deterministic order: commit time ascending, then sha ascending."
  [commits]
  (sort-by (juxt commit-time :commit) commits))

(defn- bound-bytes
  "Keep a prefix of COMMITS whose serialized vector stays within LIMIT bytes.

  The estimate charges the brackets, one separator per element, and the
  UTF-8 `pr-str` size of each commit.  It is a strict upper bound on the
  final `pr-str` size."
  [commits limit]
  (loop [remaining (seq commits)
         kept []
         used 2]
    (if-let [commit (first remaining)]
      (let [size (inc (count (.getBytes ^String (pr-str commit) "UTF-8")))]
        (if (<= (+ used size) limit)
          (recur (next remaining)
                 (conj kept commit)
                 (+ used size))
          {:commits kept :truncated? true}))
      {:commits kept :truncated? false})))

(defn- normalize-limit
  [value]
  (let [value (try
                (long (or value default-max-commits))
                (catch Throwable _
                  default-max-commits))]
    (max 1 (min max-commits-limit value))))

(defn- normalize-timeout
  [value]
  (let [value (try
                (long (or value default-timeout-ms))
                (catch Throwable _
                  default-timeout-ms))]
    (max 1 value)))

(defn- parse-count
  [text]
  (let [text (str/trim (str (or text "")))]
    (when (re-matches #"\d+" text)
      (try
        (Long/parseLong text)
        (catch Throwable _
          nil)))))

;; ---------------------------------------------------------------------------
;; Git invocation

(defn- invoke
  "Run one git command through the injected runner.  Never throws."
  [run repository timeout argv]
  (try
    (let [result (run (into ["git"] argv)
                      {:dir repository :timeout-ms timeout})]
      (if (map? result)
        result
        {:status :failed
         :code :INVALID_RUN_RESULT
         :err (bounded-text result 2000)}))
    (catch Throwable t
      {:status :failed
       :code :GIT_RUNNER_FAILED
       :err (or (.getMessage ^Throwable t) (str t))})))

(defn- log-argv
  [ref since limit]
  (cond-> ["-c" "core.quotePath=false"
           "log" "--no-merges" "--numstat" "--no-color" "--date=unix"
           (str "--format=" log-format)
           "-n" (str (inc limit))]
    since (conj (str since ".." ref))
    (nil? since) (conj ref)))

(defn- observation
  [run repository timeout call-ref since limit head]
  (let [version-result (invoke run repository timeout
                               ["rev-list" "--count" call-ref])]
    (if-not (= :ok (:status version-result))
      (failure version-result :count-ref-commits repository)
      (let [master-version (parse-count (:out version-result))]
        (if (nil? master-version)
          (error :STEWARD_GIT_FAILED
                 "git returned an unreadable ref commit count"
                 {:operation :count-ref-commits
                  :repo (str repository)
                  :stdout (bounded-text (:out version-result) 2000)})
          (let [log-result (invoke run repository timeout
                                   (log-argv call-ref since limit))]
            (if-not (= :ok (:status log-result))
              (failure log-result :log repository)
              (let [parsed (parse-log (:out log-result))
                    overflow? (> (count parsed) limit)
                    kept (if overflow?
                           (subvec parsed 0 limit)
                           parsed)
                    ;; Byte truncation selects a deterministic sha-ordered
                    ;; prefix; the returned value is then put back into the
                    ;; contract's time/sha order.
                    bounded (bound-bytes (sort-by :commit kept)
                                         max-commit-bytes)]
                {:ok? true
                 :head head
                 :commits (vec (sort-commits (:commits bounded)))
                 :master-version master-version
                 :truncated? (boolean (or overflow?
                                          (:truncated? bounded)))
                 :scanned (count parsed)}))))))))

;; ---------------------------------------------------------------------------
;; Public factory

(defn git-log-since-fn
  "Build the Steward runtime's `git-log-since-fn` from GIT-PORT.

  OPTS:
  - `:ref` default ref, `\"HEAD\"` when absent or blank;
  - `:max-commits` default 50, clamped to 1..500;
  - `:timeout-ms` default 30000, one bound per Git command;
  - `:repo` repository path override; otherwise `(:repo git-port)`;
  - `:run-command` test seam `(fn [argv opts])`, default
    `hc.compose.fleet.git/run-command`.

  The returned function accepts `{:keys [since ref now]}`.  A per-call `:ref`
  overrides the factory default.  `:now` is accepted for the Steward
  contract but is not needed: evidence uses the commit's own `%ct`.
  `:since` is exclusive; nil or blank reads the newest bounded commits.

  Success:
  `{:ok? true :head sha-or-nil :commits [raw-evidence]
    :master-version n :truncated? bool :scanned n}`

  Failure:
  `{:ok? false :error {:code :STEWARD_GIT_UNAVAILABLE |
                       :STEWARD_GIT_TIMEOUT | :STEWARD_GIT_FAILED
                       :message string :details map}}`

  The returned function never throws."
  [git-port {:keys [ref max-commits timeout-ms repo run-command]
             :or {ref "HEAD"}}]
  (let [default-ref (let [text (str ref)]
                      (if (str/blank? text) "HEAD" text))
        limit (normalize-limit max-commits)
        timeout (normalize-timeout timeout-ms)
        repository (some-> (or repo
                              (when (string? git-port) git-port)
                              (:repo git-port))
                           str)
        run (or run-command fleet-git/run-command)]
    (fn [request]
      (try
        (let [{:keys [since ref]} (if (map? request) request {})
              since (when (some? since)
                      (let [text (str/trim (str since))]
                        (when-not (str/blank? text) text)))
              call-ref (let [text (some-> ref str str/trim)]
                         (if (str/blank? text) default-ref text))]
          (if-not (and (string? repository)
                       (not (str/blank? repository)))
            (error :STEWARD_GIT_FAILED
                   "a Git repository path is required"
                   {:operation :repository
                    :repo (some-> repository str)
                    :port (some-> git-port class .getName)})
            (let [inside (invoke run repository timeout
                                 ["rev-parse" "--is-inside-work-tree"])]
              (cond
                (not= :ok (:status inside))
                (failure inside :inside-work-tree repository)

                (not= "true" (str/trim (str (:out inside))))
                (error :STEWARD_GIT_UNAVAILABLE
                       "the configured path is not a Git worktree"
                       {:repo repository})

                :else
                (let [resolved (invoke run repository timeout
                                       ["rev-parse" "--verify"
                                        (str call-ref "^{commit}")])]
                  (if (= :ok (:status resolved))
                    (let [head (str/trim (str (:out resolved)))]
                      (if (str/blank? head)
                        (error :STEWARD_GIT_FAILED
                               "git returned a blank ref commit id"
                               {:operation :resolve-ref
                                :repo (str repository)
                                :stdout (bounded-text (:out resolved) 2000)})
                        (observation run repository timeout call-ref since limit
                                     head)))
                    (if (contains? #{:unavailable :timeout}
                                   (:status resolved))
                      (failure resolved :resolve-ref repository)
                      (let [all-count (invoke run repository timeout
                                              ["rev-list" "--all" "--count"])]
                        (cond
                          (not= :ok (:status all-count))
                          (failure all-count :count-all-commits repository)

                          :else
                          (let [total (parse-count (:out all-count))]
                            (cond
                              (nil? total)
                              (error :STEWARD_GIT_FAILED
                                     "git returned an unreadable repository commit count"
                                     {:operation :count-all-commits
                                      :repo (str repository)
                                      :stdout (bounded-text (:out all-count) 2000)})

                              (zero? total)
                              {:ok? true
                               :head nil
                               :commits []
                               :master-version 0
                               :truncated? false
                               :scanned 0}

                              :else
                              (failure resolved :resolve-ref repository))))))))))))
        (catch Throwable t
          (error :STEWARD_GIT_FAILED
                 "the Git evidence reader failed"
                 {:operation :observation
                  :repo (some-> repository str)
                  :class (.getName (class t))
                  :message (bounded-text (.getMessage ^Throwable t) 2000)}))))))
