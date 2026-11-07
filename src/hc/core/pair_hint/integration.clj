(ns hc.core.pair-hint.integration
  "Stateful adapters for the real Owner/app request path.

  `project-request` in `hc.core.pair-hint` is pure.  This namespace supplies
  the thin stateful wrapper a gateway needs: it reads the persisted Pair Hint
  session, projects one observed request, commits the new session only on
  success, then delegates to the real model gateway.  The wrapper requires no
  Owner or server namespace, so it can be installed at either seam without
  creating a dependency cycle."
  (:require [hc.core.pair-hint.hint :as hint]))

(defn- session-value
  [state]
  (let [value @state]
    (if (and (map? value) (contains? value :session))
      (:session value)
      value)))

(defn- commit-session!
  [state session]
  (swap! state
         (fn [value]
           (if (and (map? value) (contains? value :session))
             (assoc value :session session)
             session)))
  session)

(defn- option
  "Resolve a wrapper option that may be a value or a function of
  `[request session]`."
  [value request session]
  (if (fn? value)
    (value request session)
    value))

(defn- projection-opts
  [request session opts]
  (let [base (or (option (:request-opts opts) request session) {})
        base (if (map? base) base {})
        placement (or (option (:placement opts) request session)
                      (:placement base)
                      {:call-id (or (:call-id request) (:attempt-id request))})
        ;; A Nudge is a kernel continuation, not a title/prediction side
        ;; turn.  The Owner's successor-run guard treats it as chain-exempt,
        ;; so it is an explicit exception here rather than an accidental
        ;; side-turn match.
        ephemeral? (boolean (or (option (:ephemeral? opts) request session)
                                (:ephemeral? base)
                                (get-in request [:snapshot :nudge-id])
                                (get-in request [:snapshot :ephemeral?])
                                (= "agent"
                                   (get-in request [:snapshot :attribution]))))
        observations (or (option (:observations opts) request session)
                         (:observations base))
        context (or (option (:context opts) request session)
                    (:context base))
        boundary (or (option (:boundary opts) request session)
                     (:boundary base))
        ;; The hint must still reach a model that declares no `:tools`
        ;; capability, but it must not arrive as a tool-call/tool envelope
        ;; that model cannot use.  The caller states the capability fact and
        ;; the format follows from it; the fact also travels into the
        ;; projection, so why a format was chosen is recorded rather than
        ;; inferred.
        ;;
        ;; A capability stated directly on the wrapper is more specific than
        ;; one arriving through `:request-opts`, so it is consulted first and
        ;; the pass-through value is only a fallback.  Consulting `base` for
        ;; both would let `:request-opts` override the wrapper's own fact.
        direct-tools (option (:tools-supported? opts) request session)
        tools-supported? (if (some? direct-tools)
                           direct-tools
                           (:tools-supported? base))
        ;; An explicit `:format` is a real caller override.  Otherwise the
        ;; capability decides; only then does the default apply.  This keeps
        ;; a caller who states only the capability fact from silently getting
        ;; the tool envelope.
        format (or (:format opts)
                   (when-not (contains? base :format)
                     (when (false? tools-supported?) :notes))
                   (:format base)
                   :flat)]
    (cond-> (assoc base
                   :placement placement
                   :ephemeral? ephemeral?
                   :format format)
      (some? tools-supported?) (assoc :tools-supported? tools-supported?)
      (some? observations) (assoc :observations observations)
      (some? context) (assoc :context context)
      (some? boundary) (assoc :boundary boundary)
      (contains? opts :render) (assoc :render (:render opts)))))

(defn wrap-model-gateway
  "Wrap a model gateway `{:start! :cancel!}` with Pair Hint projection.

  Options:
  - `:state` atom holding either a Pair Hint session or `{:session ...}`.
    If omitted, a fresh atom is created from `:session` or `create-session`.
  - `:session` initial Pair Hint session (ignored when `:state` already holds
    a session)
  - `:load-session` `(fn [] pair-session)` used when the state atom is empty
  - `:persist-session` `(fn [session request] ...)` durable write after commit
  - `:render` required EDN/UFSS renderer, normally `hc.core.ufss/write-str`
  - `:format` `:flat` (default) or `:blocks`
  - `:request-opts` map or `(fn [request session] map)` merged into
    `project-request` options.  One key is a fact rather than a preference:
    a `:tools-supported?` stated here still selects `:notes` for a tools-less
    model, and a directly stated `:tools-supported?` on the wrapper outranks
    it.  An explicit `:format` from either channel wins over both.  The
    distinction matters because a computed default `:flat` would otherwise be
    indistinguishable from a caller who deliberately chose `:flat`.
  - `:placement` value or `(fn [request session] key)`; defaults to
    `{:call-id (:call-id request)}` so retries replay the same pair
  - `:ephemeral?` value or `(fn [request session] bool)`
  - `:observations` value or `(fn [request session] observations)`
  - `:context` value or `(fn [request session] context)`
  - `:boundary` value or `(fn [request session] boundary)`
  - `:tools-supported?` value or `(fn [request session] bool)`; `false`
    selects the `:notes` hint format so a model that declares no `:tools`
    capability never receives a tool-call/tool envelope.  Absent means the
    caller states nothing and the default `:flat` tool pair is used.
  - `:on-session` `(fn [session request] ...)` after a successful projection
  - `:on-error` `(fn [error request] ...)` before projection failure is thrown

  `:on-session` and `:persist-session` run on the dispatch thread after the
  projection lock is released.  They must return promptly; durable writes
  belong on a worker/future.  The app wires mission-extension persistence
  through a future so a slow Owner mailbox cannot deadlock model start.

  The underlying gateway receives the projected request.  A projection
  failure throws `ExceptionInfo` with `:error`; the real gateway is never
  called.  `:cancel!` delegates unchanged.

  The wrapper is safe for concurrent `:start!` calls: projection and session
  commit happen under one lock; the provider call happens outside it."
  [gateway opts]
  (when-not (and (map? gateway) (ifn? (:start! gateway)))
    (throw (ex-info "pair-hint: wrap-model-gateway needs a :start! gateway"
                    {:error :PAIR_HINT_GATEWAY_INVALID})))
  (let [state (or (:state opts) (atom nil))
        _ (when-not (instance? clojure.lang.IAtom state)
            (throw (ex-info "pair-hint: :state must be an atom"
                            {:error :PAIR_HINT_STATE_INVALID})))
        _ (when (nil? (session-value state))
            (let [loaded (or (:session opts)
                             (when-let [load (:load-session opts)]
                               (load)))
                  initial (if (and (map? loaded) (:language loaded))
                            (hint/resume-session loaded opts)
                            (hint/create-session opts))]
              (commit-session! state initial)))
        lock (Object.)
        session #(or (session-value state)
                     (throw (ex-info "pair-hint: wrapper state has no session"
                                     {:error :PAIR_HINT_SESSION_MISSING})))]
    {:state state
     :session session
     :start!
     (fn [request deliver!]
       (let [projected
             (locking lock
               (let [current (session)
                     result (hint/project-request
                             current
                             request
                             (projection-opts request current opts))]
                 (if (:ok? result)
                   (let [changed? (not= current (:session result))]
                     (commit-session! state (:session result))
                     (assoc result :changed? changed?))
                   result)))]
         (if (:ok? projected)
           (do
             ;; Persistence callbacks run outside the projection lock and must
             ;; not block the dispatch thread.  The app wraps durable submits
             ;; in a future; a callback that performs blocking I/O belongs on
             ;; its own worker.
             (when (:changed? projected)
               (when-let [on-session (:on-session opts)]
                 (on-session (:session projected) request))
               (when-let [persist (:persist-session opts)]
                 (persist (:session projected) request)))
             ((:start! gateway) (:request projected) deliver!))
           (do
             (when-let [on-error (:on-error opts)]
               (on-error (:error projected) request))
             (throw (ex-info "pair-hint: request projection failed"
                             {:error (:error projected)
                              :request request}))))))
     :cancel!
     (fn [handle]
       (when-let [cancel (:cancel! gateway)]
         (cancel handle)))}))

(defn board-snapshot-entry
  "Build an observation entry from a full board snapshot.

  The owner/app supplies the full board; `:writer? true` makes the next
  projection a clean-break that discards old board deltas.  A reader that
  only has a delta should pass its own `:changes` entry instead."
  ([board] (board-snapshot-entry board {}))
  ([board {:keys [writer?]}]
   (when (map? board)
     {:observation {:kind :snapshot
                    :board-version (or (:board-version board) 0)
                    :snapshot board}
      :opts (cond-> {} writer? (assoc :writer? true))})))

(defn scope-boundary
  "Translate a settled Scope annotation/event into a Pair Hint boundary.

  `:reset-scope` opens `:scope-reset`; `:remove-last` opens
  `:scope-remove`.  Anything else returns nil, so the caller can keep the
  existing chain."
  [annotation]
  (let [kind (:kind annotation)
        call-id (or (:call-id annotation) (:turn-id annotation))]
    (case kind
      :reset-scope (cond-> {:kind :scope-reset :call-id call-id}
                     (some? annotation) (assoc :raw annotation))
      :remove-last (cond-> {:kind :scope-remove :call-id call-id}
                     (some? annotation) (assoc :raw annotation))
      nil)))
