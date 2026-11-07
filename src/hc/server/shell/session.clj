(ns hc.server.shell.session
  "Managed shell session orchestration.

  Lifecycle for one accepted request:

    accepted -> waiting-gate -> starting -> running -> finished
                                             \\-> uncertain

  W is held from admission through process start, process exit, output pump
  EOF and every known managed child.  It is released before `run!` returns on
  a completed session, and retained by a watchdog when the outcome cannot be
  confirmed.  A stop request signals the process tree but never releases W on
  behalf of the session."
  (:refer-clojure :exclude [await run!])
  (:require [clojure.string :as str]
            [hc.core.workspace-gate.gate :as gate]
            [hc.server.shell.board :as board]
            [hc.server.shell.error :as err]
            [hc.server.shell.output :as output]
            [hc.server.shell.process :as process]
            [hc.server.shell.read-only :as read-only])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Duration Instant)
           (java.util Arrays Date HexFormat)))

(set! *warn-on-reflection* true)

(def default-limits
  {:inline-output-bytes output/default-inline-limit
   :artifact-output-bytes output/default-artifact-limit
   :stop-grace-ms 2000
   :child-poll-ms 50
   :wait-poll-ms 50
   :drain-timeout-ms 500
   :publish-retries 5})

(defrecord ShellRunner [root gate board artifact-dir limits resolve-secret
                        registry authorization])
(defrecord ShellSession [runner request-id request fingerprint state listeners
                         result-promise known-children])

;; ---------------------------------------------------------------------------
;; request identity

(defn- canonical
  [x]
  (cond
    (map? x)
    (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map (fn [[k v]] [k (canonical v)]))
          x)

    (vector? x) (mapv canonical x)

    (set? x)
    (into (sorted-set-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map canonical x))

    :else x))

(defn- sha256
  [^String text]
  (let [digest (MessageDigest/getInstance "SHA-256")
        ^bytes bytes (.digest digest (.getBytes text StandardCharsets/UTF_8))
        ^HexFormat hex (HexFormat/of)]
    (.formatHex hex bytes)))

(defn- fingerprint
  [request]
  (sha256
   (pr-str
    (canonical
     (select-keys request
                  [:request-id :actor-id :token-term :term-id :task-id
                   :claim :claim-id :command :cwd :purpose :output-mode
                   :env :env-refs])))))

;; ---------------------------------------------------------------------------
;; request normalization

(defn- nonblank?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn- read-only-runner?
  [runner]
  (= :read-only (:authorization runner)))

(defn- ->date
  "Convert an internal Instant to a `java.util.Date` so public results print
  as EDN `#inst` values instead of opaque JVM objects."
  [x]
  (cond
    (instance? Instant x) (Date/from ^Instant x)
    (instance? Date x) x
    :else x))

(defn- env-name
  [k]
  (if (keyword? k) (name k) (str k)))

(defn- env-map
  [runner request]
  (let [env (:env request)
        refs (:env-refs request)]
    (when-not (or (nil? env) (map? env))
      (err/fail! :INVALID_ARGUMENT :invalid-env
                 "Shell :env must be a map of names to non-secret values"
                 {:env env}))
    (when-not (or (nil? refs) (map? refs) (sequential? refs))
      (err/fail! :INVALID_ARGUMENT :invalid-env-refs
                 "Shell :env-refs must be a map or sequence of names"
                 {:env-refs refs}))
    (let [direct (reduce-kv (fn [m k v]
                              (when (nil? v)
                                (err/fail! :INVALID_ARGUMENT :invalid-env
                                           "Shell environment values must not be nil"
                                           {:name (str k)}))
                              (assoc m (env-name k) (str v)))
                            {}
                            (or env {}))
          ref-pairs (cond
                      (map? refs) (seq refs)
                      (sequential? refs) (map (fn [name] [name name]) refs)
                      :else nil)
          resolver (:resolve-secret runner)]
      (reduce (fn [m [env-key ref]]
                (when-not resolver
                  (err/fail! :UNSUPPORTED_OPERATION :secret-ref-unavailable
                             "No secret resolver is configured for :env-refs"
                             {:name (env-name env-key)}))
                (let [value (try (resolver ref)
                                 (catch Throwable t
                                   (err/fail! :UNSUPPORTED_OPERATION :secret-ref-failed
                                              "Secret resolver failed"
                                              {:name (env-name env-key)
                                               :exception (err/throwable->data t)})))]
                  (when (nil? value)
                    (err/fail! :UNSUPPORTED_OPERATION :secret-ref-unavailable
                               "Secret resolver returned no value"
                               {:name (env-name env-key)}))
                  (assoc m (env-name env-key) (str value))))
              direct
              ref-pairs))))

(defn- normalize-request
  [runner request]
  (try
    (when-not (map? request)
      (err/fail! :INVALID_ARGUMENT :invalid-request
                 "A shell request must be a map"
                 {:request request}))
    (let [request-id (:request-id request)
          actor-id (:actor-id request)
          term (board/token-term request)
          task-id (:task-id request)
          claim (board/claim-id request)
          command (:command request)
          cwd (:cwd request)
          purpose (:purpose request)
          output-mode (:output-mode request)
          read-only? (read-only-runner? runner)]
      (when-not (or (nonblank? request-id) (keyword? request-id))
        (err/fail! :INVALID_ARGUMENT :request-id-required
                   "A shell request requires a non-blank :request-id"
                   {:request-id request-id}))
      (when (and (not read-only?) (nil? actor-id))
        (err/fail! :INVALID_ARGUMENT :actor-id-required
                   "A shell request requires :actor-id"
                   {}))
      (when (and (not read-only?) (nil? term))
        (err/fail! :INVALID_ARGUMENT :token-term-required
                   "A shell request requires :token-term"
                   {}))
      (when (and (not read-only?) (nil? task-id))
        (err/fail! :INVALID_ARGUMENT :task-id-required
                   "A shell request requires :task-id"
                   {}))
      (when (and (not read-only?) (nil? claim))
        (err/fail! :INVALID_ARGUMENT :claim-required
                   "A shell request requires :claim"
                   {}))
      (when (nil? command)
        (err/fail! :INVALID_ARGUMENT :command-required
                   "A shell request requires :command"
                   {}))
      (when-not (nonblank? cwd)
        (err/fail! :INVALID_ARGUMENT :cwd-required
                   "A shell request requires a non-blank :cwd"
                   {:cwd cwd}))
      (when-not (nonblank? purpose)
        (err/fail! :INVALID_ARGUMENT :purpose-required
                   "A shell request requires a non-blank :purpose"
                   {:purpose purpose}))
      (when-not (or (nil? output-mode) (map? output-mode))
        (err/fail! :INVALID_ARGUMENT :invalid-output-mode
                   "Shell :output-mode must be a map"
                   {:output-mode output-mode}))
      (let [spec (process/command-spec command)
            _ (when read-only? (read-only/check-command! spec))
            cwd-path (process/cwd-path (:root runner) cwd)
            env (env-map runner request)]
        {:ok? true
         :request (assoc request
                         :request-id request-id
                         :actor-id actor-id
                         :token-term term
                         :term-id term
                         :task-id task-id
                         :claim claim
                         :claim-id claim
                         :command-spec spec
                         :cwd-path cwd-path
                         :env-map env
                         :env-names (vec (sort (keys env)))
                         :purpose purpose
                         :output-mode (or (:output-mode request) {}))}))
    (catch clojure.lang.ExceptionInfo e
      (if (err/expected? e)
        {:ok? false :error (ex-data e)}
        (throw e)))
    (catch Throwable t
      {:ok? false
       :error (err/rejection :INVALID_ARGUMENT :request-normalization-failed
                             "Shell request could not be normalized"
                             {:exception (err/throwable->data t)})})))

;; ---------------------------------------------------------------------------
;; session construction

(defn- new-session
  [runner request fp]
  (->ShellSession runner
                  (:request-id request)
                  request
                  fp
                  (atom {:phase :accepted
                         :done? false
                         :stop-requested? false
                         :stop-requested-at nil
                         :stop-signalled-at nil
                         :stop-signal-sent? false
                         :stop-signal-attempted? false
                         :process nil
                         :pid nil
                         :permit nil
                         :board-permit nil
                         :capture nil
                         :pumps []
                         :child-monitor nil
                         :write-gate-held? false
                         :hold-write-gate? false
                         :result nil
                         :started-at nil
                         :ended-at nil
                         :interrupted? false
                         :publish-attempts 0})
                  (atom #{})
                  (promise)
                  (atom {})))

(defn- rejected-session
  [runner request-id request error]
  (let [session (new-session runner (if (map? request) request {:request-id request-id}) nil)
        result (assoc error
                      :request-id request-id
                      :write-gate-held? false
                      :ended-at (->date (Instant/now)))]
    (swap! (:state session) assoc
           :done? true
           :phase :finished
           :result result
           :ended-at (Instant/now))
    (deliver (:result-promise session) result)
    session))

(defn- cached-session
  [runner request fp result]
  (let [session (new-session runner request fp)]
    (swap! (:state session) assoc
           :done? true
           :phase :finished
           :result result
           :ended-at (Instant/now))
    (deliver (:result-promise session) result)
    session))

(defn- claim-request!
  [runner request fp]
  (let [session (new-session runner request fp)
        request-id (:request-id request)
        entry {:fingerprint fp :status :pending :session session :result nil}
        [old new] (swap-vals! (:registry runner)
                              (fn [registry]
                                (if (contains? registry request-id)
                                  registry
                                  (assoc registry request-id entry))))]
    (if (identical? old new)
      (let [existing (get old request-id)]
        (cond
          (not= fp (:fingerprint existing))
          {:role :mismatch :entry existing}

          (= :pending (:status existing))
          {:role :waiter :session (:session existing)}

          :else
          {:role :cached :session (:session existing) :result (:result existing)}))
      {:role :owner :session session})))

;; ---------------------------------------------------------------------------
;; result helpers

(defn- base-result
  [session status outcome data]
  (let [state @(:state session)
        request (:request session)
        started-instant (:started-at state)
        ended-instant (or (:ended-at data) (Instant/now))
        duration (when started-instant
                   (.toMillis (Duration/between ^Instant started-instant
                                                ^Instant ended-instant)))]
    (let [result (merge {:request-id (:request-id session)
                         :status status
                         :outcome outcome
                         :file-changed? :unknown
                         :cwd (str (:cwd-path request))
                         :command (get-in request [:command-spec :command])
                         :purpose (:purpose request)
                         :env-names (:env-names request)
                         :started-at (->date started-instant)
                         :ended-at (->date ended-instant)
                         :duration-ms duration
                         :write-gate-held? false
                         :external-best-effort? true}
                        data)]
      (assoc result
             :started-at (->date (:started-at result))
             :ended-at (->date (:ended-at result))
             :output-truncated?
             (boolean (get-in result [:output :output-truncated?]))))))

(defn- cancelled-before-start
  [request-id]
  (err/rejection :CANCELLED :stopped-before-start
                 "Managed shell was stopped before the process started"
                 {:cancelled? true :request-id request-id}))

(defn- gate-rejection
  [request-id data]
  (let [status (or (:status data) :WAITING_FOR_WRITE_GATE)]
    (if (= :WAITING_FOR_WRITE_GATE status)
    (let [cancelled? (or (:cancelled? data)
                         (= :interrupted-while-waiting (:code data)))]
      (merge (err/rejection :WAITING_FOR_WRITE_GATE
                            (or (:code data) :cancelled-while-waiting)
                            (or (:message data)
                                "Stopped while waiting for the workspace write gate")
                            (assoc (select-keys data [:queued?])
                                   :cancelled? cancelled?))
             {:request-id request-id}))
      ;; Admission refusals (for example :RECOVERY_UNRESOLVED) are not waits:
      ;; keep their status and details instead of relabelling them.
      (merge (err/rejection status
                            (or (:code data) status)
                            (or (:message data) "Write admission refused")
                            (select-keys data [:details]))
             {:request-id request-id
              :write-gate-held? false}))))

(defn- hard-failure-result
  [session t phase]
  (merge (err/rejection :HARD_FAILURE :shell-orchestration-failure
                        "Managed shell failed outside the ordinary path"
                        {:phase phase
                         :exception (err/throwable->data t)})
         {:request-id (:request-id session)
          :write-gate-held? false}))

(defn- board-summary
  [request]
  {:purpose (:purpose request)
   :program (get-in request [:command-spec :program])
   :arg-count (count (get-in request [:command-spec :args]))
   :cwd (str (:cwd-path request))})

(defn- board-result-summary
  [result]
  {:summary (str/join " "
                      (remove nil? [(:status result)
                                    (when (some? (:exit-code result))
                                      (str "exit=" (:exit-code result)))
                                    (when (:cancelled? result) "cancelled")
                                    (when (:output-truncated? result) "truncated")]))
   :status (:status result)
   :outcome (:outcome result)
   :exit-code (:exit-code result)
   :cancelled? (boolean (:cancelled? result))
   :output-truncated? (boolean (get-in result [:output :output-truncated?]))
   :output-complete? (boolean (:output-complete? result))
   :artifact-path (get-in result [:output :artifact :path])
   :known-child-count (count (:known-child-activities result))})

;; ---------------------------------------------------------------------------
;; state transitions

(defn- release-permit!
  [session]
  (let [state (:state session)
        permit (:permit @state)]
    (when permit
      (swap! state assoc :permit nil :write-gate-held? false)
      (try
        (gate/release! permit)
        (catch Throwable _ nil)))))

(defn- finish-session!
  [session result]
  (let [state (:state session)
        phase (if (= :UNCERTAIN (:status result)) :uncertain :finished)
        result (assoc result :request-id (:request-id session))]
    (swap! state
           (fn [s]
             (if (:done? s)
               s
               (assoc s
                      :done? true
                      :phase phase
                      :result result
                      :ended-at (Instant/now)))))
    (deliver (:result-promise session) (:result @state))
    (swap! (:registry (:runner session))
           (fn [registry]
             (if (contains? registry (:request-id session))
               (update registry (:request-id session)
                       assoc :status :finished :result (:result @state))
               registry)))
    (:result @state)))

(defn- publish-board!
  [runner session permit result]
  (if-not permit
    (assoc result :published? :not-applicable :publish-attempts 0)
    (let [board (:board runner)
          retries (max 1 (long (get-in runner [:limits :publish-retries] 5)))]
      (loop [attempt 1]
        (let [outcome (try
                        (if (= :UNCERTAIN (:status result))
                          (board/publish-uncertain! board permit
                                                    (board-result-summary result))
                          (board/publish-finished! board permit
                                                   (board-result-summary result)))
                        {:ok true}
                        (catch Throwable t
                          {:error (err/throwable->data t)}))]
          (if (:ok outcome)
            (-> result
                (assoc :published? true :publish-attempts attempt)
                (dissoc :retry :publish-error))
            (if (< attempt retries)
              (recur (inc attempt))
              (assoc result
                     :published? false
                     :publish-attempts attempt
                     :publish-error (:error outcome)
                     :retry :publish-only))))))))

(defn- remember-publish-retry!
  "Keep the permit and result when board publication is still pending.
  `retry-publish!` can later finish the pure control update without rerunning
  the process or applying any file operation."
  [session permit result]
  (if (and permit (false? (:published? result)))
    (let [retry {:permit permit :result result}]
      (swap! (:state session) assoc :publish-retry retry)
      (swap! (:registry (:runner session)) update (:request-id session)
             (fn [entry]
               (if entry (assoc entry :publish-retry retry) entry))))
    (do
      (swap! (:state session) dissoc :publish-retry)
      (swap! (:registry (:runner session)) update (:request-id session)
             (fn [entry]
               (if entry (dissoc entry :publish-retry) entry))))))

(defn retry-publish!
  "Retry only the shell action's board publication.

  Never reruns the process and never reapplies a file operation.  Returns the
  updated result; `:published? true` means the board action reached its
  terminal publication.  A still-pending result keeps `:retry
  :publish-only` and the action responsibility."
  [runner request-id]
  (let [entry (get @(:registry runner) request-id)
        session (:session entry)]
    (cond
      (nil? session)
      {:status :UNKNOWN_REQUEST :request-id request-id}

      (not (:done? @(:state session)))
      {:status :SHELL_STILL_RUNNING :request-id request-id}

      :else
      (let [state (:state session)
            retry (:publish-retry @state)]
        (if-not retry
          (:result @state)
          (let [result (publish-board! runner session
                                       (:permit retry) (:result retry))
                result (assoc result :request-id request-id)
                retry' (when (false? (:published? result))
                         {:permit (:permit retry) :result result})]
            (swap! state assoc :result result :publish-retry retry')
            (swap! (:registry runner) update request-id
                   (fn [e]
                     (if e
                       (assoc e :result result :publish-retry retry')
                       e)))
            result))))))

(defn- finish-before-start!
  [runner session permit result]
  (let [result (assoc result
                      :request-id (:request-id session)
                      :outcome :not-started
                      :file-changed? false
                      :write-gate-held? false
                      :ended-at (or (:ended-at result)
                                    (->date (Instant/now)))
                      :output-truncated?
                      (boolean (get-in result [:output :output-truncated?])))
        result (publish-board! runner session permit result)]
    (remember-publish-retry! session permit result)
    (release-permit! session)
    (finish-session! session result)))

;; ---------------------------------------------------------------------------
;; process activity

(defn- pump-alive?
  [pump]
  (boolean (and pump (.isAlive ^Thread (:thread pump)))))

(defn- session-activity?
  [process pumps known]
  (or (and process (process/alive? process))
      (boolean (some pump-alive? pumps))
      (boolean (seq (process/alive-known-children known)))))

(defn- stop-expired?
  [session limits]
  (let [state @(:state session)
        at (or (:stop-signalled-at state) (:stop-requested-at state))]
    (and (:stop-requested? state)
         at
         (> (System/currentTimeMillis)
            (+ (.toEpochMilli ^Instant at)
               (long (:stop-grace-ms limits)))))))

(defn- note-interrupt!
  "Treat a pending executor interrupt as an operator cancel before a process
  starts.  The wait loop has its own InterruptedException path; this covers
  the short board/capture windows before that loop begins."
  [state]
  (when (.isInterrupted (Thread/currentThread))
    (swap! state assoc
           :interrupted? true
           :stop-requested? true
           :stop-requested-at (or (:stop-requested-at @state) (Instant/now)))))

(defn- notify!
  [session stream ^bytes data off len]
  (let [listeners @(:listeners session)]
    (when (seq listeners)
      (let [copy (Arrays/copyOfRange data (int off) (int (+ off len)))
            text (String. copy StandardCharsets/UTF_8)
            event {:stream stream :bytes len :data copy :text text}]
        (doseq [listener listeners]
          (try
            (listener event)
            (catch Throwable _ nil)))))))

;; ---------------------------------------------------------------------------
;; late confirmation watchdog

(defn- start-watchdog!
  [runner session process capture pumps known permit]
  (let [poll-ms (long (get-in runner [:limits :wait-poll-ms] 50))
        drain-ms (long (get-in runner [:limits :drain-timeout-ms] 500))
        thread (Thread.
                (fn []
                  (loop [managed-ended-at nil]
                    (let [managed? (or (process/alive? process)
                                       (seq (process/alive-known-children known)))
                          pumps-alive? (boolean (some pump-alive? pumps))
                          now (System/currentTimeMillis)
                          managed-ended-at (or managed-ended-at
                                               (when-not managed? now))
                          drain-expired? (and managed-ended-at
                                              (not managed?)
                                              pumps-alive?
                                              (> now (+ (long managed-ended-at)
                                                        drain-ms)))]
                      (if (and (not managed?)
                               (or (not pumps-alive?) drain-expired?))
                        (let [out (when capture (output/finish! capture))
                              current (:result @(:state session))
                              exit (process/exit-code process)
                              pump-errors (vec (keep #(some-> @(:error %)
                                                              err/throwable->data)
                                                     pumps))
                              artifact-error? (boolean (get-in out [:artifact :error]))
                              result (merge current
                                            {:status :COMPLETED
                                             :outcome :completed
                                             :exit-code exit
                                             :exit-code-known? (some? exit)
                                             :output out
                                             :output-complete? (and (empty? pump-errors)
                                                                    (not pumps-alive?)
                                                                    (not artifact-error?))
                                             :output-errors pump-errors
                                             :detached-activity? (and (not managed?)
                                                                      pumps-alive?)
                                             :known-child-activities
                                             (process/known-child-activities known)
                                             :write-gate-held? false
                                             :ended-at (->date (Instant/now))})
                              result (assoc result :output-truncated?
                                            (boolean (get-in result [:output :output-truncated?])))
                              result (publish-board! runner session permit result)]
                          (remember-publish-retry! session permit result)
                          (release-permit! session)
                          (swap! (:state session) assoc
                                 :result result
                                 :write-gate-held? false
                                 :hold-write-gate? false
                                 :phase :finished
                                 :done? true
                                 :ended-at (Instant/now))
                          (swap! (:registry runner) update (:request-id session)
                                 (fn [entry]
                                   (if entry
                                     (assoc entry :result result)
                                     entry))))
                        (do
                          (try
                            (Thread/sleep poll-ms)
                            (catch InterruptedException _ nil))
                          (recur managed-ended-at))))))
                (str "hc-shell-watchdog-" (:request-id session)))]
    (.setDaemon thread true)
    (.start thread)
    thread))

;; ---------------------------------------------------------------------------
;; process execution

(defn- handle-process-failure!
  [runner session process capture t]
  (let [state (:state session)
        known (:known-children session)
        pumps (:pumps @state)
        limits (:limits runner)
        _ (process/stop-tree! process known {:grace-ms (:stop-grace-ms limits)})
        managed? (or (process/alive? process)
                     (seq (process/alive-known-children known)))
        pumps-alive? (boolean (some pump-alive? pumps))
        out (when capture
              (if pumps-alive?
                (output/snapshot capture)
                (output/finish! capture)))
        pump-errors (vec (keep #(some-> @(:error %) err/throwable->data) pumps))
        exit (process/exit-code process)
        result (base-result session :UNCERTAIN :unknown
                            {:exit-code exit
                             :exit-code-known? (some? exit)
                             :cancelled? (boolean (:stop-requested? @state))
                             :interrupted? (boolean (:interrupted? @state))
                             :output out
                             :output-complete? (and (empty? pump-errors)
                                                    (not pumps-alive?))
                             :output-errors pump-errors
                             :detached-activity? (and (not managed?) pumps-alive?)
                             :known-child-activities (process/known-child-activities known)
                             :write-gate-held? managed?
                             :exception (err/throwable->data t)
                             :ended-at (Instant/now)})]
    (if managed?
      (do
        (swap! state assoc :hold-write-gate? true
               :write-gate-held? true
               :phase :uncertain)
        (let [result (publish-board! runner session (:board-permit @state) result)]
          (remember-publish-retry! session (:board-permit @state) result)
          (finish-session! session result)
          (start-watchdog! runner session process capture pumps known
                            (:board-permit @state))
          result))
      (let [result (publish-board! runner session (:board-permit @state) result)]
        (remember-publish-retry! session (:board-permit @state) result)
        (release-permit! session)
        (finish-session! session result)
        result))))

(defn- run-process!
  [runner session ^java.lang.Process process capture]
  (let [state (:state session)
        known (:known-children session)
        limits (:limits runner)
        listener (fn [stream data off len]
                   (notify! session stream data off len))
        stdout-pump (process/start-pump! (.getInputStream process)
                                         capture :stdout listener)
        stderr-pump (process/start-pump! (.getErrorStream process)
                                         capture :stderr listener)
        pumps [stdout-pump stderr-pump]
        monitor (process/start-child-monitor! process known
                                              (:child-poll-ms limits))]
    (swap! state assoc :pumps pumps :child-monitor monitor)
    (try
      (let [{:keys [uncertain? detached? exit-code]}
            (loop [process-ended-at nil]
              (let [s @state
                    process-alive? (process/alive? process)
                    children (process/alive-known-children known)
                    pumps-alive? (boolean (some pump-alive? pumps))
                    now (System/currentTimeMillis)
                    process-ended-at (or process-ended-at
                                         (when-not process-alive? now))
                    drain-expired? (and process-ended-at
                                        (not process-alive?)
                                        (empty? children)
                                        pumps-alive?
                                        (> now
                                           (+ (long process-ended-at)
                                              (long (:drain-timeout-ms limits)))))
                    stop? (:stop-requested? s)]
                (cond
                  ;; Main process, managed children and output pumps all ended.
                  (and (not process-alive?)
                       (empty? children)
                       (not pumps-alive?))
                  {:uncertain? false :detached? false
                   :exit-code (process/exit-code process)}

                  ;; Main process and managed children ended, but some
                  ;; unmanaged pipe holder still keeps the output open.  This
                  ;; is external best-effort: drain briefly, then close and
                  ;; release W instead of waiting forever.
                  (and (not process-alive?)
                       (empty? children)
                       pumps-alive?
                       drain-expired?)
                  {:uncertain? false :detached? true
                   :exit-code (process/exit-code process)}

                  ;; A known managed child survives the stop grace.
                  (and stop?
                       (stop-expired? session limits)
                       (or process-alive? (seq children)))
                  {:uncertain? true :detached? false
                   :exit-code (process/exit-code process)}

                  (and stop? (not (:stop-signal-attempted? s)))
                  (let [result (process/stop-tree!
                                process known
                                {:grace-ms (:stop-grace-ms limits)})]
                    (swap! state assoc
                           :stop-signal-sent? (:signal-sent? result)
                           :stop-signal-attempted? true
                           :stop-signalled-at (Instant/now)
                           :stop-signal-result result)
                    (try
                      (Thread/sleep (long (:wait-poll-ms limits)))
                      (catch InterruptedException _
                        (.interrupt (Thread/currentThread))))
                    (recur process-ended-at))

                  :else
                  (do
                    (try
                      (Thread/sleep (long (:wait-poll-ms limits)))
                      (catch InterruptedException _
                        ;; An interrupted executor thread is an operator
                        ;; cancel path (§1.1-6): stop the managed process
                        ;; tree instead of silently reporting uncertainty.
                        (Thread/interrupted)
                        (swap! state assoc
                               :interrupted? true
                               :stop-requested? true
                               :stop-requested-at
                               (or (:stop-requested-at s) (Instant/now)))))
                    (recur process-ended-at)))))
            uncertain? (boolean uncertain?)
            detached? (boolean detached?)
            output-view (if (and uncertain? (some pump-alive? pumps))
                          (output/snapshot capture)
                          (output/finish! capture))
            pump-errors (vec (keep #(some-> @(:error %) err/throwable->data) pumps))
            artifact-error? (boolean (get-in output-view [:artifact :error]))
            cancelled? (boolean (:stop-requested? @state))
            result (base-result session
                                (if uncertain? :UNCERTAIN :COMPLETED)
                                (if uncertain? :unknown :completed)
                                {:exit-code exit-code
                                 :exit-code-known? (some? exit-code)
                                 :cancelled? cancelled?
                                 :stop-signal-sent? (boolean (:stop-signal-sent? @state))
                                 :interrupted? (boolean (:interrupted? @state))
                                 :output output-view
                                 :output-complete? (and (not uncertain?)
                                                        (not detached?)
                                                        (empty? pump-errors)
                                                        (not artifact-error?))
                                 :output-errors pump-errors
                                 :detached-activity? detached?
                                 :known-child-activities (process/known-child-activities known)
                                 :write-gate-held? uncertain?
                                 :ended-at (Instant/now)})]
        (if uncertain?
          (do
            (swap! state assoc :hold-write-gate? true
                   :write-gate-held? true
                   :phase :uncertain)
            (let [result (publish-board! runner session
                                          (:board-permit @state) result)]
              (remember-publish-retry! session (:board-permit @state) result)
              (finish-session! session result)
              (start-watchdog! runner session process capture pumps known
                                (:board-permit @state))
              result))
          (let [result (publish-board! runner session
                                        (:board-permit @state) result)]
            (remember-publish-retry! session (:board-permit @state) result)
            (release-permit! session)
            (finish-session! session result)
            result)))
      (catch InterruptedException _
        (Thread/interrupted)
        (swap! state assoc
               :interrupted? true
               :stop-requested? true
               :stop-requested-at (or (:stop-requested-at @state) (Instant/now)))
        (handle-process-failure!
         runner session process capture
         (ex-info "Managed shell wait was interrupted" {:interrupted? true})))
      (catch Throwable t
        (handle-process-failure! runner session process capture t))
      (finally
        (process/stop-child-monitor! monitor)))))

(defn- capture-options
  [runner request]
  (let [mode (:output-mode request)
        limits (:limits runner)]
    {:inline-limit (long (or (:inline-limit mode)
                             (:inline-output-bytes limits)))
     :artifact-limit (long (or (:artifact-limit mode)
                               (:artifact-output-bytes limits)))
     :artifact-path (:artifact-path mode)
     :artifact-dir (:artifact-dir runner)
     :root (:root runner)}))

(defn- run-after-gate!
  [runner session permit eligibility]
  (let [state (:state session)
        request (assoc (:request session)
                       :expected-eligibility eligibility)]
    (swap! state assoc :permit permit :write-gate-held? true)
    (note-interrupt! state)
    (if (:stop-requested? @state)
      (finish-before-start! runner session nil
                            (cancelled-before-start (:request-id session)))
      (let [bp (when-not (read-only-runner? runner)
                 (try
                   (board/begin-run! (:board runner) request
                                     (board-summary request))
                   (catch clojure.lang.ExceptionInfo e
                     ;; The adapter may have created an action and then
                     ;; failed before returning its permit.  Keep the permit
                     ;; so the pre-start failure path can close the action
                     ;; instead of stranding it.
                     (when-let [permit (:permit (ex-data e))]
                       (swap! state assoc :board-permit permit))
                     (throw e))))]
        (when (and (not (read-only-runner? runner)) (nil? bp))
          (throw (ex-info "Board adapter did not register the running action"
                          {:code :SHELL_ACTION_NOT_RUNNING
                           :request-id (:request-id session)})))
        (swap! state assoc :board-permit bp :phase :starting)
        (note-interrupt! state)
        (if (:stop-requested? @state)
          (finish-before-start! runner session bp
                                (cancelled-before-start (:request-id session)))
          (let [capture (try
                          (output/capture (capture-options runner request))
                          (catch Throwable t {:error t}))]
            (if-let [t (:error capture)]
              (finish-before-start!
               runner session bp
               (err/rejection :PREPARE_REJECTED :output-unavailable
                              "Could not create the bounded output capture"
                              {:exception (err/throwable->data t)}))
              (do
                (swap! state assoc :capture capture)
                (note-interrupt! state)
                (if (:stop-requested? @state)
                  (let [out (output/finish! capture)]
                    (finish-before-start!
                     runner session bp
                     (assoc (cancelled-before-start (:request-id session))
                            :output out
                            :output-complete? true)))
                  (let [proc (try
                               (process/start! (:command-spec request)
                                               (:cwd-path request)
                                               (:env-map request))
                               (catch clojure.lang.ExceptionInfo e
                                 (if (err/expected? e)
                                   {:error (ex-data e)}
                                   (throw e))))]
                    (if-let [e (:error proc)]
                      (let [out (output/finish! capture)]
                        (finish-before-start!
                         runner session bp
                         (assoc e
                                :request-id (:request-id session)
                                :output out
                                :output-complete? true)))
                      (do
                        (swap! state assoc
                               :process proc
                               :pid (some-> (.toHandle ^java.lang.Process proc) .pid)
                               :phase :running
                               :started-at (Instant/now))
                        ;; The process now exists.  From here every failure is
                        ;; a started-process failure, never a not-started
                        ;; refusal.
                        (try
                          (run-process! runner session proc capture)
                          (catch Throwable t
                            (if (:done? @state)
                              (:result @state)
                              (handle-process-failure! runner session proc
                                                       capture t))))))))))))))))

(defn- acquire-gate!
  [runner session]
  (let [state (:state session)]
    (try
      {:permit (gate/acquire! (:gate runner)
                              (:request-id session)
                              {:stop? #(boolean (:stop-requested? @state))})}
      (catch clojure.lang.ExceptionInfo e
        (if (err/expected? e)
          {:error (ex-data e)}
          (throw e))))))

(defn- execute-session!
  [runner session]
  (let [state (:state session)
        request (:request session)]
    (try
      (let [eligibility (when-not (read-only-runner? runner)
                          (board/check-eligibility! (:board runner) request))]
        (swap! state assoc
               :eligibility eligibility
               :phase :waiting-gate
               :waiting-since (Instant/now))
        (let [acquired (acquire-gate! runner session)]
          (if-let [e (:error acquired)]
            (finish-session! session
                             (assoc (gate-rejection (:request-id session) e)
                                    :write-gate-held? false))
            (run-after-gate! runner session (:permit acquired) eligibility))))
      (catch clojure.lang.ExceptionInfo e
        (if (:done? @state)
          (:result @state)
          (if (err/expected? e)
            (do
              (when-let [capture (:capture @state)]
                (output/finish! capture))
              (finish-before-start! runner session (:board-permit @state)
                                    (ex-data e)))
            (if-let [process (:process @state)]
              (handle-process-failure! runner session process
                                       (:capture @state) e)
              (do
                (when-let [capture (:capture @state)]
                  (output/finish! capture))
                (finish-before-start! runner session (:board-permit @state)
                                      (hard-failure-result session e :orchestration)))))))
      (catch Throwable t
        (if (:done? @state)
          (:result @state)
          (if-let [process (:process @state)]
            (handle-process-failure! runner session process
                                     (:capture @state) t)
            (do
              (when-let [capture (:capture @state)]
                (output/finish! capture))
              (finish-before-start! runner session (:board-permit @state)
                                    (hard-failure-result session t :orchestration)))))))))

;; ---------------------------------------------------------------------------
;; public API

(defn runner
  "Compose a managed shell runner.

  Required:

    :root  workspace root used to resolve relative :cwd
    :gate  the shared `hc.core.workspace-gate` WriteGate, or :workspace-gate

  Optional:

    :board          shell board adapter; defaults to an empty memory board
    :artifact-dir   directory for temporary artifact logs
    :limits         override output, poll, stop-grace or publish limits
    :resolve-secret (fn [ref] value) for :env-refs

  The gate is deliberately not created here.  A private gate would not be the
  workspace's shared W and would let a shell session overlap a protected
  commit."
  [opts]
  (let [{:keys [root gate workspace-gate board artifact-dir limits resolve-secret]} opts
        gate (cond
               (and gate (:gate gate) (not (:semaphore gate))) (:gate gate)
               gate gate
               workspace-gate (:gate workspace-gate)
               :else nil)]
    (when-not root
      (throw (IllegalArgumentException. "shell runner requires :root")))
    (when-not gate
      (throw (IllegalArgumentException.
              "shell runner requires the shared :gate (or :workspace-gate)")))
    (->ShellRunner (str root)
                   gate
                   (or board (board/memory-board))
                   artifact-dir
                   (merge default-limits limits)
                   resolve-secret
                   (atom {})
                   :swarm-holder)))

(defn read-only-runner
  "Compose the Plan dedicated-worker shell path.

  It has no board, token, claim or register authority.  It still uses the
  shared W and the same bounded output/stop/session machinery, and refuses
  obvious mutating commands through `hc.server.shell.read-only`.  This is a
  best-effort read-only path, not a sandbox; the normal `runner` remains the
  only Swarm-holder path."
  [opts]
  (let [{:keys [root gate workspace-gate artifact-dir limits resolve-secret]} opts
        gate (cond
               (and gate (:gate gate) (not (:semaphore gate))) (:gate gate)
               gate gate
               workspace-gate (:gate workspace-gate)
               :else nil)]
    (when-not root
      (throw (IllegalArgumentException. "shell read-only runner requires :root")))
    (when-not gate
      (throw (IllegalArgumentException.
              "shell read-only runner requires the shared :gate (or :workspace-gate)")))
    (->ShellRunner (str root)
                   gate
                   nil
                   artifact-dir
                   (merge default-limits limits)
                   resolve-secret
                   (atom {})
                   :read-only)))

(defn start!
  "Accept a shell request and return a ShellSession immediately.

  The session runs on a daemon thread.  Invalid requests and replay
  mismatches return an already-finished session whose result is the ordinary
  rejection.  The same request-id with the same payload replays the original
  session; a different payload yields `:CALL_REPLAY_MISMATCH`."
  [runner request]
  (let [original request
        {:keys [ok? request error]} (normalize-request runner request)]
    (if-not ok?
      (rejected-session runner (when (map? original) (:request-id original))
                        original error)
      (let [fp (fingerprint request)
            claimed (claim-request! runner request fp)]
        (case (:role claimed)
          :mismatch
          (rejected-session
           runner (:request-id request) request
           (err/rejection :CALL_REPLAY_MISMATCH :call-replay-mismatch
                          "The same request-id was reused with a different payload"
                          {:request-id (:request-id request)}))

          :cached (:session claimed)

          :waiter (:session claimed)

          :owner
          (let [session (:session claimed)
                thread (Thread.
                        #(execute-session! runner session)
                        (str "hc-shell-session-" (:request-id session)))]
            (.setDaemon thread true)
            (swap! (:state session) assoc :thread thread)
            (try
              (.start thread)
              (catch Throwable t
                (finish-session! session
                                  (hard-failure-result session t :thread-start))))
            session))))))

(defn await
  "Wait for the first terminal result.  With TIMEOUT-MS, return a `:TIMEOUT`
  map instead of blocking past the deadline."
  ([session]
   (deref (:result-promise session)))
  ([session timeout-ms]
   (let [value (deref (:result-promise session) timeout-ms ::timeout)]
     (if (= value ::timeout)
       {:status :TIMEOUT
        :request-id (:request-id session)
        :phase (:phase @(:state session))
        :write-gate-held? (:write-gate-held? @(:state session))}
       value))))

(defn run!
  "Start a request and wait for its first terminal result."
  [runner request]
  (await (start! runner request)))

(defn result
  "Return the latest recorded result.  A late watchdog may replace an earlier
  `:UNCERTAIN` result after the process and its known children actually end."
  [session]
  (:result @(:state session)))

(defn status
  "Return a bounded session status without captured output."
  [session]
  (let [state @(:state session)
        process (:process state)
        started (:started-at state)
        now (or (:ended-at state) (Instant/now))
        elapsed (when started
                  (.toMillis (Duration/between ^Instant started ^Instant now)))]
    {:request-id (:request-id session)
     :phase (:phase state)
     :done? (:done? state)
     :stop-requested? (:stop-requested? state)
     :stop-signal-sent? (:stop-signal-sent? state)
     :interrupted? (:interrupted? state)
     :pid (:pid state)
     :process (when process (process/process-info process))
     :started-at (->date started)
     :ended-at (->date (:ended-at state))
     :elapsed-ms elapsed
     :write-gate-held? (:write-gate-held? state)
     :publish-retry? (boolean (:publish-retry state))
     :known-child-activities (process/known-child-activities (:known-children session))
     :result-status (get-in state [:result :status])}))

(defn subscribe!
  "Register a listener for live output chunks.

  The listener receives `{:stream :stdout|:stderr :bytes n :data bytes
  :text string}`.  It must return quickly; it runs on the pump thread.  The
  returned function unsubscribes."
  [session listener]
  (swap! (:listeners session) conj listener)
  (fn [] (swap! (:listeners session) disj listener)))

(defn unsubscribe!
  [session listener]
  (swap! (:listeners session) disj listener)
  session)

(defn- sessions-matching
  [runner target]
  (let [registry @(:registry runner)]
    (cond
      (and (map? target) (contains? target :request-id))
      (when-let [entry (get registry (:request-id target))]
        (when-let [session (:session entry)] [session]))

      (and (map? target) (contains? target :actor-id))
      (->> registry
           vals
           (keep :session)
           (filter #(= (:actor-id (:request %)) (:actor-id target)))
           vec)

      :else
      (when-let [entry (get registry target)]
        (when-let [session (:session entry)] [session])))))

(defn- stop-session!
  [runner session opts]
  (let [state (:state session)
        s @state
        limits (update (merge (:limits runner)
                              (select-keys (or opts {}) [:stop-grace-ms]))
                       :stop-grace-ms #(or % 2000))
        request-id (:request-id session)]
    (cond
      (and (:done? s)
           (not (and (:process s) (process/alive? (:process s))))
           (empty? (process/alive-known-children (:known-children session))))
      {:status :SESSION_ENDED
       :request-id request-id
       :result (:result s)}

      (:stop-requested? s)
      (if-let [process (:process s)]
        (let [result (process/stop-tree! process (:known-children session)
                                         {:grace-ms (:stop-grace-ms limits)})]
          (swap! state assoc
                 :stop-signalled-at (Instant/now)
                 :stop-signal-sent? (:signal-sent? result)
                 :stop-signal-attempted? true
                 :stop-signal-result result)
          (if (:signal-sent? result)
            {:status :STOP_SIGNAL_SENT
             :request-id request-id
             :alive (:alive result)}
            {:status :STOP_UNCONFIRMED
             :request-id request-id
             :alive (:alive result)
             :errors (:errors result)}))
        {:status :STOP_REGISTERED
         :request-id request-id
         :already-requested? true})

      :else
      (do
        (swap! state assoc
               :stop-requested? true
               :stop-requested-at (Instant/now))
        (if-let [process (:process s)]
          (let [result (process/stop-tree! process (:known-children session)
                                           {:grace-ms (:stop-grace-ms limits)})]
            (swap! state assoc
                   :stop-signalled-at (Instant/now)
                   :stop-signal-sent? (:signal-sent? result)
                   :stop-signal-attempted? true
                   :stop-signal-result result)
            (if (:signal-sent? result)
              {:status :STOP_SIGNAL_SENT
               :request-id request-id
               :alive (:alive result)}
              {:status :STOP_UNCONFIRMED
               :request-id request-id
               :alive (:alive result)
               :errors (:errors result)}))
          {:status :STOP_REGISTERED
           :request-id request-id})))))

(defn- aggregate-stop
  [results]
  (if (= 1 (count results))
    (first results)
    (let [statuses (set (map :status results))]
      {:status (cond
                 (contains? statuses :STOP_UNCONFIRMED) :STOP_UNCONFIRMED
                 (contains? statuses :STOP_SIGNAL_SENT) :STOP_SIGNAL_SENT
                 (contains? statuses :STOP_REGISTERED) :STOP_REGISTERED
                 :else :SESSION_ENDED)
       :sessions results})))

(defn request-stop!
  "Request a stop by request-id, actor-id, `{:request-id ...}` or
  `{:actor-id ...}`.

  Returns `:STOP_REGISTERED`, `:STOP_SIGNAL_SENT`, `:SESSION_ENDED` or
  `:STOP_UNCONFIRMED`.  A signal is not a rollback and does not release W.
  The session records the result when the process and its known children
  actually end."
  ([runner target]
   (request-stop! runner target {}))
  ([runner target opts]
   (let [sessions (sessions-matching runner target)]
     (if (empty? sessions)
       {:status :STOP_UNCONFIRMED
        :reason :unknown-session
        :target target}
       (aggregate-stop (mapv #(stop-session! runner % opts) sessions))))))

(defn action
  "Query a shell request by id without executing it."
  [runner request-id]
  (when-let [entry (get @(:registry runner) request-id)]
    (let [session (:session entry)
          state (when session @(:state session))]
      {:request-id request-id
       :status (cond
                 (:done? state)
                 (if (= :UNCERTAIN (get-in state [:result :status]))
                   :uncertain
                   :finished)

                 (= :waiting-gate (:phase state)) :waiting-gate
                 (= :starting (:phase state)) :starting
                 (= :running (:phase state)) :running
                 (= :uncertain (:phase state)) :uncertain
                 (= :finished (:phase state)) :finished
                 :else (:status entry))
       :phase (:phase state)
       :publish-retry? (boolean (:publish-retry state))
       :result (:result state)})))
