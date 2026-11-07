(ns hc.server.program.shell-port
  "Reference `:call` port over `hc.server.shell`.

  The program runtime owns `park`/`unpark` and the deadline.  This adapter
  starts a managed shell session, subscribes to its live output, publishes a
  command view through the request's `:on-command-view` callback, and returns
  the terminal shell result.  The runtime merges that view into the current
  command atom, so a deadline park during `:waiting` carries actual
  stdout/stderr, byte counts, duration, and exit status instead of a
  placeholder.

  The shell runner still owns W, the token/claim checks, process lifetime, and
  stop semantics.  Application assembly must supply `:context-fn` when the
  shell request needs board token terms or a different cwd/purpose."
  (:require [clojure.string :as str]
            [hc.server.shell :as shell]
            [hc.server.shell.output :as shell-output]))

(defn- final-status
  [status]
  (case status
    :COMPLETED :completed
    :CANCELLED :cancelled
    :UNCERTAIN :uncertain
    :failed))

(defn- final-view
  [command result live]
  (let [output (:output result)]
    {:command command
     :status (final-status (:status result))
     :phase (if (= :UNCERTAIN (:status result)) :uncertain :finished)
     :stdout (get-in output [:stdout :inline] "")
     :stderr (get-in output [:stderr :inline] "")
     :stdout-bytes (get-in output [:stdout :bytes] 0)
     :stderr-bytes (get-in output [:stderr :bytes] 0)
     :duration-ms (:duration-ms result)
     :exit-code (:exit-code result)
     :output-truncated? (or (:output-truncated? result)
                            (:output-truncated? live))}))

(defn- append-bounded
  "Append TEXT to S without growing the live view past LIMIT characters.
  Returns `[text' truncated?]`."
  [s text limit]
  (let [s (or s "")
        text (or text "")
        remaining (max 0 (- (long limit) (count s)))]
    (if (zero? remaining)
      ;; The view is already at its bound.  Do not copy the 1 MiB string for
      ;; every later chunk; the byte counters still advance in the caller.
      [s (pos? (count text))]
      (let [take (int (min (count text) remaining))]
        [(str s (subs text 0 take))
         (< take (count text))]))))

(defn- error-data
  [^Throwable t]
  {:class (.getName (class t))
   :message (.getMessage t)})

(defn- command-label
  "A printable, non-blank purpose for any accepted shell command shape.

  `:purpose` is a required string in the shell request.  A vector or
  `{:program :args}` command must not leak a collection into that field."
  [command]
  (let [label (cond
                (string? command) command
                (vector? command) (str/join " " command)
                (map? command) (str/join " " (cons (:program command)
                                                   (or (:args command) [])))
                :else (str command))]
    (if (str/blank? label) "managed shell call" label)))

(defn- managed-activity?
  [status]
  (or (true? (:write-gate-held? status))
      (true? (get-in status [:process :alive?]))
      (boolean (some :alive? (:known-child-activities status)))))

(defn shell-call-port
  "Build a program `:call` function over a managed shell RUNNER.

  Options:
  - `:runner` required `hc.server.shell/runner`
  - `:context-fn` optional `(fn [ctx request] extra)` merged into the shell
    request; use it for `:token-term`/`:term-id`, `:claim`, `:cwd`,
    `:purpose`, `:output-mode`, or other shell fields
  - `:term-fn` optional `(fn [ctx]) -> current holder term`.  The host
    resolves the live term from its board; a caller-supplied `:token-term`
    in CTX or in the context-fn extra still wins, so a stale explicit term
    is refused by the board instead of being silently replaced.
  - `:cwd` default `\".\"`
  - `:purpose` default the command string
  - `:output-mode` optional shell output mode
  - `:inline-limit` maximum characters retained in the live stdout/stderr
    view (default `hc.server.shell.output/default-inline-limit`, 1 MiB);
    excess text sets `:output-truncated? true` but byte counters stay exact
  - `:start!`/`:await`/`:subscribe!`/`:status` injection points for tests;
    defaults are the corresponding `hc.server.shell` functions
  - `:result` optional `(fn [session] latest-result)`; default
    `hc.server.shell/result`.  A provisional `:UNCERTAIN` result is not
    returned while the session still holds W or managed activity: the port
    waits for the late watchdog terminal, so a program call keeps its real
    responsibility until the process is observed ended.
  - `:uncertain-poll-ms` poll interval for that confirmation (default 50)

  The returned function accepts `(ctx request)` from the program runtime.
  It uses `:operation-id` as the shell request id when present, so the
  program receipt's `:pending-operations` id can be used to stop the shell
  session. Live views are published through `:on-command-view`; the runtime
  derives `:delta` from the accumulated output."
  [{:keys [runner context-fn term-fn cwd purpose output-mode
           start! await subscribe! status result inline-limit
           uncertain-poll-ms]}]
  (when-not runner
    (throw (IllegalArgumentException.
            "shell-call-port requires a :runner")))
  (let [start! (or start! shell/start!)
        await (or await shell/await)
        subscribe! (or subscribe! shell/subscribe!)
        status (or status shell/status)
        result-fn (or result shell/result)
        uncertain-poll-ms (long (or uncertain-poll-ms 50))
        inline-limit (long (or inline-limit
                               shell-output/default-inline-limit))]
    (fn [ctx request]
      (let [command (:command request)
          extra (or (when context-fn (context-fn ctx request)) {})
          request-id (or (:shell-request-id request)
                         (:operation-id request)
                         (:call-id ctx)
                         (str (random-uuid)))
          requested-purpose (or (:purpose extra) purpose)
          resolved-term (or (:token-term ctx) (:term-id ctx)
                            (when (fn? term-fn) (term-fn ctx)))
          merged (merge {:actor-id (or (:agent-id ctx)
                                       (:actor-id ctx))
                         :token-term resolved-term
                         :task-id (:task-id ctx)
                         :claim (:claim-id ctx)
                         :command command
                         :cwd (or (:cwd extra) cwd ".")
                         :output-mode (or (:output-mode extra)
                                          output-mode)}
                        extra)
          ;; A nil extra must not erase the host-resolved term.  A non-nil
          ;; explicit term stays authoritative and is checked by the board.
          merged (if (some? (:token-term merged))
                   merged
                   (assoc merged :token-term resolved-term))
          shell-request (assoc merged
                               :request-id request-id
                               :purpose (if (and (string? requested-purpose)
                                                 (not (str/blank? requested-purpose)))
                                          requested-purpose
                                          (command-label command)))
          session (start! runner shell-request)
          view (atom {:command command
                      :status :starting
                      :phase :accepted
                      :stdout ""
                      :stderr ""
                      :stdout-bytes 0
                      :stderr-bytes 0
                      :exit-code nil
                      :output-truncated? false
                      :started-at (System/currentTimeMillis)})
          emit! (fn [extra-view]
                  (when-let [cb (:on-command-view request)]
                    (try
                      (cb (merge @view extra-view))
                      (catch Throwable _ nil))))
          view-error (atom nil)
          unsubscribe
          (when-let [cb (:on-command-view request)]
            (try
              (subscribe!
               session
               (fn [chunk]
                 (swap! view
                        (fn [v]
                          (let [stream (:stream chunk)
                                text (or (:text chunk) "")
                                bytes (or (:bytes chunk) 0)
                                [bounded truncated?]
                                (append-bounded (get v stream) text inline-limit)]
                            (-> v
                                (assoc stream bounded)
                                (update (if (= :stdout stream)
                                          :stdout-bytes
                                          :stderr-bytes)
                                        (fnil + 0)
                                        bytes)
                                (update :output-truncated?
                                        #(or % truncated?))))))
                 (let [s (status session)
                       done? (:done? s)
                       result-status (:result-status s)]
                   (emit! {:status (cond
                                     (not done?) :running
                                     (= :UNCERTAIN result-status) :uncertain
                                     (= :CANCELLED result-status) :cancelled
                                     :else :completed)
                           :phase (:phase s)
                           :duration-ms (:elapsed-ms s)
                           :pid (:pid s)}))))
              (catch Throwable t
                ;; The session already started.  Live view is an observation,
                ;; not the process lifetime: keep awaiting the real terminal
                ;; and report the view failure with it.
                (reset! view-error t)
                nil)))]
        (try
          (let [terminal
                (try
                  (loop [current (await session)]
                    (let [s (status session)]
                      (if (and (= :UNCERTAIN (:status current))
                               (managed-activity? s))
                        (do
                          ;; The session watchdog owns the late terminal.
                          ;; Returning the provisional result here would let
                          ;; the program settle while the process can still
                          ;; act and W is still held.
                          (try
                            (Thread/sleep uncertain-poll-ms)
                            (catch InterruptedException _
                              (Thread/interrupted)))
                          (recur (result-fn session)))
                        ;; The watchdog updates the result before it releases
                        ;; W.  Read once more so a confirmed end is not lost.
                        (if (= :UNCERTAIN (:status current))
                          (result-fn session)
                          current))))
                  (catch Throwable t
                    ;; `await` or the confirmation read failed after the
                    ;; process started.  That is not evidence that it ended.
                    (let [latest (try (result-fn session)
                                      (catch Throwable _ nil))
                          s (try (status session)
                                 (catch Throwable _ nil))]
                      (if (and (map? latest)
                               (not= :UNCERTAIN (:status latest))
                               (not (managed-activity? s)))
                        (assoc latest :await-error (error-data t))
                        (merge {:status :UNCERTAIN
                                :outcome :unknown
                                :phase :uncertain
                                :request-id request-id
                                :message "Shell call failed after the process started"
                                :error (error-data t)}
                               (when (map? latest)
                                 {:last-result latest}))))))
                terminal
                (cond-> terminal
                  (and (map? terminal) @view-error)
                  (assoc :live-view-error (error-data @view-error)))]
            (emit! (final-view command terminal @view))
            terminal)
          (finally
            (when unsubscribe (unsubscribe))))))))
