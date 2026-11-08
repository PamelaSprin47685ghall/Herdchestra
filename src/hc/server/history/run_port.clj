(ns hc.server.history.run-port
  "Bridge one explicit history regeneration run to the Owner model loop.

  `start` claims the session call, freezes the model request from the session's
  outbound context, and returns the request plus the session call identity.
  `accept` routes one Owner model terminal back to the session run, where
  `sessions/accept-model-event` appends the regenerated version.

  The Owner owns dispatch and the model gateway.  This namespace performs no
  I/O and never calls a provider directly."
  (:require [hc.server.history.context :as context]
            [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.runs :as runs]))

(defn- version-entry
  "Return ENTRY with the exact frozen version fields when the run snapshot
  records a version id for its message."
  [session entry version-ids]
  (if-let [version-id (get version-ids (:message-id entry))]
    (let [stored (get-in session [:messages (:message-id entry)])
          version (some-> stored
                          message/normalize-message
                          (message/version-by-id version-id))]
      (if version
        (assoc entry
               :content (:content version)
               :attachments (vec (or (:attachments version) []))
               :citations (vec (or (:citations version) []))
               :quotes (vec (or (:quotes version) [])))
        entry))
    entry))

(defn- model-message
  [entry]
  (if (= :context-summary (:kind entry))
    {:role :system :text (:content entry)}
    (cond-> {:role (:role entry) :text (:content entry)}
      (:message-id entry) (assoc :message-id (:message-id entry))
      (seq (:attachments entry)) (assoc :attachments (vec (:attachments entry)))
      (seq (:citations entry)) (assoc :citations (vec (:citations entry)))
      (seq (:quotes entry)) (assoc :quotes (vec (:quotes entry))))))

(defn- frozen-entries
  "Return the model entries for RUN, invalidating a stale frozen projection.

  A run freezes its input when it starts.  A compression applied or undone
  while that run is still the active turn changes which messages the next
  request must carry; the frozen copy then no longer matches the session's
  current outbound entries.  Compare the ordered entry keys — message ids
  and compression ids in send order — because an undo changes which real
  messages a summary covers without changing the summary-id set.  A run
  created before any projection was frozen also falls back to the current
  projection."
  [session run]
  (let [snapshot (:input-snapshot run)
        frozen (get-in snapshot [:context :messages])
        entry-keys (fn [entries]
                     (vec (keep (fn [entry]
                                  (or (:compression-id entry)
                                      (:message-id entry)))
                                (or entries []))))
        outbound (context/outbound-context session)
        current-keys (entry-keys (:messages outbound))
        frozen-keys (entry-keys frozen)
        stale? (or (not (and (vector? frozen) (seq frozen)))
                   (if (seq current-keys)
                     (not= frozen-keys current-keys)
                     (not= (set (get-in snapshot [:context :summary-ids]))
                           (set (:summary-ids outbound)))))
        target (:target-message-id snapshot)
        ids (set (if stale? (:message-ids outbound) (:message-ids snapshot)))
        entries (if stale? (:messages outbound) frozen)
        entries (if target
                  (vec (take-while #(not= target (:message-id %)) entries))
                  entries)]
    (if stale?
      (filterv (fn [entry]
                 (or (= :context-summary (:kind entry))
                     (contains? ids (:message-id entry))))
               entries)
      entries)))

(defn- model-messages
  [session run]
  (let [version-ids (get-in run [:input-snapshot :context :version-ids])]
    (->> (frozen-entries session run)
         (map #(version-entry session % version-ids))
         (mapv model-message))))

(defn start
  "Claim the session call for a `:run-started` effect.

  Returns `{:ok? true :session s :request {...} :session-run {...}}` or a
  refusal.  The Owner creates its model call from `:request`; it must not
  dispatch before committing the returned session."
  [session effect {:keys [now id-source]}]
  (let [run-id (:run-id effect)
        call-id (:call-id effect)
        run (get-in session [:runs run-id])
        call (get-in session [:calls call-id])
        binding (:model-binding session)
        attempt-id (ids/next-id! (or id-source (ids/uuid-source))
                                 :attempt)]
    (cond
      (nil? run)
      {:ok? false
       :error {:code :SESSION_RUN_NOT_FOUND
               :message "session run does not exist"
               :details {:run-id run-id}}}

      (nil? call)
      {:ok? false
       :error {:code :SESSION_CALL_NOT_FOUND
               :message "session call does not exist"
               :details {:call-id call-id}}}

      (not (map? binding))
      {:ok? false
       :error {:code :MODEL_NOT_SELECTED
               :message "session has no model binding"}}

      :else
      (let [claim (runs/claim-call session
                                   {:call-id call-id
                                    :attempt-id attempt-id
                                    :now now})]
        (if-not (:ok? claim)
          claim
          (let [session' (:session claim)
                messages (model-messages session' run)
                regenerate? (= :append-version
                               (get-in run [:input-snapshot :target-mode]))]
            (if (empty? messages)
              {:ok? false
               :error {:code :REGENERATE_CONTEXT_EMPTY
                       :message "regeneration has no model messages"}}
              {:ok? true
               :session session'
               :attempt-id attempt-id
               :request {:snapshot {:role :main
                                    :connection (:connection-id binding)
                                    :model (:model-entry-id binding)
                                    :session-id (:session-id session')
                                    :user-turn? (not regenerate?)
                                    :regenerate? regenerate?
                                    :turn-id (:run-id effect)
                                    :temporary-read?
                                    (true? (get-in run
                                                   [:input-snapshot
                                                    :temporary-read?]))
                                    :messages messages}
                         :required-capabilities #{}}
               :session-run {:run-id run-id
                             :call-id call-id
                             :attempt-id attempt-id
                             :session-id (:session-id session')}})))))))

(defn accept
  "Route one Owner model terminal to the session run.

  `session-run` is the metadata stored on the Owner model call.  The returned
  `:session` already contains the appended version for a successful terminal."
  [session {:keys [event session-run now]}]
  (let [kind (:event event)
        session-event (cond-> {:kind kind
                               :attempt-id (:attempt-id session-run)}
                        (some? (:text event))
                        (assoc :content (:text event))
                        (some? (:usage event))
                        (assoc :usage (:usage event))
                        (some? (:error event))
                        (assoc :error (:error event)))]
    (sessions/accept-model-event session session-event {:now now})))

(defn fail
  "Close a session run that could not be dispatched.

  The run is cancelled with a stable reason, the session call/attempt is
  closed, and the failure is attached to the run so the responsibility stays
  visible.  This prevents an accepted regeneration from remaining `:running`
  forever when the model bridge is unavailable or invalid."
  [session effect error {:keys [now]}]
  (let [run-id (:run-id effect)
        run (get-in session [:runs run-id])]
    (if (nil? run)
      {:ok? false
       :error {:code :SESSION_RUN_NOT_FOUND
               :message "session run does not exist"
               :details {:run-id run-id}}}
      (let [reason (or (:code error) :model-dispatch-unavailable)
            result (runs/cancel-run session
                                    {:run-id run-id
                                     :reason reason
                                     :now now})]
        (if-not (:ok? result)
          result
          {:ok? true
           :session (assoc-in (:session result)
                              [:runs run-id :failure]
                              {:reason reason
                               :error error
                               :at now})
           :effects (:effects result)})))))
