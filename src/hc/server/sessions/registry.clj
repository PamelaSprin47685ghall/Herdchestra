(ns hc.server.sessions.registry
  "Assembly adapter: session metadata routing and Owner association.

  Authority: AGENTS.md §5.1.  This namespace is deliberately narrow.  It
  does not become a second writer for conversation, board, relay, calls, or
  cancellation.  Those commands belong to the compose Owner, which is the
  single client-command epoch authority for a session.

  The compose Owner is the single serial writer and the single command gate.
  The session record has no `:commands` field.  This registry only routes
  session-metadata commands through `OwnerPort/submit-command!`:

    :session/rename
    :session/update-draft
    :session/pin
    :session/archive
    :session/restore
    :session/delete

  A conversation command sent here is refused with
  `:CONVERSATION_COMMAND_NOT_ROUTED`; it must go to the compose Owner.
  `OwnerPort/enqueue!` carries internal lifecycle messages
  (`:session/close`, `:session/stop`), never client envelopes.

  The registry holds only `{session-id -> owner handle}` plus one lock.  The
  authoritative session snapshot lives in `SessionPersistence`; the live
  mutable state lives in the per-session Owner."
  (:require [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]))

(def metadata-command-types
  "The only command types this registry may route to the Owner.  The Owner
  gate is the single epoch authority; conversation commands go directly to
  the compose Owner."
  #{:session/rename
    :session/update-draft
    :session/pin
    :session/archive
    :session/restore
    :session/delete
    :session/transfer})

(defrecord SessionRegistry [persistence owner-factory now-fn id-source state lock])

(defn registry
  "Create a registry.

  Options:

    :persistence    a `SessionPersistence`
    :owner-factory  `(fn [{:keys [session-id owner-id session]}] owner)` or nil
    :now-fn         zero-argument clock, default `System/currentTimeMillis`
    :id-source      an `IdSource`, default UUID source"
  [{:keys [persistence owner-factory now-fn id-source]}]
  (when-not (satisfies? ports/SessionPersistence persistence)
    (throw (ex-info "registry needs a SessionPersistence"
                    {:code :INVALID_PERSISTENCE})))
  (->SessionRegistry persistence
                     owner-factory
                     (or now-fn #(System/currentTimeMillis))
                     (or id-source (ids/uuid-source))
                     (atom {:owners {}})
                     (Object.)))

(defn- error
  [code message details]
  {:status :error
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- start-owner!
  [registry session]
  (if-let [factory (:owner-factory registry)]
    (try
      {:ok? true
       :owner (factory {:session-id (:session-id session)
                        :owner-id (:owner-id session)
                        :session session})}
      (catch Exception exception
        {:ok? false
         :owner nil
         :error {:code :OWNER_START_FAILED
                 :message (str "session Owner could not start: "
                               (.getMessage exception))}}))
    {:ok? true :owner nil}))

(defn- as-owner-port
  "Accept either an OwnerPort or a compose Owner handle map."
  [owner]
  (if (satisfies? ports/OwnerPort owner)
    owner
    (ports/owner-port owner)))

(defn owner-for
  "Return the registered Owner handle for SESSION-ID, or nil."
  [registry session-id]
  (get-in @(:state registry) [:owners session-id]))

(defn register-owner!
  "Register an already-started Owner handle for SESSION-ID.

  The app router resumes Owners directly from its factory at startup and then
  registers them here.  Without this registration `get-session` and
  `list-sessions` cannot merge the live Owner session after a restart, so a
  committed model binding looks nil even though the Owner holds it."
  [registry session-id owner]
  (cond
    (not (ids/valid-id? session-id))
    (error :SESSION_INVALID
           "register-owner! needs a valid :session-id"
           {:session-id session-id})

    (nil? owner)
    (error :OWNER_REQUIRED
           "register-owner! needs an Owner handle"
           {:session-id session-id})

    :else
    (do
      (swap! (:state registry) assoc-in [:owners session-id] owner)
      {:ok? true :owner owner})))

(defn- owner-snapshot
  [registry session-id]
  (when-let [owner (owner-for registry session-id)]
    (when (ifn? (:snapshot owner))
      (try ((:snapshot owner)) (catch Throwable _ nil)))))

(defn- live-session-value
  "Merge a live Owner session over the committed record when the Owner is at
  least as new.  The Owner is the only writer after creation, so the
  committed registry record can lag metadata/draft/history updates."
  [registry session]
  (let [snapshot (owner-snapshot registry (:session-id session))
        live (get-in snapshot [:state :session])]
    (if (and (map? live)
             (>= (long (or (:revision live) -1))
                 (long (or (:revision session) -1))))
      (merge session live)
      session)))

(defn- commit-and-register!
  [registry session effects]
  (let [commit (sessions/commit-session! (:persistence registry) session)]
    (case (:status commit)
      :committed
      (let [session (:session commit)
            owner-result (start-owner! registry session)]
        (swap! (:state registry)
               assoc-in [:owners (:session-id session)]
               (:owner owner-result))
        (if (:ok? owner-result)
          {:status :created
           :session session
           :owner (:owner owner-result)
           :commit commit
           :effects effects}
          {:status :error
           :session session
           :owner nil
           :commit commit
           :error (:error owner-result)}))

      :duplicate
      {:status :duplicate
       :session (:session commit)
       :commit commit}

      :conflict
      (error :SESSION_COMMIT_CONFLICT
             "session already exists at a different revision"
             {:session-id (:session-id (:session commit))
              :revision (:revision commit)})

      :unknown
      (assoc (error :SESSION_COMMIT_UNKNOWN
                    "session commit outcome is unknown"
                    {:session-id (:session-id (:session commit))})
             :session (:session commit)
             :commit commit)

      (assoc (error :SESSION_COMMIT_FAILED
                    "session commit did not complete"
                    {:commit commit})
             :session (:session commit)
             :commit commit))))

(defn- read-session-failure
  "Return a failure map when RESULT is neither `:found` nor `:missing`.

  The storage adapter reports a read failure as `:unknown`; a custom adapter
  may use `:error`.  Neither may be silently dropped from a list or a reuse
  candidate scan."
  [result]
  (when-not (contains? #{:found :missing} (:status result))
    (if (= :error (:status result))
      result
      {:status :error
       :error (or (:error result)
                  {:code :SESSION_READ_FAILED
                   :message "session read did not return found or missing"
                   :details {:status (:status result)}})})))

(defn- load-sessions
  "Read every committed session.  Returns `{:status :ok :sessions [...]}` or
  the persistence error.  A read that is neither found nor missing fails the
  whole scan; it never becomes a silently shorter list."
  [registry]
  (let [ids-result (ports/list-session-ids (:persistence registry))]
    (if (= :ok (:status ids-result))
      (let [loaded (mapv (fn [session-id]
                           (ports/read-session (:persistence registry)
                                               session-id))
                         (:ids ids-result))
            failure (first (keep read-session-failure loaded))]
        (if failure
          failure
          {:status :ok
           :sessions (vec (keep (fn [result]
                                  (when (= :found (:status result))
                                    (live-session-value registry
                                                        (:session result))))
                                loaded))}))
      (if (= :error (:status ids-result))
        ids-result
        {:status :error
         :error (or (:error ids-result)
                    {:code :SESSION_LIST_FAILED
                     :message
                     "session list read did not return ok or error"
                     :details {:status (:status ids-result)}})}))))

(defn- reusable-blank-session
  [registry opts]
  (when (and (:reuse-blank? opts)
             (not (:temporary? opts))
             (nil? (:connection-id opts))
             (nil? (:model-entry-id opts))
             (nil? (:assistant-id opts))
             (nil? (:language opts))
             (nil? (:capability-scope opts)))
    (let [loaded (load-sessions registry)]
      (if (= :ok (:status loaded))
        {:status :ok
         :session (sessions/find-reusable-blank-session
                   (remove :temporary? (:sessions loaded)))}
        loaded))))

(defn- ensure-owner!
  [registry session]
  (if-let [owner (owner-for registry (:session-id session))]
    {:ok? true :owner owner :reused-owner? true}
    (let [result (start-owner! registry session)]
      (swap! (:state registry)
             assoc-in [:owners (:session-id session)]
             (:owner result))
      result)))

(defn ensure-owner-for-session!
  "Ensure SESSION has a live Owner for crash recovery.

  This is the recovery seam for a session or reuse claim that committed
  before its Owner started.  It never creates or mutates a session record;
  a missing, invalid, deleted, or archived session is refused so recovery
  cannot resurrect a session that is no longer active.  Returns the same
  shape as the internal owner start: `{:ok? true :owner handle}` or
  `{:ok? false :error ...}`."
  [registry session]
  (locking (:lock registry)
    (cond
      (not (and (map? session)
                (ids/valid-id? (:session-id session))))
      {:ok? false
       :owner nil
       :error {:code :SESSION_INVALID
               :message
               "ensure-owner-for-session! requires a committed session with a valid :session-id"}}

      (not= :active (:status session))
      {:ok? false
       :owner nil
       :error {:code :SESSION_NOT_ACTIVE
               :message "session is no longer active"
               :details {:session-id (:session-id session)
                         :status (:status session)}}}

      :else
      (ensure-owner! registry session))))

(defn- reuse-committed-session
  [registry session]
  (let [owner-result (ensure-owner! registry session)]
    (if (:ok? owner-result)
      {:status :created
       :reused? true
       :replayed? true
       :session session
       :owner (:owner owner-result)
       :commit {:status :unchanged}
       :effects []}
      {:status :error
       :session session
       :owner nil
       :commit {:status :committed}
       :error (:error owner-result)})))

(defn create-session!
  "Create, commit, and register one session.

  With `:reuse-blank? true`, an existing active, message-free session with
  only blank drafts is returned instead of creating another empty history
  entry.  Explicit `:connection-id`/`:model-entry-id`/`:assistant-id`/
  `:language` disables reuse so a model or language choice is never silently
  ignored.  Temporary sessions are never reused.

  Commit happens before Owner creation.  A failed commit creates neither an
  Owner nor a registry entry.  An Owner start failure leaves the committed
  session visible and returns an error; it never reports a usable Owner.

  A supplied `:creation-command` identity
  `{:scope s :command-epoch n :command-id id}` makes the operation
  idempotent: if the session persistence already holds that identity, the
  committed session is returned instead of creating a second session.  The
  identity and the session are one atomic commit; the same command-id in a
  different epoch is a different command."
  [registry opts]
  (locking (:lock registry)
    (let [identity (:creation-command opts)
          existing (when identity
                     (ports/lookup-session-by-command
                      (:persistence registry) identity))]
      (cond
        (and existing
             (not (contains? #{:found :missing} (:status existing))))
        (assoc existing
               :status :error
               :outcome :unknown
               :error (or (:error existing)
                          {:code :SESSION_COMMAND_LOOKUP_FAILED
                           :message
                           "session creation command-id lookup failed"}))

        (and existing (= :found (:status existing)))
        (reuse-committed-session registry (:session existing))

        :else
        (let [reusable (reusable-blank-session registry opts)]
          (cond
            (and reusable (not= :ok (:status reusable)))
            ;; Candidate discovery reads the current-state index before any
            ;; business effect.  A storage read failure is infrastructure,
            ;; not a business refusal: the session was not created, but the
            ;; command-id must stay available for a resync instead of being
            ;; burned as a final `:refused` receipt.  The persistence adapter
            ;; reports read failures as `:unknown`; test adapters may use
            ;; `:error`.  Both must stop before creating a session.
            (assoc reusable :status :error :outcome :unknown)

            (:session reusable)
            (let [session (:session reusable)
                  claim-supported? (or (nil? identity)
                                       (satisfies?
                                        ports/SessionCommandClaim
                                        (:persistence registry)))
                  claim (when (and identity claim-supported?)
                          (ports/claim-session-command!
                           (:persistence registry)
                           identity (:session-id session)))]
              (cond
                (and identity (not claim-supported?))
                {:status :error
                 :outcome :unknown
                 :session nil
                 :error {:code :SESSION_COMMAND_CLAIM_UNSUPPORTED
                         :message
                         "session persistence cannot record a reuse command"}}

                (and claim (= :duplicate (:status claim)))
                (reuse-committed-session registry (:session claim))

                (and claim
                     (not (contains? #{:claimed :unchanged}
                                     (:status claim))))
                {:status :error
                 :outcome :unknown
                 :session nil
                 :error (or (:error claim)
                            {:code :SESSION_COMMAND_CLAIM_FAILED
                             :message
                             "session reuse command could not be persisted"})}

                :else
                (let [owner-result (ensure-owner! registry session)]
                  (if (:ok? owner-result)
                    {:status :created
                     :reused? true
                     :replayed? (= :unchanged (:status claim))
                     :session session
                     :owner (:owner owner-result)
                     :commit {:status :unchanged}
                     :effects []}
                    {:status :error
                     :session session
                     :owner nil
                     :commit {:status :committed}
                     :error (:error owner-result)}))))

            :else
            (let [now ((:now-fn registry))
                  created (sessions/new-session
                           (assoc opts
                                  :id-source (:id-source registry)
                                  :now now))]
              (if-not (:ok? created)
                {:status :error :error (:error created)}
                (let [result (commit-and-register! registry
                                                   (:session created)
                                                   (:effects created))]
                  (if (= :duplicate (:status result))
                    (if-let [session (:session result)]
                      (reuse-committed-session registry session)
                      {:status :error
                       :error
                       {:code :SESSION_COMMAND_DUPLICATE
                        :message
                        "another session already owns this creation command"}})
                    result))))))))))

(defn session-by-command
  "Return the committed session created by IDENTITY, or the persistence
  result.  This is a read-only recovery lookup; it does not start an Owner."
  [registry identity]
  (locking (:lock registry)
    (ports/lookup-session-by-command (:persistence registry) identity)))

(declare get-session)

(defn branch-session!
  "Create, commit, and register an independent branch of SOURCE-SESSION-ID.

  The branch point and copied messages follow `sessions/branch-session`.
  Commit and Owner registration use the same order as `create-session!`."
  [registry source-session-id opts]
  (locking (:lock registry)
    (let [loaded (get-session registry source-session-id)]
      (case (:status loaded)
        :missing (error :SESSION_NOT_FOUND
                        "source session does not exist"
                        {:session-id source-session-id})
        :error loaded
        :found
        (let [now ((:now-fn registry))
              branch (sessions/branch-session
                      (:session loaded)
                      (assoc opts
                             :id-source (:id-source registry)
                             :now now))]
          (if-not (:ok? branch)
            (assoc (error (:code (:error branch))
                          (:message (:error branch))
                          (:details (:error branch)))
                   :session (:session branch))
            (commit-and-register! registry (:session branch)
                                  (:effects branch))))))))

(defn create-summary-session!
  "Create, commit, and register a session seeded with one applied
  compression summary from SOURCE-SESSION-ID.

  The source messages are not copied.  A missing source or a compression that
  is not applied is refused before any session is written."
  [registry source-session-id opts]
  (locking (:lock registry)
    (let [loaded (get-session registry source-session-id)]
      (case (:status loaded)
        :missing (error :SESSION_NOT_FOUND
                        "source session does not exist"
                        {:session-id source-session-id})
        :error loaded
        :found
        (let [now ((:now-fn registry))
              created (sessions/summary-session
                       (:session loaded)
                       (assoc opts
                              :id-source (:id-source registry)
                              :now now))]
          (if-not (:ok? created)
            (assoc (error (get-in created [:error :code])
                          (get-in created [:error :message])
                          (get-in created [:error :details]))
                   :session (:session created))
            (commit-and-register! registry (:session created)
                                  (:effects created))))))))

(defn get-committed-session
  "Read the committed session record without merging live Owner state.

  Use this for lifecycle decisions (purge eligibility, retry-recovery start).
  A live Owner can lag or be stale, while the committed record is the durable
  authority for `:status`."
  [registry session-id]
  (ports/read-session (:persistence registry) session-id))

(defn get-session
  "Read one session, merging a live Owner session when it is newer.

  The committed record can lag because the Owner is the only writer after
  creation.  This view is what the baseline and branch paths use, so a
  non-active session does not silently show a stale binding or history.
  It is a content view, not a lifecycle authority; use
  `get-committed-session` when `:status` decides a destructive or recovery
  action."
  [registry session-id]
  (let [result (ports/read-session (:persistence registry) session-id)]
    (if (= :found (:status result))
      {:status :found
       :session (live-session-value registry (:session result))}
      result)))

(defn ensure-owner-for!
  "Return a live Owner for SESSION-ID, starting it from the committed session
  when needed.  This never creates a session; a missing session is an explicit
  failure.  The retry recovery startup uses it to reach a persisted
  `:retry-call` obligation after a restart.

  Only a committed `:active` session may gain an Owner.  The committed record
  is the lifecycle authority: starting from the live-Owner merge could
  resurrect a live `:active` Owner over a committed `:deleted` or `:archived`
  record.  A refused start leaves the retry obligation open for explicit
  recovery."
  [registry session-id]
  (if-let [owner (owner-for registry session-id)]
    {:ok? true :owner owner :reused? true}
    (let [loaded (get-committed-session registry session-id)]
      (case (:status loaded)
        :found
        (let [session (:session loaded)]
          (if (not= :active (:status session))
            {:ok? false
             :owner nil
             :error {:code :SESSION_NOT_ACTIVE
                     :message "session is no longer active"
                     :details {:session-id session-id
                               :status (:status session)}}}
            (let [result (start-owner! registry session)]
              (if (and (:ok? result) (:owner result))
                (do
                  (swap! (:state registry)
                         assoc-in [:owners session-id] (:owner result))
                  result)
                {:ok? false
                 :owner nil
                 :error (or (:error result)
                            {:code :OWNER_UNAVAILABLE
                             :message "session Owner factory returned no Owner"})}))))

        :missing
        {:ok? false
         :owner nil
         :error {:code :SESSION_NOT_FOUND
                 :message "retry recovery needs the committed session"
                 :session-id session-id}}

        {:ok? false
         :owner nil
         :error {:code :SESSION_READ_FAILED
                 :message "could not read the committed session"
                 :session-id session-id
                 :details loaded}}))))

(defn list-sessions
  "Read every committed session and return ordered summaries."
  ([registry] (list-sessions registry {}))
  ([registry opts]
   (let [loaded (load-sessions registry)]
     (case (:status loaded)
       :ok {:status :ok
            :sessions (sessions/list-sessions (:sessions loaded) opts)}
       :error loaded))))

(defn update-session!
  "Submit one session-metadata command through the session Owner.

  The Owner mailbox is the single serial writer and its command gate is the
  only epoch authority.  This registry does not execute, validate an epoch,
  or commit session state.  A missing Owner is an error, never a bypass.
  The client must send the Owner-issued `:command-epoch`.

  The one exception is `:session/restore`: a deleted session has no Owner by
  design, so restore applies the business transition and commits the record
  directly, then starts the Owner for the now-active session."
  [registry session-id command]
  (let [command-type (or (:type command) (:kind command))]
    (cond
      (not (contains? metadata-command-types command-type))
      (error :CONVERSATION_COMMAND_NOT_ROUTED
             "conversation commands belong to the compose Owner"
             {:session-id session-id
              :type command-type
              :metadata-types (vec (sort metadata-command-types))})

      :else
      (if-let [owner (owner-for registry session-id)]
        (let [payload (:payload command)
              payload (if (map? payload)
                        (assoc payload :kind command-type)
                        {:kind command-type})
              request (cond-> (assoc (select-keys command
                                                  [:command-id :command-epoch
                                                   :client-id :kind :type])
                                     :payload payload)
                        (nil? (:command-id command))
                        (assoc :command-id
                               (ids/next-id! (:id-source registry) :command)))
              receipt (ports/submit-command! (as-owner-port owner) request)
              receipt (if (map? receipt)
                        receipt
                        {:status :unknown
                         :error {:code :OWNER_NO_RECEIPT
                                 :message "Owner returned no receipt"}
                         :action :resync-state})]
          (assoc receipt
                 :session-id session-id
                 :request request))
        (if (= :session/restore command-type)
          (let [loaded (ports/read-session (:persistence registry) session-id)]
            (case (:status loaded)
              :found
              (let [now ((:now-fn registry))
                    result (sessions/apply-command
                            (:session loaded)
                            {:kind :session/restore :session-id session-id}
                            {:now now :config nil :delegate nil
                             :outbound-context nil})]
                (if (:ok? result)
                  (let [restored (:session result)
                        ;; The domain restore keeps the session value; the
                        ;; Owner normally bumps the revision on commit.  The
                        ;; ownerless path must do the same or commit-session!
                        ;; sees an unchanged value.
                        candidate (update restored :revision inc)
                        commit (sessions/commit-session!
                                (:persistence registry) candidate)]
                    (if (= :committed (:status commit))
                      (let [ensured (ensure-owner-for-session!
                                     registry (:session commit))]
                        {:status :accepted
                         :session (:session commit)
                         :owner (:owner ensured)
                         :owner-error (when-not (:ok? ensured)
                                        (:error ensured))
                         :receipt {:status :accepted
                                   :session-id session-id
                                   :session (dissoc (:session commit)
                                                    :commands)}})
                      (assoc (error :SESSION_COMMIT_FAILED
                                    "restore could not commit the session"
                                    {:status (:status commit)
                                     :error (:error commit)})
                             :session (:session result))))
                  (assoc (error (or (get-in result [:error :code])
                                    :SESSION_RESTORE_FAILED)
                                (or (get-in result [:error :message])
                                    "restore was refused")
                                (get-in result [:error :details]))
                         :session (:session result))))
              :missing (error :SESSION_NOT_FOUND
                              "session does not exist"
                              {:session-id session-id})
              :error loaded))
          (error :NO_OWNER
                 "session has no live Owner; metadata commands cannot bypass it"
                 {:session-id session-id}))))))

(defn forget-owner!
  "Remove the registry route for a stopped Owner without touching the
  session record.  Use this after `:stop!` when the next step is a hard
  discard or a synchronous close."
  [registry session-id]
  (locking (:lock registry)
    (swap! (:state registry) update :owners dissoc session-id)
    {:status :forgotten :session-id session-id}))

(defn close-session!
  "Close one session.

  With a live Owner, enqueue the internal `:session/close` message and drop
  the route.  Without an Owner, close and commit synchronously.  `:delete?`
  is a soft delete, never a hard purge."
  [registry session-id opts]
  (locking (:lock registry)
    (if-let [owner (owner-for registry session-id)]
      (let [result (ports/enqueue! (as-owner-port owner)
                                   {:kind :session/close
                                    :session-id session-id
                                    :options opts})]
        (when (or (= :accepted (:status result))
                  (:force? opts))
          (swap! (:state registry) update :owners dissoc session-id))
        {:status :submitted :owner-result result})
      (let [loaded (ports/read-session (:persistence registry) session-id)]
        (case (:status loaded)
          :missing (error :SESSION_NOT_FOUND
                          "session does not exist"
                          {:session-id session-id})
          :error loaded
          :found
          (let [now ((:now-fn registry))
                closed (sessions/close (:session loaded)
                                       (assoc opts :now now))
                commit (sessions/commit-session! (:persistence registry)
                                                 (:session closed))]
            (case (:status commit)
              :committed {:status :closed
                          :session (:session commit)
                          :commit commit}
              :conflict (assoc (error :SESSION_COMMIT_CONFLICT
                                      "session changed before close committed"
                                      {:revision (:revision commit)})
                               :session (:session commit)
                               :commit commit)
              :unknown (assoc (error :SESSION_COMMIT_UNKNOWN
                                     "close commit outcome is unknown"
                                     {:error (:error commit)})
                              :session (:session commit)
                              :commit commit)
              (assoc (error :SESSION_COMMIT_FAILED
                            "close commit did not complete"
                            {:commit commit})
                     :session (:session commit)
                     :commit commit))))))))

(defn purge-session!
  "Permanently remove one deleted session record.

  A live Owner blocks the purge.  Any soft-deleted session is eligible for an
  explicit purge, including one inside the 30-day retention window; that
  window governs default retention, not the operator's confirmed deletion.
  The committed record is the deletion authority: an accepted delete has
  already written `:deleted` there before its receipt.  A live-Owner view is
  never merged in, because a lagging live value must not create a deletion
  the business commit did not make.
  External workspace files are never touched."
  [registry session-id]
  (locking (:lock registry)
    (cond
      (owner-for registry session-id)
      (error :OWNER_ACTIVE
             "stop the session Owner before purging"
             {:session-id session-id})

      :else
      (let [loaded (ports/read-session (:persistence registry) session-id)]
        (case (:status loaded)
          :missing (error :SESSION_NOT_FOUND
                          "session does not exist"
                          {:session-id session-id})
          :error loaded
          :found
          (let [session (:session loaded)]
            (cond
              (not= :deleted (:status session))
              (error :SESSION_NOT_DELETED
                     "only a soft-deleted session can be purged"
                     {:status (:status session)})

              :else
              (let [result (ports/delete-session!
                            (:persistence registry)
                            (:revision session)
                            session-id)]
                (case (:status result)
                  :deleted {:status :purged
                            :session-id session-id}
                  :missing {:status :purged
                            :session-id session-id
                            :already-missing? true}
                  :conflict (assoc (error :SESSION_COMMIT_CONFLICT
                                          "session changed before purge"
                                          {:revision (:revision result)})
                                   :session-id session-id)
                  :unknown (assoc (error :SESSION_COMMIT_UNKNOWN
                                         "purge outcome is unknown"
                                         {:error (:error result)})
                                  :session-id session-id)
                  result)))))))))

(defn discard-session!
  "Discard one temporary session when no live Owner exists.

  This is the hard privacy path: the record is removed immediately, without
  entering the deleted area.  A live Owner is stopped and forgotten first;
  if it does not explicitly report both `:stopped?` and `:drained?`, the
  discard is refused.  A bare `{:ok? true}` is not evidence of a drain.  A
  non-temporary session is refused."
  [registry session-id]
  (locking (:lock registry)
    (let [owner (owner-for registry session-id)
          stopped
          (when owner
            (if (ifn? (:stop! owner))
              (try
                ((:stop! owner))
                (catch Exception exception
                  {:ok? false
                   :error {:code :OWNER_STOP_FAILED
                           :message (str "session Owner could not stop: "
                                         (.getMessage exception))}}))
              {:ok? false
               :error {:code :OWNER_STOP_UNAVAILABLE
                       :message "live Owner cannot be stopped by the registry"}}))]
      (let [stop-failure
            (cond
              (nil? owner) nil
              (nil? stopped)
              {:code :OWNER_STOP_INCOMPLETE
               :message "session Owner returned no stop result"}
              (false? (:ok? stopped))
              (or (:error stopped)
                  {:code :OWNER_STOP_FAILED
                   :message "session Owner stop failed"})
              (not (true? (:stopped? stopped)))
              {:code :OWNER_STOP_INCOMPLETE
               :message "session Owner did not stop before discard"
               :details {:stopped? (:stopped? stopped)
                         :drained? (:drained? stopped)}}
              (not (true? (:drained? stopped)))
              {:code :OWNER_STOP_INCOMPLETE
               :message "session Owner did not drain before discard"
               :details {:stopped? (:stopped? stopped)
                         :drained? (:drained? stopped)}})]
        (if stop-failure
          (assoc (error (or (:code stop-failure) :OWNER_STOP_INCOMPLETE)
                        (or (:message stop-failure)
                            "session Owner did not stop and drain before discard")
                        (merge {:session-id session-id}
                               (:details stop-failure)))
                 :session-id session-id)
        (do
          (when owner
            (swap! (:state registry) update :owners dissoc session-id))
          (let [loaded (ports/read-session (:persistence registry) session-id)]
            (case (:status loaded)
              :missing (error :SESSION_NOT_FOUND
                              "session does not exist"
                              {:session-id session-id})
              :error loaded
              :found
              (let [session (:session loaded)]
                (if-not (:temporary? session)
                  (error :NOT_TEMPORARY
                         "only a temporary session can be discarded directly"
                         {:session-id session-id})
                  (let [result (ports/delete-session!
                                (:persistence registry)
                                (:revision session)
                                session-id)]
                    (case (:status result)
                      :deleted {:status :discarded
                                :session-id session-id
                                :external-effects (:external-effects result)}
                      :missing {:status :discarded
                                :session-id session-id
                                :already-missing? true
                                :external-effects (:external-effects result)}
                      :conflict (assoc (error :SESSION_COMMIT_CONFLICT
                                              "session changed before discard"
                                              {:revision (:revision result)})
                                       :session-id session-id)
                      :unknown (assoc (error :SESSION_COMMIT_UNKNOWN
                                             "discard outcome is unknown"
                                             {:error (:error result)})
                                      :session-id session-id)
                      result))))))))))))

(defn stop-all!
  "Enqueue an internal `:session/stop` to every registered Owner and clear
  the routes.  It does not delete sessions or interrupt a commit."
  [registry]
  (locking (:lock registry)
    (let [owners @(:state registry)
          results (mapv (fn [[session-id owner]]
                          (ports/enqueue! (as-owner-port owner)
                                          {:kind :session/stop
                                           :session-id session-id}))
                        (:owners owners))]
      (swap! (:state registry) assoc :owners {})
      {:status :stopped
       :count (count results)
       :results results})))

(defn registered?
  [registry session-id]
  (contains? (get-in @(:state registry) [:owners]) session-id))

(defn check-invariants
  "Return registry invariant violations."
  [registry]
  (let [owners (get-in @(:state registry) [:owners])]
    (cond-> []
      (not (map? owners))
      (conj {:invariant :owners-not-a-map})

      (and (map? owners)
           (some (fn [[session-id _]]
                   (not (ids/valid-id? session-id)))
                 owners))
      (conj {:invariant :owner-route-id-invalid}))))
