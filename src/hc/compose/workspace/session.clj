(ns hc.compose.workspace.session
  "One program's protected workspace session.

  A session owns a `workspace-gate` batch view, the run identity and the
  ordered receipts.  Reads never take W; staged writes stay in memory until
  `settle!`; `settle!` takes W, verifies the complete read-set, fixes the
  `:applying` qualification in the swarm board, installs the batch and
  publishes the action result.

  The namespace never exposes the raw `workspace-gate` session to the
  program.  Every file capability goes through this adapter, which is what
  makes a bypass impossible from the program surface."
  (:refer-clojure :exclude [cat])
  (:require [clojure.string :as str]
            [hc.compose.mission.control :as control]
            [hc.compose.mission.proof :as proof]
            [hc.compose.workspace.board :as board]
            [hc.compose.workspace.error :as werr]
            [hc.core.swarm-stm :as swarm]
            [hc.core.workspace-gate :as wg]
            [hc.core.workspace-gate.error :as err]
            [hc.core.workspace-gate.stamp :as stamp]))

;; ---------------------------------------------------------------------------
;; Identity and stop observation

(defn- id
  "Board ids are keywords in the STM.  Models and transport envelopes often
  carry them as strings, so accept both without changing keyword ids.  A
  leading colon is tolerated because it is a common transport artifact."
  [x]
  (if (string? x)
    (keyword (if (str/starts-with? x ":") (subs x 1) x))
    x))

(defn- normalize-ctx
  [ctx]
  (cond-> ctx
    (contains? ctx :agent-id) (update :agent-id id)
    (contains? ctx :claim-id) (update :claim-id id)
    (contains? ctx :task-id) (update :task-id id)))

(defn- context
  [session]
  @(:ctx session))

(defn open
  "Create one program's batch session.

  CTX carries the caller identity and the evaluator's call identity:
  `:agent-id :claim-id :task-id :attempt-id :call-id :program-name :role`."
  [runtime ctx]
  ;; The session stores a print-safe runtime view.  Keeping the full runtime
  ;; here would create a cycle: runtime -> :sessions -> session -> runtime,
  ;; and any `pr-str` of a receipt or result would overflow the stack.
  {:runtime (dissoc runtime :sessions :evaluate)
   :batch (wg/session (:root runtime) (:limits runtime))
   :ctx (atom (normalize-ctx
               (merge {:agent-id nil
                       :claim-id nil
                       :task-id nil
                       :attempt-id nil
                       :call-id nil
                       :program-name nil
                       :role nil}
                      ctx)))
   :receipts (atom [])
   :effects (atom [])
   :settle-seq (atom 0)
   :dead? (atom false)})

(defn- stop-key
  [session]
  (or (:attempt-id (context session))
      (:call-id (context session))))

(defn- stopped?
  [session]
  (let [rt (:runtime session)
        key (stop-key session)]
    (and key (contains? @(:stops rt) key))))

(defn check-live!
  "Reject before the next capability call when the program was stopped or
  its actor died.  A stop is cooperative: it is observed only here, never by
  interrupting the evaluating thread."
  [session]
  (when (stopped? session)
    (werr/stop-signal (stop-key session)))
  (when @(:dead? session)
    (err/fail! :ACTOR_INACTIVE :actor-dead
               "The program's actor is no longer alive"
               {:agent-id (:agent-id (context session))}))
  nil)

;; ---------------------------------------------------------------------------
;; Receipts and effects

(defn record-receipt!
  [session receipt]
  (swap! (:receipts session) conj receipt)
  receipt)

(defn record-effect!
  [session effect]
  (swap! (:effects session) conj effect)
  effect)

(defn receipts [session] @(:receipts session))
(defn effects [session] @(:effects session))

(defn- record-rejection!
  [session type path t]
  (when (err/expected-failure? t)
    (record-receipt! session
                      (assoc (ex-data t)
                             :type type
                             :path path
                             :at-millis ((:clock (:runtime session))))))
  t)

(defn- with-receipt
  [session type path f]
  (try
    (let [value (f)]
      (record-receipt! session
                        {:type type
                         :path path
                         :status :OBSERVED
                         :at-millis ((:clock (:runtime session)))})
      value)
    (catch clojure.lang.ExceptionInfo e
      (record-rejection! session type path e)
      (throw e))))

(defn- observation-of
  [session path]
  (let [p (stamp/path-string (:root (:runtime session)) path)]
    (last (get-in (wg/snapshot (:batch session)) [:read-set p]))))

;; ---------------------------------------------------------------------------
;; File capabilities (the :workspace port shape)

(defn cat
  "Return UTF-8 text.  The read enters the batch read-set."
  [session path]
  (check-live! session)
  (let [text (with-receipt session :file/read path
               #(wg/cat (:batch session) path))
        observation (observation-of session path)]
    (record-receipt! session
                      {:type :file/stamp
                       :path (:path observation)
                       :status (if (:stamp observation) :OBSERVED :MISSING)
                       :stamp (:stamp observation)
                       :changed-during-read? (boolean (:unstable? observation))
                       :at-millis ((:clock (:runtime session)))})
    text))

(defn stat
  [session path]
  (check-live! session)
  (with-receipt session :file/stat path
    #(wg/stat (:batch session) path)))

(defn ls
  [session path]
  (check-live! session)
  (with-receipt session :file/list path
    #(wg/ls (:batch session) path)))

(defn write
  [session path content]
  (check-live! session)
  (let [staged (try
                 (wg/write (:batch session) path content)
                 (catch clojure.lang.ExceptionInfo e
                   (record-rejection! session :file/write path e)
                   (throw e)))]
    (record-receipt! session
                      (assoc staged
                             :type :file/staged
                             :op :write
                             :at-millis ((:clock (:runtime session)))))
    staged))

(defn mkdir
  [session path]
  (check-live! session)
  (let [staged (try
                 (wg/mkdir (:batch session) path)
                 (catch clojure.lang.ExceptionInfo e
                   (record-rejection! session :file/mkdir path e)
                   (throw e)))]
    (record-receipt! session
                      (assoc staged :type :file/staged :op :mkdir
                             :at-millis ((:clock (:runtime session)))))
    staged))

(defn rm
  [session path]
  (check-live! session)
  (let [staged (try
                 (wg/rm (:batch session) path)
                 (catch clojure.lang.ExceptionInfo e
                   (record-rejection! session :file/rm path e)
                   (throw e)))]
    (record-receipt! session
                      (assoc staged :type :file/staged :op :rm
                             :at-millis ((:clock (:runtime session)))))
    staged))

(defn mv
  ([session source target] (mv session source target {}))
  ([session source target opts]
   (check-live! session)
   (let [staged (try
                  (wg/mv (:batch session) source target opts)
                  (catch clojure.lang.ExceptionInfo e
                    (record-rejection! session :file/mv source e)
                    (throw e)))]
     (record-receipt! session
                       (assoc staged :type :file/staged :op :mv
                              :at-millis ((:clock (:runtime session)))))
     staged)))

(defn facts
  "The current batch facts.  The runtime settles when any of the three
  collections is non-empty; a read-set-only settle verifies a board
  conclusion against the real workspace."
  [session]
  (let [b (wg/snapshot (:batch session))
        ctx (context session)]
    ;; A read-set-only commit publishes an edit action, so it needs a claim.
    ;; After a successful finish the claim is gone; those reads already
    ;; backed the finish and must not force a redundant empty settle.  A
    ;; batch with pending ops still reports them so a missing claim fails
    ;; loudly instead of silently dropping staged changes.
    (if (:claim-id ctx)
      {:read-set (:read-set b)
       :checks (:checks b)
       :ops (:ops b)}
      {:read-set {}
       :checks {}
       :ops (:ops b)
       ;; The discarded read-set is not a board conclusion: the program has
       ;; no claim, so it cannot publish one.  Mark the fact explicitly so a
       ;; caller can see why no settle follows instead of treating the empty
       ;; read-set as "nothing was observed".
       :read-only-conclusion? true
       :discarded-read-set-count (count (:read-set b))})))

;; ---------------------------------------------------------------------------
;; Read-set normalization for swarm finish

(defn- observation->entry
  [path observation]
  (cond
    (not (map? observation))
    {:path path :stamp observation}

    (contains? observation :stamp)
    (cond-> {:path (or (:path observation) path)}
      (:stamp observation) (assoc :stamp (:stamp observation))
      (:kind observation) (assoc :kind (:kind observation))
      (:unstable? observation) (assoc :unstable? true)
      (:changed-during-read? observation) (assoc :changed-during-read? true))

    (contains? observation :kind)
    (cond-> {:path (or (:path observation) path)
             :kind (:kind observation)}
      (:unstable? observation) (assoc :unstable? true)
      (:changed-during-read? observation) (assoc :changed-during-read? true))

    :else
    {:path path :stamp observation}))

(defn normalize-read-set
  "Accept the shapes a program can produce: a vector of `{:path :stamp}` or
  `{:path :kind}`, a map of path to stamp, or the batch read-set map of path
  to observation vectors.  The verifier needs one entry per observation so
  every observed stamp must still match."
  [read-set]
  (cond
    (nil? read-set) []

    (and (map? read-set) (contains? read-set :path))
    [read-set]

    (map? read-set)
    (vec
     (mapcat (fn [[path value]]
               (cond
                 (and (sequential? value) (not (map? value)))
                 (mapv #(observation->entry path %) value)

                 (map? value)
                 [(observation->entry path value)]

                 :else
                 [{:path path :stamp value}]))
             read-set))

    (sequential? read-set)
    (vec read-set)

    :else
    (err/fail! :UNSUPPORTED_OPERATION :bad-read-set
               "Read-set must be a sequence or map"
               {:actual (some-> read-set class str)})))

;; ---------------------------------------------------------------------------
;; Board commands

(def ^:private program-ops
  #{:split :claim :finish :cancel :replace :release :reopen
    :comment :update :take-or-die :park-slot
    ;; Recovery commands are explicit, evidence-bearing transitions.  A
    ;; program may resolve its own uncertain action and then acknowledge the
    ;; worktree recovery barrier; neither command applies files.
    :resolve-action :acknowledge-recovery})

(defn program-op?
  "True when OP is one of the board primitives available to a worker
  program.  `begin-action`/`advance-action`/`batch` stay internal."
  [op]
  (contains? program-ops op))

(def ^:private claim-affecting-ops
  "Commands that can release or replace the claim that authorizes the
  current batch.  A staged write cannot be committed after its claim is
  gone, so the bridge requires a settle first.  `:finish` is deliberately
  not in this set: it never drains or delays writes; it verifies its read-set
  against the old real filesystem and commits the board finish immediately."
  #{:claim :split :release :cancel :replace :take-or-die})

(declare settle! resolve-action!)

(defn- gate-port
  [session]
  {:acquire (fn [owner]
              (wg/acquire-write-gate! (:gate (:runtime session)) owner
                                      {:stop? #(stopped? session)}))
   :release (fn [permit] (wg/release-write-gate! permit))})

(defn- board-snapshot
  [session]
  (let [rt (:runtime session)]
    (if-let [f (:board-snapshot rt)]
      (f)
      (swarm/snapshot (:kernel rt)))))

(defn- board-version
  [session]
  (:board-version (board-snapshot session)))

(defn- execute-command!
  "Issue one board command through the single configured writer.

  With `:block? true`, wait until the Owner has committed or refused; the
  caller uses this while holding W so W is never released before the board
  CAS.  The opaque `:write-gate-proof` capability is forwarded as the
  Owner-side proof for a pre-verified read-set basis."
  ([session cmd] (execute-command! session cmd {}))
  ([session cmd opts]
   (let [rt (:runtime session)]
     (if-let [f (:execute-board! rt)]
       (board/command-result
        (if (:block? opts)
          (f cmd Long/MAX_VALUE
             (select-keys opts [:write-gate-proof :verify-read-set]))
          (f cmd)))
       (swarm/execute! (:kernel rt) cmd)))))

(defn- finish-under-owner-w!
  "Verify a finish read-set under W, then issue the command to the Owner.

  The Owner's pure command has no verifier and no W, so the bridge acquires
  W, runs the workspace verifier, replaces the read-set with
  `:verified-read-set` and passes the opaque `:write-gate-proof` capability to
  the Owner's blocking execute handle.  The Owner must pass that capability
  through to the board, which translates it into the under-W entry.  A bare
  boolean from wire data is never accepted.  W is released after the Owner
  returns; the command itself is a short pure transition."
  [session cmd]
  (let [rt (:runtime session)
        ctx (context session)
        read-set (:read-set cmd)
        permit (wg/acquire-write-gate! (:gate rt) (:agent-id ctx)
                                       {:stop? #(stopped? session)})]
    (try
      (let [verdict (try
                      ((:verify-read-set rt) read-set)
                      (catch Throwable e
                        {:ok? false
                         :reason :read-set-check-failed
                         :message (.getMessage e)}))]
        (if (:ok? verdict)
          (let [result (execute-command!
                        session
                        (-> cmd
                            (assoc :read-set [])
                            (assoc :verified-read-set read-set))
                        {:block? true
                         :write-gate-proof (proof/token)})]
            (cond-> result
              (:ok? result) (assoc :verified-read-set read-set)
              (:result-ref result) (assoc-in [:result-ref :read-set] read-set)))
          {:ok? false
           :error :FILE_CONFLICT
           :detail verdict
           :verified-read-set read-set}))
      (finally
        (wg/release-write-gate! permit)))))

(defn- clear-claim-if
  [session claim-id]
  (when claim-id
    (swap! (:ctx session)
           (fn [ctx]
             (if (= claim-id (:claim-id ctx))
               (assoc ctx :claim-id nil)
               ctx)))))

(defn- normalize-command
  [cmd]
  (cond-> cmd
    (contains? cmd :task-id) (update :task-id id)
    (contains? cmd :claim-id) (update :claim-id id)
    (contains? cmd :action-id) (update :action-id id)
    (contains? cmd :parent-id) (update :parent-id id)
    (contains? cmd :owner-id) (update :owner-id id)
    (contains? cmd :wait-for) (update :wait-for
                                      (fn [ids] (set (map id ids))))
    (contains? cmd :children)
    (update :children
            (fn [children]
              (mapv (fn [child]
                      (cond-> child
                        (contains? child :task-id) (update :task-id id)
                        (contains? child :owner-id) (update :owner-id id)))
                    children)))
    (contains? cmd :replacement)
    (update :replacement
            (fn [replacement]
              (cond-> replacement
                (contains? replacement :task-id) (update :task-id id))))
    (contains? cmd :expected-cond)
    (update :expected-cond
            (fn [condition]
              (cond-> condition
                (contains? condition :claim-id) (update :claim-id id))))))

(defn- update-context!
  [session op request result]
  (when (:ok? result)
    (case op
      :claim
      (swap! (:ctx session)
             assoc
             :claim-id (:claim-id result)
             :task-id (:task-id result))

      :release
      (clear-claim-if session (:claim-id request))

      :finish
      (let [ctx (context session)
            request-claim (:claim-id request)
            request-task (:task-id request)]
        (when (and (:claim-id ctx)
                   (or (= request-claim (:claim-id ctx))
                       (= request-task (:task-id ctx))))
          (clear-claim-if session (:claim-id ctx))))

      :split
      (when-let [released (:released-claim-id result)]
        (clear-claim-if session released))

      :cancel
      (when (= (:task-id request) (:task-id (context session)))
        (clear-claim-if session (get-in (context session) [:claim-id])))

      :replace
      (when (= (:task-id request) (:task-id (context session)))
        (clear-claim-if session (get-in (context session) [:claim-id])))

      :take-or-die
      (when (or (= :attempt-lost (:outcome result))
                (= :dead (:life-state result))
                (= :dead (:life result)))
        (reset! (:dead? session) true))

      nil))
  result)

(defn board-command
  "Run one swarm STM command for the session's actor.

  The actor identity always comes from the session, never from the program
  payload.  A finish never drains or delays staged writes.  With a non-empty
  read-set it verifies against the real (pre-write) filesystem while holding
  W and commits the board finish immediately; staged writes stay pending, and
  a later settle may fail `:CLAIM_CHANGED` after the finish released the
  claim.  Other commands are pure board transitions."
  [session request]
  (check-live! session)
  (if (= :resolve-action (:op request))
    (resolve-action! session request)
    (let [op (:op request)]
      (when-not (contains? program-ops op)
        (err/fail! :UNSUPPORTED_OPERATION :not-a-program-command
                   "This operation is not available to a worker program"
                   {:op op}))
      (when (and (contains? claim-affecting-ops op)
                 (seq (:ops (facts session))))
        (err/fail! :PREPARE_REJECTED :pending-file-changes
                   "Settle pending file changes before a claim-affecting board command"
                   {:pending-ops (count (:ops (facts session)))}))
      (let [ctx (context session)
            cmd (cond-> (-> request
                            (dissoc :agent-id)
                            (assoc :actor-id (:agent-id ctx))
                            normalize-command)
                  ;; The acknowledgement must emit its event on every call so
                  ;; a retry after a persistence failure can release the
                  ;; runtime barrier.  A cached request replay would carry no
                  ;; event and look successful without being durable.
                  (= :acknowledge-recovery op) (dissoc :request-id))
            cmd (if (= :finish op)
                  (update cmd :read-set normalize-read-set)
                  cmd)
            result (cond
                     (and (= :finish op) (seq (:read-set cmd))
                          (:execute-board! (:runtime session)))
                     (finish-under-owner-w! session cmd)

                     (and (= :finish op) (seq (:read-set cmd)))
                     (swarm/execute-under-write-gate!
                      (:kernel (:runtime session))
                      (gate-port session)
                      (:agent-id ctx)
                      cmd)

                     :else
                     (execute-command! session cmd))]
        (record-receipt! session
                          {:type :board/command
                           :op op
                           :ok? (:ok? result)
                           :status (or (:error result) :OK)
                           :result result
                           :at-millis ((:clock (:runtime session)))})
        (record-effect! session
                        {:type :board/changed
                         :op op
                         :ok? (:ok? result)
                         :board-version (:board-version (board-snapshot session))})
        (update-context! session op cmd result)))))

(defn resolve-action!
  "Close the caller's own `:uncertain` edit action after inspecting the site.

  A hard failure leaves the action visible in R(a) on purpose.  The program
  or the Owner resolves it explicitly with this narrow transition; arbitrary
  `:advance-action` targets stay internal."
  [session request]
  (check-live! session)
  (let [ctx (context session)
        aid (id (:action-id request))
        snapshot (board-snapshot session)
        action (get-in snapshot [:actions aid])]
    (when-not action
      (err/fail! :UNKNOWN_ACTION :unknown-action
                 "No such action"
                 {:action-id aid}))
    (when-not (= (:agent-id ctx) (:agent-id action))
      (err/fail! :CLAIM_CHANGED :not-action-owner
                 "The action belongs to another actor"
                 {:action-id aid :actor-id (:agent-id ctx)
                  :action-agent-id (:agent-id action)}))
    (when-not (= :uncertain (:phase action))
      (err/fail! :INVALID_ACTION_TRANSITION :action-not-uncertain
                 "Only an uncertain action can be resolved"
                 {:action-id aid :phase (:phase action)}))
    (when (and (get-in action [:recovery :state])
               (not (and (map? (:result-ref request))
                         (seq (:result-ref request)))))
      (err/fail! :RECOVERY_EVIDENCE_REQUIRED :recovery-evidence-required
                 "A recovered uncertain action requires an explicit :result-ref"
                 {:action-id aid
                  :recovery (:recovery action)}))
    (let [result (execute-command!
                  session
                  {:op :advance-action
                   :actor-id (:agent-id ctx)
                   :action-id aid
                   :phase :finished
                   :result-ref (or (:result-ref request)
                                   {:status :RESOLVED
                                    :resolved-at-version
                                    (:board-version snapshot)})})]
      (record-receipt! session
                        {:type :board/resolve-action
                         :action-id aid
                         :ok? (:ok? result)
                         :result result
                         :at-millis ((:clock (:runtime session)))})
      (record-effect! session
                      {:type :board/changed
                       :op :resolve-action
                       :ok? (:ok? result)
                       :board-version (:board-version (board-snapshot session))})
      result)))

;; ---------------------------------------------------------------------------
;; Settle

(defn- next-request-id
  [session]
  (let [n (swap! (:settle-seq session) inc)
        key (or (stop-key session) "program")]
    (str key ":settle:" (dec n))))

(defn- workspace-relative
  [root p]
  (let [root (str root)
        p (str p)
        slash (str root "/")
        backslash (str root "\\")]
    (cond
      (str/starts-with? p slash) (subs p (count slash))
      (str/starts-with? p backslash) (subs p (count backslash))
      :else p)))

(defn- changed-paths
  [batch]
  (->> (:ops batch)
       (mapcat (fn [{:keys [op path source target]}]
                 (if (= :mv op) [source target] [path])))
       (remove nil?)
       (map #(workspace-relative (:root batch) %))
       set
       sort
       vec))

(defn- register-pending-settle!
  [session request-id]
  (when-let [key (stop-key session)]
    (swap! (:pending-settles (:runtime session))
           update key (fnil conj #{}) request-id)))

(defn- unregister-pending-settle!
  [session request-id]
  (when-let [key (stop-key session)]
    (swap! (:pending-settles (:runtime session))
           (fn [m]
             (let [ids (disj (get m key #{}) request-id)]
               (if (seq ids)
                 (assoc m key ids)
                 (dissoc m key)))))
    (wg/clear-stop! (:wg (:runtime session)) request-id)))

(defn- first-draft-path
  "The path of a Plan first-write create in this batch, or nil.

  The Owner claim is needed only while the session context has no fixed
  draft path; once the first apply succeeds the context carries the path."
  [ctx batch]
  (when (and (= :clj-plan (:role ctx))
             (nil? (:draft-path ctx)))
    (some (fn [{:keys [op path create?]}]
            (when (and (= :write op) create?)
              (let [relative (workspace-relative (:root batch) path)]
                (when (control/draft-path? relative)
                  relative))))
          (:ops batch))))

(defn- draft-claim-key
  [ctx]
  (or (:call-id ctx) (:attempt-id ctx)))

(defn- claim-draft!
  "Ask the Owner to reserve PATH before any draft bytes are applied.

  Returns the Owner command result, or nil for the kernel-only test path.
  A successful claim is tracked by the session's call-id so a failed
  terminal can release it."
  [session path request-id]
  (let [rt (:runtime session)
        ctx (context session)]
    (when (:execute-board! rt)
      (let [result (execute-command!
                    session
                    {:op :claim-draft
                     :path path
                     :request-id request-id
                     :call-id (:call-id ctx)
                     :tenure-id (:tenure-id ctx)}
                    {:block? true})]
        (when (:ok? result)
          (when-let [key (draft-claim-key ctx)]
            (swap! (:draft-claims rt) update key (fnil conj [])
                   {:request-id request-id :path path})))
        result))))

(defn- release-draft-claim!
  [runtime key request-id path]
  (when-let [f (:execute-board! runtime)]
    (try
      (board/command-result
       (f {:op :release-draft
           :path path
           :request-id request-id}
          Long/MAX_VALUE
          {}))
      (catch Throwable _ nil)))
  (when key
    (swap! (:draft-claims runtime) update key
           (fn [claims]
             (vec (remove #(and (= request-id (:request-id %))
                                (= path (:path %)))
                          claims))))))

(defn release-draft-claims!
  "Release every tracked draft claim for KEYS.

  A successful terminal keeps the Owner claim for the app's final
  `:plan/write-draft`; a failed/cancelled/unknown terminal releases it so a
  later program is not blocked by a dead writer."
  [runtime keys status]
  (let [keys (set (remove nil? keys))
        ;; The evaluator may report the success as `:COMPLETED`,
        ;; `:completed`, `:succeeded`, or `:ok`; all of them keep the claim
        ;; for the app's final draft sync.
        release? (not (contains? #{:COMPLETED :completed :succeeded :ok}
                                 status))]
    (doseq [key keys
            claim (get @(:draft-claims runtime) key)]
      (when release?
        (release-draft-claim! runtime key
                              (:request-id claim) (:path claim))))
    (swap! (:draft-claims runtime)
           (fn [claims]
             (apply dissoc claims keys)))))

(defn- draft-pre-apply!
  "Under-W first-draft qualification: Owner claim, then target absence.

  Both checks run before the dry-run/apply.  A refusal is an ordinary
  rejection; the claim is released before returning it."
  [session path request-id claim-state]
  (let [rt (:runtime session)
        ctx (context session)
        key (draft-claim-key ctx)
        owner? (some? (:execute-board! rt))
        claim (when owner? (claim-draft! session path request-id))]
    (if (and claim (false? (:ok? claim)))
      {:ok? false
       :status (or (:error claim) :DRAFT_NAME_TAKEN)
       :message (or (:message claim)
                    "Plan draft name is already claimed")
       :path path}
      (if (= :draft-already-fixed
             (or (:outcome claim) (get-in claim [:receipt :outcome])))
        {:ok? true}
        (try
          (if (:exists (wg/stat-path (:root rt) path))
            (do
              (when owner?
                (release-draft-claim! rt key request-id path))
              {:ok? false
               :status :DRAFT_NAME_TAKEN
               :message "Plan draft target already exists"
               :path path})
            (do
              (reset! claim-state (when owner? claim))
              {:ok? true}))
          (catch Throwable t
            (when owner?
              (release-draft-claim! rt key request-id path))
            (throw t)))))))

(defn settle!
  "Commit the current batch under W and publish the STM edit action.

  Returns `{:status ... :session next-session :result commit-result}`.  A
  successful apply returns a fresh empty session; a rejection returns the
  unchanged session and the classified commit result.  The runtime aborts on
  any non-`:APPLIED` status."
  [session request]
  (check-live! session)
  (let [ctx (context session)
        rt (:runtime session)
        facts (facts session)
        rid (or (:request-id request) (next-request-id session))
        agent-id (:agent-id ctx)
        request-agent (some-> (or (:actor-id request) (:agent-id request)) id)
        ;; The session context is authoritative: a successful claim, finish
        ;; or release updates it, and the evaluator's request fields are
        ;; informational.  Falling back to a stale request claim after a
        ;; finish would resurrect a claim the board already released.
        claim-id (:claim-id ctx)
        task-id (:task-id ctx)]
    (cond
      (and request-agent (not= request-agent agent-id))
      (err/fail! :CLAIM_CHANGED :identity-mismatch
                 "A program cannot settle under another actor's identity"
                 {:actor-id agent-id :requested request-agent})

      (and (empty? (:ops facts))
           (empty? (:read-set facts))
           (empty? (:checks facts)))
      {:status :NO_FACTS
       :session session
       :result {:status :UNSUPPORTED_OPERATION
                :code :no-file-facts
                :message "Nothing to verify or apply; use the board STM directly"
                :outcome :not-written
                :file-changed? false}}

      :else
      (let [batch (wg/snapshot (:batch session))
            paths (changed-paths batch)
            draft-path (first-draft-path ctx batch)
            ;; A Plan write is authorized by its pinned tenure and the
            ;; mission draft claim, never by a swarm actor.  A Plan batch
            ;; without a write op (which the Plan capability set cannot
            ;; stage) keeps the normal board path.
            plan-draft-path (when (= :clj-plan (:role ctx))
                              (or draft-path
                                  (some (fn [{:keys [op path]}]
                                          (when (= :write op)
                                            (workspace-relative (:root batch)
                                                                path)))
                                        (:ops batch))))
            plan-draft (when plan-draft-path
                         {:tenure-id (:tenure-id ctx)
                          :call-id (:call-id ctx)
                          :path plan-draft-path
                          :request-id rid})
            claim-state (atom nil)
            pre-apply! (when draft-path
                         (fn [_request]
                           (draft-pre-apply! session draft-path rid
                                             claim-state)))
            result (do
                     (register-pending-settle! session rid)
                     (try
                       (wg/commit! (:wg rt)
                                   (cond-> {:request-id rid
                                            :actor-id agent-id
                                            :claim-id claim-id
                                            :task-id task-id
                                            :batch batch
                                            :pre-apply! pre-apply!}
                                     plan-draft
                                     (assoc :plan-draft plan-draft)))
                       (catch clojure.lang.ExceptionInfo e
                         (if (err/expected-failure? e)
                           (ex-data e)
                           (throw e)))
                       (catch Throwable t
                         {:status :HARD_FAILURE
                          :outcome :unknown
                          :file-changed? :unknown
                          :phase :commit
                          :exception (err/throwable->data t)
                          :message (.getMessage t)})
                       (finally
                         (unregister-pending-settle! session rid))))
            result (if (and draft-path
                            (= :STRUCTURE_CONFLICT (:status result))
                            (= draft-path
                               (workspace-relative (:root batch)
                                                   (:path result)))
                            (= :expected-missing (:code result)))
                     (assoc result
                            :status :DRAFT_NAME_TAKEN
                            :code :draft-name-taken
                            :message "Plan draft target already exists")
                     result)
            applied? (= :APPLIED (:status result))
            _ (when (and draft-path @claim-state (not applied?))
                (release-draft-claim! rt (draft-claim-key ctx) rid
                                      draft-path))
            _ (when (and applied? draft-path)
                (swap! (:ctx session) assoc :draft-path draft-path))
            next-session (if applied?
                           (assoc session :batch (wg/session (:root rt) (:limits rt)))
                           session)]
        (record-receipt! session
                          {:type :workspace/settle
                           :request-id rid
                           :status (:status result)
                           :published? (:published? result)
                           :result result
                           :at-millis ((:clock rt))})
        (when applied?
          (record-effect! session
                          {:type :files/applied
                           :request-id rid
                           :changed-paths paths
                           :board-version (board-version session)}))
        (when (and applied? draft-path @claim-state)
          (record-effect! session
                          {:type :draft/claimed
                           :request-id rid
                           :path draft-path
                           :board-version (board-version session)}))
        (when (and applied? (false? (:published? result)))
          (record-effect! session
                          {:type :files/publish-pending
                           :request-id rid
                           :retry :publish-only
                           :board-version (board-version session)}))
        (when (= :HARD_FAILURE (:status result))
          (record-effect! session
                          {:type :files/uncertain
                           :request-id rid
                           :phase (:phase result)
                           :board-version (board-version session)}))
        {:status (:status result)
         :session next-session
         :result result}))))
