(ns hc.server.shell.read-only
  "Best-effort read-only command policy for the Plan dedicated worker.

  This is not a sandbox.  It rejects obvious mutating programs, shell output
  redirections, package installers, and Git write subcommands before a
  process starts.  A program that writes through an unlisted path can still
  mutate the workspace; the worker role is the primary policy, this guard is
  a second line of defence."
  (:require [clojure.string :as str]
            [hc.server.shell.error :as err]))

(def ^:private denied-programs
  #{"rm" "rmdir" "mv" "cp" "mkdir" "touch" "chmod" "chown" "ln"
    "truncate" "dd" "tee" "install" "apt" "apt-get" "dnf" "yum"
    "pacman" "pip" "pip3" "npm" "yarn" "pnpm" "cargo" "mvn"
    "gradle" "lein" "git-lfs"})

(def ^:private git-read-subcommands
  #{"status" "log" "diff" "show" "rev-parse" "ls-files" "grep"
    "blame" "describe" "shortlog" "cat-file" "show-ref" "rev-list"
    "merge-base" "name-rev" "whatchanged" "for-each-ref" "diff-tree"
    "ls-tree" "check-ignore" "check-attr" "count-objects" "verify-pack"})

(def ^:private git-read-markers
  {"branch" #{"--list" "-l" "-a" "-r" "-v" "--show-current"
              "--contains" "--merged" "--no-merged"}
   "tag" #{"--list" "-l" "-v" "--contains" "--merged" "--no-merged"}
   "remote" #{"-v" "--verbose" "show" "get-url"}
   "stash" #{"list" "show"}
   "config" #{"--get" "--get-all" "--get-regexp" "--list" "-l"}
   "worktree" #{"list"}})

(def ^:private shell-write-pattern
  #"(?:^|[;&|]\s*)(?:rm|rmdir|mv|cp|mkdir|touch|chmod|chown|ln|truncate|dd|tee|install|apt|apt-get|dnf|yum|pacman|pip|pip3|npm|yarn|pnpm|cargo|mvn|gradle|lein)\b|>>?|&>|\btee\b|\bgit\s+(?:add|commit|checkout|reset|clean|apply|merge|rebase|fetch|pull|push|cherry-pick|revert)\b|\bgit\s+branch\s+(?!(?:--list|-l|-a|-r|-v|--show-current|--contains|--merged|--no-merged)\b)|\bgit\s+tag\s+(?!(?:--list|-l|-v|--contains|--merged|--no-merged)\b)|\bgit\s+remote\s+(?!(?:-v|--verbose|show|get-url)\b)|\bgit\s+stash\b(?!\s+(?:list|show)\b)|\bgit\s+config\s+(?!(?:--get|--get-all|--get-regexp|--list|-l)\b)|\bgit\s+worktree\s+(?!(?:list)\b)")

(def ^:private wrapper-programs
  "Programs that run another program named later in the argument vector."
  #{"env" "nohup" "timeout" "xargs" "stdbuf" "nice" "ionice" "command"
    "exec" "setsid"})

(defn- basename
  [program]
  (let [parts (str/split (str program) #"[\\/]")]
    (if (seq parts) (peek parts) (str program))))

(defn- reject!
  [reason details]
  (err/fail! :UNSUPPORTED_OPERATION :read-only-command-rejected
             "The Plan dedicated worker may run only best-effort read-only commands"
             (assoc details :reason reason)))

(defn- shell-string-problem
  [command]
  (cond
    (not (string? command)) nil
    (re-find shell-write-pattern command) :mutating-shell-text
    (re-find #"\$\(|`" command) :command-substitution
    :else nil))

(defn- git-problem
  [args]
  (let [subcommand (first (drop-while #(str/starts-with? % "-") args))]
    (cond
      (nil? subcommand) nil
      (contains? git-read-subcommands subcommand) nil
      (contains? git-read-markers subcommand)
      (let [tail (vec (rest (drop-while #(not= % subcommand) args)))
            markers (get git-read-markers subcommand)]
        (when-not (some markers tail) :git-write-subcommand))
      :else :git-write-subcommand)))

(defn- shell-command-string
  "The command string passed to a shell, including combined flags.

  `-c` is the obvious form; `-lc`, `-ic`, `-ec` and other short clusters
  also carry a command string.  A long option such as `--norc` is not a
  command flag."
  [program args]
  (when (contains? #{"sh" "bash" "zsh" "dash"} program)
    (loop [args (seq args)]
      (when-let [a (first args)]
        (cond
          (= "-c" a) (second args)
          (and (str/starts-with? a "-")
               (not (str/starts-with? a "--"))
               (str/includes? (subs a 1) "c"))
          (second args)
          :else (recur (next args)))))))

(defn- wrapper-problem
  "Best-effort inspection of a program wrapped by env/nohup/xargs/timeout.

  The wrapped command is not executed through a shell string, so the string
  pattern does not see it.  Check every argument for a denied program name
  or an obvious mutating shell fragment."
  [program args]
  (when (contains? wrapper-programs program)
    (or (some (fn [a]
                (let [p (basename a)]
                  (cond
                    (contains? denied-programs p)
                    {:reason :wrapped-mutating-program :program p :argument a}

                    (and (string? a) (shell-string-problem a))
                    {:reason :wrapped-mutating-text :argument a}

                    :else nil)))
              args)
        (when-let [i (first (keep-indexed
                             (fn [i a] (when (= "git" (basename a)) i))
                             args))]
          (when-let [problem (git-problem (vec (drop (inc i) args)))]
            {:reason problem :program "git"})))))

(defn check-command!
  "Throw an ordinary rejection when SPEC is an obvious write command.

  SPEC is `process/command-spec`'s `{:program :args :command :shell?}`.
  Returns nil for a command the best-effort policy allows."
  [spec]
  (let [program (basename (:program spec))
        args (vec (:args spec))
        inner (shell-command-string program args)
        wrapped (wrapper-problem program args)]
    (cond
      (and inner (shell-string-problem inner))
      (reject! (shell-string-problem inner) {:command (:command spec)})

      wrapped
      (reject! (:reason wrapped)
               (merge {:command (:command spec)} wrapped))

      (contains? denied-programs program)
      (reject! :mutating-program {:program program :command (:command spec)})

      ;; A shell command string is normally represented as sh -c "..."; the
      ;; generic string form is handled above via INNER, but a direct string
      ;; command is also checked by COMMAND.
      (and (:shell? spec) (shell-string-problem (last (:command spec))))
      (reject! (shell-string-problem (last (:command spec)))
               {:command (:command spec)})

      (= program "git")
      (let [problem (or (when (some #{"--output"} args) :git-output)
                        (git-problem args))]
        (when problem
          (reject! problem {:program program :args args})))

      (= program "sed")
      (when (some #{"-i" "--in-place"} args)
        (reject! :in-place-edit {:program program :args args}))

      (= program "find")
      (when (some #{"-delete" "-exec" "-execdir" "-ok" "-okdir"
                    "-fprint" "-fprint0" "-fprintf" "-fls"}
                  args)
        (reject! :find-mutates {:program program :args args}))

      :else nil)))
