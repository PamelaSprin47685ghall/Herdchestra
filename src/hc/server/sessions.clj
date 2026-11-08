(ns hc.server.sessions
  "Session lifecycle, drafts, message versions, model binding, and runs.

  Authority: AGENTS.md §5.1 (four-layer lifecycle, single Owner, multi-client
  command epochs, edit-during-run) and §7.4 journeys 1–5 (session drafts, model
  selection, history versions, deletion retention).

  This namespace is a pure session state machine plus explicit persistence
  helpers.  It performs no I/O outside `SessionPersistence`, starts no
  thread, and keeps no global mutable state.  The session Owner calls
  `execute`, `accept-model-event`, and the draft acknowledgement functions
  inside one serial step, then commits the returned state.

  Every mutation returns a complete next session and plain-EDN effects:

    {:ok? true
     :session session'
     :receipt {...}
     :replayed? false
     :effects [{:kind :persist-session ...} ...]}

  A refusal leaves the session untouched, except that a refused business
  command still consumes its command-id so a replay cannot turn it into a
  second, different action."
  (:require [clojure.string :as str]
            [hc.server.attachments.model :as attachment-model]
            [hc.server.commands :as commands]
            [hc.server.sessions.config :as config]
            [hc.server.sessions.connections :as connections]
            [hc.server.sessions.drafts :as drafts]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.runs :as runs]))

(def supported-languages
  #{:en :zh-CN})

(def session-statuses
  #{:active :archived :deleted})

(def title-sources
  #{:auto :manual})

(def deletion-retention-ms
  "A soft-deleted session stays recoverable for 30 days."
  (* 30 24 60 60 1000))

(defn- now-value
  [now]
  (or now (System/currentTimeMillis)))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- refusal
  [session code message details]
  {:ok? false
   :session session
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))
   :effects []})

(defn- business-ok
  [session result effects]
  {:ok? true
   :session session
   :result (or result {})
   :effects (vec effects)})

(defn- business-fail
  [session code message details]
  (refusal session code message details))

(defn- bump
  [session now]
  (-> session
      (update :revision inc)
      (assoc :updated-at now)))

(defn dirty?
  [session]
  (not= (:revision session) (:persisted-revision session)))

(defn- persist-effect
  [session]
  {:kind :persist-session
   :session-id (:session-id session)
   :owner-id (:owner-id session)
   :expected-revision (or (:persisted-revision session) 0)
   :revision (:revision session)})

;; ---------------------------------------------------------------------------
;; Messages

(defn- new-message
  [message-id role content attachments citations quotes now]
  {:message-id message-id
   :role role
   :versions [{:version-id (str message-id "-v1")
               :content content
               :quotes (vec (or quotes []))
               :created-at now
               :edited? false}]
   :current-version 0
   :attachments (vec (or attachments []))
   :citations (vec (or citations []))
   :quotes (vec (or quotes []))
   :created-at now
   :updated-at now
   :deleted? false
   :folded? false
   :deleted-at nil
   :folded-at nil
   :manual-edit? false})

(defn- append-message*
  [session message now]
  (-> session
      (assoc-in [:messages (:message-id message)] message)
      (update :message-order (fnil conj []) (:message-id message))
      (assoc :activity-at now)))

(defn- message-content
  [message]
  (get-in message [:versions (:current-version message) :content]))

(defn message-view
  "Return one visible message with its current version content.

  A version may carry its own attachments/citations snapshot; the
  message-level values are the fallback for older messages."
  [message]
  (let [version (get-in message [:versions (:current-version message)])]
    {:message-id (:message-id message)
     :role (:role message)
     :content (message-content message)
     :attachments (or (:attachments version) (:attachments message) [])
     :citations (or (:citations version) (:citations message) [])
     :quotes (or (:quotes version) (:quotes message) [])
     :version-count (count (:versions message))
     :current-version (:current-version message)
     :edited? (> (count (:versions message)) 1)
     :manual-edit? (boolean (if (contains? version :manual-edit?)
                              (:manual-edit? version)
                              (:manual-edit? message)))
     :created-at (:created-at message)
     :updated-at (:updated-at message)}))

(defn visible-messages
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (remove #(or (:deleted? %) (:folded? %)))
       (remove #(or (:nudge? %) (:nudge-id %)))
       (mapv message-view)))

(defn initial-user-input
  "Return the first visible user message as initial relay input, or nil.

  The result is `{:user-input <raw text> :user-attachments <attachments>}`.
  Text-only messages keep their text.  An attachment-only message uses the
  same `[attachments]` placeholder as operator reopen.  Attachments are the
  message's current-version descriptors; the relay layer keeps only stable
  references and display metadata, so this helper must not strip `:blob-id`
  before the blob reference table has seen the message."
  [session]
  (when-let [message (first (filter #(= :user (:role %))
                                    (visible-messages session)))]
    (let [text (str (or (:content message) ""))
          attachments (->> (:attachments message)
                           (filter (fn [attachment]
                                     (and (map? attachment)
                                          (or (not (str/blank?
                                                    (str (:attachment-id attachment))))
                                              (not (str/blank?
                                                    (str (:id attachment))))
                                              (not (str/blank?
                                                    (str (:blob-id attachment))))
                                              (not (str/blank?
                                                    (str (:send-blob-id attachment))))))))
                           vec)]
      (when (or (not (str/blank? text)) (seq attachments))
        {:user-input (if (str/blank? text) "[attachments]" text)
         :user-attachments attachments}))))

(defn initial-conversation
  "Project the session's visible history into Owner conversation entries.

  A session created from a compression summary or a branch already carries
  messages; a fresh Owner conversation starts empty and the first model
  request would drop them (the reviewed summary never reaches the model).
  Nudge answers, deleted and folded messages stay out, matching the
  outbound projection.  The stable `:message-id` is preserved so history
  mirroring stays idempotent."
  [session]
  (vec
   (for [message (->> (:message-order session)
                      (keep #(get-in session [:messages %]))
                      (remove #(or (:deleted? %) (:folded? %)
                                   (:nudge? %) (:nudge-id %))))]
     (let [version (get-in message [:versions (:current-version message)])
           content (str (or (:content version)
                            (:content message)
                            ""))
           attachments (vec (or (:attachments version)
                                 (:attachments message)
                                 []))
           citations (vec (or (:citations version)
                               (:citations message)
                               []))
           quotes (vec (or (:quotes version)
                            (:quotes message)
                            []))
           tool-records (vec (or (:tool-records version)
                                  (:tool-records message)
                                  []))]
       (cond-> {:role (:role message)
                :message-id (:message-id message)
                :text content
                :at (:created-at message)}
         (seq attachments) (assoc :attachments attachments)
         (seq citations) (assoc :citations citations)
         (seq quotes) (assoc :quotes quotes)
         (seq tool-records) (assoc :tool-records tool-records))))))

(defn deleted-messages
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (filter :deleted?)
       (mapv message-view)))

(defn message-history
  "Return every version of MESSAGE-ID, including superseded versions."
  [session message-id]
  (get-in session [:messages message-id :versions]))

(defn- visible-message-ids
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (remove #(or (:deleted? %) (:folded? %)))
       (remove #(or (:nudge? %) (:nudge-id %)))
       (mapv :message-id)))

;; ---------------------------------------------------------------------------
;; Creation and persistence

(defn- resolve-creation-binding
  [opts]
  (let [config (:config opts)
        connection-id (:connection-id opts)
        model-entry-id (:model-entry-id opts)
        assistant-id (:assistant-id opts)]
    (cond
      (and connection-id model-entry-id)
      (config/resolve-binding config connection-id model-entry-id)

      assistant-id
      (config/assistant-binding config assistant-id)

      ;; No explicit selection: a configured main model is the discoverable
      ;; default.  A missing :main binding means the session starts unbound,
      ;; not that another role may be borrowed.
      config
      (config/resolve-role-binding config :main)

      :else
      {:ok? true :binding nil :availability nil})))

(defn new-session
  "Create one unpersisted session value.

  The caller supplies a configuration snapshot when a model or assistant is
  requested.  A session may be created without a model so the user can browse
  and write a draft; sending a message is what requires a usable model."
  [{:keys [session-id owner-id assistant-id language title origin-session-id
           creation-command client-id temporary? capability-scope now id-source
           config]
    :or {language :en}
    :as opts}]
  (let [now (now-value now)
        assistant-id (or assistant-id
                         (config/default-assistant-id config))
        id-source (or id-source (ids/uuid-source))
        session-id (or session-id (ids/next-id! id-source :session))
        owner-id (or owner-id (ids/next-id! id-source :owner))]
    (cond
      (not (ids/valid-id? session-id))
      {:ok? false
       :error {:code :SESSION_ID_INVALID
               :message "session-id must be a non-blank string"
               :details {:session-id session-id}}}

      (not (ids/valid-id? owner-id))
      {:ok? false
       :error {:code :OWNER_ID_INVALID
               :message "owner-id must be a non-blank string"
               :details {:owner-id owner-id}}}

      (and origin-session-id (not (ids/valid-id? origin-session-id)))
      {:ok? false
       :error {:code :ORIGIN_SESSION_ID_INVALID
               :message "origin-session-id must be a non-blank string or nil"
               :details {:origin-session-id origin-session-id}}}

      (and creation-command
           (not (ports/valid-command-identity? creation-command)))
      {:ok? false
       :error {:code :CREATION_COMMAND_INVALID
               :message
               "creation-command must be {:scope string :command-epoch positive-integer :command-id string} or nil"
               :details {:creation-command creation-command}}}

      (not (contains? supported-languages language))
      {:ok? false
       :error {:code :LANGUAGE_INVALID
               :message "language must be :en or :zh-CN"
               :details {:language language}}}

      (and title (not (non-blank-string? title)))
      {:ok? false
       :error {:code :TITLE_INVALID
               :message "title must be a non-blank string"
               :details {:title title}}}

      :else
      (let [binding-result (resolve-creation-binding opts)]
        (if-not (:ok? binding-result)
          {:ok? false :error (:error binding-result)}
          (let [session
                (cond-> {:session-id session-id
                 :owner-id owner-id
                 :revision 0
                 :persisted-revision nil
                 :status :active
                 :title (or title "New session")
                 :title-source (if title :manual :auto)
                 :language language
                 :assistant-id assistant-id
                 :origin-session-id origin-session-id
                 :origin-message-id nil
                 :created-at now
                 :updated-at now
                 :activity-at now
                 :pinned? false
                 :temporary? (boolean temporary?)
                 :capability-scope capability-scope
                 :deleted-at nil
                 :context {}
                 :model-binding (:binding binding-result)
                 :pending-model-binding nil
                 :drafts (if client-id
                           {(drafts/normalize-client-id client-id)
                            (drafts/new-draft client-id now)}
                           {})
                 :messages {}
                 :message-order []
                 :runs {}
                 :run-order []
                 :current-run-id nil
                 :calls {}
                 :attempts {}}
                  creation-command
                  (assoc :creation-command creation-command))]
            {:ok? true
             :session session
             :effects [{:kind :session-created
                        :session-id session-id
                        :owner-id owner-id}]}))))))

(defn load-session
  [persistence session-id]
  (ports/read-session persistence session-id))

(declare acknowledge-committed-drafts)

(defn commit-session!
  "Commit one dirty session with compare-and-set revision semantics.

  A conflict or unknown outcome returns the session still dirty.  The caller
  must read the stored value or retry; it must never report success on an
  unknown commit."
  [persistence session]
  (if-not (dirty? session)
    {:status :unchanged :session session}
    (let [expected (or (:persisted-revision session) 0)
          ;; The stored value represents committed state, so its persisted
          ;; revision must equal its revision.  Keep the caller's value dirty
          ;; when the commit does not succeed.
          candidate (-> session
                        (acknowledge-committed-drafts
                         (or (:updated-at session) 0))
                        (assoc :persisted-revision (:revision session))
                        (assoc :visible-message-count
                               (count (visible-messages session))))]
      (try
        (let [result (ports/commit-session! persistence expected candidate)]
          (case (:status result)
            :committed
            {:status :committed
             :session candidate
             :revision (:revision result)}

            :conflict
            {:status :conflict
             :session session
             :revision (:revision result)}

            :unknown
            {:status :unknown
             :session session
             :error (:error result)}

            result))
        (catch Exception exception
          {:status :unknown
           :session session
           :error {:code :PERSISTENCE_ERROR
                   :message (str "session commit failed: "
                                 (.getMessage exception))}})))))

(defn- session-business-content
  "The business fields of SESSION, without persistence bookkeeping.

  `:revision` is a local ordering number, `:persisted-revision` records the
  last acknowledged commit, and `:updated-at` is derived from the commit
  clock.  Two values with the same business content describe the same
  session even when one was already committed and the other is the same
  transition retried after an Owner-store conflict."
  [session]
  (when (map? session)
    (dissoc session :revision :persisted-revision :updated-at
            :visible-message-count)))

(defn- acknowledge-committed-drafts
  "Mark pending drafts saved because the enclosing session commit succeeded.

  A draft that is part of the committed session is durable at its current
  revision.  This is the acknowledgement the send path needs: a cleared
  draft is committed with `:save-status :pending` and must become saved
  without a second command.  A failed or unknown commit never reaches this
  helper, so the draft stays visibly unsaved.
  "
  [session now]
  (if-not (map? (:drafts session))
    session
    (update session :drafts
            (fn [draft-map]
              (into (empty draft-map)
                    (map (fn [[client-id draft]]
                           [client-id
                            (if (and (map? draft)
                                     (= :pending (:save-status draft)))
                              (let [result (drafts/mark-saved
                                            draft (:revision draft) now)]
                                (if (:ok? result) (:draft result) draft))
                              draft)]))
                    draft-map)))))

(defn commit-owner-session!
  "Commit one Owner business transition to PERSISTENCE.

  PREVIOUS is the Owner's session before the message; SESSION is the value
  after the business transition.  The Owner is the single writer, so a
  changed live session wins even when its revision equals the committed
  revision: older Owners did not bump the revision for metadata commands.

  This function reads the committed record first and then commits with that
  revision as the compare-and-set token.  The read is the barrier: a caller
  may report an accepted receipt only after this function returns `:ok?`.
  A committed record ahead of the live session is a stale Owner; it is
  refused and returned, never overwritten.

  Returns `{:ok? true :status :committed|:unchanged :session committed}`
  or `{:ok? false :error e :session session}`.  `:unknown? true` means the
  commit outcome could not be known; the caller must resynchronize."
  [persistence previous session now]
  (if-not (and (map? previous) (map? session))
    {:ok? false
     :error {:code :SESSION_INVALID
             :message "session commit needs the previous and current session"}}
    (let [session-id (:session-id session)
          loaded (ports/read-session persistence session-id)]
      (case (:status loaded)
        :missing
        {:ok? false
         :error {:code :SESSION_NOT_FOUND
                 :message "the committed session no longer exists"
                 :session-id session-id}}

        :error
        {:ok? false
         :error (or (:error loaded)
                    {:code :SESSION_READ_FAILED
                     :message "could not read the committed session"})}

        :found
        (let [committed (:session loaded)
              committed-revision (long (or (:revision committed) 0))
              previous-revision (long (or (:revision previous) 0))
              session-revision (long (or (:revision session) 0))
              changed? (not= (dissoc previous :persisted-revision)
                             (dissoc session :persisted-revision))
              already-committed?
              (= (session-business-content session)
                 (session-business-content committed))]
          (cond
            (not changed?)
            {:ok? true :status :unchanged :session committed}

            ;; A retry after an Owner-store conflict re-applies the same
            ;; message to the reloaded state.  The session record already
            ;; holds that business transition; adopt its revision instead of
            ;; writing a second one.
            already-committed?
            {:ok? true :status :unchanged :session committed}

            (< session-revision committed-revision)
            {:ok? false
             :error {:code :SESSION_COMMIT_STALE
                     :message "the Owner session is behind the committed record"
                     :details {:committed-revision committed-revision
                               :session-revision session-revision}}
             :session committed}

            :else
            (let [revision (max (inc committed-revision)
                                (if (> session-revision previous-revision)
                                  session-revision
                                  (inc session-revision)))
                  candidate (-> session
                                (acknowledge-committed-drafts
                                 (or now (:updated-at session) 0))
                                (assoc :revision revision)
                                (assoc :updated-at (or now (:updated-at session)))
                                (assoc :persisted-revision committed-revision))
                  commit (commit-session! persistence candidate)]
              (case (:status commit)
                :committed
                {:ok? true
                 :status :committed
                 :session (:session commit)}

                :conflict
                {:ok? false
                 :error {:code :SESSION_COMMIT_CONFLICT
                         :message "session changed before the Owner commit"
                         :details {:revision (:revision commit)}}
                 :session committed}

                :unknown
                {:ok? false
                 :unknown? true
                 :error (or (:error commit)
                            {:code :SESSION_COMMIT_UNKNOWN
                             :message "Owner session commit outcome is unknown"})
                 :session session}

                {:ok? false
                 :error (or (:error commit)
                            {:code :SESSION_COMMIT_FAILED
                             :message "Owner session commit did not complete"})
                 :session session}))))))))

(defn reconcile-owner-session!
  "Reconcile the Owner's loaded session with the committed record at start.

  The Owner state and the session record are separate documents.  A previous
  process could have accepted a metadata command and persisted only its own
  state, leaving the session record behind.  Before this Owner accepts new
  work, a live session that differs from the committed record is written
  back through `commit-owner-session!`; a committed record that is ahead of
  the live session is adopted instead.

  Returns `{:ok? true :status :committed|:unchanged|:adopted :session s}`
  or `{:ok? false :error e :session committed-or-live}`.  A missing
  committed record is an error: the Owner must not resurrect a session that
  the store no longer holds."
  [persistence live-session now]
  (if-not (map? live-session)
    {:ok? false
     :error {:code :SESSION_INVALID
             :message "session reconciliation needs the Owner session"}}
    (let [session-id (:session-id live-session)
          loaded (ports/read-session persistence session-id)]
      (case (:status loaded)
        :missing
        {:ok? false
         :error {:code :SESSION_NOT_FOUND
                 :message "the committed session no longer exists"
                 :session-id session-id}}

        :error
        {:ok? false
         :error (or (:error loaded)
                    {:code :SESSION_READ_FAILED
                     :message "could not read the committed session"})}

        :found
        (let [committed (:session loaded)
              committed-revision (long (or (:revision committed) 0))
              live-revision (long (or (:revision live-session) 0))
              same-business?
              (= (session-business-content live-session)
                 (session-business-content committed))]
          (cond
            same-business?
            {:ok? true :status :unchanged :session committed}

            (>= live-revision committed-revision)
            (let [result (commit-owner-session! persistence committed
                                                live-session now)]
              (cond-> result
                (:ok? result) (assoc :status
                                     (if (= :unchanged (:status result))
                                       :adopted
                                       :committed))
                (not (:ok? result)) (assoc :session committed)))

            :else
            {:ok? true :status :adopted :session committed}))))))

(defn- create-and-commit!
  [persistence opts]
  (let [created (new-session opts)]
    (if-not (:ok? created)
      {:status :error :error (:error created)}
      (let [commit (commit-session! persistence (:session created))]
        (case (:status commit)
          :committed
          {:status :created
           :session (:session commit)
           :commit commit
           :effects (:effects created)}

          :conflict
          {:status :error
           :session (:session commit)
           :commit commit
           :error {:code :SESSION_COMMIT_CONFLICT
                   :message "session already exists at a different revision"
                   :details {:revision (:revision commit)}}}

          :unknown
          {:status :error
           :session (:session commit)
           :commit commit
           :error (or (:error commit)
                      {:code :SESSION_COMMIT_UNKNOWN
                       :message "session commit outcome is unknown"})}

          {:status :error
           :session (:session commit)
           :commit commit
           :error {:code :SESSION_COMMIT_FAILED
                   :message "session commit did not complete"
                   :details commit}})))))

(defn open!
  "Load a session, or create and commit it when missing.

  Returns `{:status :opened|:created|:missing|:error ...}`.  This helper is
  for assembly code; the per-session Owner still performs later mutations."
  [persistence {:keys [session-id create?] :or {create? true} :as opts}]
  (if session-id
    (let [result (ports/read-session persistence session-id)]
      (case (:status result)
        :found {:status :opened :session (:session result)}
        :missing (if create?
                   (create-and-commit! persistence opts)
                   {:status :missing})
        :error {:status :error :error (:error result)}))
    (create-and-commit! persistence opts)))

(defn close
  "Archive or soft-delete one session.

  An active run is cancelled first.  Deletion is soft: the record stays for
  the 30-day recovery window.  External workspace files are never touched."
  [session {:keys [now reason delete?]}]
  (let [now (now-value now)
        active (runs/active-run session)
        cancel (when active
                 (runs/cancel-run session
                                  {:run-id (:run-id active)
                                   :reason (or reason :closed)
                                   :now now}))
        session (if cancel (:session cancel) session)
        status (if delete? :deleted :archived)
        session' (cond-> (-> session
                             (assoc :status status)
                             (assoc :updated-at now)
                             (bump now))
                   delete? (assoc :deleted-at now))]
    {:ok? true
     :session session'
     :effects (conj (vec (:effects cancel))
                    (persist-effect session'))}))

(defn close!
  [persistence session opts]
  (let [closed (close session opts)]
    (if-not (:ok? closed)
      closed
      (let [commit (commit-session! persistence (:session closed))]
        (assoc closed
               :commit commit
               :session (:session commit)
               :ok? (= :committed (:status commit)))))))

;; ---------------------------------------------------------------------------
;; Drafts

(defn draft-view
  [session client-id now]
  (let [client-id (drafts/normalize-client-id client-id)
        draft (or (get-in session [:drafts client-id])
                  (drafts/new-draft client-id (now-value now)))]
    (drafts/public-draft draft)))

(defn confirm-draft-saved
  "Owner callback: the enclosing session commit for REVISION succeeded."
  [session client-id revision now]
  (let [now (now-value now)
        client-id (drafts/normalize-client-id client-id)
        draft (get-in session [:drafts client-id])]
    (if-not draft
      (refusal session :DRAFT_NOT_FOUND
               "draft does not exist for this client"
               {:client-id client-id})
      (let [result (drafts/mark-saved draft revision now)]
        (cond
          (not (:ok? result))
          (refusal session (:code (:error result))
                   (:message (:error result))
                   (:details (:error result)))

          (:stale? result)
          {:ok? true
           :session session
           :stale? true
           :draft (drafts/public-draft (:draft result))
           :effects []}

          :else
          (let [session' (-> session
                             (assoc-in [:drafts client-id] (:draft result))
                             (bump now))]
            {:ok? true
             :session session'
             :draft (drafts/public-draft (:draft result))
             :effects [(persist-effect session')]}))))))

(defn fail-draft-save
  "Owner callback: the enclosing session commit for REVISION failed.

  The text remains present and the draft stays visibly unsaved."
  [session client-id revision failure now]
  (let [now (now-value now)
        client-id (drafts/normalize-client-id client-id)
        draft (get-in session [:drafts client-id])]
    (if-not draft
      (refusal session :DRAFT_NOT_FOUND
               "draft does not exist for this client"
               {:client-id client-id})
      (let [result (drafts/mark-failed draft revision failure now)]
        (cond
          (not (:ok? result))
          (refusal session (:code (:error result))
                   (:message (:error result))
                   (:details (:error result)))

          (:stale? result)
          {:ok? true
           :session session
           :stale? true
           :draft (drafts/public-draft (:draft result))
           :effects []}

          :else
          (let [session' (-> session
                             (assoc-in [:drafts client-id] (:draft result))
                             (bump now))]
            {:ok? true
             :session session'
             :draft (drafts/public-draft (:draft result))
             :effects [(persist-effect session')]}))))))

(declare apply-command)

(defn confirm-draft-saved-port
  "Owner port adapter for draft acknowledgement.

  Accepts `{:draft-id client-id :revision n :now t}`.  `:draft-id` is the
  client ID; `:revision` defaults to the current draft revision."
  [session request]
  (if-not (map? request)
    (refusal session :DRAFT_PORT_REQUEST_INVALID
             "draft acknowledgement request must be a map"
             {:request request})
    (let [client-id (drafts/normalize-client-id
                     (or (:client-id request) (:draft-id request)))
          revision (or (:revision request)
                       (get-in session [:drafts client-id :revision]))
          now (now-value (:now request))]
      (if (nil? revision)
        (refusal session :DRAFT_NOT_FOUND
                 "draft does not exist for this client"
                 {:client-id client-id})
        (confirm-draft-saved session client-id revision now)))))

(defn fail-draft-save-port
  "Owner port adapter for a failed draft save.

  Accepts `{:draft-id client-id :revision n :error {...} :now t}`."
  [session request]
  (if-not (map? request)
    (refusal session :DRAFT_PORT_REQUEST_INVALID
             "draft failure request must be a map"
             {:request request})
    (let [client-id (drafts/normalize-client-id
                     (or (:client-id request) (:draft-id request)))
          revision (or (:revision request)
                       (get-in session [:drafts client-id :revision]))
          now (now-value (:now request))]
      (if (nil? revision)
        (refusal session :DRAFT_NOT_FOUND
                 "draft does not exist for this client"
                 {:client-id client-id})
        (fail-draft-save session client-id revision
                         (or (:error request)
                             {:code :DRAFT_SAVE_FAILED
                              :message "draft save failed"})
                         now)))))

(defn session-port
  "The exact port map for `hc.compose.owner` `:session-port`.

  `:apply-command`, `:confirm-draft-saved`, and `:fail-draft-save` are pure
  and fast; the Owner may call them on its mailbox thread.

  With `:persistence`, the port also exposes the Owner commit barrier:

    :commit-session!    (fn [previous session now]) -> commit result
    :reconcile-session! (fn [session]) -> startup reconciliation result

  The Owner calls `:commit-session!` before its accepted receipt and
  `:reconcile-session!` before accepting new work after a restart.  A port
  without them can still apply commands in standalone tests, but it cannot
  claim durable business acceptance.

  Optional `:delegate` receives unknown command types.  A history domain can
  pass its pure command handler here without editing this namespace.
  `:now-fn` is a zero-argument clock used by the persistence functions."
  ([] (session-port {}))
  ([{:keys [delegate outbound-context persistence now-fn]}]
   (let [opts (cond-> {}
                delegate (assoc :delegate delegate)
                outbound-context (assoc :outbound-context outbound-context))
         now-fn (or now-fn #(System/currentTimeMillis))
         port {:apply-command (if (seq opts)
                                (fn [session request]
                                  (apply-command session request opts))
                                apply-command)
               :confirm-draft-saved confirm-draft-saved-port
               :fail-draft-save fail-draft-save-port}]
     (cond-> port
       persistence
       (assoc :commit-session!
              (fn [previous session now]
                (commit-owner-session! persistence previous session
                                       (or now (now-fn))))
              :reconcile-session!
              (fn [session]
                (reconcile-owner-session! persistence session (now-fn))))))))

;; ---------------------------------------------------------------------------
;; Command application

(defn- valid-request?
  [request]
  (and (map? request)
       (non-blank-string? (:command-id request))
       (integer? (:command-epoch request))
       (pos? (:command-epoch request))))

(defn- request-client-id
  [request]
  (let [payload (:payload request)]
    (drafts/normalize-client-id
     (or (:client-id request)
         (when (map? payload) (:client-id payload))))))

(defn- cancel-active-run
  [session reason now]
  (when-let [run (runs/active-run session)]
    (runs/cancel-run session {:run-id (:run-id run)
                              :reason reason
                              :now now})))

(defn- normalize-command-request
  "Accept either a full request or an Owner payload with `:kind`.

  A full request has `:type` and `:payload`.  A map with `:type` but no
  `:payload` keeps its remaining fields as the payload.  An Owner payload
  with `:kind` is converted to the full shape."
  [request]
  (if (and (some? (:type request)) (map? (:payload request)))
    request
    (let [payload (if (map? (:payload request))
                    (:payload request)
                    (dissoc request :kind :type :command-id
                            :command-epoch :client-id :now))
          kind (or (:type request)
                   (:kind request)
                   (when (map? payload) (:kind payload)))
          payload (if (map? payload)
                    (cond-> payload kind (dissoc :kind))
                    {})]
      {:type kind :payload payload})))

(defn- prepare-sendable-attachments
  "Validate draft attachments against the selected model and return the
  exact items that may travel.  Text-only messages skip the policy call."
  [config binding attachments]
  (if (empty? attachments)
    {:ok? true :sendable []}
    (let [connection (get-in config [:connections (:connection-id binding)])
          model (get-in connection [:models (:model-entry-id binding)])
          model-config (cond-> model
                         connection
                         (assoc :connection
                                (select-keys connection
                                             [:attachment-limits :limits])))]
      (attachment-model/prepare-send attachments model-config {}))))

(defn- outbound-projection
  "Use the history projection when supplied; otherwise send all visible
  messages.  The result is frozen into the run snapshot.

  OPTS carries the accepted turn's display facts (for example
  `:turn-card-ids`) so the outbound panel can report the same per-turn
  selection the model receives.  A projection function with the older
  one-argument shape is still accepted."
  ([session outbound-context] (outbound-projection session outbound-context {}))
  ([session outbound-context opts]
   (if-not outbound-context
     {:ok? true
      :message-ids (visible-message-ids session)
      :context {}}
     (try
       (let [projection (try
                          (outbound-context session opts)
                          (catch clojure.lang.ArityException _
                            (outbound-context session)))]
         (if (and (map? projection)
                  (vector? (:message-ids projection))
                  (every? ids/valid-id? (:message-ids projection)))
           {:ok? true
            :message-ids (:message-ids projection)
            ;; The outbound projection is the one real per-turn projection.
            ;; Keep its context snapshot sections in the frozen run; dropping
            ;; them here left the model request without the system/assistant/
            ;; instructions/memory/worldbook/tools/history/attachments/
            ;; estimates facts the client and diagnostics need.
            :context (select-keys projection
                                  [:messages :message-ids :summary-ids
                                   :version-ids :excluded
                                   :pinned-attachments :warnings
                                   :system :assistant :instructions :memory
                                   :worldbook :instruction-cards :tools
                                   :history :attachments :estimates])}
           {:ok? false
            :error {:code :OUTBOUND_CONTEXT_INVALID
                    :message "outbound projection needs a vector of message-ids"
                    :details {:projection projection}}}))
       (catch Exception exception
         {:ok? false
          :error {:code :OUTBOUND_CONTEXT_INVALID
                  :message (str "outbound projection failed: "
                                (.getMessage exception))}})))))

(defn apply-command
  "Apply one command's business transition without a command gate.

  The Owner calls this only after its single gate accepted the command.
  Returns `{:ok? bool :session s :result m :effects [...]}`.  A refusal
  leaves the session unchanged, except that `:session/send-message`
  preserves the submitted draft text and attachments on refusal.  This
  function never bumps `:revision`; the Owner commits one command as one
  revision.

  A deleted session accepts only `:session/restore` and `:session/delete`.
  Every other command is refused with `:SESSION_DELETED`, including on the
  assembled Owner path that calls this function directly.

  The two-argument arity accepts an Owner payload such as
  `{:kind :session/rename :title <string>}` and normalizes it."
  ([session request]
   (apply-command session request {:now (:now request)}))
  ([session request {:keys [config id-source now delegate outbound-context]
                     :as opts}]
   (let [request (normalize-command-request request)
         payload (:payload request)
         payload (if (map? payload) payload {})
         id-source (or id-source (ids/uuid-source))
         client-id (request-client-id request)
         now (now-value now)
         type (:type request)]
    (if (and (= :deleted (:status session))
             (not (contains? #{:session/restore :session/delete} type)))
      (business-fail session :SESSION_DELETED
                     "session is deleted; restore it before other commands"
                     {:session-id (:session-id session)})
      (case type
      :session/rename
      (let [title (:title payload)]
        (if-not (non-blank-string? title)
          (business-fail session :TITLE_REQUIRED
                         "title must be a non-blank string" {:title title})
          (business-ok (-> session
                           (assoc :title title :title-source :manual))
                       {:title title}
                       [])))

      :session/update-draft
      (let [draft (or (get-in session [:drafts client-id])
                      (drafts/new-draft client-id now))
            changes (assoc payload :client-id client-id)
            result (drafts/update-draft draft changes now)]
        (if-not (:ok? result)
          (business-fail session (:code (:error result))
                         (:message (:error result))
                         (:details (:error result)))
          (let [draft' (:draft result)
                session' (assoc-in session [:drafts client-id] draft')]
            (business-ok session'
                         {:client-id client-id
                          :draft-id client-id
                          :draft (drafts/public-draft draft')}
                         [{:kind :draft-updated
                           :session-id (:session-id session)
                           :client-id client-id
                           :revision (:revision draft')
                           :saved? (drafts/saved? draft')}]))))

      :session/record-outbound
      (let [message-id (:message-id payload)
            message (get-in session [:messages message-id])]
        (cond
          (nil? message)
          (business-fail session :MESSAGE_NOT_FOUND
                         "message does not exist" {:message-id message-id})

          (not= :user (:role message))
          (business-fail session :MESSAGE_ROLE_INVALID
                         "only a user message freezes an outbound snapshot"
                         {:message-id message-id :role (:role message)})

          :else
          (let [outbound (outbound-projection
                          session outbound-context
                          (select-keys payload
                                       [:turn-card-ids :instruction-values
                                        :temporary-read?]))]
            (if-not (:ok? outbound)
              (business-fail session
                             (:code (:error outbound))
                             (:message (:error outbound))
                             (:details (:error outbound)))
              (let [run-id (or (:run-id payload)
                               (ids/next-id! id-source :run))
                    recorded (runs/record-outbound
                              session
                              {:run-id run-id
                               :message-id message-id
                               :message-ids (:message-ids outbound)
                               :context (:context outbound)
                               :temporary-read? (:temporary-read? payload)
                               :now now})]
                (if-not (:ok? recorded)
                  (business-fail session
                                 (:code (:error recorded))
                                 (:message (:error recorded))
                                 (:details (:error recorded)))
                  (business-ok (:session recorded)
                               {:run-id run-id
                                :message-id message-id
                                :context-summary-ids
                                (get-in outbound [:context :summary-ids])
                                :context-warnings
                                (get-in outbound [:context :warnings])}
                               [])))))))

      :session/send-message
      (let [text (:text payload)
            attachments (or (:attachments payload) [])
            citations (or (:citations payload) [])
            pending (:pending-model-binding session)
            binding (or (when pending (:binding pending))
                        (:model-binding session))
            draft (or (get-in session [:drafts client-id])
                      (drafts/new-draft client-id now))
            draft-result (drafts/update-draft
                          draft
                          {:client-id client-id
                           :text text
                           :attachments attachments
                           :citations citations}
                          now)]
        (if-not (:ok? draft-result)
          (business-fail session (:code (:error draft-result))
                         (:message (:error draft-result))
                         (:details (:error draft-result)))
          (let [draft' (:draft draft-result)
                session-with-draft (assoc-in session [:drafts client-id] draft')]
            (cond
              (not= :active (:status session))
              (business-fail session-with-draft :SESSION_NOT_ACTIVE
                             "session is not active" {:status (:status session)})

              (nil? binding)
              (business-fail session-with-draft :MODEL_NOT_SELECTED
                             "select a model before sending" {})

              (nil? config)
              (business-fail session-with-draft :CONFIG_REQUIRED
                             "sending a message needs the current configuration" {})

              (not (config/binding-usable? config binding))
              (business-fail session-with-draft :MODEL_UNAVAILABLE
                             "the selected model is not currently usable"
                             {:availability (config/binding-status config binding)
                              :binding (select-keys binding
                                                    [:connection-id :model-entry-id
                                                     :display-name])})

              (and (str/blank? (str (:text draft')))
                   (empty? (:attachments draft')))
              (business-fail session-with-draft :MESSAGE_EMPTY
                             "a message needs text or an attachment" {})

              :else
              (let [prepared (prepare-sendable-attachments
                              config binding (:attachments draft'))]
                (cond
                  (not (:ok? prepared))
                  (business-fail session-with-draft
                                 :ATTACHMENT_NOT_SENDABLE
                                 "some attachments need a choice or repair"
                                 {:issues (:issues prepared)
                                  :summary (:summary prepared)})

                  (and (str/blank? (str (:text draft')))
                       (empty? (:sendable prepared)))
                  (business-fail session-with-draft
                                 :MESSAGE_EMPTY
                                 "a message needs text or an attachment" {})

                  :else
                  (let [message-id (ids/next-id! id-source :message)
                        message (new-message message-id :user
                                             (:text draft')
                                             (:sendable prepared)
                                             (:citations draft')
                                             (:quotes payload)
                                             now)
                        session' (append-message* session-with-draft message now)
                        session' (if pending
                                   (-> session'
                                       (assoc :model-binding (:binding pending))
                                       (assoc :pending-model-binding nil))
                                   session')]
                    (let [outbound (outbound-projection
                                    session' outbound-context
                                    (select-keys payload
                                                 [:turn-card-ids
                                                  :instruction-values
                                                  :temporary-read?]))]
                      (if-not (:ok? outbound)
                        (business-fail session-with-draft
                                       (:code (:error outbound))
                                       (:message (:error outbound))
                                       (:details (:error outbound)))
                        (let [run-id (ids/next-id! id-source :run)
                              call-id (ids/next-id! id-source :call)
                              run-result (runs/start-run
                                          session'
                                          {:run-id run-id
                                           :call-id call-id
                                           :model-binding (:model-binding session')
                                           :message-ids (:message-ids outbound)
                                           :context (:context outbound)
                                           :temporary-read? (:temporary-read? payload)
                                           :policy (or (:policy payload) {})
                                           :language (:language session')
                                           :now now})]
                          (if-not (:ok? run-result)
                            (business-fail session-with-draft
                                           (:code (:error run-result))
                                           (:message (:error run-result))
                                           (:details (:error run-result)))
                            (let [cleared-result (drafts/clear-after-send draft' now)
                                  cleared (:draft cleared-result)
                                  session'' (-> (:session run-result)
                                                (assoc-in [:drafts client-id] cleared)
                                                (assoc :activity-at now))]
                              (business-ok
                               session''
                               {:message-id message-id
                                :run-id run-id
                                :call-id call-id
                                :attachments-sent (count (:sendable prepared))
                                :context-summary-ids (get-in outbound [:context :summary-ids])
                                :context-warnings (get-in outbound [:context :warnings])
                                :draft (drafts/public-draft cleared)}
                               (conj (vec (:effects run-result))
                                     {:kind :draft-updated
                                      :session-id (:session-id session)
                                      :client-id client-id
                                      :revision (:revision cleared)
                                      :saved? (drafts/saved? cleared)}))))))))))))))

      :session/edit-message
      (let [message-id (:message-id payload)
            content (:content payload)
            message (get-in session [:messages message-id])]
        (cond
          (nil? message)
          (business-fail session :MESSAGE_NOT_FOUND
                         "message does not exist" {:message-id message-id})

          (:deleted? message)
          (business-fail session :MESSAGE_DELETED
                         "a deleted message cannot be edited"
                         {:message-id message-id})

          (not (string? content))
          (business-fail session :MESSAGE_CONTENT_INVALID
                         "message content must be a string" {:message-id message-id})

          (and (contains? payload :attachments)
               (not (vector? (:attachments payload))))
          (business-fail session :MESSAGE_VERSION_INVALID
                         "version attachments must be a vector"
                         {:message-id message-id})

          (and (contains? payload :citations)
               (not (vector? (:citations payload))))
          (business-fail session :MESSAGE_VERSION_INVALID
                         "version citations must be a vector"
                         {:message-id message-id})

          (and (contains? payload :tool-records)
               (not (vector? (:tool-records payload))))
          (business-fail session :MESSAGE_VERSION_INVALID
                         "version tool-records must be a vector"
                         {:message-id message-id})

          (and (contains? payload :usage)
               (not (map? (:usage payload))))
          (business-fail session :MESSAGE_VERSION_INVALID
                         "version usage must be a map"
                         {:message-id message-id})

          :else
          (let [version-id (str message-id "-v" (inc (count (:versions message))))
                version (cond-> {:version-id version-id
                                 :content content
                                 :created-at now
                                 :edited? true
                                 :manual-edit? (= :assistant (:role message))}
                          (contains? payload :attachments)
                          (assoc :attachments (:attachments payload))
                          (contains? payload :citations)
                          (assoc :citations (:citations payload))
                          (contains? payload :tool-records)
                          (assoc :tool-records (:tool-records payload))
                          (contains? payload :usage)
                          (assoc :usage (:usage payload)))
                message' (-> message
                             (update :versions conj version)
                             (assoc :current-version (count (:versions message))
                                    :updated-at now)
                             (cond-> (= :assistant (:role message))
                               (assoc :manual-edit? true)))
                session' (assoc-in session [:messages message-id] message')
                active (runs/active-run session')
                cancel (when (and active
                                  (runs/run-affected-by-message? session' message-id))
                         (runs/cancel-run session'
                                          {:run-id (:run-id active)
                                           :reason :input-changed
                                           :now now}))
                session'' (if cancel (:session cancel) session')]
            (business-ok (assoc session'' :activity-at now)
                         {:message-id message-id
                          :version-id version-id
                          :cancelled-run-id (when cancel (:run-id active))}
                         (:effects cancel)))))

      :session/fold-message
      (let [message-id (:message-id payload)
            message (get-in session [:messages message-id])]
        (if (nil? message)
          (business-fail session :MESSAGE_NOT_FOUND
                         "message does not exist" {:message-id message-id})
          (let [message' (assoc message :folded? true :folded-at now)
                session' (assoc-in session [:messages message-id] message')
                active (runs/active-run session')
                cancel (when (and active
                                  (runs/run-affected-by-message? session' message-id))
                         (runs/cancel-run session'
                                          {:run-id (:run-id active)
                                           :reason :input-changed
                                           :now now}))
                session'' (if cancel (:session cancel) session')]
            (business-ok (assoc session'' :activity-at now)
                         {:message-id message-id
                          :folded? true
                          :cancelled-run-id (when cancel (:run-id active))}
                         (:effects cancel)))))

      :session/delete-message
      (let [message-id (:message-id payload)
            message (get-in session [:messages message-id])]
        (if (nil? message)
          (business-fail session :MESSAGE_NOT_FOUND
                         "message does not exist" {:message-id message-id})
          (let [message' (assoc message :deleted? true :deleted-at now)
                session' (assoc-in session [:messages message-id] message')
                active (runs/active-run session')
                cancel (when (and active
                                  (runs/run-affected-by-message? session' message-id))
                         (runs/cancel-run session'
                                          {:run-id (:run-id active)
                                           :reason :input-changed
                                           :now now}))
                session'' (if cancel (:session cancel) session')]
            (business-ok (assoc session'' :activity-at now)
                         {:message-id message-id
                          :deleted? true
                          :cancelled-run-id (when cancel (:run-id active))}
                         (:effects cancel)))))

      :session/stop-run
      (let [run-id (or (:run-id payload)
                       (some-> (runs/active-run session) :run-id))]
        (if (nil? run-id)
          (business-ok session {:ignored :no-active-run} [])
          (let [result (runs/cancel-run session
                                        {:run-id run-id
                                         :reason (or (:reason payload) :user-cancel)
                                         :now now})]
            (if-not (:ok? result)
              (business-fail session (:code (:error result))
                             (:message (:error result))
                             (:details (:error result)))
              (business-ok (:session result)
                           {:run-id run-id
                            :ignored (:ignored result)}
                           (:effects result))))))

      :session/select-model
      (let [connection-id (:connection-id payload)
            model-entry-id (:model-entry-id payload)
            mode (:mode payload)
            resolved (config/resolve-binding config connection-id model-entry-id)
            active (runs/active-run session)]
        (cond
          (not (:ok? resolved))
          (business-fail session (:code (:error resolved))
                         (:message (:error resolved))
                         (:details (:error resolved)))

          (nil? active)
          (business-ok (assoc session
                              :model-binding (:binding resolved)
                              :pending-model-binding nil)
                       {:effective :now
                        :model-binding (:binding resolved)}
                       [])

          (= mode :next-round)
          (business-ok (assoc session
                              :pending-model-binding
                              {:binding (:binding resolved)
                               :mode :next-round
                               :requested-at now})
                       {:effective :next-round
                        :model-binding (:binding resolved)}
                       [])

          (= mode :stop-and-switch)
          (let [cancel (runs/cancel-run session
                                        {:run-id (:run-id active)
                                         :reason :model-switch
                                         :now now})
                session' (assoc (:session cancel)
                                :pending-model-binding
                                {:binding (:binding resolved)
                                 :mode :stop-and-switch
                                 :requested-at now})]
            (business-ok session'
                         {:effective :next-round
                          :stopped-run-id (:run-id active)
                          :model-binding (:binding resolved)}
                         (:effects cancel)))

          :else
          (business-fail session :MODEL_SWITCH_MODE_INVALID
                         "an active run needs :next-round or :stop-and-switch"
                         {:mode mode})))

      :session/select-skills
      (let [mode (or (:mode payload) :follow)
            raw-skills (:skills payload)
            skills (cond
                     (nil? raw-skills) []
                     (or (sequential? raw-skills) (set? raw-skills))
                     (->> raw-skills
                          (filter non-blank-string?)
                          distinct
                          sort
                          vec)
                     :else nil)
            scope (or (:mcp-session-scope session) {})]
        (cond
          (not (contains? #{:follow :custom} mode))
          (business-fail session :SKILL_SELECTION_MODE_INVALID
                         "skill selection mode must be :follow or :custom"
                         {:mode mode})

          (nil? skills)
          (business-fail session :SKILL_SELECTION_INVALID
                         "skills must be a sequence of skill ids"
                         {:skills raw-skills})

          :else
          (let [scope' (cond-> (assoc scope :mode mode)
                         (= :custom mode) (assoc :skills (set skills))
                         (= :follow mode) (dissoc :skills))
                session' (assoc session :mcp-session-scope scope')]
            (business-ok session'
                         {:effective :now
                          :session-scope scope'}
                         []))))

      :session/pin
      (let [pinned? (boolean (:pinned? payload))]
        (business-ok (assoc session :pinned? pinned?)
                     {:pinned? pinned?}
                     []))

      :session/archive
      (let [cancel (cancel-active-run session :archived now)]
        (business-ok (-> (if cancel (:session cancel) session)
                         (assoc :status :archived))
                     {:status :archived}
                     (:effects cancel)))

      :session/restore
      (business-ok (-> session
                       (assoc :status :active)
                       (assoc :deleted-at nil))
                   {:status :active}
                   [])

      :session/delete
      (let [cancel (cancel-active-run session :deleted now)]
        (business-ok (-> (if cancel (:session cancel) session)
                         (assoc :status :deleted)
                         (assoc :deleted-at now))
                     {:status :deleted :deleted-at now}
                     (:effects cancel)))

      :session/transfer
      (if-not (:temporary? session)
        (business-ok session {:temporary? false :ignored :already-permanent} [])
        (business-ok (assoc session :temporary? false)
                     {:temporary? false}
                     []))

      :session/append-message
      (let [role (:role payload)
            content (:content payload)
            message-id (or (:message-id payload)
                           (ids/next-id! id-source :message))]
        (cond
          (not (contains? #{:user :assistant :system} role))
          (business-fail session :MESSAGE_ROLE_INVALID
                         "role must be :user, :assistant, or :system"
                         {:role role})

          (not (string? content))
          (business-fail session :MESSAGE_CONTENT_INVALID
                         "message content must be a string" {:content content})

          (not (ids/valid-id? message-id))
          (business-fail session :MESSAGE_ID_INVALID
                         "message-id must be a non-blank string"
                         {:message-id message-id})

          (contains? (:messages session) message-id)
          (business-fail session :MESSAGE_ID_TAKEN
                         "message-id already exists" {:message-id message-id})

          :else
          (let [message (new-message message-id role content [] [] [] now)
                session' (append-message* session message now)]
            (business-ok session'
                         {:message-id message-id}
                         [{:kind :message-appended
                           :session-id (:session-id session)
                           :message-id message-id
                           :role role}]))))

        (if delegate
          (delegate session request (assoc opts :now now))
          (business-fail session :COMMAND_TYPE_UNKNOWN
                         "unknown session command type"
                         {:type type})))))))

(defn execute
  "Accept one client command under an explicit Owner gate.

  The gate state is passed in `:gate` and returned as `:gate-state`; the
  session record contains no command epoch.  This is the standalone/library
  path and the function an Owner may use with its own gate.  Deduplication
  happens before the business transition: the gate stores a
  `:dispatching` placeholder, the business transition runs only after the
  epoch is accepted, and the final receipt replaces the placeholder.  A
  replay returns the original receipt and changes neither the session nor
  the gate.

  `:scope` in OPTS, or `:command-key` in REQUEST, selects the same key shape
  the durable gate uses (`[scope command-id]`).  Without either, the key is
  the bare command-id, matching the session Owner gate."
  [session request opts]
  (let [now (now-value (:now opts))
        gate-state (:gate opts)
        scope (:scope opts)
        command-key (or (:command-key request)
                        (if (some? scope)
                          [scope (:command-id request)]
                          (:command-id request)))
        keyed-request (assoc request :command-key command-key)
        receipt-query (if (some? scope)
                        {:command-key command-key}
                        command-key)]
    (cond
      (nil? gate-state)
      {:ok? false
       :session session
       :error {:code :COMMAND_GATE_REQUIRED
               :message "execute needs the Owner command gate in :gate"}
       :effects []}

      (not (valid-request? request))
      (let [result (commands/accept gate-state request)]
        {:ok? false
         :session session
         :gate-state gate-state
         :error (:error result)
         :effects []})

      (some? (commands/receipt-for gate-state receipt-query))
      (let [result (commands/accept gate-state keyed-request)]
        (if-not (:ok? result)
          {:ok? false
           :session session
           :gate-state gate-state
           :error (:error result)
           :effects []}
          (let [receipt (:receipt result)]
            {:ok? (= :accepted (:status receipt))
             :session session
             :gate-state gate-state
             :receipt receipt
             :replayed? true
             :error (when (= :refused (:status receipt))
                      (:error receipt))
             :effects []})))

      :else
      (let [placeholder {:status :dispatching
                         :command-id (:command-id request)
                         :command-epoch (:command-epoch request)}
            accepted (commands/accept gate-state keyed-request placeholder)]
        (if-not (:ok? accepted)
          {:ok? false
           :session session
           :gate-state gate-state
           :error (:error accepted)
           :effects []}
          (let [business (apply-command session request (assoc opts :now now))]
            (if-not (and (map? business)
                         (contains? business :ok?)
                         (map? (:session business)))
              (let [receipt {:status :refused
                             :command-id (:command-id request)
                             :command-epoch (:command-epoch request)
                             :type (:type request)
                             :error {:code :COMMAND_RESULT_INVALID
                                     :message "command handler returned an invalid result"
                                     :details {:result business}}}
                    completed (commands/complete (:state accepted)
                                                 command-key receipt)]
                (if (:ok? completed)
                  {:ok? false
                   :session session
                   :gate-state (:state completed)
                   :receipt receipt
                   :error (:error receipt)
                   :effects []}
                  {:ok? false
                   :session session
                   :gate-state (:state accepted)
                   :error (:error completed)
                   :effects []}))
              (let [changed? (not= (:session business) session)
                    session' (if changed?
                               (bump (:session business) now)
                               (:session business))
                    receipt-status (if (:ok? business) :accepted :refused)
                    receipt-base {:status receipt-status
                                  :command-id (:command-id request)
                                  :command-epoch (:command-epoch request)
                                  :type (:type request)}
                    receipt-base (if (:ok? business)
                                   (assoc receipt-base
                                          :result (:result business))
                                   (assoc receipt-base
                                          :error (:error business)))
                    receipt (assoc receipt-base
                                   :session-revision (:revision session'))
                    completed (commands/complete (:state accepted)
                                                 command-key receipt)]
                (if-not (:ok? completed)
                  {:ok? false
                   :session session
                   :gate-state (:state accepted)
                   :error (:error completed)
                   :effects []}
                  (let [effects (cond-> (vec (:effects business))
                                  changed?
                                  (conj (persist-effect session')))
                        result {:ok? (:ok? business)
                                :session session'
                                :gate-state (:state completed)
                                :receipt receipt
                                :replayed? false
                                :effects effects}]
                    (if (:ok? business)
                      result
                      (assoc result :error (:error business)))))))))))))

;; ---------------------------------------------------------------------------
;; Asynchronous model events

(defn- append-target-version
  "Append one regenerated version to an existing assistant message.

  Returns nil when the target disappeared, was folded/deleted, or is not an
  assistant message.  PARENT-VERSION-ID is the version frozen in the run
  snapshot; a later version selection cannot change the new version's
  parent.  A late or cancelled result never reaches this helper."
  [session message-id content event parent-version-id now]
  (let [message (get-in session [:messages message-id])]
    (when (and message
               (= :assistant (:role message))
               (not (:deleted? message))
               (not (:folded? message)))
      (let [version-id (str message-id "-v" (inc (count (:versions message))))
            current (get-in message [:versions (:current-version message)])
            version (cond-> {:version-id version-id
                             :content content
                             :created-at now
                             :edited? false
                             :manual-edit? false
                             :source :regenerated
                             :parent-version-id (or parent-version-id
                                                    (:version-id current))
                             :attachments []
                             :citations (vec (or (:citations event) []))
                             :tool-records (vec (or (:tool-records event) []))}
                      (some? (:usage event)) (assoc :usage (:usage event)))
            message' (-> message
                         (update :versions conj version)
                         (assoc :current-version (count (:versions message))
                                :updated-at now
                                :attachments []
                                :citations (vec (or (:citations event) []))))]
        {:session (-> session
                      (assoc-in [:messages message-id] message')
                      (assoc :activity-at now))
         :version-id version-id}))))

(defn accept-model-event
  "Accept one model-port event for a run attempt.

  A terminal success appends an assistant message only when the attempt is
  current, the run was not cancelled, and the result is not late.  Chunks
  from a deleted-message run never resurrect the message; they stay in the
  non-chat attempt record."
  [session event {:keys [now id-source]}]
  (let [now (now-value now)
        id-source (or id-source (ids/uuid-source))
        result (runs/accept-event session event now)]
    (cond
      (not (:ok? result))
      result

      (:ignored result)
      (if (= :late-terminal (:ignored result))
        (let [session' (bump (:session result) now)]
          (assoc result
                 :session session'
                 :effects [(persist-effect session')]))
        (assoc result :session session :effects []))

      :else
      (let [session' (:session result)
            attempt (get-in session' [:attempts (:attempt-id event)])
            run (get-in session' [:runs (:run-id attempt)])
            terminal (:terminal result)
            content (:content result)
            append? (and (= :succeeded terminal)
                         (not (:late? result))
                         (not= :cancelled (:state run))
                         (string? content)
                         (not (str/blank? content)))
            target-id (get-in run [:input-snapshot :target-message-id])
            target-version (when (and append? target-id)
                             (append-target-version session' target-id
                                                    content event
                                                    (get-in run
                                                            [:input-snapshot
                                                             :context
                                                             :target-version-id])
                                                    now))
            new-message-id (when (and append? (nil? target-id))
                             (ids/next-id! id-source :message))
            session'' (cond
                        target-version (:session target-version)
                        new-message-id (append-message* session'
                                                         (let [message (new-message
                                                                       new-message-id
                                                                       :assistant
                                                                       content [] [] [] now)]
                                                           ;; The terminal event's
                                                           ;; usage report belongs
                                                           ;; to this first
                                                           ;; version; dropping it
                                                           ;; here loses the only
                                                           ;; record of provider
                                                           ;; token counts.
                                                           (cond-> message
                                                             (some? (:usage event))
                                                             (update-in [:versions 0]
                                                                        assoc
                                                                        :usage
                                                                        (:usage event))))
                                                         now)
                        :else session')
            session''' (bump session'' now)
            message-id (or (when target-version target-id)
                           new-message-id)]
        (assoc result
               :session session'''
               :message-id message-id
               :version-id (:version-id target-version)
               :effects (conj (vec (:effects result))
                              (persist-effect session''')))))))

;; ---------------------------------------------------------------------------
;; Listing, retention, and commands

(defn- copy-message
  "Copy one message into a branch with fresh message and version IDs."
  [message new-message-id now session-id]
  (let [old-versions (vec (:versions message))
        new-version-id (fn [index]
                         (str new-message-id "-v" (inc index)))
        remap (into {}
                    (keep-indexed (fn [index version]
                                    (when-let [old (:version-id version)]
                                      [old (new-version-id index)])))
                    old-versions)
        versions (mapv (fn [index version]
                         (cond-> (assoc version
                                        :version-id (new-version-id index)
                                        :created-at now)
                           (:parent-version-id version)
                           (assoc :parent-version-id
                                  (get remap (:parent-version-id version)))

                           (:regenerated-from-version-id version)
                           (assoc :regenerated-from-version-id
                                  (get remap
                                       (:regenerated-from-version-id version)))))
                       (range)
                       old-versions)]
    (cond-> (assoc message
                   :message-id new-message-id
                   :session-id session-id
                   :versions versions
                   :created-at now
                   :updated-at now)
      (:run-id message)
      (-> (assoc :origin-run-id (:run-id message))
          (dissoc :run-id))
      ;; Call and attempt identifiers belong to the source turn's runtime
      ;; machinery; the branch starts with no calls or attempts, so the
      ;; copied message must not keep pointers into records that do not
      ;; exist there.  Nudge markers stay: the history projection filters
      ;; nudge messages by `:nudge?`/`:nudge-id`, and stripping them would
      ;; make internal nudges visible in the branch.
      true
      (-> (dissoc :call-id :attempt-id)))))

(defn branch-session
  "Create an independent session from SOURCE before a branch point.

  Messages strictly before `:before-message-id` are copied with fresh IDs.
  When the option is nil, all messages are copied.  The branch keeps the
  source model-binding snapshot, language, assistant, and origin link; it
  starts with no drafts, runs, calls, or attempts.  Deleting the source later
  does not affect the branch."
  [source {:keys [session-id owner-id before-message-id id-source now title
                  client-id]}]
  (let [now (now-value now)
        id-source (or id-source (ids/uuid-source))
        new-session-id (or session-id (ids/next-id! id-source :session))
        order (:message-order source)
        before? (some #(= before-message-id %) order)
        prefix (if before-message-id
                 (take-while #(not= % before-message-id) order)
                 order)
        copied (reduce (fn [[messages ids] source-id]
                         (if-let [message (get-in source [:messages source-id])]
                           (let [new-id (ids/next-id! id-source :message)]
                             [(assoc messages new-id
                                     (copy-message message new-id now new-session-id))
                              (conj ids new-id)])
                           [messages ids]))
                       [{} []]
                       prefix)
        [messages message-order] copied
        new-owner-id (or owner-id (ids/next-id! id-source :owner))]
    (cond
      (not= :active (:status source))
      (refusal source :SESSION_NOT_ACTIVE
               "only an active session can be branched"
               {:status (:status source)})

      (and before-message-id (not before?))
      (refusal source :MESSAGE_NOT_FOUND
               "branch point does not exist in this session"
               {:message-id before-message-id})

      (and title (not (non-blank-string? title)))
      (refusal source :TITLE_INVALID
               "title must be a non-blank string"
               {:title title})

      (not (ids/valid-id? new-session-id))
      {:ok? false
       :error {:code :SESSION_ID_INVALID
               :message "session-id must be a non-blank string"
               :details {:session-id new-session-id}}}

      (not (ids/valid-id? new-owner-id))
      {:ok? false
       :error {:code :OWNER_ID_INVALID
               :message "owner-id must be a non-blank string"
               :details {:owner-id new-owner-id}}}

      :else
      (let [branch {:session-id new-session-id
                    :owner-id new-owner-id
                    :revision 0
                    :persisted-revision nil
                    :status :active
                    :title (or title (str "Branch of " (:title source)))
                    :title-source (if title :manual :auto)
                    :language (:language source)
                    :assistant-id (:assistant-id source)
                    :origin-session-id (:session-id source)
                    :origin-message-id before-message-id
                    :created-at now
                    :updated-at now
                    :activity-at now
                    :pinned? false
                    :temporary? (boolean (:temporary? source))
                    :capability-scope (:capability-scope source)
                    :deleted-at nil
                    :context {}
                    :model-binding (:model-binding source)
                    :pending-model-binding nil
                    :drafts (if client-id
                              {(drafts/normalize-client-id client-id)
                               (drafts/new-draft client-id now)}
                              {})
                    :messages messages
                    :message-order message-order
                    :runs {}
                    :run-order []
                    :current-run-id nil
                    :calls {}
                    :attempts {}}]
        {:ok? true
         :session branch
         :effects [{:kind :session-created
                    :session-id new-session-id
                    :owner-id new-owner-id
                    :origin-session-id (:session-id source)}]}))))

(defn summary-session
  "Create an independent session seeded with one reviewed compression summary.

  Only the reviewed summary text travels.  Source messages are never copied:
  the new session must not carry the replaced history in the background.  The
  first message is a :system message whose text names the source session and
  the original range, so the operator can identify where the summary came
  from; the reviewed summary itself is kept verbatim after that header.

  The source session must be active and COMPRESSION-ID must be an applied
  compression.  A draft, reverted or cancelled compression is refused."
  [source {:keys [session-id owner-id compression-id id-source now title
                  client-id]}]
  (let [now (now-value now)
        id-source (or id-source (ids/uuid-source))
        new-session-id (or session-id (ids/next-id! id-source :session))
        new-owner-id (or owner-id (ids/next-id! id-source :owner))
        compression (get-in source [:context :compressions compression-id])
        summary (:summary compression)
        source-ids (vec (or (:message-ids compression) []))
        message-id (ids/next-id! id-source :message)
        content (str "【压缩摘要 · 来源会话《"
                     (or (:title source) (:session-id source))
                     "》】\n原发送范围 " (count source-ids)
                     " 条消息（未复制原文）。\n\n"
                     summary)
        message (assoc (new-message message-id :system content [] [] [] now)
                       :summary-origin {:session-id (:session-id source)
                                        :compression-id compression-id
                                        :message-ids source-ids})
        summary-session {:session-id new-session-id
                         :owner-id new-owner-id
                         :revision 0
                         :persisted-revision nil
                         :status :active
                         :title (or title (str "摘要 · "
                                               (or (:title source) "会话")))
                         :title-source (if title :manual :auto)
                         :language (:language source)
                         :assistant-id (:assistant-id source)
                         :origin-session-id nil
                         :origin-message-id nil
                         :summary-origin {:session-id (:session-id source)
                                          :compression-id compression-id
                                          :message-ids source-ids}
                         :created-at now
                         :updated-at now
                         :activity-at now
                         :pinned? false
                         :temporary? false
                         :capability-scope (:capability-scope source)
                         :deleted-at nil
                         :context {}
                         :model-binding (:model-binding source)
                         :pending-model-binding nil
                         :drafts (if client-id
                                   {(drafts/normalize-client-id client-id)
                                    (drafts/new-draft client-id now)}
                                   {})
                         :messages {message-id message}
                         :message-order [message-id]
                         :runs {}
                         :run-order []
                         :current-run-id nil
                         :calls {}
                         :attempts {}}]
    (cond
      (not= :active (:status source))
      (refusal source :SESSION_NOT_ACTIVE
               "only an active session can provide a summary"
               {:status (:status source)})

      (nil? compression)
      (refusal source :COMPRESSION_NOT_FOUND
               "compression does not exist"
               {:compression-id compression-id})

      (not= :applied (:state compression))
      (refusal source :COMPRESSION_NOT_APPLIED
               "only an applied compression can seed a new session"
               {:compression-id compression-id
                :state (:state compression)})

      (not (non-blank-string? summary))
      (refusal source :COMPRESSION_SUMMARY_REQUIRED
               "compression needs a non-blank summary"
               {:compression-id compression-id})

      (not (ids/valid-id? new-session-id))
      {:ok? false
       :error {:code :SESSION_ID_INVALID
               :message "session-id must be a non-blank string"
               :details {:session-id new-session-id}}}

      (not (ids/valid-id? new-owner-id))
      {:ok? false
       :error {:code :OWNER_ID_INVALID
               :message "owner-id must be a non-blank string"
               :details {:owner-id new-owner-id}}}

      (and title (not (non-blank-string? title)))
      (refusal source :TITLE_INVALID
               "title must be a non-blank string"
               {:title title})

      :else
      {:ok? true
       :session summary-session
       :effects [{:kind :session-created
                  :session-id new-session-id
                  :owner-id new-owner-id
                  :summary-of (:session-id source)
                  :compression-id compression-id}]})))

(defn command-epoch
  "Return the active epoch from the Owner gate state, or nil.

  There is exactly one epoch per session Owner; it is the same value the
  compose Owner advertises as `:sync/command-epoch`."
  [gate-state]
  (commands/active-epoch gate-state))

(def metadata-command-epoch
  "Deprecated alias for `command-epoch`; kept for callers from the earlier
  metadata-only gate design."
  command-epoch)

(defn issue-command-epoch
  "Retire the current epoch in the Owner gate state and open the next one.

  Returns the new gate state.  The Owner persists it in its own state; the
  session record has no command gate."
  [gate-state now]
  (let [now (now-value now)
        result (commands/issue-epoch gate-state)]
    {:ok? true
     :gate-state (:state result)
     :epoch (:epoch result)
     :retired-epoch (:retired-epoch result)
     :now now}))

(defn suggest-title
  "Apply a late automatic title only when the user has not renamed manually.

  A manual title wins permanently; an automatic title never overwrites it.
  Neither path changes content activity ordering."
  [session title now]
  (let [now (now-value now)]
    (cond
      (not (non-blank-string? title))
      (refusal session :TITLE_REQUIRED
               "suggested title must be a non-blank string"
               {:title title})

      (= :manual (:title-source session))
      {:ok? true
       :session session
       :ignored :manual-title
       :effects []}

      :else
      (let [session' (-> session
                         (assoc :title title :title-source :auto)
                         (bump now))]
        {:ok? true
         :session session'
         :effects [(persist-effect session')]}))))

(defn temporary?
  "True when this session is isolated from long-term history, memory, and
  backup surfaces.  Use this predicate instead of reading the raw flag."
  [session]
  (boolean (:temporary? session)))

(defn session-summary
  [session]
  {:session-id (:session-id session)
   :owner-id (:owner-id session)
   :title (:title session)
   :title-source (:title-source session)
   :status (:status session)
   :pinned? (boolean (:pinned? session))
   :temporary? (boolean (:temporary? session))
   :deleted-at (:deleted-at session)
   :created-at (:created-at session)
   :activity-at (:activity-at session)
   ;; The snapshot session cache keys non-active summary rows by this
   ;; revision.  Without it every listed session shares the key `[nil epoch]`
   ;; and a stale `:session/by-id` row (for example an undo that only
   ;; changed `:context/compressions`) survives every publish.
   :revision (:revision session)
   :updated-at (:updated-at session)
   ;; `:visible-message-count` is stored on the committed record so summary
   ;; reads can skip the message bodies.  Records written before the field
   ;; existed fall back to counting the live messages.
   :message-count (or (:visible-message-count session)
                      (count (visible-messages session)))
   :has-draft? (boolean (some #(not (drafts/blank? %))
                              (vals (:drafts session))))
   :origin-session-id (:origin-session-id session)
   :origin-message-id (:origin-message-id session)
   :assistant-id (:assistant-id session)
   ;; The client's model-availability and composer need the binding identity,
   ;; not only the display copy.  Omitting it made a configured default model
   ;; look like "no model selected" for every newly created session.
   :model-binding (:model-binding session)
   :model (select-keys (:model-binding session)
                       [:connection-id :model-entry-id :display-name
                        :connection-name])})

(defn list-sessions
  "Return ordered session summaries.

  Pinned sessions come first, then content activity (newest first), then
  creation time.  Reading, scrolling, or switching a message version does not
  update `:activity-at`, so it cannot reorder the list."
  [sessions {:keys [include-archived? include-deleted?] :as _opts}]
  (let [sessions (if (map? sessions) (vals sessions) sessions)]
    (->> sessions
         (filter (fn [session]
                   (case (:status session)
                     :active true
                     :archived (boolean include-archived?)
                     :deleted (and (boolean include-deleted?)
                                   (not (:temporary? session)))
                     false)))
         (sort-by (fn [session]
                    [(if (:pinned? session) 0 1)
                     (- (or (:activity-at session) 0))
                     (- (or (:created-at session) 0))
                     (:session-id session)]))
         (mapv session-summary))))

(defn blank-session?
  "True when a session has no visible history, no non-blank draft, and no
  active run.  Repeatedly pressing New can reuse such a session."
  [session]
  (and (= :active (:status session))
       (zero? (count (:messages session)))
       (not (runs/run-active? session))
       (every? drafts/blank? (vals (:drafts session)))))

(defn find-reusable-blank-session
  [sessions]
  (->> (if (map? sessions) (vals sessions) sessions)
       (filter blank-session?)
       (sort-by (fn [session]
                  [(- (or (:created-at session) 0))
                   (:session-id session)]))
       first))

(defn restore
  [session now]
  (let [now (now-value now)
        session' (-> session
                     (assoc :status :active)
                     (assoc :deleted-at nil)
                     (bump now))]
    {:ok? true
     :session session'
     :effects [(persist-effect session')]}))

(defn purge
  "Permanently remove one soft-deleted session record.

  The caller must pass `:confirm? true`.  This function returns the purge
  intent; the assembly layer performs `delete-session!`.  It never deletes
  workspace files."
  [session {:keys [confirm?]}]
  (cond
    (not= :deleted (:status session))
    (refusal session :SESSION_NOT_DELETED
             "only a soft-deleted session can be purged"
             {:status (:status session)})

    (not confirm?)
    (refusal session :CONFIRMATION_REQUIRED
             "permanent deletion needs :confirm? true" {})

    :else
    {:ok? true
     :purge? true
     :session-id (:session-id session)
     :effects [{:kind :delete-session
                :session-id (:session-id session)
                :owner-id (:owner-id session)}]}))

(defn expired-deletion?
  [session now]
  (and (= :deleted (:status session))
       (or (:temporary? session)
           (and (integer? (:deleted-at session))
                (<= (:deleted-at session)
                    (- (now-value now) deletion-retention-ms))))))

;; ---------------------------------------------------------------------------
;; Invariants

(defn check-invariants
  "Return a vector of session invariant violations."
  [session]
  (if-not (map? session)
    [{:invariant :session-not-a-map}]
    (let [messages (:messages session)
          message-order (:message-order session)
          drafts-map (:drafts session)
          binding (:model-binding session)
          pending (:pending-model-binding session)]
      (into
       []
       (concat
        (cond-> []
          (not (ids/valid-id? (:session-id session)))
          (conj {:invariant :session-id-invalid
                 :session-id (:session-id session)})

          (not (ids/valid-id? (:owner-id session)))
          (conj {:invariant :owner-id-invalid
                 :owner-id (:owner-id session)})

          (not (integer? (:revision session)))
          (conj {:invariant :revision-invalid
                 :revision (:revision session)})

          (and (some? (:persisted-revision session))
               (not (integer? (:persisted-revision session))))
          (conj {:invariant :persisted-revision-invalid
                 :persisted-revision (:persisted-revision session)})

          (and (integer? (:revision session))
               (integer? (:persisted-revision session))
               (> (:persisted-revision session) (:revision session)))
          (conj {:invariant :persisted-ahead-of-revision
                 :revision (:revision session)
                 :persisted-revision (:persisted-revision session)})

          (not (contains? session-statuses (:status session)))
          (conj {:invariant :session-status-invalid
                 :status (:status session)})

          (not (contains? supported-languages (:language session)))
          (conj {:invariant :session-language-invalid
                 :language (:language session)})

          (not (contains? title-sources (:title-source session)))
          (conj {:invariant :title-source-invalid
                 :title-source (:title-source session)})

          (not (string? (:title session)))
          (conj {:invariant :title-invalid
                 :title (:title session)})

          (not (boolean? (:temporary? session)))
          (conj {:invariant :temporary-flag-invalid
                 :temporary? (:temporary? session)})

          (and (:origin-session-id session)
               (not (ids/valid-id? (:origin-session-id session))))
          (conj {:invariant :origin-session-id-invalid
                 :origin-session-id (:origin-session-id session)})

          (and (:origin-message-id session)
               (not (ids/valid-id? (:origin-message-id session))))
          (conj {:invariant :origin-message-id-invalid
                 :origin-message-id (:origin-message-id session)})

          (and (= :deleted (:status session))
               (not (integer? (:deleted-at session))))
          (conj {:invariant :deleted-without-timestamp
                 :deleted-at (:deleted-at session)})

          (and binding
               (or (not (map? binding))
                   (not (ids/valid-id? (:connection-id binding)))
                   (not (ids/valid-id? (:model-entry-id binding)))
                   (connections/secret-field? binding)))
          (conj {:invariant :model-binding-invalid})

          (and pending
               (or (not (map? pending))
                   (not (map? (:binding pending)))
                   (connections/secret-field? (:binding pending))
                   (not (contains? #{:next-round :stop-and-switch}
                                   (:mode pending)))))
          (conj {:invariant :pending-model-binding-invalid})

          (not (map? messages))
          (conj {:invariant :messages-not-a-map})

          (not= (set message-order) (set (keys messages)))
          (conj {:invariant :message-order-mismatch
                 :order message-order
                 :keys (keys messages)})

          (not= (count message-order) (count (set message-order)))
          (conj {:invariant :message-order-duplicate})

          (not (map? drafts-map))
          (conj {:invariant :drafts-not-a-map})

          (and (:current-run-id session)
               (not (contains? (:runs session) (:current-run-id session))))
          (conj {:invariant :current-run-missing
                 :current-run-id (:current-run-id session)}))
        (mapcat
         (fn [[message-id message]]
           (cond-> []
             (not= message-id (:message-id message))
             (conj {:invariant :message-id-mismatch
                    :key message-id :message-id (:message-id message)})

             (not (contains? #{:user :assistant :system} (:role message)))
             (conj {:invariant :message-role-invalid
                    :message-id message-id :role (:role message)})

             (not (and (vector? (:versions message))
                       (seq (:versions message))))
             (conj {:invariant :message-versions-invalid
                    :message-id message-id})

             (and (vector? (:versions message))
                  (not (contains? (set (range (count (:versions message))))
                                  (:current-version message))))
             (conj {:invariant :message-current-version-invalid
                    :message-id message-id
                    :current-version (:current-version message)})

             (and (vector? (:versions message))
                  (some #(not (string? (:content %))) (:versions message)))
             (conj {:invariant :message-content-invalid
                    :message-id message-id})))
         messages)
        (mapcat
         (fn [[client-id draft]]
           (concat
            (when (not= client-id (:client-id draft))
              [{:invariant :draft-key-mismatch
                :key client-id :client-id (:client-id draft)}])
            (drafts/check-invariants draft)))
         drafts-map)
        (runs/check-invariants session))))))
