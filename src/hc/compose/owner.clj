(ns hc.compose.owner
  "Public facade for one single-writer Owner.

  Start one Owner per session or board coordination domain.  The returned
  handle is safe to share across gateway, model, and tool threads:

    (:submit! handle message)          async, returns queue acceptance
    (:submit-command! handle request)  synchronous client command receipt
    (:execute-board! handle command)   synchronous board command receipt
    (:board-snapshot handle)           last committed swarm board
    (:subscribe! handle listener)      ordered notifications, returns cancel
    (:snapshot handle)                 committed state and stats
    (:stats handle)                    counters
    (:await-idle! handle timeout-ms)   mailbox and attempts drained
    (:stop! handle [opts])             close admission, cancel, drain, close

  Only `:submit-command!`, `:execute-board!`, `:snapshot`, and `:stats` are
  safe to call from the Owner thread itself; the others may block or
  enqueue.  Subscribers run on a separate ordered notification thread."
  (:require [hc.compose.owner.board :as board]
            [hc.compose.owner.runtime :as runtime]
            [hc.compose.owner.state :as state]
            [hc.compose.owner.store :as store]))

(def memory-store
  "In-memory `Store` for tests and ephemeral sessions."
  store/memory-store)

;; Session ids with a live Owner mailbox in this process.  The store CAS
;; serializes commits, but it cannot make two mailboxes one logical writer.
(defonce ^:private active-owners (atom #{}))

(defn- claim-owner-slot!
  [session-id]
  (let [claimed? (atom false)]
    (swap! active-owners
           (fn [owners]
             (if (contains? owners session-id)
               owners
               (do (reset! claimed? true)
                   (conj owners session-id)))))
    (when-not @claimed?
      (throw (ex-info "an Owner is already running for this session"
                      {:code :OWNER_ALREADY_RUNNING
                       :session-id session-id})))))

(defn- release-owner-slot!
  [session-id]
  (swap! active-owners disj session-id))

(defn report-bridge
  "Build the cross-Owner report sink and acknowledgement router.

  SELF returns this Owner's handle, or nil while it is starting.
  OWNER-FOR resolves a session id to another Owner handle, or nil.

  A report with `:parent-session-id` goes to that Owner.  A report without
  one goes to SELF.  The sink adds `:child-session-id`; the parent stores it
  and returns it in the acknowledgement, so the ack routes back to the child
  Owner.  A missing target is a retryable sink failure, never a dropped
  report."
  ([self owner-for] (report-bridge self owner-for {}))
  ([self owner-for {:keys [forward-effect!]}]
   (when-not (ifn? self)
     (throw (ex-info "report-bridge needs a self function"
                     {:code :INVALID_SELF})))
   (when-not (ifn? owner-for)
     (throw (ex-info "report-bridge needs an owner-for function"
                     {:code :INVALID_OWNER_FOR})))
   (when (and (some? forward-effect!) (not (ifn? forward-effect!)))
     (throw (ex-info "report-bridge :forward-effect! must be callable"
                     {:code :INVALID_FORWARD_EFFECT})))
   (letfn [(resolve-owner [session-id]
             (let [child (self)]
               (if (nil? session-id)
                 child
                 (or (owner-for session-id)
                     (when (and child
                                (= session-id (:session-id child)))
                       child)))))
          (report-sink! [report]
            (let [child (self)
                  parent-session-id (:parent-session-id report)
                  target (if (some? parent-session-id)
                           (resolve-owner parent-session-id)
                           child)]
              (cond
                (nil? child)
                {:ok? false
                 :error {:code :CHILD_OWNER_UNAVAILABLE
                         :message "the child Owner handle is not ready"}}

                (nil? target)
                {:ok? false
                 :error {:code :PARENT_OWNER_UNAVAILABLE
                         :message "the parent Owner is not running"
                         :details {:parent-session-id parent-session-id}}}

                :else
                (let [accepted ((:submit! target)
                                {:type :child/report
                                 :report (assoc report
                                                :child-session-id
                                                (:session-id child))})]
                  (if (:ok? accepted)
                    {:ok? true}
                    {:ok? false :error (:error accepted)})))))
          (on-effect! [event]
            (if (= :report/ack (:type event))
              (let [ack (:ack event)
                    target (resolve-owner (:child-session-id ack))]
                (when target
                  ((:submit! target) {:type :report/ack :ack ack}))
                target)
              (do
                ;; Report routing is not a reason to swallow the rest of the
                ;; Owner's advisory effects.  A composed handler receives
                ;; `:owner/blocked`, `:swarm/wake-all`, and similar events.
                (when forward-effect!
                  (forward-effect! event))
                nil)))]
     {:report-sink! report-sink!
      :on-effect! on-effect!})))

(defn start-owner!
  "Start one Owner runtime and return its handle.

  Required deps: `:session-id` and either `:store` or `:ephemeral? true`.

  Optional deps:

    :initial-state    state used only when the store is empty
    :owner-instance-id Owner-start identity; defaults to a fresh value per
                       start so persisted draft claims from a dead
                       continuation are cleared, while an explicitly reused
                       identity retains them
    :user-input       first user text for a new session
    :draft            `{:path \"plans/<name>.md\" :content \"...\"}`
    :plan-milestone   `:S1` (default) through `:pending`
    :retry-policy     retry policy map
    :config           `{:max-tool-concurrency n
                        :plan-tools [tool-spec]
                        :extension-tools-fn (fn [scope] [tool-spec])
                        :skill-context-fn (fn [scope] [{:id :name :text}])}`
    :executors        `{:tool f :shell f :workspace f}`
    :model-gateway    `{:start! (fn [request deliver!] handle)
                        :cancel! (fn [handle])}`
    :report-sink!     `(fn [report] result)`
    :session-port     `{:apply-command f :confirm-draft-saved f
                        :fail-draft-save f
                        :commit-session! (fn [previous session now])
                        :reconcile-session! (fn [session])}`
                       The commit function is the business barrier: a changed
                       session is persisted before the owner state and the
                       accepted receipt.  The reconcile function repairs a
                       lag between owner state and the committed session at
                       start.  A port without them refuses a session change.
    :clock            `(fn [] epoch-ms)`
    :quota            scheduler quota, default 32
    :on-effect!       advisory notification handler
    :on-user-send!    diagnostics callback for an accepted user send
    :on-call-terminal! diagnostics callback for a non-model executor terminal
    :on-error!        error handler
    :close-store?     stop! closes the store, default true"
  [deps]
  (let [session-id (:session-id deps)
        _ (claim-owner-slot! session-id)
        runtime (try
                  (runtime/start! deps)
                  (catch Throwable error
                    (release-owner-slot! session-id)
                    (throw error)))
        handle
        (try
          (let [handle
        {:session-id (:session-id runtime)
         :submit! (fn [message]
                    (runtime/submit! runtime message))
         :submit-command! (fn
                            ([request]
                             (runtime/submit-command! runtime request))
                            ([request timeout-ms]
                             (runtime/submit-command! runtime request
                                                      timeout-ms)))
         :execute-board! (fn
                           ([command]
                            (runtime/submit-board-command! runtime command))
                           ([command timeout-or-opts]
                            (if (map? timeout-or-opts)
                              (runtime/submit-board-command! runtime command
                                                             5000
                                                             timeout-or-opts)
                              (runtime/submit-board-command! runtime command
                                                             timeout-or-opts)))
                           ([command timeout-ms opts]
                            (runtime/submit-board-command! runtime command
                                                           timeout-ms opts)))
         :board-snapshot
         (fn []
           ;; `:board` is the single-active-tenure projection.  A swarm may
           ;; have several execution tenures (a relayed Reviewer leaves the
           ;; old one active), so the projection can be nil while the current
           ;; execution board exists.  The shell/token path needs that board
           ;; to resolve the live holder term.
           (let [state (:state (runtime/snapshot runtime))]
             (or (:board state)
                 (when-let [tenure (state/latest-active-execution-tenure state)]
                   (get-in state [:mission :boards (:tenure-id tenure)])))))
         :put-extension! (fn [key value]
                           (runtime/submit! runtime
                                            {:type :mission/extensions-put
                                             :key key
                                             :value value}))
         :subscribe! (fn [listener]
                       (runtime/subscribe! runtime listener))
         :snapshot (fn [] (runtime/snapshot runtime))
         :stats (fn [] (runtime/stats runtime))
         :await-idle! (fn [timeout-ms]
                        (runtime/await-idle! runtime timeout-ms))
         :stop! (fn
                  ([]
                   (try
                     (runtime/stop! runtime)
                     (finally (release-owner-slot! session-id))))
                  ([opts]
                   (try
                     (runtime/stop! runtime opts)
                     (finally (release-owner-slot! session-id)))))
         ::runtime runtime}
        port (board/make-port handle)]
    (assoc handle
           :board-port port
           :workspace-board (board/workspace-adapter port)
           :shell-board (board/shell-port port)))
          (catch Throwable error
            (release-owner-slot! session-id)
            (throw error)))]
    handle))
