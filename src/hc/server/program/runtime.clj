(ns hc.server.program.runtime
  "In-process Clojure program runtime: `defprogram`, capability dispatch,
  genuine park/unpark continuations, intent-based deadlines, and call-id
  idempotency.

  Continuations are JVM threads blocked on a promise.  A park returns its
  receipt to the caller while the program thread keeps its stack and batch
  view.  This is a real continuation inside one process, not a replay of the
  source; it does not survive a process restart.  Deadline parking is
  cooperative at capability calls and injected loop/function checkpoints,
  because the JVM cannot preempt arbitrary pure computation while preserving
  a resumable stack.

  Authority: root `AGENTS.md` §4.1–§4.3."
  (:require [clojure.string :as str]
            [hc.core.text-location :as text]
            [hc.core.ufss :as ufss]
            [hc.server.program.capability :as cap]
            [hc.server.program.receipt :as receipt]
            [hc.server.program.sandbox :as sandbox]
            [hc.server.program.workspace-port :as workspace-port])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat)
           (java.util.concurrent Executors ScheduledThreadPoolExecutor
                                 ThreadFactory TimeUnit)))

(set! *warn-on-reflection* true)

(def ^:dynamic *runtime* nil)
(def ^:dynamic *program* nil)

(def active-states #{:running :waiting :draining :settling :parked})
(def terminal-states #{:completed :failed :cancelled})

;; ---------------------------------------------------------------------------
;; Small utilities

(defn utf8-length
  [^String s]
  (alength (.getBytes s StandardCharsets/UTF_8)))

(defn intent->deadline-ms
  "The §4.3 deadline: UTF-8 bytes of the call's `:intent` times 50 ms."
  [intent]
  (when (string? intent)
    (* 50 (utf8-length intent))))

(defn- now
  [rt]
  ((:clock rt)))

(defn- sha256
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        ^bytes bytes (.digest digest (.getBytes s StandardCharsets/UTF_8))
        ^HexFormat hex (HexFormat/of)]
    (.formatHex hex bytes)))

(defn- canonical
  [x]
  (cond
    (map? x)
    (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map (fn [[k v]] [k (canonical v)]))
          x)

    (set? x)
    (into (sorted-set-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map canonical)
          x)

    (or (vector? x) (seq? x)) (mapv canonical x)
    :else x))

(defn- request-fingerprint
  [request]
  (sha256
   (pr-str
    (canonical
     (select-keys request
                  [:role :source :intent :agent-id :claim-id :task-id
                   :tenure-id :lane :workspace
                   :holder? :s1? :phase :capabilities :draft-path
                   :write-unlocked? :readable-paths])))))

(defn- active?
  [p]
  (and p (contains? active-states @(:state p))))

(defn- terminal?
  [p]
  (and p (contains? terminal-states @(:state p))))

(defn- set-state!
  [p state]
  (reset! (:state p) state)
  (swap! (:revision p) inc))

(declare pending-operations)

(defn- program-view
  [rt p]
  (assoc (receipt/base p)
         :attempt-id (:attempt-id (:request p))
         :name (:name p)
         :pending-operations (pending-operations p)
         :command @(:command p)
         :delta @(:delta p)
         :control-results (vec @(:control-results p))
         :commits (vec @(:commits p))))

(defn- pending-operations
  "In-flight calls plus calls whose port returned an unconfirmed result.

  The second group is no longer blocking the program thread, but the external
  resource may still be alive; it must stay visible to a later cancellation."
  [p]
  (into (vec @(:pending p)) @(:unsettled p)))

(defn- cancelled-result
  "The terminal receipt for a cancellation.

  An earlier call whose port returned an unconfirmed result may still own a
  process or a write.  Keep that responsibility visible as
  `:OUTCOME_UNKNOWN` instead of reporting a clean cancellation; the applied
  batch, if any, remains in `:commits`."
  [rt p commit]
  (if (seq @(:unsettled p))
    (receipt/cancelled-unconfirmed (program-view rt p)
                                   @(:command p)
                                   (mapv :id @(:unsettled p)))
    (receipt/cancelled (program-view rt p) commit)))

(defn- remaining-ms
  [rt p]
  (if-let [d @(:deadline p)]
    (max 0 (- (long (:at d)) (long (now rt))))
    0))

;; ---------------------------------------------------------------------------
;; Program record

(defrecord Program [name call-id role capabilities source numbered-source
                    intent intent-bytes deadline-ms started-at context request
                    phase holder? s1? on-terminal before-terminal
                    session state revision park park-id segment result
                    last-receipt
                    deadline deadline-generation deadline-expired? aborted
                    pending inflight command delta control-results commits
                    call-counter settle-counter
                    committing? cancelled? thread lock plan-draft-path
                    settle-pending unsettled stream-seen])

(defn- new-program
  [rt request compiled session]
  (let [intent (:intent request)
        bytes (utf8-length intent)
        ms (* 50 bytes)]
    (->Program (:name compiled)
               (:call-id request)
               (:role request)
               (:capabilities request)
               (:source compiled)
               (:numbered-source compiled)
               intent bytes ms
               (now rt)
               (:context request)
               request
               (:phase request)
               (boolean (:holder? request))
               (boolean (:s1? request))
               (:on-terminal request)
               (:before-terminal request)
               (atom session)
               (atom :running)
               (atom 0)
               (atom nil)
               (atom 0)
               (atom (promise))
               (atom nil)
               (atom nil)
               (atom nil)
               (atom 0)
               (atom false)
               (atom nil)
               (atom [])
               (atom #{})
               (atom nil)
               (atom "")
               (atom [])
               (atom [])
               (atom 0)
               (atom 0)
               (atom false)
               (atom false)
               (atom nil)
               (Object.)
               (atom (:draft-path (:context request)))
               ;; `settle-pending` marks a protected batch that is applying or
               ;; has applied but whose commit is not yet visible in a fixed
               ;; terminal.  `cancel!` must not fix a clean cancellation while
               ;; it is non-nil.
               (atom nil)
               ;; Calls whose port returned an unconfirmed result.  They are
               ;; no longer in flight, but the external resource may still be
               ;; alive; keep them visible for a later cancellation.
               (atom [])
               ;; Per-stream delivered text lengths, for an accurate `:delta`.
               (atom {:stdout 0 :stderr 0}))))

;; ---------------------------------------------------------------------------
;; Segment notification and deadline

(defn- deliver-segment!
  [p value]
  (let [segment @(:segment p)]
    (when-not (realized? segment)
      (deliver segment value))))

(defn- cancel-deadline!
  [p]
  (swap! (:deadline-generation p) inc)
  (when-let [d @(:deadline p)]
    (when-let [cancel (:cancel d)]
      (try (cancel) (catch Throwable _ nil)))
    (reset! (:deadline p) nil)))

(defn- schedule!
  [rt delay-ms f]
  ((:schedule rt) (long (max 0 delay-ms)) f))

(declare deadline-fired!)

(defn- arm-deadline!
  [rt p intent]
  (cancel-deadline! p)
  (when-not (or (terminal? p) @(:cancelled? p) @(:committing? p))
    (let [bytes (utf8-length intent)
          ms (* 50 bytes)
          generation (swap! (:deadline-generation p) inc)
          cancel (schedule! rt ms #(deadline-fired! rt p generation))]
      (if (or (terminal? p) (= :parked @(:state p)) @(:cancelled? p))
        (when cancel (cancel))
        (do
          (reset! (:deadline p)
                  {:at (+ (long (now rt)) (long ms))
                   :ms ms
                   :intent-bytes bytes
                   :generation generation
                   :cancel cancel})
          (reset! (:deadline-expired? p) false))))))

(defn- arm-deadline-until!
  "Re-arm the intent deadline at its original wall-clock instant after a
  protected pre-call drain.

  The drain cancels the deadline so a timer cannot park the program in the
  middle of a protected apply.  That cancellation must not give the rest of
  the program an unbounded budget: a staged write before `call` would
  otherwise disable the deadline for the shell wait.  Keeping the original
  instant means time spent in the drain still counts; an already-passed
  instant parks at the next checkpoint before starting new work."
  [rt p deadline-at]
  (cancel-deadline! p)
  (when-not (or (terminal? p)
                @(:cancelled? p)
                @(:committing? p)
                (= :parked @(:state p)))
    (let [ms (long (max 0 (- (long deadline-at) (long (now rt)))))
          generation (swap! (:deadline-generation p) inc)
          cancel (schedule! rt ms #(deadline-fired! rt p generation))]
      (if (or (terminal? p)
              (= :parked @(:state p))
              @(:cancelled? p))
        (when cancel (cancel))
        (do
          (reset! (:deadline p)
                  {:at (+ (long (now rt)) ms)
                   :ms ms
                   :intent-bytes (:intent-bytes p)
                   :generation generation
                   :cancel cancel})
          (reset! (:deadline-expired? p) false))))))

(defn- finish-program!
  [rt p result]
  (if (compare-and-set! (:result p) nil result)
    (do
      (set-state! p (:state result))
      (cancel-deadline! p)
      ;; `:before-terminal` is the publication barrier: a caller waiting on
      ;; the terminal segment (notably `unpark!`) must not observe
      ;; completion before per-program cleanup (workspace session aliases,
      ;; terminal evidence) has finished.  It is internal, synchronous, and
      ;; must not block or reenter the runtime.  Ordinary `:on-terminal`
      ;; notifications stay after publication so a slow callback cannot
      ;; delay the terminal receipt or deadlock a same-call waiter.
      (when-let [cb (:before-terminal p)]
        (try (cb result) (catch Throwable _ nil)))
      (deliver-segment! p result)
      (when-let [cb (:on-terminal p)]
        (try (cb result) (catch Throwable _ nil)))
      (when (and (:on-terminal rt)
                 (not (:synthetic? (:request p))))
        (try ((:on-terminal rt) result) (catch Throwable _ nil)))
      ;; The terminal now carries whatever commit evidence exists; a later
      ;; cancellation must no longer treat this batch as pending.
      (reset! (:settle-pending p) nil)
      result)
    @(:result p)))

(defn- make-park!
  "Create a park point and deliver its receipt to the current segment.

  Caller must hold the program lock.  Returns the park map, or nil when the
  program is already terminal."
  [rt p reason payload]
  (locking (:lock p)
    (cond
      (terminal? p) nil
      (= :parked @(:state p)) @(:park p)
      :else
      (let [resume (promise)
            park-id (swap! (:park-id p) inc)
            park {:id park-id
                  :reason reason
                  :payload payload
                  :resume resume
                  :resumed? false}]
        (reset! (:park p) park)
        (set-state! p :parked)
        ;; Capture the remaining budget before the deadline is canceled; the
        ;; receipt field is otherwise always zero and carries no information.
        (let [remaining (remaining-ms rt p)]
          (cancel-deadline! p)
          (when (= reason :deadline)
            (reset! (:deadline-expired? p) true))
          (let [delta @(:delta p)
                receipt (receipt/park
                         (program-view rt p)
                         {:reason reason
                          :payload payload
                          :remaining-wait-ms remaining
                          :pending-operations (pending-operations p)
                          :command @(:command p)
                          :delta delta
                          :park-id park-id
                          :revision @(:revision p)})]
            ;; `:delta` is output since the previous receipt, not since the
            ;; previous command.  Consume it when the receipt is delivered.
            (reset! (:delta p) "")
            (reset! (:last-receipt p) receipt)
            (deliver-segment! p receipt))
          park)))))

(defn- request-deadline-park!
  "Mark P parked for a deadline from another thread.  No-op when already
  parked or terminal."
  [rt p]
  (let [d @(:deadline p)]
    (make-park! rt p :deadline
                {:deadline-ms (or (:ms d) (:deadline-ms p))
                 :intent-bytes (or (:intent-bytes d) (:intent-bytes p))})))

(defn- deadline-fired!
  [rt p generation]
  (when (and (= generation @(:deadline-generation p))
             (not (terminal? p))
             (not @(:cancelled? p))
             (not= :parked @(:state p)))
    (request-deadline-park! rt p)))

;; ---------------------------------------------------------------------------
;; Park / checkpoint / abort

(def ^:private cancelled-sentinel ::cancelled)

(def ^:private cancelled-signal
  "The cancellation control signal.

  It is a private `Error` subclass, not an `ExceptionInfo`: the sandbox
  allows `catch` of `ExceptionInfo`/`RuntimeException`/`Exception`, so an
  exception-shaped signal could be swallowed by a program.  `Error` is not
  in `sandbox/allowed-catch`, and `new` cannot construct this class."
  (proxy [Error] ["Program was cancelled"]
    (fillInStackTrace [] this)))

(def ^:private abort-signal
  "The control abort signal.  See `cancelled-signal`."
  (proxy [Error] ["Program aborted"]
    (fillInStackTrace [] this)))

(defn- throw-cancelled!
  "Throw the runtime's explicit cancellation signal.

  `run-program!` recognizes only this shape as cancellation.  A program's
  own exception is never a cancellation signal, even when `cancel!`
  set the flag concurrently."
  []
  (throw cancelled-signal))

(def ^:private uncertain-call-statuses
  #{:UNCERTAIN :uncertain :OUTCOME_UNKNOWN :outcome-unknown
    :UNKNOWN :unknown :TIMEOUT :timeout})

(defn- uncertain-call-result?
  "True when a call port returned without observing an end.  Such a result
  must not be reported as a clean cancellation."
  [result]
  (or (not (map? result))
      (contains? uncertain-call-statuses (:status result))))

(defn- failed-call-result?
  "True when the call port returned its own failure result.  A cancellation
  request must not rewrite that real failure into a clean cancellation."
  [result]
  (and (map? result)
       (contains? #{:FAILED :failed} (:status result))))

(defn- abort!
  [p result]
  (reset! (:aborted p) result)
  (throw abort-signal))

(defn- throw-abort!
  [p]
  (throw abort-signal))

(defn- park!
  "Block the program thread at a park point and return the injected value.

  REASON is `:explicit`, `:where-ambiguous`, `:ask`, or `:deadline`."
  [rt p reason payload]
  (when-let [abort @(:aborted p)] (throw-abort! p))
  (let [resume (locking (:lock p)
                 (cond
                   (terminal? p)
                   (throw-cancelled!)

                   (= :parked @(:state p))
                   (or (some-> @(:park p) :resume)
                       (throw-abort! p))

                   :else
                   (let [park (make-park! rt p reason payload)]
                     (if park
                       (:resume park)
                       (throw-cancelled!)))))]
    (let [value @resume]
      (when (= cancelled-sentinel value)
        (throw-cancelled!))
      (locking (:lock p)
        (when-not (terminal? p)
          (set-state! p :running)
          (reset! (:park p) nil)))
      (when-let [abort @(:aborted p)]
        (reset! (:aborted p) abort)
        (throw-abort! p))
      value)))

(defn- checkpoint!
  [rt p]
  (when-let [_ @(:aborted p)] (throw-abort! p))
  (when @(:cancelled? p)
    (throw-cancelled!))
  (when-not (terminal? p)
    (let [expired? (or @(:deadline-expired? p)
                       (when-let [d @(:deadline p)]
                         (>= (long (now rt)) (long (:at d)))))]
      (when expired?
        (let [d @(:deadline p)]
          (park! rt p :deadline
                 {:deadline-ms (or (:ms d) (:deadline-ms p))
                  :intent-bytes (or (:intent-bytes d) (:intent-bytes p))}))))))

(defn- await-unpark!
  [rt p]
  (loop []
    (when (= :parked @(:state p))
      (let [resume (some-> @(:park p) :resume)]
        (if resume
          (let [value @resume]
            (when (= cancelled-sentinel value)
              (throw-cancelled!))
            ;; A delivered resume normally flips the state before the
            ;; program thread wakes.  Clear a residual parked state here so
            ;; finalization cannot dereference the same promise forever.
            (locking (:lock p)
              (when (= :parked @(:state p))
                (set-state! p :running)
                (reset! (:park p) nil))))
          (throw (ex-info "Parked program has no resumable park point"
                          {:status :FAILED
                           :error :FAILED
                           :reason :park-point-missing
                           :name (:name p)})))))
    (when (= :parked @(:state p))
      (recur))))

;; ---------------------------------------------------------------------------
;; Workspace port

(defn- workspace-session
  [rt p]
  (or @(:session p)
      (throw (ex-info "Program has no workspace session"
                      {:status :UNSUPPORTED_OPERATION
                       :error :UNSUPPORTED_OPERATION
                       :reason :workspace-session-missing}))))

(defn- workspace-fn
  [rt key]
  (or (get (:workspace rt) key)
      (throw (ex-info (str "Workspace port is missing " key)
                      {:status :UNSUPPORTED_OPERATION
                       :error :UNSUPPORTED_OPERATION
                       :reason :workspace-port-missing
                       :key key}))))

(defn- workspace-facts
  [rt p]
  (if-let [facts-fn (get (:workspace rt) :facts)]
    (if-let [session @(:session p)]
      (or (facts-fn session) {})
      {})
    {}))

(defn- empty-facts?
  "Automatic settlement is for staged file changes only.  Read-set-only
  board conclusions are published by the control port's `finish`, which owns
  the empty-change verification under W."
  [facts]
  (empty? (:ops facts)))

(defn- settle!
  "Drain the in-memory batch through the workspace port under W.

  Returns `{:status ... :result commit-result :facts facts}`.  `:APPLIED`
  installs the returned fresh session.  Any other status is a program abort
  decided by the caller."
  [rt p phase]
  (let [facts (workspace-facts rt p)]
    (if (empty-facts? facts)
      {:status :NO_FILE_FACTS :result nil :facts facts}
      (do
        ;; Cancellation and the commit admission share the lock.  A cancel
        ;; that runs first aborts before any write; a commit that runs first
        ;; makes `cancel!` return `:cancel-after-apply?` and finish the
        ;; batch.
        (locking (:lock p)
          (when @(:cancelled? p)
            (abort! p (receipt/cancelled (program-view rt p) nil)))
          (set-state! p phase)
          (cancel-deadline! p)
          (reset! (:committing? p) true))
        (try
          ;; From this point until the caller fixes the terminal, a
          ;; cancellation must not produce a clean `:CANCELLED` receipt: the
          ;; batch may already be on disk and its commit must stay visible.
          (reset! (:settle-pending p) {:phase phase :status :in-flight})
          (let [n (swap! (:settle-counter p) inc)
                request {:request-id (str (:call-id p) ":settle:" n)
                         :settle-id n
                         :call-id (:call-id p)
                         :program-name (:name p)
                         :role (:role p)
                         :agent-id (:agent-id (:request p))
                         :claim-id (:claim-id (:request p))
                         :task-id (:task-id (:request p))
                         :actor-id (:agent-id (:request p))
                         :phase :applied
                         :intent-bytes (:intent-bytes p)}
                result ((workspace-fn rt :settle!) @(:session p) request)]
            (if (= :APPLIED (:status result))
              (do
                (when-let [next-session (:session result)]
                  (reset! (:session p) next-session))
                (swap! (:commits p) conj
                       {:phase phase
                        :result (:result result)
                        ;; Facts may contain staged byte arrays.  Keep only
                        ;; EDN counts so the commit ledger can travel through
                        ;; an unpark wrapper's force-result.
                        :operation-count (count (:ops facts))
                        :read-set-count (count (:read-set facts))
                        :checks-count (count (:checks facts))})
                (reset! (:settle-pending p)
                        {:phase phase
                         :status :applied
                         :result (:result result)})
                {:status :APPLIED
                 :result (:result result)
                 :facts facts})
              (do
                ;; A rejected batch applied nothing; a clean cancellation is
                ;; safe again.
                (reset! (:settle-pending p) nil)
                {:status (:status result)
                 :result (:result result)
                 :facts facts})))
          (finally
            (reset! (:committing? p) false)))))))

;; ---------------------------------------------------------------------------
;; Capability dispatch

(defn- control-context
  [p]
  (merge (select-keys (:request p) [:agent-id :claim-id :task-id :attempt-id
                                    :tenure-id :lane :workspace])
         ;; The host may resolve a live holder term for a managed shell.  It
         ;; is server state, not a model argument; only this field crosses
         ;; from the program context into the call port.
         (select-keys (:context (:request p)) [:token-term])
         (select-keys p [:role :call-id :phase :holder? :s1?])
         {:program-name (:name p)}))

(defn- request-context
  [request name]
  (merge (select-keys request [:agent-id :claim-id :task-id :attempt-id
                               :tenure-id :lane :workspace :role
                               :call-id :phase :holder? :s1?
                               :draft-path :write-unlocked? :readable-paths])
         {:program-name name}))

(defn- require-map
  [capability request]
  (when-not (map? request)
    (throw (ex-info (str capability " takes one EDN map")
                    {:status :INVALID_ARGUMENT
                     :error :INVALID_ARGUMENT
                     :capability capability
                     :expected "a map"
                     :actual (some-> request class .getName)})))
  request)

(defn- deny!
  [denial]
  (throw (ex-info (:message denial)
                  (assoc denial :program-refusal true))))

(defn- check!
  [rt p capability]
  (when-let [_ @(:aborted p)] (throw-abort! p))
  (when @(:cancelled? p)
    (throw-cancelled!))
  ;; §4.3: every capability call is a checkpoint.  The injected forms cover
  ;; loop/function/let entry points, but a sequence of capability calls in a
  ;; long body may not cross one; checking here makes the deadline effective
  ;; before the call's own work starts.
  (checkpoint! rt p)
  (when-not (contains? (:capabilities p) capability)
    (deny! (cap/denial :CAPABILITY_DENIED
                       "The effective capability set does not grant this function"
                       {:role (:role p)
                        :capability capability
                        :effective (:capabilities p)})))
  (when-let [denial (cap/check-static (:role p) capability)]
    (deny! denial))
  (case capability
    :ask (when-let [denial (cap/check-ask (:role p) (:s1? p))]
           (deny! denial))
    :call (when-let [denial (cap/check-call (:role p) (:holder? p))]
            (deny! denial))
    :abandon (when-let [denial (cap/check-abandon (:role p) (:holder? p))]
               (deny! denial))
    nil))

(defn- do-file-read
  [rt p capability request]
  (require-map capability request)
  (let [path (:path request)]
    (when-not (string? path)
      (throw (ex-info (str capability " requires a string :path")
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :capability capability :field :path})))
    (when (= :clj-plan (:role p))
      (when-let [denial (cap/check-plan-read
                         (:context p) path
                         (or @(:plan-draft-path p)
                             (:draft-path (:context p))))]
        (deny! denial)))
    ((workspace-fn rt capability) (workspace-session rt p) path)))

(defn- do-write
  [rt p request]
  (require-map :write request)
  (let [path (:path request)
        content (:content request)]
    (when-not (string? path)
      (throw (ex-info "write requires a string :path"
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :capability :write :field :path})))
    (when-not (string? content)
      (throw (ex-info "write requires a string :content"
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :capability :write :field :content})))
    (when (= :clj-plan (:role p))
      (let [context (:context p)
            fixed @(:plan-draft-path p)
            draft (or fixed (:draft-path context))]
        (when-let [denial (cap/check-plan-write context path draft)]
          (deny! denial))
        (when (nil? draft)
          ;; The first write fixes the path.  It must create a new file;
          ;; replacing an existing draft would silently take another name.
          ;; `plans/` is infrastructure for the only writable Plan path; a
          ;; fresh workspace may not have it yet.
          (let [session (workspace-session rt p)
                parent-stat ((workspace-fn rt :stat) session "plans")]
            (when-not (:exists parent-stat)
              ((workspace-fn rt :mkdir) session "plans"))
            (let [stat ((workspace-fn rt :stat) session path)]
              (when (:exists stat)
                (deny! (cap/denial :DRAFT_NAME_TAKEN
                                   "Plan draft name is already taken"
                                   {:path path}))))))))
    (let [result ((workspace-fn rt :write) (workspace-session rt p)
                  path content)]
      (when (and (= :clj-plan (:role p))
                 (nil? @(:plan-draft-path p))
                 (nil? (:draft-path (:context p))))
        (reset! (:plan-draft-path p) path))
      result)))

(defn- do-mkdir
  [rt p request]
  (require-map :mkdir request)
  (let [path (:path request)]
    (when-not (string? path)
      (throw (ex-info "mkdir requires a string :path"
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :capability :mkdir :field :path})))
    ((workspace-fn rt :mkdir) (workspace-session rt p) path)))

(defn- do-rm
  [rt p request]
  (require-map :rm request)
  (let [path (:path request)]
    (when-not (string? path)
      (throw (ex-info "rm requires a string :path"
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :capability :rm :field :path})))
    ((workspace-fn rt :rm) (workspace-session rt p) path)))

(defn- do-mv
  [rt p request]
  (require-map :mv request)
  (let [source (:source request)
        target (:target request)]
    (when-not (and (string? source) (string? target))
      (throw (ex-info "mv requires string :source and :target"
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :capability :mv})))
    ((workspace-fn rt :mv) (workspace-session rt p) source target
     (select-keys request [:overwrite?]))))

(defn- do-where
  [rt p request]
  (require-map :where request)
  (checkpoint! rt p)
  (let [result (text/where request)]
    (if (text/park-signal? result)
      (park! rt p :where-ambiguous (:payload result))
      (do
        (checkpoint! rt p)
        result))))

(defn- do-where-all
  [rt p request]
  (require-map :whereAll request)
  (checkpoint! rt p)
  (let [result (text/whereAll request)]
    (checkpoint! rt p)
    result))

(defn- do-park
  [rt p request]
  (require-map :park request)
  (park! rt p :explicit (:payload request)))

(defn- do-ask
  [rt p request]
  (require-map :ask request)
  (when-not (contains? request :question)
    (throw (ex-info "ask requires :question"
                    {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                     :capability :ask :field :question})))
  (park! rt p :ask {:question (:question request)}))

(defn- find-program
  "Resolve a cancellation/state target.

  Returns `{:program p}` for a unique match, `{:ambiguous [p ...]}` when an
  attempt-id is shared by several programs, or nil.  Name and call-id are
  exact and unique; attempt-id is only a fallback and must not silently pick
  one of several programs."
  [rt key]
  (let [k (some-> key str)
        programs (vals @(:programs rt))]
    (when k
      (or (when-let [p (get @(:programs rt) k)]
            {:program p})
          (when-let [p (some #(when (= k (:call-id %)) %) programs)]
            {:program p})
          (let [matches (filterv #(= k (some-> (:attempt-id (:request %)) str))
                                 programs)]
            (cond
              (empty? matches) nil
              (= 1 (count matches)) {:program (first matches)}
              :else {:ambiguous matches}))))))

(defn- stale-control
  [rt target request name]
  (merge (receipt/rejection request :STALE_CONTROL
                            "The target program is not at a resumable park point"
                            {:name name
                             :target-state (when target @(:state target))})
         {:target (when target
                    (or @(:result target)
                        (program-view rt target)))}))

(defn- unpark-capability!
  [rt caller request]
  (require-map :unpark request)
  (let [name (some-> (:name request) str)
        target (get @(:programs rt) name)
        observed (:observed-park-id request)
        caller-name (:name caller)]
    (cond
      ;; Observation first, except that a self-reference is identified
      ;; before reporting another program's stale control.
      (and target (not= :parked @(:state target)))
      (if (= name caller-name)
        (receipt/rejection request :INVALID_ARGUMENT
                           "A program cannot unpark itself"
                           {:name name})
        (stale-control rt target request name))

      (and target
           (let [^Thread thread @(:thread target)]
             (or (nil? thread) (not (.isAlive thread)))))
      (stale-control rt target request name)

      (and target
           (or (nil? @(:park target))
               (get-in @(:park target) [:resumed?])))
      (stale-control rt target request name)

      (and target observed
           (not= observed (get-in @(:park target) [:id])))
      (stale-control rt target request name)

      (= name caller-name)
      (receipt/rejection request :INVALID_ARGUMENT
                         "A program cannot unpark itself"
                         {:name name})

      (nil? target)
      (receipt/rejection request :PROGRAM_UNKNOWN
                         "No program with that name is registered"
                         {:name name})

      (not (contains? (:capabilities caller) :unpark))
      (receipt/rejection request :CAPABILITY_DENIED
                         "This role cannot unpark"
                         {:role (:role caller) :capability :unpark})

      :else
      (let [new-segment (promise)
            value (:value request)
            resumed?
            (locking (:lock target)
              (let [park @(:park target)]
                (if (or (not= :parked @(:state target))
                        (nil? park)
                        (:resumed? park))
                  false
                  (do
                    (reset! (:segment target) new-segment)
                    (set-state! target :running)
                    (reset! (:park target) (assoc park :resumed? true))
                    (reset! (:deadline-expired? target) false)
                    (arm-deadline! rt target (:intent caller))
                    (deliver (:resume park) value)
                    true))))]
        (if-not resumed?
          (stale-control rt target request name)
          (loop []
            (let [remaining (remaining-ms rt caller)
                  value (deref new-segment (long remaining) ::timeout)]
              (cond
                (not= ::timeout value) value

                :else
                (do
                  (request-deadline-park! rt target)
                  (park! rt caller :deadline
                         {:deadline-ms (:deadline-ms caller)
                          :intent-bytes (:intent-bytes caller)})
                  (recur))))))))))

(declare safe-error)

(defn- stream-suffix
  "New text for one stream after SEEN characters were already delivered.

  The live view is bounded, so it can stop growing or reset; then the whole
  current text is the new part."
  [text seen]
  (let [text (or text "")
        seen (long (or seen 0))]
    (if (<= seen (count text))
      (subs text seen)
      text)))

(defn- apply-command-view!
  "Merge a live command view from the call port.

  The view is the current command map.  If it carries an explicit `:delta`,
  that is the new output since the previous view; otherwise the runtime
  derives one suffix per stream.  Concatenating the two streams before
  comparing would re-emit the whole buffer whenever both grew.  `make-park!`
  consumes `:delta` when it delivers a receipt."
  [p view]
  (let [old @(:command p)
        view (if (contains? view :command)
               view
               (assoc view :command (:command old)))
        stdout (str (or (:stdout view) ""))
        stderr (str (or (:stderr view) ""))
        seen @(:stream-seen p)
        explicit (:delta view)
        delta (if (string? explicit)
                explicit
                (str (stream-suffix stdout (:stdout seen))
                     (stream-suffix stderr (:stderr seen))))]
    (reset! (:stream-seen p) {:stdout (count stdout) :stderr (count stderr)})
    (reset! (:command p) view)
    (when (string? delta)
      (reset! (:delta p) delta))))

(defn- command-failure
  [p t]
  (let [failure {:status :FAILED
                 :error (safe-error t)
                 :message (or (.getMessage ^Throwable t)
                              "Managed shell call failed")}]
    (reset! (:command p) failure)
    (reset! (:delta p) "")
    failure))

(defn- shell-command?
  "The shell contract accepts a script string, a non-empty string vector, or
  `{:program ... :args [...]}`.  The program runtime must not narrow that
  contract to strings: a holder may need a direct executable so shell
  metacharacters in arguments stay literal."
  [command]
  (or (and (string? command) (not (str/blank? command)))
      (and (vector? command)
           (seq command)
           (every? string? command)
           (not (str/blank? (first command))))
      (and (map? command)
           (string? (:program command))
           (not (str/blank? (:program command)))
           (or (nil? (:args command))
               (and (sequential? (:args command))
                    (every? string? (:args command)))))))

(defn- do-call
  [rt p request]
  (require-map :call request)
  (let [command (:command request)]
    (when-not (shell-command? command)
      (throw (ex-info "call requires a non-blank string, a non-empty string vector, or {:program :args}"
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :capability :call :field :command})))
    ;; A missing port is a composition error, not a command that ran and
    ;; failed.  Fail the program before it stages a pending operation.
    (when-not (:call rt)
      (throw (ex-info "Shell call port is unavailable"
                      {:status :UNSUPPORTED_OPERATION
                       :error :UNSUPPORTED_OPERATION
                       :reason :call-port-missing})))
    (when @(:cancelled? p) (throw-cancelled!))
    ;; `call` drains staged file operations before the shell.  The program
    ;; phase is `:draining`, not `:settling`: this is not finalization.
    (set-state! p :draining)
    (let [deadline-at (:at @(:deadline p))
          drained (settle! rt p :draining)]
      (when-not (contains? #{:APPLIED :NO_FILE_FACTS} (:status drained))
        (abort! p (receipt/failed (program-view rt p)
                                  {:status (:status drained)
                                   :error (:status drained)
                                   :message "Protected commit did not apply before call"
                                   :commit (:result drained)}
                                  (:result drained))))
      (when @(:cancelled? p)
        (abort! p (cancelled-result rt p (:result drained))))
      ;; This call has consumed the drain result.  The applied batch stays in
      ;; `:commits`, so a later cancellation may fix a clean terminal again.
      (reset! (:settle-pending p) nil)
      ;; `settle!` cancels the deadline for the protected apply.  A drain is
      ;; not finalization: restore the original wall-clock instant so the
      ;; shell wait is still governed by the intent deadline.  An instant
      ;; already in the past parks at the next checkpoint before the process
      ;; starts.
      (when (and deadline-at
                 (= :APPLIED (:status drained))
                 (not @(:deadline-expired? p)))
        (arm-deadline-until! rt p deadline-at)))
    (let [call-id (str (:call-id p) ":call:" (swap! (:call-counter p) inc))
          pending {:kind :call
                   :id call-id
                   :command command
                   :started-at (now rt)}]
      (swap! (:pending p) conj pending)
      (reset! (:stream-seen p) {:stdout 0 :stderr 0})
      (reset! (:command p)
              {:command command
               :status :queued
               :phase :queued
               :started-at (now rt)
               :stdout ""
               :stderr ""
               :delta ""})
      (set-state! p :waiting)
      ;; The deadline may have expired while the batch drained or while the
      ;; pending command view was being installed.  Park before starting the
      ;; shell; unpark then starts it with a fresh control deadline.
      (checkpoint! rt p)
      (when @(:cancelled? p)
        (throw-cancelled!))
      (swap! (:command p) assoc :status :running :phase :starting)
      ;; Re-check immediately before the port starts the process.  This
      ;; closes the window between the first checkpoint and call-fn.
      (checkpoint! rt p)
      (when @(:cancelled? p)
        (throw-cancelled!))
      (try
        ;; Registration and the cancellation decision share the lock.  A
        ;; cancel that sees no in-flight call may fix the terminal; this
        ;; call then observes the flag and never starts.  A cancel that sees
        ;; the registration must wait for the real call result.
        (locking (:lock p)
          (when @(:cancelled? p)
            (throw-cancelled!))
          (swap! (:inflight p) conj call-id))
        ;; `cancel!` may have observed the registration and requested a
        ;; stop before this point.  Do not start the process after that.
        (when @(:cancelled? p)
          (throw-cancelled!))
        (let [call-fn (:call rt)
              view-cb (fn [view]
                        (when (map? view)
                          (apply-command-view! p view)))
              result (try
                       (call-fn (control-context p)
                                (assoc request
                                       :on-command-view view-cb
                                       :operation-id call-id))
                       (catch Error e
                         ;; Control signals and other Errors must reach the
                         ;; program-level handler, never become a command
                         ;; result.
                         (throw e))
                       (catch InterruptedException e
                         (if @(:cancelled? p)
                           (throw e)
                           (command-failure p e)))
                       (catch Throwable t
                         ;; A started shell that fails after a deadline park
                         ;; must still be resumable.  The failure becomes the
                         ;; command result, not a program-level crash.
                         (command-failure p t)))]
          (reset! (:command p) result)
          (reset! (:delta p) (if (string? (:delta result)) (:delta result) ""))
          (when @(:cancelled? p)
            (cond
              ;; A call that returns without an observed end keeps its
              ;; responsibility.  Report an unconfirmed cancellation instead
              ;; of a clean one; consumers map this to `:outcome-unknown`.
              (uncertain-call-result? result)
              (abort! p
                      (receipt/cancelled-unconfirmed
                       (program-view rt p)
                       result
                       [(:id pending)]))

              ;; A real call failure is not rewritten by the user's stop.
              (failed-call-result? result)
              (let [error (:error result)
                    error (cond
                            (map? error) error
                            (instance? Throwable error)
                            (receipt/throwable->data error)
                            :else
                            {:status :FAILED
                             :error :FAILED
                             :message (or (:message result)
                                          "Managed call failed")})]
                (abort! p
                        (assoc (receipt/failed (program-view rt p)
                                               error
                                               nil)
                             ;; A parked program has no executor task left
                             ;; to post this real failure; the runtime's
                             ;; terminal callback must deliver it.
                               :cancel-requested? true)))

              ;; The call returned a reliable result.  The program did not
              ;; accept it because cancellation was requested; the command
              ;; view records that decision while keeping the exit evidence.
              :else
              (swap! (:command p) assoc
                     :status :CANCELLED
                     :phase :finished
                     :cancelled? true)))
          result)
        (finally
          (swap! (:inflight p) disj call-id)
          (let [op (some #(when (= call-id (:id %)) %) @(:pending p))]
            (swap! (:pending p)
                   (fn [ops] (into [] (remove #(= call-id (:id %))) ops)))
            ;; A port that returned an unconfirmed result may still own a
            ;; process or a write.  Keep the operation visible for a later
            ;; cancellation instead of forgetting it with the in-flight set.
            (when (and op (uncertain-call-result? @(:command p)))
              (swap! (:unsettled p) conj op)))
          (when-not (or (terminal? p) (= :parked @(:state p)))
            (set-state! p :running))))
      (checkpoint! rt p)
      @(:command p))))

(defn- do-control
  [rt p primitive request]
  (require-map primitive request)
  (let [ctx (control-context p)
        control (:control rt)]
    (try
      (let [result
            (cond
              (contains? control :execute!)
              ((:execute! control) ctx {:primitive primitive :request request})

              (contains? control primitive)
              ((get control primitive) ctx request)

              :else
              (throw (ex-info "Control port is unavailable"
                              {:status :CAPABILITY_UNAVAILABLE
                               :error :CAPABILITY_UNAVAILABLE
                               :primitive primitive})))]
        (swap! (:control-results p) conj {:primitive primitive :result result})
        result)
      (catch Throwable t
        (swap! (:control-results p)
               conj {:primitive primitive
                     :error (try (receipt/throwable->data t)
                                 (catch Throwable _ {:class (.getName (class t))}))})
        (throw t)))))

(defn- dispatch
  [capability request]
  (let [rt *runtime*
        p *program*]
    (when-not (and rt p)
      (throw (ex-info "A capability was called outside a program"
                      {:status :UNSUPPORTED_OPERATION
                       :error :UNSUPPORTED_OPERATION
                       :capability capability})))
    (check! rt p capability)
    (case capability
      :cat (do-file-read rt p :cat request)
      :stat (do-file-read rt p :stat request)
      :ls (do-file-read rt p :ls request)
      :write (do-write rt p request)
      :mkdir (do-mkdir rt p request)
      :rm (do-rm rt p request)
      :mv (do-mv rt p request)
      :where (do-where rt p request)
      :whereAll (do-where-all rt p request)
      :park (do-park rt p request)
      :ask (do-ask rt p request)
      :unpark (unpark-capability! rt p request)
      :call (do-call rt p request)
      (do-control rt p capability request))))

(defn- capability-functions
  []
  (into {}
        (map (fn [sym]
               [sym (fn [request] (dispatch (get cap/program-functions sym)
                                            request))]))
        (keys cap/program-functions)))

(defn- checkpoint-function
  []
  (fn []
    (when (and *runtime* *program*)
      (checkpoint! *runtime* *program*))))

;; ---------------------------------------------------------------------------
;; Request normalization and call registry

(def ^:private invalid-workspace (Object.))

(defn- normalize-request
  [request]
  (if-not (map? request)
    {:ok? false
     :status :INVALID_ARGUMENT
     :message "Program request must be a map"
     :details {:actual (some-> request class .getName)}}
    (let [call-id (or (not-empty (some-> (:call-id request) str))
                      (not-empty (some-> (:attempt-id request) str)))
          role (let [r (or (:role request) (:tool request))]
                 (if (string? r) (keyword r) r))
          workspace (let [w (or (:workspace request) (:workspace-id request))]
                      (cond
                        (nil? w) nil
                        (instance? java.nio.file.Path w) (str w)
                        (or (string? w) (keyword? w) (symbol? w)) w
                        :else invalid-workspace))
          source (cond
                   (string? (:source request)) (:source request)
                   (string? (:program request)) (:program request)
                   (some? (:program request))
                   (try
                     (let [p (:program request)]
                       (ufss/write-str
                        (if (and (sequential? p)
                                 (= 1 (count p))
                                 (seq? (first p)))
                          (first p)
                          p)))
                     (catch Throwable _ nil))
                   :else nil)
          intent (:intent request)
          capabilities (:capabilities request)
          on-terminal (:on-terminal request)
          before-terminal (:before-terminal request)]
      (cond
        (not (and (string? call-id) (not (str/blank? call-id))))
        {:ok? false :status :INVALID_ARGUMENT
         :message "Program request requires a non-blank string :call-id"
         :details {:call-id call-id}}

        (not (cap/role? role))
        {:ok? false :status :INVALID_ARGUMENT
         :message "Unknown program role"
         :details {:role role :known-roles (set (keys cap/roles))}}

        (identical? invalid-workspace workspace)
        {:ok? false :status :INVALID_ARGUMENT
         :message ":workspace must be a string, keyword, symbol, Path, or :workspace-id"
         :details {:actual (some-> (:workspace request) class .getName)}}

        (not (string? source))
        {:ok? false :status :INVALID_ARGUMENT
         :message "Program request requires a string :source (or :program)"
         :details {:actual (some-> source class .getName)}}

        (not (string? intent))
        {:ok? false :status :INVALID_ARGUMENT
         :message "Program request requires a string :intent for the deadline"
         :details {:actual (some-> intent class .getName)}}

        (and (some? capabilities)
             (not (or (set? capabilities) (sequential? capabilities))))
        {:ok? false :status :INVALID_ARGUMENT
         :message ":capabilities must be a set or sequence"
         :details {:actual (some-> capabilities class .getName)}}

        (and (some? on-terminal) (not (fn? on-terminal)))
        {:ok? false :status :INVALID_ARGUMENT
         :message ":on-terminal must be a function"
         :details {:actual (some-> on-terminal class .getName)}}

        (and (some? before-terminal) (not (fn? before-terminal)))
        {:ok? false :status :INVALID_ARGUMENT
         :message ":before-terminal must be a function"
         :details {:actual (some-> before-terminal class .getName)}}

        :else
        {:ok? true
         :request (assoc request
                         :call-id call-id
                         :role role
                         :workspace workspace
                         :source source
                         :intent intent
                         :capabilities (cap/narrowed role capabilities)
                         :context (or (:context request) {})
                         :holder? (boolean (:holder? request))
                         :s1? (boolean (:s1? request)))}))))

(defn- begin-call!
  [registry call-id fingerprint]
  (let [p (promise)
        entry {:fingerprint fingerprint
               :status :pending
               :promise p}]
    (loop []
      (let [[old new] (swap-vals! registry
                                  (fn [m]
                                    (if (contains? m call-id)
                                      m
                                      (assoc m call-id entry))))]
        (if (identical? old new)
          (let [existing (get old call-id)]
            (cond
              (not= fingerprint (:fingerprint existing))
              {:role :mismatch :entry existing}

              (= :pending (:status existing))
              {:role :waiter :promise (:promise existing)}

              :else
              {:role :cached :result (:result existing)}))
          {:role :owner :promise p})))))

(defn- finish-call!
  [registry call-id p result]
  (swap! registry update call-id assoc :status :finished :result result)
  (deliver p result)
  result)

;; ---------------------------------------------------------------------------
;; Execution

(defn- register-program!
  [rt p]
  (let [[old new] (swap-vals! (:programs rt)
                              (fn [m]
                                (let [existing (get m (:name p))]
                                  (if (active? existing)
                                    m
                                    (assoc m (:name p) p)))))]
    (if (identical? old new) :taken :ok)))

(defn- failed-result
  [rt p error commit]
  (let [error (if (map? error)
                (try
                  (receipt/sanitize-value error {:max-nodes 2000})
                  (catch Throwable _
                    {:status :FAILED
                     :error :FAILED
                     :message "Program error data was not EDN"
                     :class (some-> error :class)}))
                {:status :FAILED :error :FAILED
                 :message (str error)})]
    (receipt/failed (program-view rt p) error commit)))

(defn- enter-settling!
  "Move an evaluated program into the §4.3 settling transition.

  Evaluation is over, so the deadline is canceled.  Terminal and cancelled
  programs are left untouched; a second call is idempotent."
  [p]
  (when-not (or (terminal? p)
                @(:cancelled? p)
                (= :settling @(:state p)))
    (cancel-deadline! p)
    (set-state! p :settling)))

(defn- run-program!
  [rt p compiled]
  (binding [*runtime* rt
            *program* p
            *ns* (sandbox/sandbox-ns)]
    (try
      (let [f (eval (:fn-form compiled))
            value (f)]
        (when (= :parked @(:state p))
          (await-unpark! rt p))
        (if-let [abort @(:aborted p)]
          (do
            (enter-settling! p)
            (finish-program! rt p abort))
          (when-not (terminal? p)
            ;; Evaluation is over (and any park has resumed).  A late
            ;; deadline must not turn a completed body into a spurious park
            ;; during settlement.
            (enter-settling! p)
            (let [value (receipt/sanitize-value value)
                  facts (workspace-facts rt p)
                  drained (if (empty-facts? facts)
                            {:status :NO_FILE_FACTS :result nil :facts facts}
                            (settle! rt p :settling))]
              (cond
                (= :APPLIED (:status drained))
                (finish-program! rt p
                                 (if @(:cancelled? p)
                                   (cancelled-result rt p (:result drained))
                                   (receipt/completed (program-view rt p)
                                                      value (:result drained)
                                                      facts)))

                (= :NO_FILE_FACTS (:status drained))
                (finish-program! rt p
                                 (if @(:cancelled? p)
                                   (cancelled-result rt p nil)
                                   (receipt/completed (program-view rt p)
                                                      value nil facts)))

                :else
                (finish-program! rt p
                                 (failed-result rt p
                                                {:status (:status drained)
                                                 :error (:status drained)
                                                 :message "Protected commit did not apply"
                                                 :commit (:result drained)}
                                                (:result drained))))))))
      (catch Error e
        (enter-settling! p)
        (cond
          (identical? e abort-signal)
          (finish-program! rt p
                           (or @(:aborted p)
                               (failed-result rt p
                                              (receipt/throwable->data e)
                                              nil)))

          (identical? e cancelled-signal)
          (finish-program! rt p
                           (if @(:cancelled? p)
                             (cancelled-result rt p nil)
                             (failed-result rt p
                                            (receipt/throwable->data e)
                                            nil)))

          :else
          ;; An unrelated Error (for example a program-thrown
          ;; AssertionError) is a real failure, not a control signal.
          (finish-program! rt p
                           (failed-result rt p
                                          (receipt/throwable->data e)
                                          nil))))
      (catch clojure.lang.ExceptionInfo e
        (if-let [abort @(:aborted p)]
          (do
            (enter-settling! p)
            (finish-program! rt p abort))
          (do
            ;; An unrelated ExceptionInfo is a real program failure even
            ;; when the user asked to stop at the same moment.  The
            ;; request is not evidence that this error did not happen.
            (enter-settling! p)
            (finish-program! rt p
                             (failed-result rt p
                                            (merge (receipt/throwable->data e)
                                                   (ex-data e))
                                            nil)))))
      (catch InterruptedException e
        (enter-settling! p)
        (finish-program! rt p
                         (if @(:cancelled? p)
                           (cancelled-result rt p nil)
                           (failed-result rt p (receipt/throwable->data e) nil))))
      (catch Throwable t
        (if-let [abort @(:aborted p)]
          (do
            (enter-settling! p)
            (finish-program! rt p abort))
          (do
            (enter-settling! p)
            (finish-program! rt p
                             (failed-result rt p (receipt/throwable->data t) nil))))))))

(defn- safe-error
  [t]
  (try
    (receipt/sanitize-value (receipt/throwable->data t) {:max-nodes 2000})
    (catch Throwable _
      {:class (.getName (class t))
       :message "Error data was not EDN"})))

(defn- start-thread!
  [rt p compiled]
  (let [thread (Thread. #(run-program! rt p compiled))]
    (reset! (:thread p) thread)
    (.setDaemon thread true)
    (.setName thread (str "hc-program-" (:name p)))
    (try
      (.start thread)
      (catch Throwable t
        (finish-program! rt p (failed-result rt p (receipt/throwable->data t) nil))))))

(defn- execute-owner!
  [rt request]
  (let [compiled (sandbox/compile-program
                  (:source request)
                  {:max-source-bytes (:max-source-bytes rt)})]
    (if-not (:ok? compiled)
      (let [err (:error compiled)]
        (receipt/rejection request
                           (:status err)
                           (:message err)
                           err))
      (let [name (:name compiled)]
        (if (active? (get @(:programs rt) name))
          ;; Fast path: do not allocate a workspace session for a name that
          ;; is already active.  `register-program!` still closes the race
          ;; between this check and a concurrent start.
          (assoc (receipt/rejection request :NAME_TAKEN
                                    "An active program already uses this name"
                                    {:name name})
                 :name name)
          (let [session (if-let [open (get (:workspace rt) :open-session)]
                          (try
                            (open (request-context request name))
                            (catch Throwable t t))
                          nil)]
            (if (instance? Throwable session)
              (receipt/rejection request :FAILED
                                 "Could not open a workspace session"
                                 (safe-error session))
              (let [p (new-program rt request compiled session)]
                (if (= :taken (register-program! rt p))
                  (assoc (receipt/rejection request :NAME_TAKEN
                                            "An active program already uses this name"
                                            {:name name})
                         :name name)
                  (do
                    (try
                      (arm-deadline! rt p (:intent request))
                      (start-thread! rt p compiled)
                      (catch Throwable t
                        (finish-program! rt p
                                         (failed-result rt p
                                                        (receipt/throwable->data t)
                                                        nil))))
                    @(deref (:segment p))))))))))))

(defn- with-transport-receipt
  "Attach the full receipt for executors that keep `:receipts`/`:receipt`.

  The copy excludes the transport fields, so it is acyclic.  Re-wrapping an
  already wrapped result is a no-op."
  [result]
  (if (and (map? result) (not (contains? result :receipt)))
    (let [receipt (dissoc result :receipt :receipts)]
      (assoc result :receipt receipt :receipts [receipt]))
    result))

(defn evaluate-program!
  "Submit one physical program call.

  Returns immediately at the first park or terminal state.  The same
  `:call-id` with the same payload replays the original receipt; a changed
  payload returns `:CALL_REPLAY_MISMATCH`."
  [rt request]
  (with-transport-receipt
   (if @(:stopped? rt)
     (receipt/rejection request :STOPPED "The program runtime is stopped" {})
    (let [normalized (normalize-request request)]
      (if-not (:ok? normalized)
        (receipt/rejection request
                           (:status normalized)
                           (:message normalized)
                           (:details normalized))
        (let [request (:request normalized)
              call-id (:call-id request)
              fingerprint (request-fingerprint request)
              begun (begin-call! (:calls rt) call-id fingerprint)]
          (case (:role begun)
            :mismatch
            (receipt/rejection request :CALL_REPLAY_MISMATCH
                               "The same call-id arrived with a different payload"
                               {:call-id call-id})

            :cached
            (:result begun)

            :waiter
            @(:promise begun)

            :owner
            (let [result (try
                           (execute-owner! rt request)
                           (catch Throwable t
                             (receipt/rejection request :FAILED
                                                "Program execution failed before it started"
                                                (safe-error t))))]
              (finish-call! (:calls rt) call-id (:promise begun) result)))))))))

(defn execute!
  "Alias for `evaluate-program!`."
  [rt request]
  (evaluate-program! rt request))

;; ---------------------------------------------------------------------------
;; Runtime lifecycle and convenience API

(defn- default-schedule
  []
  (let [executor (doto (ScheduledThreadPoolExecutor.
                        1
                        (reify ThreadFactory
                          (newThread [_ r]
                            (doto (Thread. ^Runnable r "hc-program-deadline")
                              (.setDaemon true)))))
                   (.setRemoveOnCancelPolicy true))]
    {:executor executor
     :schedule (fn [delay-ms f]
                 (let [future (.schedule executor
                                         ^Runnable f
                                         (long delay-ms)
                                         TimeUnit/MILLISECONDS)]
                   (fn [] (.cancel future false))))}))

(defn runtime
  "Compose a program runtime.

  Options:
  - `:workspace` port map, or `:workspace-root` / `:workspace-gate` to build
    the reference `hc.core.workspace-gate` adapter
  - `:call` (alias `:shell`) optional `(fn [ctx request] result)` shell port
  - `:control` optional `:execute!` or per-primitive functions
  - `:clock` `(fn [] millis)`, `:schedule` `(fn [delay-ms f] cancel-fn)`
  - `:on-terminal` optional `(fn [result])`
  - `:max-source-bytes` default 1 MiB"
  [opts]
  (let [opts (or opts {})
        workspace (or (:workspace opts)
                      (when (or (:workspace-gate opts) (:workspace-root opts))
                        (workspace-port/workspace-gate-port opts)))
        {:keys [executor schedule]}
        (if (:schedule opts)
          {:executor nil :schedule (:schedule opts)}
          (default-schedule))]
    (sandbox/install-capabilities!
     (assoc (capability-functions)
            'checkpoint! (checkpoint-function)
            'force-result (fn [value] (receipt/sanitize-value value))))
    {:workspace workspace
     :call (or (:call opts) (:shell opts) (:call workspace))
     :call-stop! (:call-stop! opts)
     :control (or (:control opts) {})
     :clock (or (:clock opts) #(System/currentTimeMillis))
     :schedule schedule
     :executor executor
     :max-source-bytes (or (:max-source-bytes opts) sandbox/default-max-source-bytes)
     :on-terminal (:on-terminal opts)
     :programs (atom {})
     :calls (atom {})
     :stopped? (atom false)}))

(declare cancel!)

(defn stop!
  "Stop every active continuation and the deadline executor."
  [rt]
  (reset! (:stopped? rt) true)
  (let [active (filter active? (vals @(:programs rt)))]
    (doseq [p active]
      (cancel! rt (:call-id p)))
    (doseq [p active]
      (when-let [^Thread thread @(:thread p)]
        (try (.join thread 1000) (catch InterruptedException _ nil))))
    (when-let [^ScheduledThreadPoolExecutor executor (:executor rt)]
      (.shutdownNow executor))
    {:status :STOPPED
     :programs (count active)
     ;; `cancel!` defers while a shell call is in flight; the caller must be
     ;; able to see which programs and operations may still be alive.
     :active (mapv (fn [p]
                     {:name (:name p)
                      :call-id (:call-id p)
                      :state @(:state p)
                      :pending-operations (pending-operations p)})
                   active)}))

(defn cancel!
  "Cancel by call-id, attempt-id, or program name.

  A call already inside a protected apply finishes that apply; the result
  then reports cancellation.  A call still inside the `:call` port is not
  evidence that its shell ended: this returns a non-terminal
  `{:status :CANCELLING ...}` acknowledgement and the program stays active
  until the call returns and settles.  Only that real return can fix the
  terminal receipt."
  [rt key]
  (let [{:keys [program ambiguous]} (find-program rt key)]
    (cond
      (seq ambiguous)
      {:status :AMBIGUOUS_TARGET
       :error :AMBIGUOUS_TARGET
       :message "The attempt-id matches more than one program"
       :key key
       :candidates (mapv (fn [p] {:name (:name p) :call-id (:call-id p)})
                         ambiguous)}

      (nil? program)
      {:status :PROGRAM_UNKNOWN
       :error :PROGRAM_UNKNOWN
       :message "No program with that call-id or name is registered"
       :key key}

      (terminal? program)
      @(:result program)

      :else
      (let [p program
            [pending committing? settle-pending unsettled]
            (locking (:lock p)
              (reset! (:cancelled? p) true)
              (cancel-deadline! p)
              [(vec @(:pending p))
               (true? @(:committing? p))
               @(:settle-pending p)
               (vec @(:unsettled p))])]
        ;; Stop managed shell sessions.  The operation id is the shell
        ;; request id when the reference adapter is used.  A stop request is
        ;; not an observation; the call result below is.
        (when-let [stop (:call-stop! rt)]
          (doseq [op (concat pending unsettled)
                  :when (= :call (:kind op))]
            (try
              (stop (control-context p) (:id op))
              (catch Throwable _ nil))))
        (cond
          (or committing? (some? settle-pending))
          ;; Do not interrupt a protected apply, and never fix a clean
          ;; terminal while an applied batch's commit is not yet visible in
          ;; one.  The program thread finishes the current minimal batch and
          ;; reports cancellation with its commit evidence.
          {:status :CANCELLED
           :cancel-after-apply? true
           :name (:name p)
           :call-id (:call-id p)
           :attempt-id (:attempt-id (:request p))
           :settle-status (:status settle-pending)}

          (seq (locking (:lock p) @(:inflight p)))
          ;; A shell call is still in flight.  Fixing a terminal here would
          ;; discard its command state and let the Owner release a process
          ;; that nobody observed end.  Keep the program active; the call's
          ;; real return settles it and the runtime's `:on-terminal` posts
          ;; that observation.
          (let [pending-calls (filterv #(= :call (:kind %)) pending)]
            {:status :CANCELLING
             :cancel-pending? true
             :cancel-requested? true
             :name (:name p)
             :call-id (:call-id p)
             :attempt-id (:attempt-id (:request p))
             :state @(:state p)
             :pending-operations (mapv :id (concat pending-calls unsettled))
             :command @(:command p)})

          (seq unsettled)
          ;; The program's own work can be cancelled, but a call whose port
          ;; returned an unconfirmed result may still own a process or a
          ;; write.  Keep that responsibility visible instead of reporting a
          ;; clean cancellation.
          (let [result (finish-program! rt p
                                        (receipt/cancelled-unconfirmed
                                         (program-view rt p)
                                         @(:command p)
                                         (mapv :id unsettled)))]
            (when-let [park @(:park p)]
              (deliver (:resume park) cancelled-sentinel))
            (when-let [^Thread thread @(:thread p)]
              (.interrupt thread))
            result)

          :else
          (let [result (finish-program! rt p
                                        (receipt/cancelled (program-view rt p) nil))]
            (when-let [park @(:park p)]
              (deliver (:resume park) cancelled-sentinel))
            (when-let [^Thread thread @(:thread p)]
              (.interrupt thread))
            result))))))

(defn program-state
  "Current receipt/state for NAME, or nil."
  [rt name]
  (when-let [{:keys [program]} (find-program rt name)]
    (let [p program]
      {:name (:name p)
       :call-id (:call-id p)
       :attempt-id (:attempt-id (:request p))
       :state @(:state p)
       :receipt (or @(:result p) @(:last-receipt p))
       :result @(:result p)
       :commits (vec @(:commits p))
       :control-results (vec @(:control-results p))})))

(defn name-active?
  [rt name]
  (active? (get @(:programs rt) (str name))))

(defn active-programs
  "All live program descriptors, for an operator cancellation sweep.

  Each entry is `{:name :call-id :attempt-id :state}`.  A parked program is
  still live and appears here until `cancel!` or a later unpark reaches a
  terminal state."
  [rt]
  (->> (vals @(:programs rt))
       (filter active?)
       (mapv (fn [p]
               {:name (:name p)
                :call-id (:call-id p)
                :attempt-id (:attempt-id (:request p))
                :state @(:state p)}))))

(defn unpark!
  "Convenience: execute a synthetic `(unpark {:name ... :value ...})`
  program.  The request is the normal evaluate request plus `:name` and
  `:value`; `:role` defaults to `:clj-worker`."
  [rt request]
  (if-not (map? request)
    (receipt/rejection request :INVALID_ARGUMENT
                       "unpark request must be a map"
                       {:actual (some-> request class .getName)})
    (let [call-id (or (:call-id request)
                    (:attempt-id request)
                    (str "unpark-" (random-uuid)))
        target-name (str (:name request))
        role (or (:role request) :clj-worker)
        intent (or (:intent request) "unpark")
        program-name (str "unpark-" (subs (sha256 (str call-id)) 0 12))
        observed (:observed-park-id request)
        value (try
                (receipt/sanitize-value (:value request))
                (catch Throwable _
                  ::non-edn))
        source (when-not (= ::non-edn value)
                 (str "(defprogram " program-name " [] "
                      "(unpark {:name " (ufss/write-str target-name)
                      (when (some? observed)
                        (str " :observed-park-id " (ufss/write-str observed)))
                      " :value " (ufss/write-str value) "}))"))]
    (if (= ::non-edn value)
      (receipt/rejection request :UNSUPPORTED_OPERATION
                         "unpark value must be EDN data"
                         {:reason :non-edn-value})
      (let [result (evaluate-program! rt
                                      (assoc (dissoc request
                                                     :on-terminal
                                                     :before-terminal)
                                             :call-id call-id
                                             :role role
                                             :source source
                                             :intent intent
                                             :synthetic? true))]
      ;; The synthetic program's body is exactly `(unpark ...)`, so its
      ;; completed value is the target's next receipt.  Return that receipt
      ;; directly; callers do not need the wrapper.
      (if (and (= :COMPLETED (:status result))
               (map? (:value result))
               (contains? (:value result) :status))
        (:value result)
        result))))))

(defn render
  "Render a receipt as EDN+UFSS.  A transport result unwraps its `:receipt`
  first, so the model does not see a duplicate nested copy."
  [result]
  (receipt/render (or (:receipt result) result)))

(defn render-tool-result
  "Render an executor/bridge tool payload as EDN+UFSS for the model.

  Prefer a full program receipt from `:receipt` or the last program-like entry
  in `:receipts`; otherwise render the payload itself.  A non-EDN payload
  falls back to a small printable refusal with correlation fields."
  [payload]
  (let [payload (if (map? payload) payload {:value payload})
        receipt-like? (fn [x]
                        (and (map? x)
                             (contains? x :status)
                             (contains? x :state)
                             (or (contains? x :numbered-source)
                                 (contains? x :reason)
                                 (contains? x :commit)
                                 (contains? x :value)
                                 (= :rejected (:state x)))))
        program-receipt (or (when (receipt-like? (:receipt payload))
                              (:receipt payload))
                            (some (fn [x] (when (receipt-like? x) x))
                                  (reverse (:receipts payload))))
        target (or program-receipt payload)]
    (try
      (ufss/write-str (receipt/sanitize-value target {:max-nodes 20000}))
      (catch Throwable t
        (ufss/write-str
         (cond-> {:status :UNSUPPORTED_OPERATION
                  :error :UNSUPPORTED_OPERATION
                  :message "Tool result could not be rendered as EDN+UFSS"
                  :class (.getName (class t))}
           (map? payload) (merge (select-keys payload
                                               [:call-id :attempt-id :name])
                                 {:original-status (:status payload)})))))))
