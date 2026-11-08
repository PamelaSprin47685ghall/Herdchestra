(ns hc.compose.workspace
  "Protected file program bridge.

  This namespace composes two independent core modules for the executor:

  * `hc.core.workspace-gate` decides what a batch may read and write, holds
    the single write entrance W, re-checks every observed `stamp`, performs
    the complete dry-run and installs the plan atomically;
  * `hc.core.swarm-stm` decides whether the actor and claim are still valid,
    fixes the `:applying` action qualification, and carries the resulting
    responsibility.

  Plan draft commits are the exception: Plan has no swarm actor, claim, or
  task.  The session marks a Plan write with `:plan-draft`; the Owner board
  adapter then asks the mission to validate the pinned Plan tenure and the
  first-write draft claim instead of the swarm board.  W and the dry-run are
  unchanged, and a retired tenure is refused before any file is touched.

  The bridge is deliberately blocking and value-oriented.  `run-program!`
  returns data for every expected rejection; it never throws for a program
  error.  The program evaluator is injected by application assembly
  (`hc.server.program/evaluate-program!` wrapped with its runtime), so this
  compose namespace does not depend on the server layer.

  Public surface:

  * `make-runtime` / `workspace-port` — build the shared W, swarm adapter and
    the evaluator port;
  * `run-program!` — evaluate one program and return the executor result;
  * `execute!` — one structured operation with the same result shape;
  * `request-stop!` / `clear-stop!` — cooperative stop set;
  * `runtime-snapshot` — read-only diagnostics."
  (:require [clojure.string :as str]
            [hc.compose.workspace.board :as board]
            [hc.compose.workspace.error :as werr]
            [hc.compose.workspace.session :as session]
            [hc.core.swarm-stm :as swarm]
            [hc.core.ufss :as ufss]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.stamp :as stamp]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Runtime

(defn make-runtime
  "Compose one workspace runtime.

  Options:
  `:root`             workspace root (required)
  `:kernel`           swarm STM kernel; optional when the Owner functions
                      below are supplied
  `:write-gate`       shared W; a fresh one is created when absent
  `:clock`            `(fn [] epoch-ms)` for receipts
  `:evaluate`         `(fn [request] result)` program evaluator; application
                      assembly injects `(partial hc.server.program/evaluate-program! rt)`
  `:program-runtime`  alias for `:evaluate`
  `:verify-read-set`  override the read-set verifier (tests only)
  `:apply-fn`         apply injection point (tests only)
  `:limits`           batch limits passed to every session
  `:execute-board!`   Owner handle `(fn [command] receipt)` or the blocking
                      `(fn [command timeout-ms opts] receipt)`; when present,
                      every board command goes through the Owner.  The
                      three-argument form carries the opaque
                      `:write-gate-proof` capability for a verified finish.
  `:board-snapshot`   Owner handle `(fn [] board)`

  The kernel's verifier is set to `workspace-gate/read-set-verifier` for this
  root unless an override is supplied.  W and the kernel are shared with the
  rest of the swarm; the bridge never creates a second W.

  With `:execute-board!`/`:board-snapshot`, a commit carrying `:plan-draft`
  is a Plan draft write: the mission validates the pinned Plan tenure, the
  first-review write lock and the draft claim instead of requiring a swarm
  actor.  Every other commit keeps the full board eligibility and
  publication lifecycle."
  [{:keys [root kernel write-gate clock evaluate program-runtime
           verify-read-set apply-fn execute-board! board-snapshot limits]
    :or {clock (fn [] (System/currentTimeMillis))}}]
  (when-not root
    (throw (IllegalArgumentException. "make-runtime requires :root")))
  (when-not (or kernel (and execute-board! board-snapshot))
    (throw (IllegalArgumentException.
            "make-runtime requires :kernel or both :execute-board! and :board-snapshot")))
  (let [root (str (stamp/root-path root))
        gate (or write-gate (wg/write-gate))
        verifier (or verify-read-set (wg/read-set-verifier root))
        kernel (when kernel
                 (assoc-in kernel [:env :verify-read-set] verifier))
        board-adapter (if (and execute-board! board-snapshot)
                        (board/owner-adapter execute-board! board-snapshot)
                        (board/adapter kernel))
        workspace-gate (wg/workspace-gate {:root root
                                           :gate gate
                                           :board board-adapter
                                           :apply-fn apply-fn
                                           :limits limits
                                           :publish-retries 5})]
    {:root root
     :kernel kernel
     :gate gate
     :wg workspace-gate
     :execute-board! execute-board!
     :board-snapshot board-snapshot
     :verify-read-set verifier
     :limits limits
     :stops (atom #{})
     :clock clock
     :evaluate (or evaluate program-runtime)
     :closed? (atom false)
     :sessions (atom {})
     ;; The evaluator may omit :attempt-id from the session context.  Owner
     ;; stops by attempt-id, so keep the mapping and stop both identities.
     :attempt-calls (atom {})
     ;; stop-key -> request-ids currently waiting for W.  A cooperative stop
     ;; is forwarded to workspace-gate so a queued settle can leave the queue
     ;; before applying; an already applying batch still completes.
     :pending-settles (atom {})
     ;; Terminal sessions are removed to avoid leaks, but a replayed call-id
     ;; still needs its receipts/effects.  Keep a bounded evidence cache.
     :terminal-evidence (atom {})
     ;; call-id/attempt-id -> [{:request-id :path}] first-write claims that
     ;; still need release on a failed terminal.
     :draft-claims (atom {})}))

(defn request-stop!
  "Register a cooperative stop for ATTEMPT-ID.

  The evaluator observes the stop between capability calls; no thread is
  interrupted and an already applied commit is never rolled back.  A stop
  request is not an observation that the action ended, so this function
  never claims one.

  Returns `{:status ... :attempt-id ... :call-id ... :ended? false}`:

  * `:stop-registered` — the stop is recorded and forwarded to any queued
    settle; the action may still be running, and its terminal comes only
    from the evaluator's own result.
  * `:unknown` — no attempt id, or the runtime is closed; nothing was
    recorded.

  `:call-id` is the call the stop was forwarded to while that mapping is
  live, and nil otherwise.  `:ended?` is always false.  The bridge keeps
  receipts for replay in `:terminal-evidence`, but it does not observe an
  external end here, so a caller must not synthesize a call terminal from
  this result.  Only the executor's own terminal is evidence."
  [runtime attempt-id]
  (if (or (nil? attempt-id) @(:closed? runtime))
    {:status :unknown
     :attempt-id attempt-id
     :call-id nil
     :ended? false}
    (let [call-id (get @(:attempt-calls runtime) attempt-id)]
      (swap! (:stops runtime) conj attempt-id)
      (when call-id
        (swap! (:stops runtime) conj call-id))
      (doseq [key (distinct (keep identity [attempt-id call-id]))
              rid (get @(:pending-settles runtime) key)]
        (wg/request-stop! (:wg runtime) rid))
      {:status :stop-registered
       :attempt-id attempt-id
       :call-id call-id
       :ended? false})))

(defn clear-stop!
  [runtime attempt-id]
  (if (or (nil? attempt-id) @(:closed? runtime))
    :unknown
    (do (swap! (:stops runtime) disj attempt-id)
        (when-let [call-id (get @(:attempt-calls runtime) attempt-id)]
          (swap! (:stops runtime) disj call-id))
        (doseq [key (distinct (keep identity [attempt-id
                                              (get @(:attempt-calls runtime)
                                                   attempt-id)]))
                rid (get @(:pending-settles runtime) key)]
          (wg/clear-stop! (:wg runtime) rid))
        :ok)))

(defn runtime-snapshot
  "Read-only observation for Owner diagnostics.  Never mutates the runtime."
  [runtime]
  {:stopped (set @(:stops runtime))
   :write-gate-held? (wg/write-gate-held? (:gate runtime))
   :write-gate-owner (wg/write-gate-owner (:gate runtime))
   :board-version (if-let [f (:board-snapshot runtime)]
                    (:board-version (f))
                    (swarm/board-version (:kernel runtime)))})

;; ---------------------------------------------------------------------------
;; Workspace port for the program evaluator

(defn- synthetic-unpark-name?
  "The program runtime names its synthetic unpark wrapper
  `unpark-<12 lowercase hex>`.  A user program named `unpark-foo` is real
  and must keep its session."
  [name]
  (boolean (re-matches #"unpark-[0-9a-f]{12}" (str name))))

(defn- session-keys
  "Keys under which a program session is registered.

  Program names are not aliases: the runtime's synthetic unpark wrapper
  would otherwise leave a program-name key behind.  A synthetic unpark
  wrapper opens a session but never uses file/control capabilities, so it is
  not registered at all; the target keeps its call-id/attempt-id aliases
  until `:on-terminal` removes them."
  [ctx]
  (if (synthetic-unpark-name? (:program-name ctx))
    []
    (distinct (keep ctx [:call-id :attempt-id]))))

(defn workspace-port
  "Return the plain map expected by `hc.server.program/runtime`.

  The evaluator owns `defprogram`, `where`, `park`, `unpark` and `call`; this
  port owns the file/session half.  `open-session` registers the session by
  call-id so the blocking `run-program!` can collect receipts after the
  evaluator returns at a park or terminal."
  [runtime]
  (let [sessions (:sessions runtime)
        open! (fn [ctx]
                (let [s (session/open runtime ctx)]
                  (doseq [k (session-keys ctx)]
                    (swap! sessions assoc k s))
                  s))]
    {:open-session open!
     :cat (fn [s path] (session/cat s path))
     :stat (fn [s path] (session/stat s path))
     :ls (fn [s path] (session/ls s path))
     :write (fn [s path content] (session/write s path content))
     :mkdir (fn [s path] (session/mkdir s path))
     :rm (fn [s path] (session/rm s path))
     :mv (fn [s source target opts] (session/mv s source target (or opts {})))
     :facts (fn [s] (session/facts s))
     :settle! (fn [s request] (session/settle! s request))
     ;; Bridge-only extension: board primitives.  The evaluator may bind
     ;; `split`/`claim`/... to this function; it is not a file capability.
     :board-command (fn [s request] (session/board-command s request))
     :execute-board! (fn [s request] (session/board-command s request))}))

(defn- session-for
  [runtime ctx]
  (or (get @(:sessions runtime) (:call-id ctx))
      (get @(:sessions runtime) (:attempt-id ctx))
      (get @(:sessions runtime) (:program-name ctx))
      (let [s (session/open runtime ctx)]
        (doseq [k (session-keys ctx)]
          (swap! (:sessions runtime) assoc k s))
        s)))

(defn control-port
  "Return the `:control` map expected by `hc.server.program/runtime`.

  `:execute!` receives `(ctx {:primitive kw :request map})` and returns the
  board primitive's result verbatim.  Only worker board primitives are
  handled here; `:review`, `:devops`, `:abandon`, `:askPreviousScope`,
  `:negotiate` and `:assume` belong to other composition layers.  When a
  `fallback` function is supplied it is called for those primitives;
  otherwise the bridge returns an explicit `:UNSUPPORTED_OPERATION` value."
  ([runtime] (control-port runtime nil))
  ([runtime fallback]
   {:execute!
    (fn [ctx {:keys [primitive request]}]
      (let [session (session-for runtime ctx)]
        (if (session/program-op? primitive)
          (session/board-command session
                                 (assoc (or request {}) :op primitive))
          (if fallback
            (fallback ctx {:primitive primitive :request request})
            {:ok? false
             :error :UNSUPPORTED_OPERATION
             :detail {:primitive primitive
                      :reason "not owned by the workspace bridge"}}))))}))

;; ---------------------------------------------------------------------------
;; Result shaping

(defn- success
  [value receipts effects]
  {:status :succeeded
   :value value
   :receipts (vec receipts)
   :effects (vec effects)
   :error nil})

(defn- failure
  [result receipts effects]
  {:status :failed
   :value nil
   :receipts (vec receipts)
   :effects (vec effects)
   :error (werr/error-data result)})

(defn- cancelled
  [result receipts effects]
  {:status :cancelled
   :value nil
   :receipts (vec receipts)
   :effects (vec effects)
   :error (werr/error-data result)})

(defn- unknown
  [result receipts effects]
  {:status :outcome-unknown
   :value nil
   :receipts (vec receipts)
   :effects (vec effects)
   :error (werr/error-data result)})

(defn- normalize-status
  [status]
  (case status
    (:COMPLETED :completed :succeeded :ok) :succeeded
    (:PARKED :parked) :succeeded
    (:FAILED :failed) :failed
    (:cancelled :CANCELLED :canceled :CANCELED) :cancelled
    (:OUTCOME_UNKNOWN :outcome-unknown :unknown) :outcome-unknown
    (:NAME_TAKEN :STALE_CONTROL :INVALID_ARGUMENT) :failed
    :failed))

(defn- merge-receipts
  [session result]
  (distinct
   (concat (when session (session/receipts session))
           (:receipts result))))

(defn- merge-effects
  [session result]
  (distinct
   (concat (when session (session/effects session))
           (:effects result))))

(defn- evaluator-error
  [result]
  (when-let [error (:error result)]
    (cond
      (map? error) error
      (instance? Throwable error) (werr/from-throwable error)
      :else {:code (or (:status result) :PROGRAM_FAILED)
             :message (str error)
             :details {}
             :retryable? false})))

(defn- remember-terminal-evidence!
  [runtime session result]
  (when (and session (:call-id result))
    (swap! (:terminal-evidence runtime)
           (fn [m]
             (let [m (assoc m (:call-id result)
                            {:receipts (session/receipts session)
                             :effects (session/effects session)})]
               (if (> (count m) 128)
                 (into {} (take 128 (seq m)))
                 m))))))

(defn- terminal-session-cleanup
  "Return the evaluator `:before-terminal` barrier for one program.

  A parked target is resumed by a different `run-program!` call, so the
  wrapper cannot know the target's keys when it returns.  The runtime runs
  this barrier before publishing the terminal segment, so an `unpark!`
  caller cannot observe completion before cleanup.  Preserve the session's
  receipts/effects for call-id replay, then remove the target's
  call-id/attempt-id aliases.  The barrier must stay non-blocking and must
  not reenter the runtime."
  [runtime]
  (fn [result]
    (let [keys (vec (distinct (keep identity [(:call-id result)
                                               (:attempt-id result)
                                               (:name result)])))
          key-set (set keys)
          session (some (fn [k] (get @(:sessions runtime) k)) keys)]
      (remember-terminal-evidence! runtime session result)
      (swap! (:sessions runtime)
             (fn [m]
               ;; A parked target can be resumed under a different
               ;; call-id/attempt-id.  The terminal result may name only the
               ;; wrapper ids, so remove every alias that still points at
               ;; the same session object, not just the result keys.
               (reduce-kv (fn [acc k v]
                            (if (or (contains? key-set k)
                                    (and session (identical? session v)))
                              (dissoc acc k)
                              acc))
                          m
                          m)))
      (when-let [attempt-id (:attempt-id result)]
        (swap! (:attempt-calls runtime) dissoc attempt-id))
      nil)))

(defn- release-draft-terminal!
  "Release failed first-write claims after the terminal segment is delivered.

  This is an `:on-terminal` notification, not the `:before-terminal` barrier:
  the Owner command may block, so it must not delay the terminal receipt.
  A successful terminal keeps the Owner claim for the app's final draft sync."
  [runtime result]
  (let [keys (distinct (keep identity [(:call-id result)
                                      (:attempt-id result)
                                      (:name result)]))]
    (session/release-draft-claims! runtime keys (:status result))))

(defn- shape-evaluator-result
  [session result]
  (let [status (normalize-status (:status result))
        receipts (merge-receipts session result)
        effects (merge-effects session result)
        value (:value result)
        parked? (contains? #{:PARKED :parked} (:status result))
        evaluator-error (evaluator-error result)
        stopped? (or (true? (:cancelled? evaluator-error))
                     (= :STOP_REQUESTED (:code evaluator-error)))]
    (cond
      (= :succeeded status)
      (cond-> (success value receipts effects)
        parked? (assoc :parked? true
                       ;; The evaluator returns its park receipt directly.
                       ;; Keep the full payload (candidates/nearest/state)
                       ;; so the Owner can project it to the model.
                       :value (or value (:receipt result) result)))

      (or (= :cancelled status) stopped?)
      (cancelled (or (:result result)
                     evaluator-error
                     {:status :CANCELLED
                      :code :STOP_REQUESTED
                      :message "The program was stopped"})
                 receipts effects)

      (= :outcome-unknown status)
      (unknown (or (:result result)
                   (:receipt result)
                   {:status :HARD_FAILURE
                    :outcome :unknown
                    :message "The program outcome is unknown"})
               receipts effects)

      :else
      (failure (or (:result result)
                   evaluator-error
                   {:status :PROGRAM_FAILED
                    :code (or (:code evaluator-error) :PROGRAM_FAILED)
                    :message (or (:message evaluator-error)
                                 "The program failed")
                    :outcome :not-written
                    :file-changed? false})
               receipts effects))))

(defn retry-publish!
  "Retry only the board publication of an already-applied request.

  Never reruns the program or the file batch.  The Owner calls this after an
  `:APPLIED :published? false` / `:retry :publish-only` result."
  [runtime request-id]
  (if (or (nil? request-id)
          (and (string? request-id) (str/blank? request-id)))
    (failure {:status :INVALID_ARGUMENT
              :code :request-id-required
              :message "retry-publish! requires a request-id"
              :outcome :not-written
              :file-changed? false}
             [] [])
    (let [result (wg/retry-publish! (:wg runtime) request-id)
          receipt {:type :workspace/retry-publish
                   :request-id request-id
                   :result result}
          effect {:type :files/published
                  :request-id request-id}]
      (cond
        (= :APPLIED (:status result))
        (success (dissoc result :batch) [receipt] [effect])

        (= :HARD_FAILURE (:status result))
        (unknown result [receipt] [effect])

        :else
        (failure result [receipt] [])))))

;; ---------------------------------------------------------------------------
;; Program execution
;; ---------------------------------------------------------------------------
;; Program execution

(defn- blank?
  [x]
  (or (nil? x) (and (string? x) (str/blank? x))))

(defn run-program!
  "Evaluate one program under the caller's agent and claim identity.

  PAYLOAD:
  `{:agent-id :claim-id :task-id :attempt-id :program|:source ...}` plus the
  evaluator fields `:call-id :role :intent :tenure-id :lane :workspace
  :holder? :s1? :phase :context` and the Plan context fields `:draft-path
  :write-unlocked? :readable-paths`.
  The Owner carries those Plan fields at the top level of the tool payload;
  they must reach the program's `:context` or Plan cannot read its
  attachments or write its draft.

  The Owner also pins `:tenure-id` and `:lane` at the top level of the tool
  payload.  They are copied into the evaluator request unchanged because the
  program control context (`review`, draft commands, board commands) must
  name the same tenure as the model call that produced the tool call.  A
  late tool call therefore stays bound to its own tenure and is rejected
  there after a relay, instead of being re-resolved onto the successor.

  Returns exactly `{:status :value :receipts :effects :error}`.  Expected
  program and file rejections are data, never exceptions.  The evaluator is
  injected at assembly time; without one the call fails with
  `:PROGRAM_RUNTIME_UNAVAILABLE`."
  [runtime payload]
  (if (= :retry-publish (:op payload))
    (retry-publish! runtime (:request-id payload))
    (let [{:keys [agent-id attempt-id program source call-id role intent
                holder? s1? phase capabilities context]} payload
        call-id (or call-id attempt-id)
        source-text (let [x (or program source)]
                      (when (some? x)
                        (cond
                          (string? x) x
                          ;; The Owner may hand over already-read EDN forms.
                          ;; The runtime compiles one `defprogram` form.
                          (sequential? x)
                          (when (seq x)
                            (if (sequential? (first x))
                              ;; A sequence of forms: the runtime compiles
                              ;; the first `defprogram`.
                              (ufss/write-str (first x))
                              ;; A single `(defprogram ...)` form.
                              (ufss/write-str x)))
                          :else (ufss/write-str x))))
        ;; The §4.3 deadline is derived from the UTF-8 byte length of the
        ;; model's `:intent`.  The Owner payload may omit it; the source is a
        ;; conservative non-empty fallback so a real program is not rejected
        ;; before it starts.  Callers that care about the budget should send
        ;; the model's intent verbatim.
        intent-text (if (string? intent) intent (or source-text ""))
        plan-context (merge
                      (select-keys (or context {})
                                   [:draft-path :write-unlocked? :readable-paths])
                      (cond-> {}
                        (contains? payload :draft-path)
                        (assoc :draft-path (:draft-path payload))

                        (contains? payload :write-unlocked?)
                        (assoc :write-unlocked?
                               (boolean (:write-unlocked? payload)))

                        (contains? payload :readable-paths)
                        (assoc :readable-paths (:readable-paths payload))))]
    (cond
      (blank? agent-id)
      (failure {:status :INVALID_ARGUMENT
                :code :agent-id-required
                :message "run-program! requires a caller agent-id"
                :outcome :not-written
                :file-changed? false}
               [] [])

      (nil? source-text)
      (failure {:status :INVALID_ARGUMENT
                :code :program-required
                :message "run-program! requires :program or :source"
                :outcome :not-written
                :file-changed? false}
               [] [])

      (nil? (:evaluate runtime))
      (failure {:status :FAILED
                :code :PROGRAM_RUNTIME_UNAVAILABLE
                :message "No program evaluator was injected into make-runtime"
                :outcome :not-written
                :file-changed? false}
               [] [])

      (contains? @(:stops runtime) (or attempt-id call-id))
      (cancelled {:status :CANCELLED
                  :code :STOP_REQUESTED
                  :message "The program was stopped before it started"
                  :cancelled? true}
                 [] [])

      :else
      (let [call-id (or call-id (str "program-" (System/nanoTime)))
            request (merge
                     {:call-id call-id
                      ;; A holder is a clj-worker with holder? true.  Never
                      ;; let an app mapping error hand it the dedicated
                      ;; worker capability set (no write/split/claim/finish)
                      ;; or skip the holder Shell gate.
                      :role (if holder? :clj-worker
                                (or role :clj-worker))
                      :source source-text
                      :intent intent-text
                      :agent-id agent-id
                      :claim-id (:claim-id payload)
                      :task-id (:task-id payload)
                      :attempt-id (or attempt-id call-id)
                      ;; The Owner pins the lane tenure on the model call and
                      ;; carries it verbatim on the tool request.  The
                      ;; program control context selects these keys; dropping
                      ;; them made every `(review ...)` primitive submit a nil
                      ;; tenure and fail with `:INVALID_REQUEST`.
                      :tenure-id (:tenure-id payload)
                      :lane (:lane payload)
                      :workspace (:workspace payload)
                      :holder? (boolean holder?)
                      :s1? (boolean s1?)
                      :phase (or phase :executing)
                      :capabilities capabilities
                      :before-terminal (terminal-session-cleanup runtime)
                      :on-terminal (fn [result]
                                    (release-draft-terminal! runtime result))
                      :context (merge (or context {})
                                      plan-context
                                      {:attempt-id (or attempt-id call-id)
                                       :call-id (or call-id attempt-id)})}
                     ;; Keep the Plan policy in the request fingerprint as
                     ;; well as the program context: two calls that differ
                     ;; only in draft/unlock/read policy are not replays.
                     plan-context)
            _ (when attempt-id
                (swap! (:attempt-calls runtime) assoc attempt-id call-id))
            result (try
                     ((:evaluate runtime) request)
                     (catch clojure.lang.ExceptionInfo e
                       (if (werr/stop-signal? e)
                         {:status :cancelled
                          :error (werr/from-throwable e)}
                         (let [data (ex-data e)]
                           {:status (if (or (= :unknown (:outcome data))
                                            (= :unknown (:file-changed? data)))
                                      :outcome-unknown
                                      :FAILED)
                            :error (werr/from-throwable e)})))
                     (catch Throwable t
                       {:status :outcome-unknown
                        :error (werr/from-throwable t)}))
            session (or (get @(:sessions runtime) (:call-id request))
                        (get @(:sessions runtime) (:attempt-id request)))
            evidence (when-not session
                       (or (get @(:terminal-evidence runtime)
                                (:call-id request))
                           (get @(:terminal-evidence runtime)
                                (:attempt-id request))))
            session (or session
                        (when evidence
                          {:receipts (atom (:receipts evidence))
                           :effects (atom (:effects evidence))}))]
        (let [out (shape-evaluator-result session result)]
          (when-not (:parked? out)
            (remember-terminal-evidence! runtime session result)
            (swap! (:sessions runtime)
                   (fn [m]
                     (reduce dissoc m (distinct (keep request
                                                       [:call-id :attempt-id]))))))
          (when (and attempt-id (not (:parked? out)))
            (swap! (:attempt-calls runtime) dissoc attempt-id))
          out))))))

;; ---------------------------------------------------------------------------
;; One structured operation

(defn- one-shot-session
  [runtime request]
  (session/open runtime
                {:agent-id (:agent-id request)
                 :claim-id (:claim-id request)
                 :task-id (:task-id request)
                 :attempt-id (or (:attempt-id request) (:call-id request))
                 :call-id (or (:call-id request) (:attempt-id request))
                 :program-name "execute!"
                 :role (:role request)
                 :tenure-id (:tenure-id request)
                 :lane (:lane request)
                 :draft-path (:draft-path request)
                 :write-unlocked? (:write-unlocked? request)}))

(defn- execute-file-read
  [runtime request]
  (case (:op request)
    :cat
    (let [result (wg/read-file (:root runtime) (:path request))]
      (if (= :FILE_CONFLICT (:status result))
        (failure result
                 [(assoc (dissoc result :text :bytes)
                         :type :file/read)]
                 [])
        (success (:text result)
                 [(assoc (dissoc result :text :bytes)
                         :type :file/read)]
                 [])))

    :stat
    (let [result (wg/stat-path (:root runtime) (:path request))]
      (success result
               [(assoc result :type :file/stat)]
               []))

    :ls
    (let [result (wg/list-dir (:root runtime) (:path request))]
      (success result
               [{:type :file/list :path (:path request) :entries (count result)}]
               []))))

(defn- execute-file-mutation
  [runtime request]
  (let [s (one-shot-session runtime request)]
    (try
      (case (:op request)
        :write (session/write s (:path request) (:content request))
        :mkdir (session/mkdir s (:path request))
        :rm (session/rm s (:path request))
        :mv (session/mv s (:source request) (:target request)
                        (select-keys request [:overwrite?]))
        nil)
      (let [{:keys [status session result]}
            (session/settle! s {:request-id (or (:request-id request)
                                                (str (or (:attempt-id request)
                                                         "execute")
                                                     ":settle:0"))
                                :agent-id (:agent-id request)
                                :claim-id (:claim-id request)
                                :task-id (:task-id request)
                                :actor-id (:agent-id request)})]
        (cond
          (= :APPLIED status)
          (success (select-keys result [:status :op-count :file-changed?
                                        :published? :retry])
                   (session/receipts session)
                   (session/effects session))

          (= :HARD_FAILURE status)
          (unknown result (session/receipts session) (session/effects session))

          (werr/cancelled? result)
          (cancelled result (session/receipts session) (session/effects session))

          :else
          (failure result (session/receipts session) (session/effects session))))
      (catch clojure.lang.ExceptionInfo e
        (if (or (werr/stop-signal? e) (:cancelled? (ex-data e)))
          (cancelled (ex-data e) (session/receipts s) (session/effects s))
          (failure (ex-data e) (session/receipts s) (session/effects s))))
      (catch Throwable t
        (unknown {:status :HARD_FAILURE
                  :outcome :unknown
                  :message (.getMessage t)}
                 (session/receipts s) (session/effects s))))))

(defn- execute-board-op
  [runtime request]
  (let [s (one-shot-session runtime request)]
    (try
      (let [result (if (= :resolve-action (:op request))
                     (session/resolve-action! s request)
                     (session/board-command s (dissoc request :agent-id)))]
        (cond
          (:ok? result) (success result (session/receipts s) (session/effects s))
          (werr/cancelled? result) (cancelled result (session/receipts s)
                                              (session/effects s))
          :else (failure result (session/receipts s) (session/effects s))))
      (catch clojure.lang.ExceptionInfo e
        (if (or (werr/stop-signal? e) (:cancelled? (ex-data e)))
          (cancelled (ex-data e) (session/receipts s) (session/effects s))
          (failure (ex-data e) (session/receipts s) (session/effects s))))
      (catch Throwable t
        (failure {:status :INTERNAL
                  :code :INTERNAL
                  :message (.getMessage t)
                  :outcome :not-written
                  :file-changed? false}
                 (session/receipts s) (session/effects s))))))

(def ^:private file-read-ops #{:cat :stat :ls})
(def ^:private file-mutation-ops #{:write :mkdir :rm :mv})
(def ^:private board-ops
  #{:split :claim :finish :cancel :replace :release :reopen
    :comment :update :take-or-die :park-slot :resolve-action
    :acknowledge-recovery})

(defn execute!
  "Run one structured operation with the same result shape as
  `run-program!`.

  File reads are lock-free observations.  File mutations are staged into a
  one-shot batch and settled through the full protected contract.  Board
  commands always use the caller's actor identity; a file-dependent `finish`
  is routed under W with the read-set verifier."
  [runtime request]
  (cond
    (= :retry-publish (:op request))
    (retry-publish! runtime (:request-id request))

    (blank? (:agent-id request))
    (failure {:status :INVALID_ARGUMENT
              :code :agent-id-required
              :message "execute! requires a caller agent-id"
              :outcome :not-written
              :file-changed? false}
             [] [])

    (contains? file-read-ops (:op request))
    (try
      (execute-file-read runtime request)
      (catch clojure.lang.ExceptionInfo e
        (failure (ex-data e) [] []))
      (catch Throwable t
        (failure {:status :INTERNAL :code :INTERNAL
                  :message (.getMessage t)
                  :outcome :not-written :file-changed? false}
                 [] [])))

    (contains? file-mutation-ops (:op request))
    (execute-file-mutation runtime request)

    (contains? board-ops (:op request))
    (execute-board-op runtime request)

    :else
    (failure {:status :UNSUPPORTED_OPERATION
              :code :unsupported-operation
              :message "Unknown workspace operation"
              :outcome :not-written
              :file-changed? false}
             [] [])))
