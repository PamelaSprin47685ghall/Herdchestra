(ns hc.server.app
  "Herdchestra application entry point.

  Usage:

      clojure -M:server -m hc.server.app [options]

  The process resolves configuration, takes the machine-wide instance lock,
  opens the current-state store, scans open obligations, starts the
  per-session Owners, then binds the HTTP and Sente endpoints.  A store that
  cannot be opened is a recovery entry, not a fresh database.

  A second start request never opens a second store or listener.  It sends
  one request to the running instance over a loopback control channel:
  focus/create a session or submit one command payload.

  `--check` starts the full system in an isolated data directory (unless the
  operator supplied one), prints the health map, stops, and exits non-zero
  when health or shutdown is not confirmed.  It does not take the instance
  lock."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.server.app.commands :as commands]
            [hc.server.app.config :as config]
            [hc.server.app.restore-supervisor :as restore-supervisor]
            [hc.server.app.singleton :as singleton]
            [hc.server.app.system :as system]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.sessions.registry :as registry])
  (:import (java.awt Desktop Desktop$Action)
           (java.lang ProcessHandle)
           (java.net URI)
           (java.nio.file Files Paths)
           (java.nio.file.attribute FileAttribute)))

(defn- print-error!
  [value]
  (binding [*out* *err*]
    (println (pr-str value))
    (flush)))

(defn- blocking-promise []
  (promise))

(defn- deliverable-blocking-promise []
  "A promise that doubles as its own delivery channel: callers deref it to
  block and pass its DELIVER fn to whoever should release the block.  Used
  by run-primary! so an orphaned JVM can wake the main thread through the
  same stop path the JVM shutdown hook runs."
  (let [p (promise)]
    {:promise p
     :deliver (fn [value] (deliver p value))}))

;; ---------------------------------------------------------------------------
;; Orphan watchdog
;;
;; E2E drivers start the app under `flock ... bash -c '... clojure ... &'`.
;; The clojure launcher `exec`s java, so the JVM inherits every open file
;; descriptor -- including the lock file descriptor `flock` opened.  When
;; the wrapper shell is killed, the JVM is reparented to init but keeps
;; running and keeps holding both the flock descriptor and the machine-wide
;; instance lock in `singleton/default-dir`.  The next `flock -w` then
;; waits out its whole timeout while the orphaned JVM still owns the
;; singleton lock and the control endpoint; the fresh app can only sit in
;; `run-server!`'s second-instance loop and its log stays empty until
;; SIGTERM.
;;
;; The watchdog detects that state: the process that launched this JVM is
;; gone (dead or reparented to init with a recycled pid) and no live `flock`
;; process still holds the lock file.  It then runs the same shutdown path
;; as the JVM shutdown hook, so the store closes, the control endpoint is
;; unpublished and the instance lock is released for the next start.

(defn- launcher-info
  "Identity of the process that launched this JVM: pid, start instant and
  command line.  Captured once at namespace load because the parent may be
  reparented or recycled while the app runs."
  []
  (when-let [parent (some-> (.parent (ProcessHandle/current))
                           (.orElse nil))]
    (let [info (.info parent)]
      {:pid (.pid parent)
       :start (.orElse (.startInstant info) nil)
       :command (or (.commandLine info) (.command info))})))

(def ^:private launcher (delay (launcher-info)))

(defn- start-matches?
  "True when CURRENT still names the same process START described.  A
  missing instant on either side cannot disprove identity, so it counts as
  a match."
  [^java.time.Instant start current]
  (let [handle (cond
                 (instance? java.util.Optional current) (.orElse ^java.util.Optional current nil)
                 (instance? ProcessHandle current) current
                 :else nil)]
    (if (and start (some? handle))
      (let [other (.orElse (.startInstant (.info ^ProcessHandle handle)) nil)]
        (or (nil? other) (.equals start other)))
      true)))

(defn- parent-alive?
  "True while the recorded launcher process still exists.  A recycled pid
  (same pid, different start instant) counts as dead."
  []
  (let [{:keys [pid start]} @launcher]
    (if pid
      (start-matches? start (ProcessHandle/of pid))
      true)))

(defn- shell-launcher?
  "True when the recorded launcher command line is a shell.  E2E wrappers
  run `flock ... bash -c '...'`; a directly exec'd `clojure`/`java` parent
  is a supervisor or an interactive shell and does not make the app an
  orphan when it exits."
  [command]
  (boolean
   (when (string? command)
     (let [base (-> command str (str/replace #"^.*/" "") str/lower-case)
           shells ["bash" "sh" "dash" "zsh" "ksh" "fish"]]
       (some (fn [shell] (str/includes? base shell)) shells)))))

(defn- proc-cmdline
  [pid]
  (try
    (let [bytes (Files/readAllBytes
                  (.toPath (io/file "/proc" (str pid) "cmdline")))]
      (when (pos? (alength bytes))
        (String. bytes "UTF-8")))
    (catch Exception _ nil)))

(defn- pid-directories
  "Sequence of /proc/<pid> paths for every numeric entry.  Uses
  newDirectoryStream rather than Files/list: list stats every entry and on
  /proc that costs hundreds of milliseconds per call, which would stall the
  watchdog's one-second poll."
  []
  (let [proc-dir (io/file "/proc")]
    (when (.isDirectory proc-dir)
      (with-open [stream (Files/newDirectoryStream (.toPath proc-dir))]
        (doall
         (->> stream
              (filter (fn [path]
                        (try (pos? (Long/parseLong (str (.getFileName path))))
                             (catch Exception _ false))))
              (map str)))))))

(defn- fd-holds-path?
  "True when any fd symlink under /proc/PID/fd resolves to TARGET.  PID is
  the decimal process id as a string."
  [pid target]
  (with-open [stream (Files/newDirectoryStream
                      (.toPath (io/file "/proc" pid "fd")))]
    (boolean
     (some (fn [fd]
             (= target (try (str (Files/readSymbolicLink fd))
                            (catch Exception _ nil))))
           stream))))

(defn- lock-held-by-flock?
  "True when a live `flock` process still holds DIR/instance.lock.  The
  check walks /proc/*/fd symlinks; any failure reads as 'not held' so the
  watchdog errs toward shutting down an orphan rather than staying alive
  forever.  On hosts without /proc (non-Linux) the check cannot run and
  reports 'not held'; the parent-process check still applies."
  [dir]
  (let [lock-file (str (singleton/lock-path dir))]
    (try
      (boolean
       (some (fn [pid-path]
               (let [pid (last (str/split pid-path #"/"))
                     cmd (proc-cmdline (Long/parseLong pid))]
                 (and cmd
                      (str/includes? cmd "flock")
                      (fd-holds-path? pid lock-file))))
             (pid-directories)))
      (catch Exception _ false))))

(defn- orphaned?
  "True when this JVM outlived the shell wrapper that launched it and no
  `flock` process still holds the instance lock file.  Such a JVM is an
  orphan: it keeps the singleton lock and the control endpoint alive while
  no driver can reach it, which is exactly the stuck-start state E2E hit."
  ([] (orphaned? (singleton/default-dir)))
  ([dir]
   (let [{:keys [command]} @launcher]
     (and (shell-launcher? command)
          (not (parent-alive?))
          (not (lock-held-by-flock? dir))))))

(defn- start-orphan-watchdog!
  "Poll ORPHANED? and call STOP-ONCE! once when the app is orphaned.
  Returns a zero-arg fn that stops the watchdog.  The first GRACE-MS are
  ignored so a launcher that execs and exits immediately does not trip the
  check during normal startup."
  ([stop-once!] (start-orphan-watchdog! stop-once! {}))
  ([stop-once! {:keys [poll-ms grace-ms dir]
                :or {poll-ms 1000 grace-ms 3000
                     dir nil}}]
   (let [started-at (System/currentTimeMillis)
         fired? (atom false)
         thread
         (Thread.
          ^Runnable
          (fn []
            (loop []
              (when (and (not @fired?)
                         (> (- (System/currentTimeMillis) started-at)
                            grace-ms))
                (when (orphaned? (or dir (singleton/default-dir)))
                  (reset! fired? true)
                  (try (stop-once!) (catch Throwable _ nil))))
              (when-not @fired?
                (try
                   (Thread/sleep poll-ms)
                   (catch InterruptedException _
                     ;; stop-watchdog! interrupts the thread to end the
                     ;; poll loop; exit quietly instead of dying with a
                     ;; stack trace on stderr.
                     (Thread/interrupted)
                     (reset! fired? true)))
                 (recur))))
          "hc-orphan-watchdog")]
     (.setDaemon thread true)
     (.start thread)
     (fn [] (.interrupt thread)))))

(defn- maintenance-handler
  []
  {:handle! (fn [payload]
              (restore-supervisor/handle-command! payload))
   :status (fn []
             (restore-supervisor/status))
   ;; Called by system/start! after the store is open and before any router.
   ;; The supervisor rotates the restored command epoch here instead of
   ;; opening the store itself in prepare!.
   :store-opened! (fn [store]
                    (restore-supervisor/store-opened! store))})

(defn- start-system!
  "Resolve the maintenance journal, start the system, and publish it.

  A prepare! failure is converted into the same structured blocked result as
  a system/start! failure, so -main keeps :recovery-entry, :hint and :code."
  [options]
  (let [maintenance (maintenance-handler)]
    (try
      (restore-supervisor/prepare! {:config options
                                    :maintenance maintenance})
      (let [started (system/start! options {:maintenance maintenance})]
        (when (and (not (:blocked? started))
                   (not (:check? options)))
          (restore-supervisor/install! started)
          (restore-supervisor/startup!))
        started)
      (catch Throwable t
        (let [data (or (ex-data t) {})
              blocked (:recovery-blocked data)]
          (if blocked
            (merge {:blocked? true :config options} blocked)
            {:blocked? true
             :config options
             :error {:code :START_FAILED
                     :message (or (ex-message t) (str t))
                     :class (.getName (class t))}
             :hint "Inspect the data directory and the log, then retry."
             :recovery-entry {:action :operator
                              :options [:inspect-directory :restart]}}))))))

(defn- stop-partial!
  [started]
  (when-let [partial (:partial-system started)]
    (try (system/stop! partial) (catch Throwable _ nil))))

(defn- print-blocked!
  [started]
  (print-error! (select-keys started [:error :hint :recovery-entry :path
                                      :cleanup]))
  (stop-partial! started))

(defn- check-exit-code
  [started]
  (let [health (try (system/health started)
                    (catch Throwable t
                      {:ok? false
                       :error {:message (or (ex-message t) (str t))}}))
        stopped (try (system/stop! started)
                     (catch Throwable t
                       {:stopped? false
                        :store-closed? false
                        :error {:message (or (ex-message t) (str t))}}))
        ok? (and (true? (:ok health))
                 (true? (:stopped? stopped))
                 (true? (:store-closed? stopped)))]
    (println (pr-str health))
    (when-not (true? (:ok health))
      (print-error! {:error :UNHEALTHY
                     :health (select-keys health
                                          [:component-errors :store :sessions
                                           :gateway :models])}))
    (when-not (and (true? (:stopped? stopped))
                   (true? (:store-closed? stopped)))
      (print-error! {:error :STOP_NOT_CONFIRMED
                     :stop (select-keys stopped
                                        [:stopped? :drained? :store-closed?
                                         :failures])}))
    (flush)
    (if ok? 0 1)))

(defn- run-check!
  [options]
  (let [started (start-system! options)]
    (if (:blocked? started)
      (do (print-blocked! started) 1)
      (check-exit-code started))))

(defn- explicit-arg?
  [args option]
  (boolean
   (some (fn [arg]
           (or (= arg (str "--" option))
               (str/starts-with? arg (str "--" option "="))))
         args)))

(defn- temp-check-root
  []
  (str (Files/createTempDirectory "hc-check-" (make-array FileAttribute 0))))

(defn- delete-tree!
  [root]
  (when root
    (doseq [file (reverse (file-seq (io/file root)))]
      (try (.delete ^java.io.File file)
           (catch Exception _ nil)))))

(defn- check-options
  "Isolate --check from the live data directory unless the operator supplied
  an explicit data directory or workspace root.  Returns [options temp-root]."
  [args options]
  (if-not (:check? options)
    [options nil]
    (let [env (System/getenv)
          explicit-data? (or (explicit-arg? args "data-dir")
                             (not (str/blank? (str (get env "HC_DATA_DIR")))))
          explicit-work? (or (explicit-arg? args "workspace-root")
                             (not (str/blank? (str (get env "HC_WORKSPACE_ROOT")))))]
      (let [root (when (or (not explicit-data?) (not explicit-work?))
                   (temp-check-root))
            extra (cond-> []
                    (and root (not explicit-data?))
                    (into ["--data-dir" (str root "/data")])
                    (and root (not explicit-work?))
                    (into ["--workspace-root" (str root "/work")]))]
        (if (seq extra)
          (let [options (config/load-config {:args (into (vec args) extra)})]
            ;; The isolated workspace is not a Git repository.  Start the
            ;; fleet service without Git preflight and without an immediate
            ;; retention sweep; this is a smoke check, not a real fork.
            [(cond-> options
               (and root (not explicit-work?))
               (assoc-in [:fleet :preflight?] false)
               (and root (not explicit-work?))
               (assoc-in [:fleet :gc-run-immediately?] false))
             root])
          [options nil])))))

(defn- second-instance-request
  [options]
  (let [payload (when-let [text (:send-command options)]
                  (try
                    (edn/read-string text)
                    (catch Exception _
                      ::invalid)))]
    (cond
      (= payload ::invalid)
      {:op :invalid}

      payload
      {:op :command
       :session-id (:session-id options)
       :payload payload}

      :else
      {:op :focus-or-create
       :session-id (:session-id options)})))

(defn- submit-command!
  [router payload]
  (let [reply (promise)
        request {:command-id (str (java.util.UUID/randomUUID))
                 :command-epoch (commands/app-command-epoch router)
                 :payload payload}]
    (try
      ;; `router` is a defrecord that implements `OwnerPort`; a keyword
      ;; lookup finds a field, not the protocol method, and returned nil.
      (gateway-ports/submit-command! router request #(deliver reply %))
      (catch Throwable t
        (deliver reply {:ok? false
                        :error {:code :SINGLETON_SUBMIT_FAILED
                                :message (or (ex-message t) (str t))}})))
    (deref reply 15000
           {:ok? false
            :error {:code :SINGLETON_COMMAND_TIMEOUT
                    :message "the running instance did not answer the command"}})))

(defn- session-summaries
  [registry]
  (let [listed (registry/list-sessions registry {:include-deleted? false})]
    (when (= :ok (:status listed))
      (:sessions listed))))

(defn- focus-or-create!
  [started session-id]
  (let [router (:router started)
        registry (:registry started)
        sessions (session-summaries registry)
        requested (when session-id
                    (some #(when (= session-id (:session-id %)) %)
                          sessions))]
    (cond
      requested
      (do (commands/select-session! router session-id)
          {:ok? true :active-session-id session-id})

      (seq sessions)
      (let [chosen (:session-id (first sessions))]
        (commands/select-session! router chosen)
        {:ok? true :active-session-id chosen})

      :else
      (let [created (submit-command! router
                                     {:kind :session/create
                                      :title "New session"})]
        (if (:ok? created)
          (let [sessions (session-summaries registry)
                chosen (:session-id (first sessions))]
            (when chosen (commands/select-session! router chosen))
            {:ok? true
             :active-session-id chosen
             :created created})
          created)))))

(defn- control-handler
  [started]
  (fn [request]
    ;; The loopback control protocol is also used by external E2E drivers.
    ;; Accept the string spelling of an operation so a JSON/EDN writer that
    ;; sends "ping" is not refused as an unknown operation.
    (let [op (let [value (:op request)]
               (if (string? value) (keyword value) value))]
      (case op
        :ping
      {:ok? true
       :pid (.pid (ProcessHandle/current))
       :url (:url started)
       :sessions (count (or (session-summaries (:registry started)) []))}

      :focus-or-create
      (focus-or-create! started (:session-id request))

      :create-session
      (submit-command! (:router started)
                       {:kind :session/create
                        :title (or (:title request) "New session")})

      :command
      (let [payload (:payload request)]
        (if (map? payload)
          (submit-command! (:router started)
                           (cond-> payload
                             (and (:session-id request)
                                  (not (:session-id payload)))
                             (assoc :session-id (:session-id request))))
          {:ok? false
           :error {:code :SINGLETON_BAD_COMMAND
                   :message "command payload must be an EDN map"}}))

        {:ok? false
         :error {:code :SINGLETON_UNKNOWN_OP
                 :message "unknown control operation"
                 :details {:op (:op request)}}}))))

(defn- open-browser!
  [url]
  (try
    (when (and (Desktop/isDesktopSupported)
               (.isSupported (Desktop/getDesktop)
                             Desktop$Action/BROWSE))
      (.browse (Desktop/getDesktop) (URI/create url)))
    (catch Throwable t
      (print-error! {:error :OPEN_BROWSER_FAILED
                     :message (or (ex-message t) (str t))}))))

(defn- run-primary!
  [handle options]
  (let [started (start-system! options)]
    (if (:blocked? started)
      (do
        (print-blocked! started)
        (singleton/release! handle)
        1)
      (try
        (let [control (singleton/start-server!
                       (control-handler started)
                       {:dir (:dir handle)})]
          (when (:open-browser? options)
            (open-browser! (:url started)))
          (println (str "Herdchestra listening on " (:url started)))
          (println (str "workspace: " (:workspace-root started)))
          (println (str "store:     " (:db-path started)))
          (flush)
          (let [stopped? (atom false)
                {:keys [promise deliver]} (deliverable-blocking-promise)
                stop-once! (fn []
                             (when (compare-and-set! stopped? false true)
                               (singleton/stop-server! control)
                               (restore-supervisor/stop-current!)
                               (singleton/release! handle)
                               (deliver 0)))]
            ;; If the wrapper that launched this JVM dies without releasing
            ;; the lock (killed shell, dropped `flock`), this JVM would keep
            ;; the singleton lock and control endpoint forever while every
            ;; later `flock -w` start times out against an unreachable
            ;; second instance.  The watchdog runs the same stop path so the
            ;; lock is released for the next start.
            (start-orphan-watchdog! stop-once!)
            (.addShutdownHook
             (Runtime/getRuntime)
             (Thread. ^Runnable stop-once! "hc-shutdown"))
            ;; The JVM keeps running until the watchdog or the shutdown hook
            ;; fires stop-once!, which releases the lock and delivers the
            ;; blocking promise so this fn returns 0 instead of waiting for
            ;; a signal that never arrives on an orphaned JVM.
            @promise))
        (catch Throwable t
          (try (restore-supervisor/stop-current!) (catch Throwable _ nil))
          (singleton/release! handle)
          (print-error! {:error :START_FAILED
                         :message (or (ex-message t) (str t))})
          1)))))

(defn- run-server!
  [options]
  (let [request (second-instance-request options)]
    (if (= :invalid (:op request))
      (do (print-error! {:error :SEND_COMMAND_INVALID
                         :message "--send-command must contain one EDN map"})
          2)
      (let [deadline (+ (System/currentTimeMillis) 30000)]
        (loop []
          (let [handle (singleton/acquire!)
                dir (:dir handle)]
            (if (:acquired? handle)
              (run-primary! handle options)
              (let [state (or (:state handle) (singleton/read-state dir))
                    reply (when state (singleton/send! state request))]
                (cond
                  (and reply (:ok? reply))
                  (do (println (pr-str reply)) 0)

                  (< (System/currentTimeMillis) deadline)
                  (do (Thread/sleep 100) (recur))

                  :else
                  (do
                    (print-error!
                     (or reply
                         {:error :SINGLETON_UNREACHABLE
                          :message
                          "another instance holds the lock but its control endpoint is not ready"}))
                    1))))))))))

(defn run-main!
  "Run the server and return the process exit code.

  Normal serving blocks until the JVM shutdown hook fires.  `--check`,
  `--help`, blocked starts and second-instance requests return."
  [args]
  (try
    (let [args (vec args)
          options (config/load-config {:args args})]
      (if (config/help-requested? options)
        (do (println (config/usage)) (flush) 0)
        (do
          (config/apply-log-level! options)
          (let [[options temp-root] (check-options args options)]
            (try
              (if (:check? options)
                (run-check! options)
                (run-server! options))
              (finally
                (delete-tree! temp-root)))))))
    (catch clojure.lang.ExceptionInfo e
      (print-error! (or (ex-data e)
                        {:error :CONFIG_INVALID
                         :message (or (ex-message e) (str e))}))
      (binding [*out* *err*] (println (config/usage)) (flush))
      2)
    (catch Throwable t
      (print-error! {:error :START_FAILED
                     :message (or (ex-message t) (str t))
                     :class (.getName (class t))})
      1)))

(defn -main
  [& args]
  (System/exit (run-main! args)))
