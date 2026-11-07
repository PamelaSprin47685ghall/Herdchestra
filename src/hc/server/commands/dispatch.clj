(ns hc.server.commands.dispatch
  "Durable command envelope wrapper for a multi-destination router.

  The host supplies:

    :gate              a single `hc.server.commands.durable` gate, or
    :gate-for          (fn [scope] gate)
    :window-fn         (fn [] window) or (fn [scope] window)
    :scope-fn          (fn [request] scope-or-result)
    :dispatch!         (fn [request reply-fn] result)
    :fallback-receipt  (fn [command-id] receipt) or
                       (fn [scope command-id] receipt)
    :resolve-replay    (fn [request stored-result] result-or-nil) optional
    :owner-kind?       (fn [kind] bool) optional
    :read-only-kind?   (fn [kind] bool) optional

  `:scope-fn` may return a scope string or
  `{:ok? true :scope scope}` / `{:ok? false :error error}`.

  Owner-routed kinds are already gated by the session Owner.  Read-only
  kinds do not change durable state.  Both are delegated unchanged.  Every
  other kind follows the durable sequence:

    accept placeholder -> dispatch -> complete final receipt

  A replay returns the stored receipt without dispatching.  A mismatch or a
  retired or unknown epoch is refused before dispatch.  If the receipt
  cannot be committed after a side effect, the result is `:outcome
  :unknown`, never a false failure.

  A stored `:dispatching` or `:outcome-unknown` receipt is a recovery
  placeholder, not a final answer.  When `:resolve-replay` is supplied and
  returns a result, the wrapper completes the placeholder with that result
  and replies once.  The host uses this to read the authoritative business
  store after a crash."
  (:require [hc.server.commands.durable :as durable]
            [hc.server.commands.receipts :as receipts]))

(def default-owner-kinds
  "Kinds the router delegates to the Owner without a router-side gate.

  `:send-message` and `:user/message` are excluded because they must pass
  the session command epoch before the Owner business gate.  The router
  never runs web search: the search intent stays on the payload, and the
  model gateway runs it on a worker only after the Owner commits the
  accepted turn."
  #{:stop-run :answer-ask :operator/cancel
    :user/unpark :review/submit :board/command :report/ack :report/register
    :session/issue-epoch :session/edit-message :session/select-model
    :session/rename :session/update-draft :session/pin :session/archive
    :session/restore :session/transfer})

(def default-read-only-kinds
  #{:session/list :settings/list-models :browser/snapshot :browser/cards
    :speech/snapshot :diagnostics/status :extensions/status :extensions/view
    :materials/snapshot :scheduler/list :backup/status
    :documents/ocr-status})

(defn default-owner-kind?
  [kind]
  (or (contains? default-owner-kinds kind)
      (and (keyword? kind)
           (= "history" (namespace kind))
           (not= :history/branch kind))))

(defn default-read-only-kind?
  [kind]
  (contains? default-read-only-kinds kind))

(defn kind-of
  [request]
  (get-in request [:payload :kind]))

(defn default-scope
  "The scope used when the host does not supply :scope-fn."
  [request]
  (or (get-in request [:payload :session-id])
      (:session-id request)
      "app"))

(defn owner-window
  "Read the committed command window from one compose Owner handle.

  The handle must expose `:snapshot`.  A live Owner returns its active
  window.  A retired Owner returns `{:command-epoch nil :retired-through n}`
  so the gate can classify old commands as retired instead of keeping the
  last active window forever.  A missing or foreign handle returns nil; the
  caller decides how to report that."
  [owner]
  (when (and owner (ifn? (:snapshot owner)))
    (try
      (let [commands (get-in ((:snapshot owner)) [:state :commands])
            retired (:retired-through commands)]
        (cond
          (not (map? commands)) nil
          (:command-epoch commands)
          {:command-epoch (:command-epoch commands)
           :retired-through (or retired 0)}
          (and (integer? retired) (pos? retired))
          {:command-epoch nil :retired-through retired}))
      (catch Throwable _ nil))))

(defn owner-receipt
  "Read one durable receipt from a compose Owner handle."
  [owner command-id]
  (when (and owner (ifn? (:snapshot owner)))
    (try
      (get-in ((:snapshot owner))
              [:state :commands :receipts command-id :receipt])
      (catch Throwable _ nil))))

(defn- one-arg-fn?
  [f]
  (try
    (f ::probe)
    true
    (catch clojure.lang.ArityException _ false)))

(defn- two-arg-fn?
  [f]
  (try
    (f ::probe ::probe)
    true
    (catch clojure.lang.ArityException _ false)))

(defn- scope-result
  [scope-fn request]
  (let [result (scope-fn request)]
    (cond
      (map? result)
      result

      (string? result)
      {:ok? true :scope result}

      (nil? result)
      {:ok? true :scope "app"}

      :else
      {:ok? false
       :error {:code :COMMAND_SCOPE_INVALID
               :message "scope-fn returned an invalid scope"
               :details {:scope result}}})))

(defn- gate-for
  [opts scope]
  (if-let [f (:gate-for opts)]
    (f scope)
    (:gate opts)))

(defn- window-for
  [opts scope]
  (if-let [f (:window-for opts)]
    (f scope)
    (let [f (:window-fn opts)]
      (cond
        (nil? f) nil
        (one-arg-fn? f) (f scope)
        :else (f)))))

(defn- fallback-receipt-for
  [opts scope command-id]
  (if-let [f (:fallback-receipt-for opts)]
    (f scope command-id)
    (when-let [f (:fallback-receipt opts)]
      (if (two-arg-fn? f)
        (f scope command-id)
        (f command-id)))))

(defn- sync-window-best-effort!
  "Adopt the host's current window before reading a receipt.

  A receipt query must not answer from an epoch that the Owner already
  retired.  Returns true when the gate state is trustworthy for this query:
  no window was supplied, the window was already current, or the sync
  committed.  A store failure leaves the cached state in place and returns
  false, so the caller skips the gate receipt."
  [opts gate scope]
  (if gate
    (try
      (if-let [window (window-for opts scope)]
        (boolean (:ok? (durable/sync-window! gate window)))
        true)
      (catch Throwable _ false))
    true))

(defn- normalize-result
  [result]
  (cond
    (not (map? result))
    {:ok? false
     :error {:code :COMMAND_RESULT_INVALID
             :message "router returned a non-map result"}}

    (contains? result :ok?)
    result

    (= :refused (:status result))
    {:ok? false
     :receipt result
     :error (or (:error result)
                {:code :COMMAND_REFUSED
                 :message "command was refused"})}

    :else
    {:ok? true :receipt result}))

(defn- refusal-receipt
  [request result]
  (or (:receipt result)
      {:status :refused
       :command-id (:command-id request)
       :command-epoch (:command-epoch request)
       :error (:error result)}))

(defn- accepted-receipt
  [request result]
  (or (:receipt result)
      {:status :accepted
       :command-id (:command-id request)
       :command-epoch (:command-epoch request)}))

(defn- outcome-receipt
  [request result]
  (assoc (or (:receipt result)
             {:command-id (:command-id request)
              :command-epoch (:command-epoch request)})
         :status :outcome-unknown
         :error (or (:error result)
                    {:code :COMMAND_OUTCOME_UNKNOWN
                     :message "command outcome is unknown"})))

(defn- reply-once!
  [replied? reply-fn value]
  (when (compare-and-set! replied? false true)
    (when (ifn? reply-fn)
      (reply-fn value)))
  value)

(defn- resolved-replay
  [resolve-replay request stored]
  (when (ifn? resolve-replay)
    (try
      (resolve-replay request stored)
      (catch Throwable _ nil))))

(defn- current-window
  [opts scope]
  (try
    (window-for opts scope)
    (catch Throwable _ nil)))

(defn- complete-and-reply!
  [opts gate scope request replied? reply-fn result redact?]
  (if-not (compare-and-set! replied? false true)
    result
    (let [result (normalize-result result)
          receipt (cond
                    (= :unknown (:outcome result))
                    (outcome-receipt request result)

                    (:ok? result)
                    (accepted-receipt request result)

                    :else
                    (refusal-receipt request result))
          receipt (if redact? (receipts/redact-secrets receipt) receipt)
          window (current-window opts scope)
          completion (try
                       (if (some? window)
                         (durable/complete! gate window scope
                                            (:command-id request)
                                            receipt)
                         (durable/complete! gate scope
                                            (:command-id request)
                                            receipt))
                       (catch Throwable t
                         {:ok? false
                          :error
                          {:code :COMMAND_RECEIPT_COMMIT_FAILED
                           :message
                           "command side effect completed but its receipt could not be committed"
                           :cause (str t)
                           :action :resync-state}}))
          final (if (:ok? completion)
                  (assoc result :replayed? (boolean (:replayed? result)))
                  (assoc result
                         :ok? false
                         :receipt receipt
                         :outcome :unknown
                         :error
                         (or (:error completion)
                             {:code :COMMAND_RECEIPT_COMMIT_FAILED
                              :message
                              "command side effect completed but its receipt was not committed"
                              :action :resync-state})))]
      (when (ifn? reply-fn)
        (reply-fn final))
      final)))

(defn submit!
  "Submit one request through the durable gate and DISPATCH!.

  Returns the dispatch result when it is synchronous.  The reply function is
  called exactly once."
  [{:keys [window-fn dispatch! scope-fn owner-kind? read-only-kind?
           sensitive-request? resolve-replay]
    :or {owner-kind? default-owner-kind?
         read-only-kind? default-read-only-kind?
         sensitive-request? (constantly false)
         scope-fn default-scope}
    :as opts}
   request
   reply-fn]
  (let [kind (kind-of request)
        sensitive? (boolean (sensitive-request? request))
        gate-request (cond-> request
                       sensitive? (assoc :redact-payload? true))]
    (if (or (owner-kind? kind) (read-only-kind? kind))
      (dispatch! request reply-fn)
      (let [scope-result (scope-result scope-fn request)]
        (if-not (:ok? scope-result)
          (reply-once! (atom false) reply-fn
                       {:ok? false :error (:error scope-result)})
          (let [scope (:scope scope-result)
                gate (gate-for opts scope)
                window (window-for opts scope)]
            (cond
              (nil? gate)
              (reply-once! (atom false) reply-fn
                           {:ok? false
                            :error {:code :COMMAND_GATE_UNAVAILABLE
                                    :message "no durable command gate for this scope"
                                    :details {:scope scope}}})

              (nil? window)
              (reply-once! (atom false) reply-fn
                           {:ok? false
                            :error {:code :COMMAND_WINDOW_UNAVAILABLE
                                    :message "no active session command epoch"
                                    :details {:scope scope}
                                    :action :resync-state}})

              :else
              (let [accepted
                    (try
                      (durable/accept! gate window scope gate-request
                                       {:status :dispatching
                                        :command-id (:command-id request)
                                        :command-epoch (:command-epoch request)})
                      (catch Throwable t
                        {:ok? false
                         :state nil
                         :error
                         {:code :COMMAND_GATE_FAILED
                          :message
                          "the durable command gate failed before dispatch"
                          :cause (str t)
                          :action :resync-state}
                         :effects []}))]
                (cond
                  (not (:ok? accepted))
                  (reply-once! (atom false) reply-fn accepted)

                  (:replayed? accepted)
                  (if-let [resolved (resolved-replay resolve-replay
                                                     request accepted)]
                    (complete-and-reply! opts gate scope request (atom false)
                                         reply-fn resolved sensitive?)
                    (reply-once! (atom false) reply-fn accepted))

                  :else
                  (let [replied? (atom false)]
                    (try
                      (let [returned (dispatch!
                                      request
                                      (fn [result]
                                        (complete-and-reply!
                                         opts gate scope request replied? reply-fn
                                         result sensitive?)))]
                        (when (and (map? returned) (not @replied?))
                          (complete-and-reply!
                           opts gate scope request replied? reply-fn returned
                           sensitive?))
                        returned)
                      (catch Throwable t
                        (complete-and-reply!
                         opts gate scope request replied? reply-fn
                         {:ok? false
                          :outcome :unknown
                          :error {:code :COMMAND_DISPATCH_FAILED
                                  :message "router failed after dispatch started; the side effect may have occurred"
                                  :cause (str t)}}
                         sensitive?)))))))))))))

(defn receipt
  "Return a receipt from the durable gate, then the fallback.

  Arity 2 uses the single gate.  Arity 3 uses the gate for SCOPE."
  ([opts command-id]
   (let [gate (try (gate-for opts "app") (catch Throwable _ nil))]
     (let [synced? (sync-window-best-effort! opts gate "app")]
       (or (when (and gate synced?)
             (durable/receipt-for gate command-id))
           (fallback-receipt-for opts "app" command-id)))))
  ([opts scope command-id]
   (let [gate (try (gate-for opts scope) (catch Throwable _ nil))]
     (let [synced? (sync-window-best-effort! opts gate scope)]
       (or (when (and gate synced?)
             (durable/receipt-for gate scope command-id))
           (fallback-receipt-for opts scope command-id))))))

(defn wrap
  "Build a router-facing handle.

  The handle keeps the original options and adds `:submit!` and `:receipt`
  closures.  A caller may use either the closures or the raw functions with
  the handle."
  [opts]
  (assoc opts
         :submit! (fn [request reply-fn]
                    (submit! opts request reply-fn))
         :receipt (fn
                    ([command-id]
                     (receipt opts command-id))
                    ([scope command-id]
                     (receipt opts scope command-id)))))
