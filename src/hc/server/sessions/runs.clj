(ns hc.server.sessions.runs
  "Run, call, and attempt state for one session.

  Authority: AGENTS.md §5.1 (four-layer lifecycle, immutable claimed input
  snapshots, late replies), §5.2 (claim before dispatch), and §5.4 (retry
  keeps call-id and changes attempt-id).

  The three layers are separate maps:

    run      one cancellable execution activity
    call     one business model/tool work record
    attempt  one concrete try of a call

  A call never reuses an attempt-id.  A retry keeps the call-id and creates a
  new attempt-id.  The claimed input snapshot is frozen at call creation and
  is copied into every attempt; later session edits never mutate it.

  Functions here are pure state transitions over the session map.  They do
  not bump `:revision`; the session facade commits one command as one
  revision.  Dispatch effects are returned as data and are only valid after
  the caller persists the returned state."
  (:require [hc.server.model-port.contract :as contract]
            [hc.server.sessions.ids :as ids]))

(def run-states
  #{:running :completed :failed :cancelled :outcome-unknown})

(def call-states
  #{:ready :dispatching :running :retry-wait
    :succeeded :failed :cancelled :outcome-unknown})

(def attempt-states
  #{:dispatching :running :succeeded :failed :cancelled :outcome-unknown})

(def active-call-states
  #{:ready :dispatching :running :retry-wait})

(def terminal-call-states
  #{:succeeded :failed :cancelled :outcome-unknown})

(def terminal-attempt-states terminal-call-states)

(defn- error
  [session code message details]
  {:ok? false
   :session session
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))
   :effects []})

(defn- freeze
  [value]
  (try
    {:ok? true :value (contract/freeze-snapshot value)}
    (catch clojure.lang.ExceptionInfo exception
      {:ok? false
       :error {:code :SNAPSHOT_NOT_IMMUTABLE
               :message (.getMessage exception)
               :details (ex-data exception)}})
    (catch Exception exception
      {:ok? false
       :error {:code :SNAPSHOT_NOT_IMMUTABLE
               :message (str "snapshot could not be frozen: "
                             (.getMessage exception))}})))

(defn run-active?
  [session]
  (some (fn [run-id]
          (= :running (get-in session [:runs run-id :state])))
        (:run-order session)))

(defn active-run
  [session]
  (some (fn [run-id]
          (let [run (get-in session [:runs run-id])]
            (when (= :running (:state run)) run)))
        (reverse (:run-order session))))

(defn call-active?
  [call]
  (contains? active-call-states (:state call)))

(defn attempt-active?
  [attempt]
  (contains? #{:dispatching :running} (:state attempt)))

(defn- freeze-run-snapshot
  [binding message-ids policy language context target-message-id target-mode now]
  (freeze {:message-ids (vec message-ids)
           :model-binding binding
           :policy (or policy {})
           :language language
           :context (or context {})
           :target-message-id target-message-id
           :target-mode target-mode
           :frozen-at now}))

(defn start-run
  "Create one running run and its first ready call.

  The caller must not have another running run.  The returned effects include
  `:run-started`; the call is still `:ready` and no model request has been
  dispatched.  `:target-message-id` + `:target-mode :append-version` freezes
  an explicit regeneration target; the terminal handler appends a version
  instead of a second assistant message."
  [session {:keys [run-id call-id model-binding message-ids policy language
                    context target-message-id target-mode now]}]
  (cond
    (run-active? session)
    (error session :RUN_ALREADY_ACTIVE
           "a run is already active"
           {:current-run-id (:current-run-id session)})

    (not (ids/valid-id? run-id))
    (error session :RUN_ID_INVALID "run-id must be a non-blank string"
           {:run-id run-id})

    (not (ids/valid-id? call-id))
    (error session :CALL_ID_INVALID "call-id must be a non-blank string"
           {:call-id call-id})

    (not (map? model-binding))
    (error session :MODEL_BINDING_REQUIRED
           "a run needs a model binding snapshot"
           {:model-binding model-binding})

    (not (every? ids/valid-id? message-ids))
    (error session :MESSAGE_IDS_INVALID
           "message-ids must contain only non-blank strings"
           {:message-ids message-ids})

    (and target-message-id
         (not (ids/valid-id? target-message-id)))
    (error session :TARGET_MESSAGE_ID_INVALID
           "target-message-id must be a non-blank string or nil"
           {:target-message-id target-message-id})

    (and target-message-id
         (not (contains? #{:append-version} target-mode)))
    (error session :TARGET_MODE_INVALID
           "a target message needs :target-mode :append-version"
           {:target-mode target-mode})

    :else
    (let [snapshot-result (freeze-run-snapshot model-binding message-ids policy
                                               language context target-message-id
                                               target-mode now)]
      (if-not (:ok? snapshot-result)
        (assoc (error session (:code (:error snapshot-result))
                      (:message (:error snapshot-result))
                      (:details (:error snapshot-result)))
               :error (:error snapshot-result))
        (let [snapshot (:value snapshot-result)
              run {:run-id run-id
                   :session-id (:session-id session)
                   :state :running
                   :model-binding model-binding
                   :input-snapshot snapshot
                   :call-order [call-id]
                   :cancel-reason nil
                   :cancel-requested-at nil
                   :created-at now
                   :updated-at now
                   :completed-at nil}
              call {:call-id call-id
                    :run-id run-id
                    :session-id (:session-id session)
                    :state :ready
                    :attempt-order []
                    :current-attempt-id nil
                    :input-snapshot snapshot
                    :cancel-requested? false
                    :cancel-reason nil
                    :result nil
                    :created-at now
                    :updated-at now}
              session' (-> session
                           (assoc-in [:runs run-id] run)
                           (assoc-in [:calls call-id] call)
                           (update :run-order (fnil conj []) run-id)
                           (assoc :current-run-id run-id))]
          {:ok? true
           :session session'
           :run run
           :call call
           :effects [{:kind :run-started
                      :session-id (:session-id session)
                      :owner-id (:owner-id session)
                      :run-id run-id
                      :call-id call-id}]})))))

(defn record-outbound
  "Record the projection a UI send produced, without opening a call.

  The Owner owns the model turn; this session entry only freezes the
  outbound context so the workbench panel can show the last accepted send.
  It never touches `:current-run-id` or `:calls`, and re-recording the same
  run-id is idempotent."
  [session {:keys [run-id message-id message-ids context now]}]
  (cond
    (not (ids/valid-id? run-id))
    (error session :RUN_ID_INVALID "run-id must be a non-blank string"
           {:run-id run-id})

    (not (vector? message-ids))
    (error session :MESSAGE_IDS_INVALID "message-ids must be a vector"
           {:message-ids message-ids})

    (not (every? ids/valid-id? message-ids))
    (error session :MESSAGE_IDS_INVALID
           "message-ids must contain only non-blank strings"
           {:message-ids message-ids})

    :else
    (let [run {:run-id run-id
               :session-id (:session-id session)
               :state :completed
               :origin :ui-send
               :message-id message-id
               :input-snapshot {:message-ids (vec message-ids)
                                :context context}
               :created-at now
               :updated-at now
               :completed-at now}
          session' (-> session
                       (assoc-in [:runs run-id] run)
                       (update :run-order
                               (fn [order]
                                 (let [order (vec (or order []))]
                                   (if (some #{run-id} order)
                                     order
                                     (conj order run-id))))))]
      {:ok? true
       :session session'
       :run run
       :effects []})))

(defn- add-attempt
  [session call attempt-id now]
  (let [attempt {:attempt-id attempt-id
                 :call-id (:call-id call)
                 :run-id (:run-id call)
                 :session-id (:session-id session)
                 :state :dispatching
                 :input-snapshot (:input-snapshot call)
                 :chunks []
                 :tool-requests {}
                 :cancel-requested? false
                 :terminal nil
                 :late-terminal nil
                 :created-at now
                 :updated-at now
                 :finished-at nil}
        call' (-> call
                  (assoc :state :dispatching
                         :current-attempt-id attempt-id
                         :updated-at now)
                  (update :attempt-order (fnil conj []) attempt-id))]
    {:session (-> session
                  (assoc-in [:attempts attempt-id] attempt)
                  (assoc-in [:calls (:call-id call)] call'))
     :attempt attempt
     :call call'}))

(defn- dispatch-effect
  [session call attempt-id]
  {:kind :dispatch-model
   :session-id (:session-id session)
   :owner-id (:owner-id session)
   :run-id (:run-id call)
   :call-id (:call-id call)
   :attempt-id attempt-id
   :model-binding (get-in call [:input-snapshot :model-binding])})

(defn claim-call
  "Move a ready call to `:dispatching` and create its first attempt.

  The state change must be committed before the returned dispatch effect is
  submitted.  This is the claim-before-dispatch rule from §5.2."
  [session {:keys [call-id attempt-id now]}]
  (let [call (get-in session [:calls call-id])
        run (get-in session [:runs (:run-id call)])]
    (cond
      (nil? call)
      (error session :CALL_NOT_FOUND "call does not exist" {:call-id call-id})

      (= :cancelled (:state run))
      (error session :RUN_CANCELLED
             "cannot start an attempt for a cancelled run"
             {:call-id call-id :run-id (:run-id call)})

      (not (ids/valid-id? attempt-id))
      (error session :ATTEMPT_ID_INVALID
             "attempt-id must be a non-blank string" {:attempt-id attempt-id})

      (contains? (:attempts session) attempt-id)
      (error session :ATTEMPT_ID_TAKEN
             "attempt-id was already used" {:attempt-id attempt-id})

      (not= :ready (:state call))
      (error session :CALL_NOT_READY
             "only a ready call can be claimed"
             {:call-id call-id :state (:state call)})

      :else
      (let [{:keys [session attempt call]}
            (add-attempt session call attempt-id now)]
        {:ok? true
         :session session
         :attempt attempt
         :call call
         :effects [(dispatch-effect session call attempt-id)]}))))

(defn start-attempt
  "Start another attempt for an existing call.

  The call-id stays stable; the attempt-id is new.  The frozen input snapshot
  is reused unchanged, so a retry cannot observe later edits."
  [session {:keys [call-id attempt-id now]}]
  (let [call (get-in session [:calls call-id])
        run (get-in session [:runs (:run-id call)])]
    (cond
      (nil? call)
      (error session :CALL_NOT_FOUND "call does not exist" {:call-id call-id})

      (= :cancelled (:state run))
      (error session :RUN_CANCELLED
             "cannot start an attempt for a cancelled run"
             {:call-id call-id :run-id (:run-id call)})

      (not (ids/valid-id? attempt-id))
      (error session :ATTEMPT_ID_INVALID
             "attempt-id must be a non-blank string" {:attempt-id attempt-id})

      (contains? (:attempts session) attempt-id)
      (error session :ATTEMPT_ID_TAKEN
             "attempt-id was already used" {:attempt-id attempt-id})

      (not (contains? #{:ready :retry-wait :failed :cancelled :outcome-unknown}
                      (:state call)))
      (error session :CALL_NOT_RETRYABLE
             "call is not in a retryable state"
             {:call-id call-id :state (:state call)})

      :else
      (let [{:keys [session attempt call]}
            (add-attempt session call attempt-id now)]
        {:ok? true
         :session session
         :attempt attempt
         :call call
         :effects [(dispatch-effect session call attempt-id)]}))))

(defn mark-attempt-running
  [session attempt-id now]
  (let [attempt (get-in session [:attempts attempt-id])]
    (cond
      (nil? attempt)
      (error session :ATTEMPT_UNKNOWN
             "attempt does not exist" {:attempt-id attempt-id})

      (not= :dispatching (:state attempt))
      (error session :ATTEMPT_NOT_DISPATCHING
             "attempt is not dispatching"
             {:attempt-id attempt-id :state (:state attempt)})

      :else
      (let [call-id (:call-id attempt)
            session' (-> session
                         (assoc-in [:attempts attempt-id :state] :running)
                         (assoc-in [:attempts attempt-id :updated-at] now)
                         (assoc-in [:calls call-id :state] :running)
                         (assoc-in [:calls call-id :updated-at] now))]
        {:ok? true :session session' :effects []}))))

(defn- chunk-text
  [event]
  (or (:text event) (:delta event)))

(defn- terminal-state
  [kind]
  (case kind
    :completed :succeeded
    :succeeded :succeeded
    :failed :failed
    :cancelled :cancelled
    :outcome-unknown :outcome-unknown
    nil))

(defn- run-state-after-terminal
  [session run]
  (let [calls (keep #(get-in session [:calls %]) (:call-order run))]
    (if (every? #(contains? terminal-call-states (:state %)) calls)
      (cond
        (some #(= :failed (:state %)) calls) :failed
        (some #(= :outcome-unknown (:state %)) calls) :outcome-unknown
        (some #(= :cancelled (:state %)) calls) :cancelled
        (every? #(= :succeeded (:state %)) calls) :completed
        :else :failed)
      :running)))

(defn- accept-chunk
  [session attempt event now]
  (let [text (chunk-text event)]
    (cond
      (not (string? text))
      (error session :CHUNK_INVALID
             "a chunk needs a string :text or :delta"
             {:attempt-id (:attempt-id attempt)})

      (contains? terminal-attempt-states (:state attempt))
      {:ok? true
       :session session
       :ignored :attempt-terminal
       :effects []}

      :else
      (let [call (get-in session [:calls (:call-id attempt)])
            run (get-in session [:runs (:run-id attempt)])
            visible? (and (not (:cancel-requested? call))
                          (not= :cancelled (:state run)))
            session' (-> session
                         (update-in [:attempts (:attempt-id attempt) :chunks]
                                    (fnil conj [])
                                    {:text text :at now})
                         (assoc-in [:attempts (:attempt-id attempt) :updated-at] now))]
        {:ok? true
         :session session'
         :content text
         :visible? visible?
         :effects (if visible?
                    [{:kind :notify
                      :session-id (:session-id session)
                      :event {:type :model-chunk
                              :session-id (:session-id session)
                              :run-id (:run-id attempt)
                              :call-id (:call-id attempt)
                              :attempt-id (:attempt-id attempt)
                              :text text}}]
                    [])}))))

(defn- accept-tool-call
  [session attempt event now]
  (let [tool-call-id (:tool-call-id event)
        request {:name (:name event)
                 :arguments (:arguments event)}]
    (cond
      (not (ids/valid-id? tool-call-id))
      (error session :TOOL_CALL_ID_REQUIRED
             "a tool call needs a non-blank tool-call-id"
             {:attempt-id (:attempt-id attempt)})

      (contains? terminal-attempt-states (:state attempt))
      {:ok? true :session session :ignored :attempt-terminal :effects []}

      (:cancel-requested? (get-in session [:calls (:call-id attempt)]))
      {:ok? true :session session :ignored :call-cancelled :effects []}

      :else
      (if-let [existing (get-in session
                                [:attempts (:attempt-id attempt)
                                 :tool-requests tool-call-id])]
        (if (= request (select-keys existing [:name :arguments]))
          {:ok? true
           :session session
           :ignored :duplicate-tool-call
           :effects []}
          (error session :TOOL_CALL_PAYLOAD_CONFLICT
                 "tool-call-id was already recorded with different arguments"
                 {:tool-call-id tool-call-id
                  :accepted (select-keys existing [:name :arguments])
                  :received request}))
        (let [record (assoc request
                            :tool-call-id tool-call-id
                            :received-at now)
              session' (-> session
                           (assoc-in [:attempts (:attempt-id attempt)
                                      :tool-requests tool-call-id]
                                     record)
                           (assoc-in [:attempts (:attempt-id attempt)
                                      :updated-at]
                                     now))]
          {:ok? true
           :session session'
           :tool-call record
           :effects [{:kind :accept-tool-request
                      :session-id (:session-id session)
                      :run-id (:run-id attempt)
                      :call-id (:call-id attempt)
                      :attempt-id (:attempt-id attempt)
                      :tool-call-id tool-call-id
                      :name (:name event)
                      :arguments (:arguments event)}]})))))

(defn- accept-terminal
  [session attempt event now]
  (let [kind (:kind event)
        state (terminal-state kind)]
    (cond
      (nil? state)
      (error session :TERMINAL_EVENT_INVALID
             "terminal event kind must be :completed, :failed, :cancelled, or :outcome-unknown"
             {:kind kind})

      (contains? terminal-attempt-states (:state attempt))
      (if (= state (:state attempt))
        {:ok? true
         :session session
         :ignored :duplicate-terminal
         :effects []}
        {:ok? true
         :session (assoc-in session
                            [:attempts (:attempt-id attempt) :late-terminal]
                            {:kind kind :at now})
         :ignored :late-terminal
         :effects []})

      :else
      (let [call-id (:call-id attempt)
            run-id (:run-id attempt)
            call (get-in session [:calls call-id])
            run (get-in session [:runs run-id])
            late? (boolean (:cancel-requested? call))
            content (or (:content event)
                        (some->> (get-in session
                                         [:attempts (:attempt-id attempt) :chunks])
                                 (map :text)
                                 (apply str)))
            result (select-keys event [:content :error :usage :finish-reason])
            session' (-> session
                         (assoc-in [:attempts (:attempt-id attempt) :state] state)
                         (assoc-in [:attempts (:attempt-id attempt) :terminal] event)
                         (assoc-in [:attempts (:attempt-id attempt) :finished-at] now)
                         (assoc-in [:attempts (:attempt-id attempt) :updated-at] now)
                         (assoc-in [:calls call-id :state] state)
                         (assoc-in [:calls call-id :result] result)
                         (assoc-in [:calls call-id :late?] late?)
                         (assoc-in [:calls call-id :updated-at] now))
            run' (let [current-run (get-in session' [:runs run-id])
                       next-state (if (= :cancelled (:state current-run))
                                    :cancelled
                                    (run-state-after-terminal session' current-run))]
                   (-> current-run
                       (assoc :state next-state
                              :updated-at now)
                       (cond-> (not= :running next-state)
                         (assoc :completed-at now))))
            session'' (assoc-in session' [:runs run-id] run')]
        {:ok? true
         :session session''
         :terminal state
         :content (when (= :succeeded state) content)
         :late? (boolean late?)
         :effects [{:kind :notify
                    :session-id (:session-id session)
                    :event {:type :model-terminal
                            :session-id (:session-id session)
                            :run-id run-id
                            :call-id call-id
                            :attempt-id (:attempt-id attempt)
                            :state state
                            :late? (boolean late?)}}]}))))

(defn accept-event
  "Accept one model-port event for an attempt.

  Chunks and tool calls from a stale attempt are ignored.  The first terminal
  event for the current attempt fixes the call; later or different terminals
  are diagnostics.  An event never reopens a terminal attempt and never
  changes a deleted message."
  [session event now]
  (let [attempt-id (:attempt-id event)
        attempt (get-in session [:attempts attempt-id])
        call (get-in session [:calls (:call-id attempt)])
        kind (:kind event)]
    (cond
      (nil? attempt)
      (error session :ATTEMPT_UNKNOWN
             "attempt does not exist" {:attempt-id attempt-id})

      (not= attempt-id (:current-attempt-id call))
      {:ok? true
       :session session
       :ignored :stale-attempt
       :effects []}

      (= :chunk kind)
      (accept-chunk session attempt event now)

      (= :tool-call kind)
      (accept-tool-call session attempt event now)

      (contains? #{:completed :succeeded :failed :cancelled :outcome-unknown}
                 kind)
      (accept-terminal session attempt event now)

      :else
      (error session :EVENT_KIND_INVALID
             "unknown model event kind" {:kind kind}))))

(defn cancel-run
  "Cancel a running run.

  The run becomes `:cancelled` immediately.  Calls remain in the state with
  `:cancel-requested?`; their attempts stay until a terminal event closes
  them.  Every active attempt receives a cancellation effect.  A ready call
  is closed immediately because no external work started."
  [session {:keys [run-id reason now]}]
  (let [run (get-in session [:runs run-id])]
    (cond
      (nil? run)
      (error session :RUN_NOT_FOUND "run does not exist" {:run-id run-id})

      (not= :running (:state run))
      {:ok? true
       :session session
       :ignored :run-not-running
       :effects []}

      :else
      (let [[session' effects]
            (reduce
             (fn [[session effects] call-id]
               (let [call (get-in session [:calls call-id])]
                 (cond
                   (not (call-active? call))
                   [session effects]

                   (contains? #{:ready :retry-wait} (:state call))
                   [(assoc-in session [:calls call-id :state] :cancelled)
                    effects]

                   :else
                   (let [attempt-id (:current-attempt-id call)
                         attempt (get-in session [:attempts attempt-id])
                         session (-> session
                                     (assoc-in [:calls call-id :cancel-requested?] true)
                                     (assoc-in [:calls call-id :cancel-reason] reason)
                                     (assoc-in [:calls call-id :updated-at] now)
                                     (cond-> (and attempt
                                                  (attempt-active? attempt))
                                       (assoc-in [:attempts attempt-id
                                                  :cancel-requested?] true)))]
                     [session
                      (cond-> effects
                        (and attempt (attempt-active? attempt))
                        (conj {:kind :cancel-attempt
                               :session-id (:session-id session)
                               :run-id run-id
                               :call-id call-id
                               :attempt-id attempt-id
                               :reason reason}))]))))
             [session []]
             (:call-order run))
            run' (-> (get-in session' [:runs run-id])
                     (assoc :state :cancelled
                            :cancel-reason reason
                            :cancel-requested-at now
                            :completed-at now
                            :updated-at now))
            session'' (assoc-in session' [:runs run-id] run')]
        {:ok? true
         :session session''
         :run run'
         :effects effects}))))

(defn run-affected-by-message?
  [session message-id]
  (when-let [run (active-run session)]
    (let [snapshot (:input-snapshot run)]
      (or (contains? (set (:message-ids snapshot)) message-id)
          (= message-id (:target-message-id snapshot))))))

(defn check-invariants
  "Return a vector of run/call/attempt invariant violations."
  [session]
  (let [runs (:runs session)
        calls (:calls session)
        attempts (:attempts session)
        run-order (:run-order session)
        active-runs (filter #(= :running (:state (get runs %))) run-order)]
    (into
     []
     (concat
      (cond-> []
        (not (map? runs))
        (conj {:invariant :runs-not-a-map})

        (not (map? calls))
        (conj {:invariant :calls-not-a-map})

        (not (map? attempts))
        (conj {:invariant :attempts-not-a-map})

        (not= (set run-order) (set (keys runs)))
        (conj {:invariant :run-order-mismatch
               :order run-order
               :keys (keys runs)})

        (and (:current-run-id session)
             (not (contains? runs (:current-run-id session))))
        (conj {:invariant :current-run-missing
               :current-run-id (:current-run-id session)})

        (> (count active-runs) 1)
        (conj {:invariant :multiple-active-runs
               :run-ids (vec active-runs)})
)
      (mapcat
       (fn [[run-id run]]
         (cond-> []
           (not= run-id (:run-id run))
           (conj {:invariant :run-id-mismatch
                  :key run-id :run-id (:run-id run)})

           (not (contains? run-states (:state run)))
           (conj {:invariant :run-state-invalid
                  :run-id run-id :state (:state run)})

           (not (map? (:input-snapshot run)))
           (conj {:invariant :run-snapshot-missing
                  :run-id run-id})

           (not (map? (:model-binding run)))
           (conj {:invariant :run-binding-missing
                  :run-id run-id})

           (not= (set (:call-order run))
                 (set (filter #(= run-id (get-in calls [% :run-id]))
                              (:call-order run))))
           (conj {:invariant :run-call-order-mismatch
                  :run-id run-id})))
       runs)
      (mapcat
       (fn [[call-id call]]
         (let [run (get runs (:run-id call))
               attempt-id (:current-attempt-id call)
               attempt (get attempts attempt-id)]
           (cond-> []
             (not= call-id (:call-id call))
             (conj {:invariant :call-id-mismatch
                    :key call-id :call-id (:call-id call)})

             (nil? run)
             (conj {:invariant :call-run-missing
                    :call-id call-id :run-id (:run-id call)})

             (not (contains? call-states (:state call)))
             (conj {:invariant :call-state-invalid
                    :call-id call-id :state (:state call)})

             (not (map? (:input-snapshot call)))
             (conj {:invariant :call-snapshot-missing
                    :call-id call-id})

             (and run
                  (not= (:input-snapshot call) (:input-snapshot run)))
             (conj {:invariant :call-snapshot-differs-from-run
                    :call-id call-id})

             (and (contains? #{:dispatching :running} (:state call)) (nil? attempt))
             (conj {:invariant :active-call-without-attempt
                    :call-id call-id
                    :current-attempt-id attempt-id})

             (and attempt
                  (not (contains? attempt-states (:state attempt))))
             (conj {:invariant :attempt-state-invalid
                    :attempt-id attempt-id
                    :state (:state attempt)})

             (and attempt
                  (not= (:call-id attempt) call-id))
             (conj {:invariant :attempt-call-mismatch
                    :attempt-id attempt-id
                    :call-id (:call-id attempt)})

             (and attempt
                  (not (map? (:tool-requests attempt))))
             (conj {:invariant :attempt-tool-requests-invalid
                    :attempt-id attempt-id})))
         )
       calls)
      (mapcat
       (fn [[attempt-id attempt]]
         (cond-> []
           (nil? (get calls (:call-id attempt)))
           (conj {:invariant :attempt-call-missing
                  :attempt-id attempt-id
                  :call-id (:call-id attempt)})

           (not (contains? attempt-states (:state attempt)))
           (conj {:invariant :attempt-state-invalid
                  :attempt-id attempt-id
                  :state (:state attempt)})

           (and (contains? terminal-attempt-states (:state attempt))
                (nil? (:terminal attempt)))
           (conj {:invariant :terminal-attempt-without-event
                  :attempt-id attempt-id})))
       attempts)))))
