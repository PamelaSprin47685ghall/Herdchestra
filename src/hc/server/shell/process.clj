(ns hc.server.shell.process
  "Managed operating-system process primitives.

  This namespace owns command interpretation, process-tree observation,
  output pumps and stop signalling.  It does not decide board eligibility and
  never releases the workspace write gate; the session orchestrator owns both.

  A command is fixed when the request is accepted:

    \"...\"                    -> /bin/sh -c \"...\" (cmd.exe /c on Windows)
    [\"prog\" \"arg\" ...]     -> direct exec, no shell
    {:program \"prog\" :args [...]} -> direct exec, no shell

  The shell path deliberately does not classify commands as read-only.  A
  version check, a test run and a build all use the same token and W path."
  (:require [clojure.string :as str]
            [hc.server.shell.error :as err]
            [hc.server.shell.output :as output])
  (:import (java.io IOException InputStream)
           (java.lang ProcessHandle ProcessHandle$Info)
           (java.nio.file Files LinkOption Path Paths)))

(set! *warn-on-reflection* true)

(def ^:private empty-string-array
  (make-array String 0))

(def ^:private empty-link-options
  (make-array LinkOption 0))

(defn windows?
  []
  (str/starts-with? (str (System/getProperty "os.name" "")) "Windows"))

(defn- nonblank?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn- path
  [x]
  (if (instance? Path x)
    x
    (Paths/get (str x) empty-string-array)))

(defn command-spec
  "Interpret COMMAND once.  Returns
  `{:program :args :command :shell?}` or throws an ordinary rejection."
  [command]
  (cond
    (and (string? command) (not (str/blank? command)))
    (if (windows?)
      {:program "cmd.exe"
       :args ["/c" command]
       :command ["cmd.exe" "/c" command]
       :shell? true}
      (let [program (if (Files/exists (path "/bin/sh") empty-link-options)
                      "/bin/sh"
                      "sh")]
        {:program program
         :args ["-c" command]
         :command [program "-c" command]
         :shell? true}))

    (and (vector? command) (seq command))
    (let [parts (vec command)]
      (when-not (and (every? string? parts)
                     (not (str/blank? (first parts))))
        (err/fail! :UNSUPPORTED_OPERATION :invalid-command
                   "A command vector must contain strings and a non-blank program"
                   {:command command}))
      {:program (first parts)
       :args (vec (rest parts))
       :command parts
       :shell? false})

    (and (map? command) (nonblank? (:program command)))
    (let [args (or (:args command) [])]
      (when-not (and (sequential? args) (every? string? args))
        (err/fail! :UNSUPPORTED_OPERATION :invalid-command
                   "Command arguments must be strings"
                   {:command command}))
      {:program (:program command)
       :args (vec args)
       :command (into [(:program command)] args)
       :shell? false})

    :else
    (err/fail! :UNSUPPORTED_OPERATION :invalid-command
               "Command must be a non-blank string, a non-empty string vector, or {:program :args}"
               {:command command})))

(defn cwd-path
  "Resolve CWD against ROOT and require an existing directory.

  Relative paths resolve against ROOT.  Absolute paths are used as given,
  matching the file contract's no-artificial-root rule.  The check happens
  before W is acquired, so a missing directory is an ordinary rejection."
  [root cwd]
  (when-not (and (string? cwd) (not (str/blank? cwd)))
    (err/fail! :STRUCTURE_CONFLICT :cwd-required
               "A shell request requires :cwd"
               {:cwd cwd}))
  (let [^Path p (path cwd)
        ^Path resolved (if (.isAbsolute p)
                         (.normalize p)
                         (.normalize (.resolve ^Path (path root) p)))]
    (when-not (Files/isDirectory resolved empty-link-options)
      (err/fail! :STRUCTURE_CONFLICT :cwd-not-directory
                 "Shell working directory does not exist or is not a directory"
                 {:cwd cwd :resolved (str resolved)}))
    resolved))

(defn start!
  "Start one process.  Throws `:SHELL_START_FAILED` when the OS refuses to
  create the process; at that point no process exists."
  [{:keys [program args]} ^Path cwd env]
  (let [^"[Ljava.lang.String;" command (into-array String (cons program args))
        pb (ProcessBuilder. command)]
    (.directory pb (.toFile cwd))
    (.redirectErrorStream pb false)
    (when (map? env)
      (let [environment (.environment pb)]
        (doseq [[k v] env]
          (.put environment (str k) (str v)))))
    (try
      (let [process (.start pb)]
        (try
          (.close (.getOutputStream process))
          (catch Throwable _ nil))
        process)
      (catch IOException e
        (err/fail! :SHELL_START_FAILED :start-failed
                   "The operating system refused to start the managed process"
                   {:program program
                    :cwd (str cwd)
                    :exception (err/throwable->data e)}))
      (catch SecurityException e
        (err/fail! :SHELL_START_FAILED :start-denied
                   "The security policy refused to start the managed process"
                   {:program program
                    :cwd (str cwd)
                    :exception (err/throwable->data e)})))))

(defn start-pump!
  "Start a daemon thread that drains IN into CAPTURE for STREAM.

  LISTENER, when non-nil, receives `[stream bytes off len]` for each chunk.
  The pump always reads to EOF, even after both output limits are reached."
  [^InputStream in capture stream listener]
  (let [error (atom nil)
        thread (Thread.
                (fn []
                  (let [buffer (byte-array 65536)]
                    (try
                      (loop []
                        (let [n (.read in buffer)]
                          (when (pos? n)
                            (try
                              (output/append! capture stream buffer 0 n)
                              (when listener
                                (listener stream buffer 0 n))
                              (catch Throwable t
                                (reset! error t)))
                            (recur))))
                      (catch Throwable t
                        (reset! error t))
                      (finally
                        (try (.close in) (catch Throwable _ nil))))))
                (str "hc-shell-" (name stream) "-" (System/nanoTime)))]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread :error error :stream stream}))


(defn- handle-info
  [^ProcessHandle handle]
  (let [^ProcessHandle$Info info (.info handle)
        command (.orElse (.commandLine info) nil)]
    {:pid (.pid handle)
     :command-line command
     :alive? (.isAlive handle)
     :handle handle}))

(defn- public-handle-info
  [^ProcessHandle handle]
  (dissoc (handle-info handle) :handle))

(defn start-child-monitor!
  "Poll the process descendants and remember every handle seen.

  The monitor is a best-effort observation.  It records known managed
  children; a process that detaches before a poll remains outside the
  session boundary and is handled as external access through `stamp`."
  [^java.lang.Process process known poll-ms]
  (let [stop (atom false)
        thread (Thread.
                (fn []
                  (while (not @stop)
                    (try
                      (let [stream (.descendants (.toHandle process))]
                        (doseq [^ProcessHandle h (iterator-seq (.iterator stream))]
                          (swap! known assoc (.pid h) (handle-info h))))
                      (catch Throwable _ nil))
                    (try
                      (Thread/sleep (long poll-ms))
                      (catch InterruptedException _
                        (reset! stop true)))))
                (str "hc-shell-children-" (System/nanoTime)))]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread :stop stop}))

(defn stop-child-monitor!
  [{:keys [^Thread thread stop]}]
  (when stop (reset! stop true))
  (when thread
    (try
      (.interrupt thread)
      (catch Throwable _ nil)))
  nil)

(defn known-child-activities
  [known]
  (->> @known
       vals
       (map (fn [{:keys [^ProcessHandle handle] :as info}]
              (assoc (dissoc info :handle)
                     :alive? (boolean (and handle (.isAlive handle))))))
       (sort-by :pid)
       vec))

(defn alive-known-children
  [known]
  (->> @known
       vals
       (filter (fn [{:keys [^ProcessHandle handle]}]
                 (and handle (.isAlive handle))))
       (map (fn [{:keys [pid]}] {:pid pid}))
       vec))

(defn process-info
  [^java.lang.Process process]
  (let [handle (.toHandle process)]
    {:pid (.pid handle)
     :alive? (.isAlive handle)
     :exit-code (when-not (.isAlive handle)
                  (try (.exitValue process)
                       (catch IllegalThreadStateException _ nil)))}))

(defn- handles-to-stop
  [^java.lang.Process process known]
  (let [children (keep :handle (vals @known))
        main-handle (.toHandle process)]
    (->> (concat children [main-handle])
         (remove nil?)
         (distinct)
         vec)))

(defn- alive-handles
  [handles]
  (filterv #(.isAlive ^ProcessHandle %) handles))
(defn- refresh-handles!
  "Re-read the live descendants and fold them into `known`.

  This must run *before* the parent is killed: `ProcessHandle.descendants`
  walks the current ppid chain, so once the parent dies its children reparent
  to init and become unreachable forever.  A stop that snapshots the tree once
  at entry therefore misses any child forked in the meantime — and a child
  forked by `sh -c` appears within milliseconds, well inside the window."
  [^java.lang.Process process known]
  (try
    (let [stream (.descendants (.toHandle process))]
      (doseq [^ProcessHandle h (iterator-seq (.iterator stream))]
        (swap! known assoc (.pid h) (handle-info h))))
    (catch Throwable _ nil))
  (handles-to-stop process known))

;; A `sh -c "trap '' TERM; sleep 30"` forks `sleep` a few milliseconds after
;; start.  `ProcessHandle.descendants` walks the live ppid chain, so a child
;; forked after a scan stays invisible until the next one — and once the
;; parent dies it reparents to init and can never be found again.  A stop that
;; snapshots the tree once at entry therefore leaves that child alive forever.
;; So: let the shell finish forking, keep re-scanning, and signal children
;; before their parent.
(defn stop-tree!
  "Signal the main process and every known descendant.

  The first pass sends the ordinary destroy signal.  After GRACE-MS the
  remaining handles receive the forcible signal.  The return value reports
  what was sent and what remained alive; it never claims the session ended."
  ([process known]
   (stop-tree! process known {}))
  ([^java.lang.Process process known {:keys [grace-ms settle-ms]
                                      :or {grace-ms 2000 settle-ms 150}}]
   (let [signal-errors (atom [])
         signal-sent? (atom false)
         forcibly? (atom false)
         ;; pid → :term / :kill, so a re-scan never restates a signal and an
         ;; escalated kill is never downgraded back to a term.
         signalled (atom {})]
     (letfn [(signal! [^ProcessHandle h forcibly]
               (let [pid (.pid h)
                     previous (get @signalled pid)]
                 (when (if forcibly (not= :kill previous) (nil? previous))
                   (try
                     (when (if forcibly (.destroyForcibly h) (.destroy h))
                       (reset! signal-sent? true)
                       (when forcibly (reset! forcibly? true)))
                     (swap! signalled assoc pid (if forcibly :kill :term))
                     (catch Throwable t
                       (swap! signal-errors conj
                              {:pid pid
                               :phase (if forcibly :destroy-forcibly :destroy)
                               :error (err/throwable->data t)}))))))
             ;; `handles-to-stop` lists descendants before the main handle, so
             ;; this signals children first and the parent last.
             (signal-tree! [forcibly]
               (doseq [^ProcessHandle h (alive-handles
                                         (refresh-handles! process known))]
                 (signal! h forcibly)))
             (wait! [millis forcibly]
               (let [deadline (+ (System/currentTimeMillis) (long millis))]
                 (loop []
                   (when (< (System/currentTimeMillis) deadline)
                     (try
                       (Thread/sleep 20)
                       (catch InterruptedException _
                         (Thread/interrupted)))
                     (signal-tree! forcibly)
                    (recur)))))]
       ;; Let the shell finish forking before the first signal.  Without this
       ;; pause the first scan can miss the very child this stop exists to
       ;; reach, and the parent's death then hides it permanently.
       (try
         (Thread/sleep (long settle-ms))
         (catch InterruptedException _ (Thread/interrupted)))
       (signal-tree! false)
       (wait! grace-ms false)
       (signal-tree! true)
       (wait! 200 true)
       {:signal-sent? (boolean @signal-sent?)
        :forcibly? (boolean @forcibly?)
        :alive (mapv public-handle-info
                     (alive-handles (refresh-handles! process known)))
       :errors @signal-errors}))))

(defn alive?
  [^java.lang.Process process]
  (try
    (.isAlive process)
    (catch Throwable _ false)))

(defn exit-code
  [^java.lang.Process process]
  (when-not (alive? process)
    (try
      (.exitValue process)
      (catch IllegalThreadStateException _ nil))))
