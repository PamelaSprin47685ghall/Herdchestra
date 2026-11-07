(ns hc.server.history
  "Session message versions, editing, context boundaries, and reversible
  compression.

  This is a pure domain facade.  It never writes a store or deletes an
  attachment.  Every command is an explicit user action; only
  `:history/regenerate` starts one model run, and that run appends a version
  to the target message.  The session Owner accepts the command, calls
  `apply-command`, commits the returned session once, and only then publishes
  the receipt.

  Commands use the `:history/...` namespace so the session metadata commands
  keep their existing meaning:

    :history/edit-message
    :history/append-message
    :history/select-version
    :history/append-version
    :history/regenerate
    :history/fail-run
    :history/set-context-boundary
    :history/clear-context-boundary
    :history/set-recent-rounds
    :history/pin-attachment
    :history/unpin-attachment
    :history/prepare-compression
    :history/update-compression-draft
    :history/apply-compression
    :history/undo-compression
    :history/cancel-compression
    :history/context-preview
    :history/message-versions

  Edit, version selection, branch, context, and compression never send
  content.  Regeneration is the only command that requests a model, and only
  because the user explicitly clicked it."
  (:require [clojure.string :as str]
            [hc.server.history.compression :as compression]
            [hc.server.history.context :as context]
            [hc.server.history.message :as message]
            [hc.server.sessions.runs :as runs]))

(def outbound-context
  "Public alias for the outbound projection used by the send command."
  context/outbound-context)

(def command-types
  #{:history/edit-message
    :history/append-message
    :history/select-version
    :history/append-version
    :history/regenerate
    :history/fail-run
    :history/set-context-boundary
    :history/clear-context-boundary
    :history/set-recent-rounds
    :history/pin-attachment
    :history/unpin-attachment
    :history/prepare-compression
    :history/update-compression-draft
    :history/apply-compression
    :history/undo-compression
    :history/cancel-compression
    :history/context-preview
    :history/message-versions})

(defn- error
  [code text details]
  {:ok? false
   :error (cond-> {:code code :message text}
            (some? details) (assoc :details details))})

(defn- ok
  ([session] (ok session {}))
  ([session result]
   {:ok? true
    :session session
    :result (or result {})
    :effects []}))

(defn- fail
  [session error-value]
  (let [error-value (or (when (and (map? error-value)
                                   (map? (:error error-value)))
                          (:error error-value))
                        error-value
                        {:code :HISTORY_COMMAND_REFUSED
                         :message "history command was refused"})]
    {:ok? false
     :session session
     :error (cond-> error-value
              (nil? (:code error-value))
              (assoc :code :HISTORY_COMMAND_REFUSED)

              (nil? (:message error-value))
              (assoc :message "history command was refused"))
     :effects []}))

(defn- fail-run
  "Close an accepted regeneration run that could not be dispatched."
  [session payload now]
  (let [run-id (:run-id payload)
        run (get-in session [:runs run-id])]
    (if (nil? run)
      (fail session (error :SESSION_RUN_NOT_FOUND
                           "session run does not exist"
                           {:run-id run-id}))
      (let [reason (or (:reason payload) :model-dispatch-unavailable)
            result (runs/cancel-run session
                                    {:run-id run-id
                                     :reason reason
                                     :now now})]
        (if-not (:ok? result)
          (fail session (:error result))
          (ok (assoc-in (:session result) [:runs run-id :failure]
                        {:reason reason
                         :error (:error payload)
                         :at now})
              {:run-id run-id
               :state :cancelled
               :activity? false}))))))

(defn- normalize-request
  [request]
  (if (and (some? (:type request)) (map? (:payload request)))
    request
    (let [payload (if (map? (:payload request))
                    (:payload request)
                    (dissoc request :kind :type :command-id :command-epoch
                            :client-id :now))
          kind (or (:type request)
                   (:kind request)
                   (when (map? payload) (:kind payload)))
          payload (if (map? payload)
                    (cond-> payload kind (dissoc :kind))
                    {})]
      {:type kind :payload payload})))

(defn- message-or-error
  [session message-id]
  (let [message (get-in session [:messages message-id])]
    (cond
      (nil? message)
      {:error (error :MESSAGE_NOT_FOUND
                     "message does not exist"
                     {:message-id message-id})}

      (:deleted? message)
      {:error (error :MESSAGE_DELETED
                     "a deleted message cannot be edited or switched"
                     {:message-id message-id})}

      (:folded? message)
      {:error (error :MESSAGE_FOLDED
                     "a folded message cannot be edited or switched"
                     {:message-id message-id})}

      :else {:message (message/normalize-message message)})))

(defn- regeneration-context
  "Freeze exactly the entries before TARGET-ID.  Summary entries stay in
  position; compressed source messages are intentionally absent from
  `:message-ids` but their summaries remain in `:messages`."
  [outbound prefix target-id target-version-id]
  (let [messages (vec (or (:messages outbound) []))
        index (first (keep-indexed (fn [index entry]
                                     (when (= target-id (:message-id entry))
                                       index))
                                   messages))
        prefix-messages (if index
                          (subvec messages 0 index)
                          messages)]
    (assoc (select-keys outbound
                        [:excluded :pinned-attachments :warnings])
           :messages prefix-messages
           :message-ids prefix
           :summary-ids (vec (keep :compression-id
                                   (filter #(= :context-summary (:kind %))
                                           prefix-messages)))
           :version-ids (select-keys (:version-ids outbound) prefix)
           :target-message-id target-id
           :target-version-id target-version-id
           :target-mode :append-version)))

(defn- assoc-message
  [session message]
  (assoc-in session [:messages (:message-id message)] message))

(defn- activity-session
  [session now]
  (assoc session :activity-at now))

(defn- cancel-affected-run
  [session message-id now]
  (let [active (runs/active-run session)]
    (when (and active (runs/run-affected-by-message? session message-id))
      (runs/cancel-run session
                       {:run-id (:run-id active)
                        :reason :input-changed
                        :now now}))))

(defn- edit-message
  [session payload now]
  (let [{:keys [message] :as looked-up} (message-or-error session (:message-id payload))]
    (if (:error looked-up)
      (fail session (:error looked-up))
      (let [result (message/edit-message
                    message
                    (cond-> {:content (:content payload)
                             :now now}
                      (contains? payload :attachments)
                      (assoc :attachments (:attachments payload))
                      (contains? payload :citations)
                      (assoc :citations (:citations payload))
                      (contains? payload :quotes)
                      (assoc :quotes (:quotes payload))
                      (contains? payload :tool-records)
                      (assoc :tool-records (:tool-records payload))
                      (contains? payload :usage)
                      (assoc :usage (:usage payload))
                      (contains? payload :version-id)
                      (assoc :version-id (:version-id payload))))]
        (if-not (:ok? result)
          (fail session (:error result))
          (let [edited (assoc-message session (:message result))
                cancel (cancel-affected-run edited (:message-id payload) now)
                session' (if cancel (:session cancel) edited)]
            (assoc (ok (activity-session session' now)
                       {:message-id (:message-id result)
                        :version-id (:version-id result)
                        :version-index (:version-index result)
                        :manual-edit? (get-in result [:version :manual-edit?])
                        :cancelled-run-id (when cancel
                                            (get-in cancel [:run :run-id]))
                        :activity? true})
                   :effects (vec (:effects cancel)))))))))

(defn- append-message
  "Mirror one owner conversation message into session history.

  The caller supplies the stable conversation message ID so the client edits
  the same message it sees.  Re-appending the same ID is idempotent.

  A live assistant answer is patched chunk by chunk in the Owner conversation.
  Its history version must follow that append-only growth in place, or the
  durable copy stops at the first chunk while the live copy keeps the full
  text.  Growth is accepted only when the new content extends the current
  content as a prefix and the message is still one unedited model version.
  A user message, an edited answer, a manual answer, or an unrelated
  replacement never takes this path."
  [session payload now]
  (let [message-id (:message-id payload)
        role (:role payload)
        content (:content payload)
        existing (some-> (get-in session [:messages message-id])
                         message/normalize-message)]
    (cond
      (some? existing)
      (let [current (message/current-version existing)
            current-content (str (or (:content current) ""))
            next-content (str (or content ""))
            original? (and (= :assistant (:role existing))
                           (= 1 (count (:versions existing)))
                           (not (:edited? current))
                           (not (:manual-edit? current))
                           (not= :manual (:source current)))
            grows? (and original?
                        (not= next-content current-content)
                        (str/starts-with? next-content current-content)
                        (> (count next-content) (count current-content)))
            upgrade? (and original?
                          (or (str/blank? current-content)
                              (true? (:partial? payload))
                              grows?)
                          (not= content (:content current)))
            version' (cond-> current
                       upgrade? (assoc :content content)
                       (contains? payload :attachments)
                       (assoc :attachments (vec (or (:attachments payload) [])))
                       (contains? payload :citations)
                       (assoc :citations (vec (or (:citations payload) [])))
                       (contains? payload :quotes)
                       (assoc :quotes (vec (or (:quotes payload) [])))
                       (contains? payload :tool-records)
                       (assoc :tool-records (vec (or (:tool-records payload) [])))
                       (contains? payload :usage)
                       (assoc :usage (:usage payload)))
            existing' (cond-> (if upgrade?
                                (-> existing
                                    (assoc :versions [version'])
                                    (assoc :content content
                                           :updated-at now))
                                existing)
                        (and (not upgrade?) (contains? payload :attachments))
                        (assoc :attachments (vec (or (:attachments payload) [])))
                        (and (not upgrade?) (contains? payload :citations))
                        (assoc :citations (vec (or (:citations payload) [])))
                        (and (not upgrade?) (contains? payload :quotes))
                        (assoc :quotes (vec (or (:quotes payload) [])))
                        (:nudge? payload) (assoc :nudge? true)
                        (some? (:nudge-id payload))
                        (assoc :nudge-id (:nudge-id payload))
                        (some? (:run-id payload))
                        (assoc :run-id (:run-id payload)))]
        (ok (-> session
                (assoc-in [:messages message-id] existing')
                (update :message-order
                        (fn [order]
                          (let [order (vec (or order []))]
                            (if (some #{message-id} order)
                              order
                              (conj order message-id))))))
            {:message-id message-id :existing? true}))

      (not (contains? #{:user :assistant :system} role))
      (fail session (error :MESSAGE_ROLE_INVALID
                           "role must be :user, :assistant, or :system"
                           {:role role}))

      (not (string? content))
      (fail session (error :MESSAGE_CONTENT_INVALID
                           "message content must be a string"
                           {:message-id message-id}))

      (not (and (string? message-id)
                (not (str/blank? message-id))))
      (fail session (error :MESSAGE_ID_INVALID
                           "message-id must be a non-blank string"
                           {:message-id message-id}))

      :else
      (let [result (message/new-message
                    message-id role content
                    {:attachments (:attachments payload)
                     :citations (:citations payload)
                     :quotes (:quotes payload)
                     :tool-records (:tool-records payload)
                     :usage (:usage payload)
                     :now now})]
        (if-not (:ok? result)
          (fail session (:error result))
          (ok (-> session
                  (assoc-in [:messages message-id]
                            (cond-> (:message result)
                              (:nudge? payload) (assoc :nudge? true)
                              (some? (:nudge-id payload))
                              (assoc :nudge-id (:nudge-id payload))
                              (some? (:run-id payload))
                              (assoc :run-id (:run-id payload))))
                  (update :message-order (fnil conj []) message-id)
                  (assoc :activity-at now))
              {:message-id message-id}))))))

(defn- regenerate
  "Start one explicit model regeneration for an existing assistant message.

  The run freezes the target message and only the outbound context before it.
  The terminal handler appends a new version to that message; it never appends
  a second assistant message or replays a historical tool call."
  [session payload now]
  (let [{:keys [message] :as looked-up} (message-or-error session (:message-id payload))
        target-id (:message-id payload)
        active (runs/active-run session)
        binding (:model-binding session)
        outbound (context/outbound-context session)
        outbound-ids (:message-ids outbound)
        target-visible? (some #{target-id} outbound-ids)
        prefix (if target-visible?
                 (vec (take-while #(not= target-id %) outbound-ids))
                 [])]
    (cond
      (:error looked-up)
      (fail session (:error looked-up))

      (not= :assistant (:role message))
      (fail session (error :REGENERATE_TARGET_INVALID
                           "only an assistant message can be regenerated"
                           {:message-id target-id :role (:role message)}))

      active
      (fail session (error :RUN_ALREADY_ACTIVE
                           "stop or finish the current run before regenerating"
                           {:run-id (:run-id active)}))

      (not (map? binding))
      (fail session (error :MODEL_NOT_SELECTED
                           "select a model before regenerating" {}))

      (not target-visible?)
      (fail session (error :REGENERATE_TARGET_OUTSIDE_CONTEXT
                           "the target is hidden by the current context summary"
                           {:message-id target-id}))

      :else
      (let [run-id (str "regenerate-" (random-uuid))
            call-id (str "regenerate-call-" (random-uuid))
            target-version-id (:version-id (message/current-version message))
            frozen-context (regeneration-context outbound prefix target-id
                                                 target-version-id)
            run (runs/start-run
                 session
                 {:run-id run-id
                  :call-id call-id
                  :model-binding binding
                  :message-ids prefix
                  :context frozen-context
                  :target-message-id target-id
                  :target-mode :append-version
                  :policy (or (:policy payload) {})
                  :language (:language session)
                  :now now})]
        (if-not (:ok? run)
          (fail session (:error run))
          {:ok? true
           :session (:session run)
           :result {:message-id target-id
                    :run-id run-id
                    :call-id call-id
                    :mode :append-version
                    :activity? false}
           :effects (:effects run)})))))

(defn- append-version
  [session payload now]
  (let [{:keys [message] :as looked-up} (message-or-error session (:message-id payload))]
    (if (:error looked-up)
      (fail session (:error looked-up))
      (let [result (message/append-version
                    message
                    {:content (:content payload)
                     :attachments (:attachments payload)
                     :citations (:citations payload)
                     :quotes (:quotes payload)
                     :tool-records (:tool-records payload)
                     :usage (:usage payload)
                     :version-id (:version-id payload)
                     :source (:source payload)
                     :expected-version-id (:expected-version-id payload)
                     :regenerated-from-version-id
                     (:regenerated-from-version-id payload)
                     :now now})]
        (if-not (:ok? result)
          (fail session (:error result))
          (ok (activity-session (assoc-message session (:message result)) now)
              {:message-id (:message-id result)
               :version-id (:version-id result)
               :version-index (:version-index result)
               :activity? true}))))))

(defn- select-version
  [session payload now]
  (let [{:keys [message] :as looked-up} (message-or-error session (:message-id payload))]
    (if (:error looked-up)
      (fail session (:error looked-up))
      (let [result (message/select-version
                    message
                    {:version-id (:version-id payload)
                     :index (:index payload)
                     :now now})]
        (if-not (:ok? result)
          (fail session (:error result))
          ;; Switching versions is reading, not content activity.
          (ok (assoc-message session (:message result))
              {:message-id (:message-id result)
               :version-id (:version-id result)
               :version-index (:version-index result)
               :changed? (:changed? result)
               :activity? false}))))))

(defn- context-result
  [session result]
  (ok (:session result)
      (cond-> (select-keys result [:boundary :attachment-id :activity?])
        (contains? result :outbound) (assoc :outbound (:outbound result))
        (contains? result :reading) (assoc :reading (:reading result)))))

(defn- apply-command*
  [session type payload now]
  (case type
    :history/edit-message
    (edit-message session payload now)

    :history/append-message
    (append-message session payload now)

    :history/append-version
    (append-version session payload now)

    :history/regenerate
    (regenerate session payload now)

    :history/fail-run
    (fail-run session payload now)

    :history/select-version
    (select-version session payload now)

    :history/set-context-boundary
    (let [result (context/set-boundary session (:message-id payload)
                                       {:now now})]
      (if-not (:ok? result)
        (fail session (:error result))
        (context-result session result)))

    :history/clear-context-boundary
    (context-result session (context/clear-boundary session))

    :history/set-recent-rounds
    (let [result (context/set-recent-rounds session (:recent-rounds payload))]
      (if-not (:ok? result)
        (fail session (:error result))
        (context-result session result)))

    :history/pin-attachment
    (let [result (context/pin-attachment session (:attachment-id payload))]
      (if-not (:ok? result)
        (fail session (:error result))
        (context-result session result)))

    :history/unpin-attachment
    (context-result session
                    (context/unpin-attachment session (:attachment-id payload)))

    :history/prepare-compression
    (let [result (compression/prepare
                  session
                  {:compression-id (:compression-id payload)
                   :message-ids (:message-ids payload)
                   :summary (:summary payload)
                   :now now})]
      (if-not (:ok? result)
        (fail session (:error result))
        (ok (:session result)
            {:compression (:compression result)
             :activity? false})))

    :history/update-compression-draft
    (let [result (compression/update-draft
                  session (:compression-id payload) (:summary payload)
                  {:now now})]
      (if-not (:ok? result)
        (fail session (:error result))
        (ok (:session result)
            {:compression (:compression result)
             :activity? false})))

    :history/apply-compression
    (let [result (compression/apply!
                  session (:compression-id payload)
                  {:summary (:summary payload) :now now})]
      (if-not (:ok? result)
        (fail session (:error result))
        (ok (:session result)
            {:compression (:compression result)
             :outbound (context/outbound-context (:session result))
             :activity? false})))

    :history/undo-compression
    (let [result (compression/undo
                  session (:compression-id payload) {:now now})]
      (if-not (:ok? result)
        (fail session (:error result))
        (ok (:session result)
            {:compression (:compression result)
             :outbound (context/outbound-context (:session result))
             :activity? false})))

    :history/cancel-compression
    (let [result (compression/cancel
                  session (:compression-id payload) {:now now})]
      (if-not (:ok? result)
        (fail session (:error result))
        (ok (:session result)
            {:compression (:compression result)
             :activity? false})))

    :history/context-preview
    (ok session
        {:reading (context/reading-context session)
         :outbound (context/outbound-context
                    session
                    {:clips (:clips payload)})
         :activity? false})

    :history/message-versions
    (let [message-id (:message-id payload)
          message (get-in session [:messages message-id])]
      (if-not message
        (fail session (error :MESSAGE_NOT_FOUND
                             "message does not exist"
                             {:message-id message-id}))
        (ok session
            {:message-id message-id
             :current-version (:current-version message)
             :versions (message/message-history session message-id)
             :activity? false})))

    (fail session (error :COMMAND_TYPE_UNKNOWN
                         "unknown history command type"
                         {:type type}))))

(defn apply-command
  "Apply one `:history/...` command to SESSION.

  Returns `{:ok? bool :session s :result m :effects []}`.  A refusal leaves
  SESSION unchanged.  The caller owns revision bumping and persistence."
  ([session request]
   (apply-command session request {:now (:now request)}))
  ([session request {:keys [now] :as _opts}]
   (let [request (normalize-request request)
         type (:type request)
         payload (if (map? (:payload request)) (:payload request) {})]
     (if-not (contains? command-types type)
       (fail session (error :COMMAND_TYPE_UNKNOWN
                            "unknown history command type"
                            {:type type}))
       (if (= :deleted (:status session))
         (fail session (error :SESSION_DELETED
                              "session is deleted; restore it before history commands"
                              {:session-id (:session-id session)}))
         (apply-command* session type payload now))))))

;; ---------------------------------------------------------------------------
;; Invariants

(defn- attachment-identity-violations
  [session]
  (let [entries (for [message-id (:message-order session)
                      :let [message (get-in session [:messages message-id])]
                      :when message
                      version (:versions (message/normalize-message message))
                      ref (or (:attachments version) [])]
                  [(:attachment-id ref) ref])
        grouped (group-by first entries)
        conflicts (keep (fn [[attachment-id pairs]]
                          (let [refs (distinct (map second pairs))]
                            (when (> (count refs) 1)
                              {:invariant :attachment-identity-conflict
                               :attachment-id attachment-id
                               :descriptors refs})))
                        grouped)]
    (vec conflicts)))

(defn check-invariants
  "Return history invariant violations for SESSION."
  [session]
  (into
   []
   (concat
    (mapcat (fn [message-id]
              (let [message (get-in session [:messages message-id])]
                (when message
                  (message/check-invariants
                   (message/normalize-message message)))))
            (:message-order session))
    (context/check-invariants session)
    (compression/check-invariants session)
    (attachment-identity-violations session))))
