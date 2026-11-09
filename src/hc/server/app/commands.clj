(ns hc.server.app.commands
  "One command router for the browser gateway.

  Conversation, board, relay, call and mission commands go to the compose
  Owner, which is the single client-epoch authority for a session.  Session
  metadata and draft commands go to the session registry's metadata gate.
  Settings, model-auth, search-service, documents, speech, extensions,
  diagnostics, backup and scheduler commands are application configuration
  and never enter a session Owner.

  The router is the only place that translates the transport envelope into
  these destinations.  Every mutating command names its destination
  explicitly:

    * session commands carry `:session-id` and use that session Owner's
      command epoch;
    * application commands use the app scope and the app command epoch.

  A durable gate per scope stores `[scope command-id]` receipts and the
  epoch window.  A replay returns the stored receipt without repeating the
  side effect.  A retired or unknown epoch is refused before dispatch, and a
  receipt is never looked up across scopes.

  `:app/issue-epoch` rotates the application epoch in one committed gate
  transition.  It is server-controlled; the browser gateway forbids it by
  default, and a stale request epoch is refused before any rotation.

  Conversation sends never run web search here.  The `:search` intent stays
  on the payload; the Owner commits the accepted turn and the model gateway
  runs the intent after that commit.  A send refused by the Owner business
  gate therefore cannot make an external request.

  `:session/purge` is metadata-shaped but router-gated: the durable session
  gate validates the epoch, the router stops and forgets the Owner, and then
  `registry/purge-session!` removes only the session record.  It never
  deletes workspace files.

  The app scope also carries the trusted Steward planes: `:fleet/*` and
  `:steward/*` accept only the `steward-command-envelope` shape, and
  `:program/abandon` accepts only the holder program's
  `program-command-envelope`.  The browser gateway forbids all three groups.
  `:fleet/join` runs on a worker thread so no Owner mailbox or transport
  thread blocks."
  (:require [clojure.string :as str]
            [hc.compose.fleet.service :as fleet-service]
            [hc.compose.fleet.router :as fleet-router]
            [hc.compose.mission.host :as mission-host]
            [hc.compose.steward :as steward]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.sessions.registry :as registry]
            [hc.server.app.settings :as settings]
            [hc.server.app.backup :as backup-app]
            [hc.server.app.scheduler :as scheduler-app]
            [hc.server.app.search-commands :as history-search]
            [hc.server.app.steward-store :as steward-store]
            [hc.server.diagnostics.integration :as diagnostics-integration]
            [hc.server.diagnostics.service :as diagnostics-service]
            [hc.server.extensions.app :as extensions-app]
            [hc.server.materials.session :as materials-session]
            [hc.server.speech :as speech]
            [hc.server.search :as search]
            [hc.server.documents :as documents]
            [hc.server.history.snapshot :as history-snapshot]
            [hc.server.model-auth.app :as model-auth-app]
            [hc.server.browser :as browser]
            [hc.server.attachments :as attachments]
            [hc.server.commands.dispatch :as command-dispatch]
            [hc.server.commands.durable :as command-durable]
            [hc.server.commands.receipts :as command-receipts]))

(declare register-owner! notify-change! gate-for)

(def ^:private conversation-kinds
  #{:send-message :stop-run :answer-ask :user/message :operator/cancel
    :user/unpark :review/submit :board/command :worker/run-program
    ;; Plan draft authority is a trusted command-plane path (the model
    ;; reaches it through the program bridge; the app command path must also
    ;; route it for headless/server validation).  The gateway forbids it for
    ;; browser clients.
    :plan/write-draft :plan/claim-draft :plan/release-draft
    :owner/compact
    :call/ack
    :report/ack :report/register
    :session/issue-epoch :session/edit-message :session/delete-message
    :session/fold-message :session/select-model :session/select-skills
    ;; Multi-client presence (root AGENTS.md §5.1).  These are the only
    ;; session commands an observer may submit; the Owner enforces that.
    :session/attach-client :session/takeover :session/detach-client})

(def ^:private operator-kinds
  "Commands that a genuine browser operator may originate.  The Sente
  gateway is the trust boundary; agents never submit through it."
  #{:send-message :stop-run :answer-ask :user/message :operator/cancel
    :user/unpark :call/ack :session/select-skills
    ;; Presence commands are operator actions: an observer may use them to
    ;; take control, and they never start session work.
    :session/attach-client :session/takeover :session/detach-client})

(def ^:private metadata-kinds
  #{:session/rename :session/update-draft :session/pin :session/archive
    :session/restore :session/delete :session/transfer :session/purge
    ;; The Owner mirrors an accepted UI send into the session's run ledger;
    ;; the browser never originates this kind.
    :session/record-outbound})

(def ^:private app-namespaces
  "Namespaces whose commands change application configuration, not one
  session.  They use the app command epoch and the app receipt scope."
  #{"settings" "model-auth" "search" "documents" "speech" "extensions"
    "diagnostics" "backup" "scheduler" "fleet" "steward" "program"})

(def ^:private session-namespaces
  "Namespaces whose commands change one session's live services."
  #{"browser" "materials"})

(def ^:private history-search-kinds
  "App-scoped history index commands.

  These never enter a session Owner: search and status read the derived
  index, and reindex is a trusted maintenance command.  They use the app
  command epoch and the app receipt scope."
  (history-search/command-kinds))

(defn- settings-kind?
  [kind]
  (= "settings" (namespace kind)))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- session-scoped-kind?
  [kind]
  (or (contains? conversation-kinds kind)
      (contains? metadata-kinds kind)
      (= :history/branch kind)
      (and (keyword? kind)
           (contains? session-namespaces (namespace kind)))))

(defn- app-scoped-kind?
  [kind]
  (or (= :session/create kind)
      (= :session/ensure kind)
      (= :session/create-from-summary kind)
      (= :app/issue-epoch kind)
      (contains? history-search-kinds kind)
      (and (keyword? kind)
           (contains? app-namespaces (namespace kind)))))

(defn- result-error
  [code message details]
  (cond-> {:ok? false
           :error {:code code :message message}}
    details (assoc-in [:error :details] details)))

(defn- owner-of
  [router session-id]
  (when session-id
    (or (get-in @(:state router) [:owners session-id])
        (try (registry/owner-for (:registry router) session-id)
             (catch Throwable _ nil)))))

(defn- ensure-live-owner!
  "Return the live Owner for SESSION-ID, creating it on first access.

  Startup creates Owners lazily: only the presentation active session gets
  one eagerly.  Every other committed session is started here the first time
  a command or metadata request needs it.  The registry's
  `ensure-owner-for-session!` refuses missing, deleted, or archived sessions
  and never mutates the record; the router registration makes the new Owner
  visible to dispatch and the baseline.  Returns the Owner handle, or nil
  when the session cannot have one."
  [router session-id]
  (or (owner-of router session-id)
      (when-let [factory (get-in @(:state router) [:session-owner-factory])]
        (let [loaded (try (registry/get-session (:registry router) session-id)
                          (catch Throwable _ nil))
              session (when (= :found (:status loaded))
                        (:session loaded))]
          (when (and (map? session) factory)
            (let [ensured (try (registry/ensure-owner-for-session!
                                (:registry router) session)
                               (catch Throwable _ nil))]
              (when (and (:ok? ensured) (:owner ensured))
                (register-owner! router session-id (:owner ensured))
                (notify-change! router)
                (:owner ensured))))))))

(defn- remember-owner!
  [router session-id owner]
  (when owner
    (swap! (:state router) assoc-in [:owners session-id] owner))
  owner)

(defn- reply
  [reply-fn result]
  (when reply-fn
    (try (reply-fn result) (catch Throwable _ nil)))
  result)

(defn- notify-change!
  [router]
  (when-let [f (:on-change router)]
    (try (f {:type :state-changed}) (catch Throwable _ nil))))

(defn- session-record
  [router session-id]
  (when session-id
    (try
      (let [result (registry/get-session (:registry router) session-id)]
        (when (= :found (:status result)) (:session result)))
      (catch Throwable _ nil))))

;; ---------------------------------------------------------------------------
;; Explicit command scopes and durable gates

(defn- scope-for
  "Return the explicit scope for REQUEST, or an explicit refusal.

  Session commands must name a session.  Application commands use the
  single app scope.  No server-global active session is ever substituted
  for a missing session-id.  Whether a live Owner exists is a dispatch
  concern; a durable replay must still find its receipt after the Owner has
  retired."
  [router request]
  (let [payload (:payload request)
        kind (:kind payload)
        session-id (:session-id payload)]
    (cond
      (app-scoped-kind? kind)
      {:ok? true :scope "app"}

      (session-scoped-kind? kind)
      (cond
        (not (non-blank-string? session-id))
        {:ok? false
         :error {:code :SESSION_REQUIRED
                 :message "this command needs an explicit :session-id"
                 :kind kind}}

        :else
        {:ok? true :scope session-id})

      :else
      {:ok? true :scope "app"})))

(defn- window-for
  [router scope]
  (if (= "app" scope)
    (let [state (command-durable/state (:app-gate router))]
      (when (:command-epoch state)
        (select-keys state [:command-epoch :retired-through])))
    (or (command-dispatch/owner-window (owner-of router scope))
        ;; A retired Owner leaves no live window.  Its durable gate still
        ;; holds the last window and receipts, so a replay can be classified
        ;; without inventing a new epoch.  A listed session with no live
        ;; Owner (a deleted row) gets its first epoch here; issuing is
        ;; idempotent and never advances an existing epoch.
        (when (string? scope)
          (let [gate (gate-for router scope)
                state (command-durable/state gate)]
            (if (:command-epoch state)
              (select-keys state [:command-epoch :retired-through])
              (let [issued (command-durable/issue-epoch! gate)]
                (when (:ok? issued)
                  (select-keys (command-durable/state gate)
                               [:command-epoch :retired-through])))))))))

(defn- gate-for
  [router scope]
  (if (= "app" scope)
    (:app-gate router)
    (or (get @(:gates router) scope)
        (locking (:gate-lock router)
          (or (get @(:gates router) scope)
              (let [store-fn (:dedup-store-for router)
                    store (if store-fn
                            (store-fn scope)
                            (command-durable/memory-store))
                    gate (command-durable/gate store)]
                (swap! (:gates router) assoc scope gate)
                gate))))))

(defn- ensure-app-epoch!
  [gate]
  (let [state (command-durable/state gate)]
    (when (nil? (:command-epoch state))
      (let [result (command-durable/issue-epoch! gate)]
        (when-not (:ok? result)
          (throw (ex-info "app command epoch could not be issued"
                          {:error (:error result)})))))
    gate))

(defn app-command-epoch
  "Return the current application command epoch."
  [router]
  (get-in (command-durable/state (:app-gate router)) [:command-epoch]))

(defn- handle-issue-app-epoch!
  [router request reply-fn]
  (let [expected (:command-epoch request)]
    (cond
      (not (non-blank-string? (:command-id request)))
      (reply reply-fn
             (result-error
              :COMMAND_ID_REQUIRED
              "command-id must be a non-blank string"
              {:command-id (:command-id request)}))

      (not (and (integer? expected) (pos? expected)))
      (reply reply-fn
             (result-error
              :COMMAND_EPOCH_INVALID
              "app epoch rotation needs the current command-epoch"
              {:command-epoch expected}))

      :else
      (let [result (command-durable/issue-epoch! (:app-gate router) expected)]
        (if (:ok? result)
          (do
            (notify-change! router)
            (reply reply-fn
                   {:ok? true
                    :receipt {:status :accepted
                              :command-id (:command-id request)
                              :command-epoch (:epoch result)
                              :retired-epoch (:retired-epoch result)}}))
          (reply reply-fn result))))))

;; ---------------------------------------------------------------------------
;; Session lifecycle and metadata

(defn- session-creation-receipt
  [session identity]
  {:status :accepted
   :command-id (:command-id identity)
   :command-epoch (:command-epoch identity)
   :session-id (:session-id session)
   :owner-id (:owner-id session)
   :session (dissoc session :commands)})

(defn- request-creation-identity
  [request]
  {:scope "app"
   :command-epoch (:command-epoch request)
   :command-id (:command-id request)})

(def ^:private unknown-create-codes
  #{:SESSION_COMMIT_UNKNOWN :SESSION_COMMIT_FAILED
    :SESSION_COMMAND_LOOKUP_FAILED :SESSION_COMMAND_CLAIM_FAILED
    :SESSION_COMMAND_CLAIM_UNSUPPORTED :SESSION_COMMAND_DUPLICATE
    :OWNER_START_FAILED :PERSISTENCE_ERROR})

(defn- create-error-reply!
  [request result reply-fn]
  (let [error (or (:error result)
                  {:code :SESSION_CREATE_FAILED
                   :message "session could not be created"
                   :details {:status (:status result)}})
        code (or (:code error) :SESSION_CREATE_FAILED)
        session-id (or (get-in result [:session :session-id])
                       (:session-id result))]
    (if (or (= :unknown (:outcome result))
            (contains? unknown-create-codes code))
      (reply reply-fn
             {:ok? false
              :outcome :unknown
              :receipt {:status :outcome-unknown
                        :command-id (:command-id request)
                        :command-epoch (:command-epoch request)
                        :session-id session-id
                        :error error}
              :error error})
      (reply reply-fn
             (result-error code
                           (or (:message error)
                               "session could not be created")
                           (:details error))))))

(defn- handle-create!
  [router request payload config reply-fn]
  (let [registry (:registry router)
        options (select-keys payload
                             [:title :assistant-id :language :session-id
                              :owner-id :connection-id :model-entry-id
                              :reuse-blank? :temporary?])]
    (let [result (registry/create-session!
                  registry
                  (assoc options
                         :creation-command (request-creation-identity request)
                         :reuse-blank? (if (contains? payload :reuse-blank?)
                                         (:reuse-blank? payload)
                                         false)
                         :defer-owner? true
                         :config config))]
      (case (:status result)
        :created
        (let [session (:session result)
              session-id (:session-id session)
              ;; `:defer-owner? true` leaves :owner nil; the Owner must exist
              ;; before this session can accept work, so ensure it here the
              ;; same way `resolve-command-replay` does for a crash window.
              ensured (if (:owner result)
                        {:owner (:owner result)}
                        (try
                          (registry/ensure-owner-for-session! registry session)
                          (catch Throwable t
                            {:ok? false
                             :owner nil
                             :error {:code :OWNER_START_FAILED
                                     :message (str "session Owner could not start: "
                                                   (.getMessage t))}})))
              owner (:owner ensured)]
          (if-not (or owner (:ok? ensured))
            (create-error-reply!
             request
             (merge result ensured)
             reply-fn)
            (do
              (register-owner! router session-id owner)
              (swap! (:state router) assoc :active-session-id session-id)
              (when-not (:replayed? result)
                (when-let [f (:on-change router)]
                  (try (f {:type :session-created :session session})
                       (catch Throwable _ nil))))
              (reply reply-fn
                     {:ok? true
                      :replayed? (boolean (:replayed? result))
                      :receipt (session-creation-receipt
                                session (request-creation-identity request))}))))

        :error
        (create-error-reply! request result reply-fn)

        (reply reply-fn
               (result-error :SESSION_CREATE_FAILED
                             "session could not be created"
                             {:status (:status result)}))))))
(defn- resolve-command-replay
  "Resolve a crash-left session-creation placeholder from the session store.

  The session commit records the creation command-id in the same transaction.
  A placeholder therefore never needs to be re-dispatched: if the session
  exists, the original receipt is reconstructed from the committed session.
  A missing session keeps the stored unknown outcome; a new command-id is
  required to try again."
  [router request stored]
  (when (and (= :session/create (get-in request [:payload :kind]))
             (contains? #{:dispatching :outcome-unknown}
                        (get-in stored [:receipt :status]))
             (:registry router))
    (let [identity (request-creation-identity request)
          result (registry/session-by-command (:registry router) identity)]
      (when (= :found (:status result))
        (let [session (:session result)
              ensured (registry/ensure-owner-for-session!
                       (:registry router) session)]
          (if (:ok? ensured)
            (do
              (remember-owner! router (:session-id session) (:owner ensured))
              {:ok? true
               :replayed? true
               :receipt (session-creation-receipt session identity)})
            (let [recovery-error
                  {:code :SESSION_OWNER_RECOVERY_FAILED
                   :message "session owner could not be recovered"
                   :details {:session-id (:session-id session)
                             :cause (:error ensured)}}]
              {:ok? false
               :outcome :unknown
               :error recovery-error
               :receipt (assoc (:receipt stored)
                               :status :outcome-unknown
                               :error recovery-error)})))))))

(defn- handle-list!
  [router payload reply-fn]
  (let [result (registry/list-sessions (:registry router)
                                       {:include-deleted?
                                        (boolean (:include-deleted? payload))})]
    (case (:status result)
      :ok (let [sessions (:sessions result)
                epochs (into {}
                             (keep (fn [session]
                                     (when (= :deleted (:status session))
                                       (when-let [window (window-for
                                                          router
                                                          (:session-id session))]
                                         (when-let [epoch (:command-epoch window)]
                                           [(:session-id session) epoch])))))
                             sessions)]
            (reply reply-fn
                   {:ok? true
                    :receipt
                    {:status :accepted
                     :sessions
                     (mapv (fn [session]
                             (cond-> (dissoc session
                                             :commands :messages :drafts)
                               (get epochs (:session-id session))
                               (assoc :command-epoch
                                      (get epochs (:session-id session)))))
                           sessions)}}))
      (reply reply-fn
             (result-error :SESSION_LIST_FAILED
                           "session list could not be read"
                           {:status (:status result)})))))

(defn- session-command-epoch
  "The Owner-issued command epoch for SESSION-ID, or nil."
  [router session-id]
  (when-let [owner (owner-of router session-id)]
    (try
      (get-in ((:snapshot owner)) [:state :commands :command-epoch])
      (catch Throwable _ nil))))

(defn- handle-ensure-owner!
  "Ensure SESSION-ID has a live Owner and return its command epoch.

  This is the lazy-loading seam: a client that opens a session calls it once
  so the Owner exists before the first history or metadata command.  It never
  mutates the session record; a missing, deleted, or archived session is
  refused without creating anything."
  [router payload reply-fn]
  (let [session-id (:session-id payload)]
    (cond
      (not (non-blank-string? session-id))
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "ensuring an Owner needs an explicit :session-id"
                           {:kind (:kind payload)}))

      :else
      (let [owner (ensure-live-owner! router session-id)]
        (if owner
          (let [epoch (session-command-epoch router session-id)]
            (reply reply-fn
                   {:ok? true
                    :receipt {:status :accepted
                              :session-id session-id
                              :command-epoch epoch}}))
          (reply reply-fn
                 (result-error :SESSION_OWNER_UNAVAILABLE
                               "session has no committable Owner"
                               {:session-id session-id})))))))

(defn- handle-history-window!
  "Return one page of a session's message entities for lazy history load.

  The baseline publishes only the newest message window per session; older
  messages are fetched here on demand.  `:before` is the oldest currently
  loaded message id (exclusive); `:limit` caps the page (default 200, max
  1000).  The receipt carries the message entities, their order, the full
  visible id list, and whether older messages remain."
  [router payload reply-fn]
  (let [session-id (:session-id payload)
        loaded (try (registry/get-session (:registry router) session-id)
                    (catch Throwable _ nil))]
    (cond
      (not (non-blank-string? session-id))
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "history windows need an explicit :session-id"
                           {:kind (:kind payload)}))

      (not= :found (:status loaded))
      (reply reply-fn
             (result-error :SESSION_NOT_FOUND
                           "session does not exist"
                           {:session-id session-id}))

      :else
      (let [session (:session loaded)
            before (:before payload)
            limit (-> (or (:limit payload) 200)
                      long
                      (max 1)
                      (min 1000))
            probe (history-snapshot/session-history session {})
            visible-ids (:ordered-ids probe)
            before-pos (if (and (string? before)
                                (some #{before} visible-ids))
                         (.indexOf ^java.util.List (vec visible-ids) before)
                         (count visible-ids))
            eligible (subvec (vec visible-ids) 0 before-pos)
            window-ids (->> eligible
                            (drop (max 0 (- (count eligible) limit)))
                            vec)
            window-set (set window-ids)
            history (history-snapshot/session-history
                     session
                     {:messages-window {:ids window-set}})
            older-count (max 0 (- before-pos (count window-ids)))]
        ;; Ensure the Owner exists so its command epoch is published; the
        ;; fetch itself reads the committed record and never needs it.  The
        ;; epoch is read after the ensure: a lazily created Owner did not
        ;; exist when this let binding first ran.
        (ensure-live-owner! router session-id)
        (let [epoch (session-command-epoch router session-id)]
        ;; Record the fetched window so the next baseline keeps publishing
        ;; these entities instead of dropping them on the next resync.
        (when-let [windows (get-in @(:state router) [:published-windows])]
          (swap! windows update session-id
                 (fn [ids] (into (or ids #{}) window-ids))))
        (notify-change! router)
        (reply reply-fn
               {:ok? true
                :receipt {:status :accepted
                          :session-id session-id
                          :messages (:messages history)
                          :message-order window-ids
                          :message-ids visible-ids
                          :has-older? (pos? older-count)
                          :command-epoch epoch}}))))))

(defn- handle-metadata!
  [router request payload reply-fn]
  (let [session-id (:session-id payload)]
    (cond
      (not (non-blank-string? session-id))
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "a session is required for this metadata command"
                           {:kind (:kind payload)}))

      ;; Metadata commands need a live Owner.  `:session/restore` is the one
      ;; exception: a deleted session has no Owner by design and the registry
      ;; applies the restore directly.  Every other kind ensures the Owner
      ;; first so a lazily-loaded session is not refused with :NO_OWNER.
      (and (not= :session/restore (:kind payload))
           (nil? (ensure-live-owner! router session-id)))
      (reply reply-fn
             (result-error :SESSION_OWNER_UNAVAILABLE
                           "no live Owner is registered for this session"
                           {:session-id session-id}))

      :else
      (let [result (registry/update-session!
                    (:registry router) session-id
                    ;; `update-session!` takes the full Owner envelope and
                    ;; reads the business fields from `:payload`.  Passing the
                    ;; flat client payload here dropped :title/:pinned?/
                    ;; :confirm? and turned every metadata command into only
                    ;; {:kind ...}.
                    {:payload payload
                     :type (:kind payload)
                     :command-id (:command-id request)
                     :command-epoch (:command-epoch request)
                     :client-id (:client-id request)})]
        (case (:status result)
          (:accepted :refused)
          (let [receipt (or (:receipt result) result)]
            (reply reply-fn
                   {:ok? (= :accepted (:status result))
                    :receipt receipt
                    :error (when (= :refused (:status result))
                             (or (:error result)
                                 {:code :SESSION_COMMAND_REFUSED
                                  :message "session metadata command was refused"}))}))

          :submitted
          (reply reply-fn
                 {:ok? true
                  :receipt {:status :accepted
                            :command-id (:command-id result)
                            :command-epoch (:command-epoch result)}})

          :error
          (reply reply-fn
                 (result-error (or (get-in result [:error :code])
                                   :SESSION_COMMAND_FAILED)
                               (or (get-in result [:error :message])
                                   "session metadata command failed")
                               (get-in result [:error :details])))

          (reply reply-fn
                 (result-error :SESSION_COMMAND_FAILED
                               "session metadata command failed"
                               {:status (:status result)})))))))

(defn- handle-model-auth!
  [router payload reply-fn]
  (if-let [component (:model-auth router)]
    (let [result (model-auth-app/handle! component payload)]
      (when (:ok? result) (notify-change! router))
      (reply reply-fn result))
    (reply reply-fn
           (result-error :MODEL_AUTH_UNAVAILABLE
                         "model-auth component is not running"
                         {:kind (:kind payload)}))))

(defn- handle-settings!
  [router request payload reply-fn]
  (let [result (settings/handle! (:settings router)
                                 (assoc payload
                                        :command-id (:command-id request))
                                 {:model-auth (:model-auth router)})]
    ;; A successful connection/role/delete commit changes the configuration
    ;; that later snapshots project.  Test and list commands are read-only
    ;; and must not schedule a redundant publish.
    (when (and (contains? #{:settings/save-connection
                            :settings/save-role
                            :settings/save-assistants
                            :settings/delete-connection}
                          (:kind payload))
               (or (:ok? result)
                   (:saved? (:receipt result))))
      (notify-change! router))
    (reply reply-fn result)))

(defn- handle-materials!
  [router request payload reply-fn]
  (if-let [materials (:materials router)]
    (let [session-id (:session-id payload)]
      (if-not (non-blank-string? session-id)
        (reply reply-fn
               (result-error :SESSION_REQUIRED
                             "materials commands need an explicit :session-id"
                             {:kind (:kind payload)}))
        ;; A seeded or restored session may not be loaded into the live
        ;; registry yet.  Materials are library-scoped; an unknown session
        ;; still has a stable id for worldbook turns and card selection.
        (let [session (or (session-record router session-id)
                          {:session-id session-id})
              result (materials-session/handle-command!
                      materials
                      {:session session
                       :session-id session-id
                       :command-id (:command-id request)
                       :command-epoch (:command-epoch request)
                       :payload payload})]
          ;; Read-only materials projections must not publish a new server
          ;; revision.  The client bridge asks for one snapshot per revision;
          ;; notifying on the snapshot itself would create a fetch/notify loop
          ;; and can push another session's baseline over the current view.
          (when (and (:ok? result)
                     (not (contains? #{:materials/snapshot
                                       :materials/memory/search
                                       :materials/memory/effective}
                                     (:kind payload))))
            (notify-change! router))
          (reply reply-fn result))))
    (reply reply-fn
           (result-error :MATERIALS_UNAVAILABLE
                         "materials service is not available"
                         {:kind (:kind payload)}))))

(defn- handle-diagnostics!
  [router payload reply-fn]
  (if-let [diagnostics (:diagnostics router)]
    (let [result (diagnostics-integration/dispatch diagnostics payload)]
      (when (:ok? result) (notify-change! router))
      (reply reply-fn result))
    (reply reply-fn
           (result-error :DIAGNOSTICS_UNAVAILABLE
                         "diagnostics service is not available"
                         {:kind (:kind payload)}))))

(defn- handle-backup!
  [router payload reply-fn]
  (if-let [backup (:backup router)]
    (let [result (backup-app/handle! backup payload)]
      (when (:ok? result) (notify-change! router))
      (reply reply-fn result))
    (reply reply-fn
           (result-error :BACKUP_UNAVAILABLE
                         "backup service is not available"
                         {:kind (:kind payload)}))))

(defn- handle-scheduler!
  [router payload reply-fn]
  (let [runtime (when-let [r (:scheduler-ref router)] @r)
        result (scheduler-app/handle! runtime payload)]
    (when (:ok? result) (notify-change! router))
    (reply reply-fn result)))

(defn- handle-browser!
  [router request payload reply-fn]
  (let [session-id (:session-id payload)]
    (if-not (non-blank-string? session-id)
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "browser commands need an explicit :session-id"
                           {:kind (:kind payload)}))
      (let [service (get @(:browser-services router) session-id)]
        (if-not service
          (reply reply-fn
                 (result-error :BROWSER_UNAVAILABLE
                               "managed browser is not connected for this session"
                               {:session-id session-id}))
          (let [result (browser/command! service payload)]
            ;; Read-only projections must not publish a new server revision.
            ;; The client bridge asks for one snapshot per revision; notifying on
            ;; the snapshot itself would create a fetch/notify loop.
            (when (and (:ok? result)
                       (not (contains? #{:browser/snapshot :browser/cards}
                                       (:kind payload))))
              (notify-change! router))
            (reply reply-fn result)))))))

(defn- handle-documents!
  [router payload reply-fn]
  (let [service (:documents router)
        result
        (case (:kind payload)
          :documents/ocr-status
          (if service
            {:ok? true :receipt {:status :accepted
                                 :ocr (documents/ocr-status)}}
            {:ok? false :error {:code :DOCUMENTS_UNAVAILABLE
                                :message "documents service is not available"}})

          :documents/extract-attachment
          (if-let [attachment-service @(:attachments-ref router)]
            (let [result (attachments/extract-attachment!
                          attachment-service
                          (:attachment payload)
                          (:selection payload)
                          (select-keys payload [:password :max-bytes]))]
              (if (and (map? result) (contains? result :ok?))
                result
                {:ok? true :receipt {:status :accepted :result result}}))
            {:ok? false :error {:code :ATTACHMENTS_UNAVAILABLE
                                :message "attachments service is not available"}})

          :documents/parse-pages
          (if service
            (let [selection (or (:selection payload) (:pages payload))
                  options (select-keys payload [:max-pages])
                  parsed (documents/parse-pages selection options)
                  invalid (when (and (:ok? parsed) (integer? (:page-count payload)))
                            (documents/validate-pages (:pages parsed)
                                                      (:page-count payload)
                                                      options))
                  result (or invalid parsed)]
              (if (map? result)
                {:ok? true :receipt {:status :accepted :result result}}
                {:ok? false :error {:code :DOCUMENTS_RESULT_INVALID
                                    :message "documents returned a non-map"}}))
            {:ok? false :error {:code :DOCUMENTS_UNAVAILABLE
                                :message "documents service is not available"}})

          {:ok? false
           :error {:code :DOCUMENTS_COMMAND_UNSUPPORTED
                   :message "use the attachments HTTP endpoint for bytes extraction"
                   :details {:kind (:kind payload)}}})]
    (when (:ok? result) (notify-change! router))
    (reply reply-fn result)))

(defn- handle-search!
  [router payload reply-fn]
  (if-let [service (:search router)]
    (let [result (search/handle! service (:kind payload) payload)]
      (when (and (:ok? result) (:diagnostics router))
        (doseq [usage (:service-usages result)]
          (try
            (diagnostics-service/record-service-usage!
             (:diagnostics router)
             (merge usage
                    {:session-id (:session-id payload)
                     :turn-id (:turn-id payload)}))
            (catch Throwable _ nil))))
      (when (:ok? result) (notify-change! router))
      (reply reply-fn result))
    (reply reply-fn
           (result-error :SEARCH_UNAVAILABLE
                         "search service is not running"
                         {:kind (:kind payload)}))))

(defn- handle-speech!
  [router payload reply-fn]
  (let [result (if-let [component (:speech router)]
                 (speech/handle! component payload)
                 {:ok? false
                  :error {:code :SPEECH_UNAVAILABLE
                          :message "speech component is not running"}})]
    (when (:ok? result) (notify-change! router))
    (reply reply-fn result)))

(defn- mcp-role
  "Derive the MCP role from the session capability scope.

  Fail closed to `:viewer`.  A scope may be a bare capability set or a map
  with an `:mcp` subset; a missing or unrelated scope grants no write."
  [capability-scope]
  (let [caps (cond
               (set? capability-scope) capability-scope
               (map? capability-scope) (set (or (:mcp capability-scope) []))
               :else #{})]
    (cond
      (contains? caps :mcp/destructive) :admin
      (contains? caps :mcp/write) :operator
      :else :viewer)))

(defn- extension-scope
  "Derive the trusted extension scope from the committed session.

  Browser payloads may name a session but cannot assert a role, assistant,
  server set, or capability limit.  Those facts come from the session record
  and are passed to `extensions-app/handle!` as the trusted third argument."
  [router payload]
  (let [session (session-record router (:session-id payload))]
    (when session
      {:scope {:role (mcp-role (:capability-scope session))
               :session-id (:session-id session)
               :assistant-id (:assistant-id session)
               :assistant-servers (set (or (:mcp-servers session) []))
               :session (or (:mcp-session-scope session) {:mode :follow})}
       :capability-scope (:capability-scope session)})))

(defn- handle-extension!
  [router payload reply-fn]
  (if-let [extensions (:extensions router)]
    (let [trusted (extension-scope router payload)
          raw (extensions-app/handle! extensions payload trusted)
          result (if (contains? raw :ok?)
                   raw
                   {:ok? true
                    :receipt {:status :accepted :result raw}})]
      (when (:ok? result) (notify-change! router))
      (reply reply-fn result))
    (reply reply-fn
           (result-error :EXTENSIONS_UNAVAILABLE
                         "extension component is not available"
                         {:kind (:kind payload)}))))

(defn- handle-branch!
  [router payload reply-fn]
  (let [source (:session-id payload)]
    (if-not (non-blank-string? source)
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "branching needs an explicit source :session-id"
                           {:kind (:kind payload)}))
      (let [result (registry/branch-session! (:registry router) source
                                             (assoc (select-keys payload
                                                                 [:before-message-id
                                                                  :title :client-id])
                                                    :defer-owner? true))]
        (case (:status result)
          :created
          (let [session (:session result)
                session-id (:session-id session)
                ;; `:defer-owner? true` leaves :owner nil; the branch Owner
                ;; must exist so the receipt can carry its command epoch and
                ;; the client view can register it.
                owner (or (:owner result)
                          (ensure-live-owner! router session-id))
                owner-state (when owner
                              (try (:state ((:snapshot owner)))
                                   (catch Throwable _ nil)))
                epoch (get-in owner-state [:commands :command-epoch])
                receipt-session (cond-> (dissoc session :commands)
                                  (pos-int? epoch)
                                  (assoc :command-epoch epoch))]
            (when owner
              (register-owner! router session-id owner))
            ;; A branch is a new session with copied messages; the client
            ;; needs a state-changed notification so its baseline refresh
            ;; includes the branch and its message history.  The receipt also
            ;; carries the branch Owner's command epoch: without it the
            ;; settings panel cannot submit :session/select-model for the
            ;; new branch.
            (notify-change! router)
            (reply reply-fn
                   {:ok? true
                    :receipt {:status :accepted
                              :session-id session-id
                              :session receipt-session}}))
          (reply reply-fn
                 (result-error (or (get-in result [:error :code])
                                   :SESSION_BRANCH_FAILED)
                               (or (get-in result [:error :message])
                                   "session branch failed")
                               (get-in result [:error :details]))))))))

(defn- handle-create-from-summary!
  "Create a new session whose only message is one reviewed compression
  summary.  The source messages stay in the source session; nothing is
  copied into the new session."
  [router request payload reply-fn]
  (let [registry (:registry router)
        source (:session-id payload)]
    (if-not (non-blank-string? source)
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "a summary session needs an explicit source :session-id"
                           {:kind (:kind payload)}))
      (let [result (registry/create-summary-session!
                    registry source
                    (assoc (select-keys payload
                                        [:compression-id :title :client-id])
                           :defer-owner? true))]
        (case (:status result)
          :created
          (let [session (:session result)
                session-id (:session-id session)]
            ;; `:defer-owner? true` leaves :owner nil; ensure the Owner so
            ;; the new session can accept work immediately.
            (register-owner! router session-id
                              (or (:owner result)
                                  (ensure-live-owner! router session-id)))
            (swap! (:state router) assoc :active-session-id session-id)
            (notify-change! router)
            (reply reply-fn
                   {:ok? true
                    :receipt (session-creation-receipt
                              session (request-creation-identity request))}))
          :error
          (create-error-reply! request result reply-fn)
          (reply reply-fn
                 (result-error :SESSION_CREATE_FAILED
                               "summary session could not be created"
                               {:status (:status result)})))))))

(defn- cancel-owner-run!
  "Send the Owner's P0 stop-run before a discard.

  The discard must not wait forever on a live devops/tool attempt.  The
  operator-cancel walk aborts the active run subtree; the subsequent bounded
  `:stop!` cancels any remaining dispatcher attempt.  A missing command port
  or a closed Owner is a configuration fact, not a reason to skip the stop."
  [owner]
  (when-let [submit (:submit-command! owner)]
    (try
      (let [state (:state ((:snapshot owner)))
            epoch (get-in state [:commands :command-epoch] 1)
            run-id (get-in state [:cancel :active-run-id])]
        (when run-id
          (submit {:command-id (str "discard-cancel-"
                                    (java.util.UUID/randomUUID))
                   :command-epoch epoch
                   :payload {:kind :stop-run
                             :origin :operator-stop
                             :reason :user-cancel
                             :run-id run-id}})))
      (catch Throwable _ nil))))

(defn- await-owner-run-cancelled!
  [owner timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [state (:state ((:snapshot owner)))
            run-id (get-in state [:cancel :active-run-id])
            run (get-in state [:cancel :nodes run-id])]
        (cond
          (nil? run-id) true
          (contains? #{:cancelled :failed :succeeded} (:state run)) true
          (>= (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 50) (recur)))))))

(defn- call-owner-stop!
  "Call the Owner stop with a bounded timeout, falling back to zero arity."
  [owner]
  (try
    ((:stop! owner) {:timeout-ms 15000})
    (catch clojure.lang.ArityException _
      (try
        ((:stop! owner))
        (catch Throwable t
          {:ok? false
           :error {:code :OWNER_STOP_FAILED
                   :message (str "session Owner stop failed: "
                                 (.getMessage t))}})))
    (catch Throwable t
      {:ok? false
       :error {:code :OWNER_STOP_FAILED
               :message (str "session Owner stop failed: "
                             (.getMessage t))}})))

(defn- stop-owner-for-permanent-delete!
  "Strict purge stop: the Owner must report both :stopped? and :drained?.

  A permanent purge removes a deleted session; it must not proceed while the
  Owner still has live work."
  [router session-id]
  (if-let [owner (owner-of router session-id)]
    (if-not (ifn? (:stop! owner))
      {:ok? false
       :error {:code :OWNER_STOP_UNAVAILABLE
               :message "the live session Owner cannot be stopped"}}
      (let [result (call-owner-stop! owner)]
        (if (and (not (false? (:ok? result)))
                 (true? (:stopped? result))
                 (true? (:drained? result)))
          {:ok? true}
          {:ok? false
           :error (or (:error result)
                      {:code :OWNER_STOP_INCOMPLETE
                       :message "session Owner did not stop and drain before permanent deletion"
                       :details {:stopped? (:stopped? result)
                                 :drained? (:drained? result)}})})))
    {:ok? true}))

(defn- stop-owner-for-discard!
  "Bounded temporary-discard stop: cancel the run, then accept stopped-but-undrained.

  The discard must not wait forever on a live devops/tool attempt.  The P0
  stop-run aborts the active run subtree; the bounded `:stop!` cancels
  remaining dispatcher attempts.  If the drain still has live work, the
  caller proceeds with `:unsettled? true` and a diagnostic; the session
  record is removed and the work is no longer a live responsibility."
  [router session-id]
  (if-let [owner (owner-of router session-id)]
    (cond
      (not (ifn? (:stop! owner)))
      {:ok? false
       :error {:code :OWNER_STOP_UNAVAILABLE
               :message "the live session Owner cannot be stopped"}}

      :else
      (do
        (cancel-owner-run! owner)
        (await-owner-run-cancelled! owner 10000)
        (let [result (call-owner-stop! owner)]
          (cond
            (false? (:ok? result))
            {:ok? false
             :error (or (:error result)
                        {:code :OWNER_STOP_FAILED
                         :message "session Owner stop failed"})}

            (not (true? (:stopped? result)))
            {:ok? false
             :error {:code :OWNER_STOP_INCOMPLETE
                     :message "session Owner did not stop before discard"
                     :details {:stopped? (:stopped? result)
                               :drained? (:drained? result)}}}

            (true? (:drained? result))
            {:ok? true}

            :else
            {:ok? true
             :unsettled? true
             :diagnostic {:code :DISCARD_UNSETTLED_WORK
                          :severity :warning
                          :message "discard proceeded after the bounded stop; remaining work is no longer a live responsibility"
                          :remedy :inspect
                          :session-id session-id
                          :stopped? true
                          :drained? false}}))))
    {:ok? true}))

(defn- purge-precondition
  "Check the non-owner purge conditions before stopping a live Owner.

  A non-deleted purge must not stop the Owner: the session is still active
  and may still accept work.  `registry/purge-session!` rechecks the same
  committed condition under its lock before deleting.  The committed record
  is the lifecycle authority, and an accepted delete has already committed
  there before its receipt.  A live Owner that still says `:deleted` over a
  committed `:active` record is not evidence of a business delete; the
  record stays, so a missing persistence barrier cannot be hidden here.
  The 30-day retention window is not a precondition for an explicit,
  confirmed purge."
  [registry session-id]
  (let [committed (registry/get-committed-session registry session-id)]
    (case (:status committed)
      :missing
      {:ok? false
       :error {:code :SESSION_NOT_FOUND
               :message "session does not exist"
               :details {:session-id session-id}}}

      :error
      {:ok? false
       :error (or (:error committed)
                  {:code :SESSION_READ_FAILED
                   :message "session could not be read"})}

      :found
      (let [committed-session (:session committed)]
        (if (= :deleted (:status committed-session))
          {:ok? true}
          {:ok? false
           :error {:code :SESSION_NOT_DELETED
                   :message "only a soft-deleted session can be purged"
                   :details {:status (:status committed-session)}}})))))

(defn- handle-purge!
  "Permanently remove one soft-deleted session record.

  This command is intentionally router-gated instead of Owner-gated: the
  Owner is stopped and forgotten before `registry/purge-session!` removes
  the record.  The durable command gate validates the per-session epoch
  before this handler runs.  The Owner must explicitly report both
  `:stopped?` and `:drained?`; a bare `{:ok? true}` is refused.
  `registry/purge-session!` still enforces committed `:deleted` status; the
  30-day window is default retention, not a precondition for an explicit
  purge.  It deletes only the session record, never workspace files."
  [router request payload reply-fn]
  (let [session-id (:session-id payload)]
    (cond
      (not (non-blank-string? session-id))
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "purging needs an explicit :session-id"
                           {:kind (:kind payload)}))

      (not (true? (:confirm? payload)))
      (reply reply-fn
             (result-error :CONFIRMATION_REQUIRED
                           "permanent deletion needs :confirm? true"
                           {:session-id session-id}))

      :else
      (let [precondition (purge-precondition (:registry router) session-id)]
        (if-not (:ok? precondition)
          (reply reply-fn
                 (result-error
                  (or (get-in precondition [:error :code])
                      :SESSION_PURGE_FAILED)
                  (or (get-in precondition [:error :message])
                      "session cannot be purged")
                  (get-in precondition [:error :details])))
          (let [stopped (stop-owner-for-permanent-delete! router session-id)]
            (if-not (:ok? stopped)
              (reply reply-fn
                     (result-error
                      (or (get-in stopped [:error :code])
                          :OWNER_STOP_INCOMPLETE)
                      (or (get-in stopped [:error :message])
                          "session Owner did not settle before purge")
                      {:session-id session-id}))
              (do
                (when (owner-of router session-id)
                  (swap! (:state router) update :owners dissoc session-id)
                  (try
                    (registry/forget-owner! (:registry router) session-id)
                    (catch Throwable _ nil)))
                (let [result (registry/purge-session!
                              (:registry router) session-id)]
                  (if (= :purged (:status result))
                    (do
                      (notify-change! router)
                      (reply reply-fn
                             {:ok? true
                              :receipt {:status :accepted
                                        :session-id session-id
                                        :purged? true}}))
                    (reply reply-fn
                           (result-error
                            (or (get-in result [:error :code])
                                :SESSION_PURGE_FAILED)
                            (or (get-in result [:error :message])
                                "session purge failed")
                            (get-in result [:error :details])))))))))))))

(defn- discard-temporary-session!
  "Stop, drain, and permanently discard one temporary session."
  [router session-id reply-fn]
  (let [stopped (stop-owner-for-discard! router session-id)]
    (if-not (:ok? stopped)
      (reply reply-fn
             (result-error
              (or (get-in stopped [:error :code])
                  :OWNER_STOP_INCOMPLETE)
              (or (get-in stopped [:error :message])
                  "temporary session Owner did not settle before discard")
              {:session-id session-id}))
      (do
        (swap! (:state router) update :owners dissoc session-id)
        (try (registry/forget-owner! (:registry router) session-id)
             (catch Throwable _ nil))
        (try (registry/close-session! (:registry router) session-id
                                      {:delete? true
                                       :reason :temporary-discard})
             (catch Throwable _ nil))
        (let [result (registry/discard-session! (:registry router) session-id)]
          (if (= :discarded (:status result))
            (do (notify-change! router)
                (reply reply-fn {:ok? true
                                 :receipt {:status :accepted
                                           :session-id session-id
                                           :discarded? true
                                           :unsettled? (boolean
                                                        (:unsettled? stopped))
                                           :diagnostic (:diagnostic stopped)
                                           :external-effects
                                           (:external-effects result)}}))
            (reply reply-fn
                   (result-error
                    (or (get-in result [:error :code])
                        :SESSION_DISCARD_FAILED)
                    (or (get-in result [:error :message])
                        "temporary session discard failed")
                    (get-in result [:error :details])))))))))

(defn- handle-delete!
  [router request payload reply-fn]
  (let [session-id (:session-id payload)]
    (if-not (non-blank-string? session-id)
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "deleting needs an explicit :session-id"
                           {:kind (:kind payload)}))
      (let [session (session-record router session-id)]
        (if (:temporary? session)
          (discard-temporary-session! router session-id reply-fn)
          (handle-metadata! router request payload reply-fn))))))

(defn- handle-owner-command!
  [router request payload reply-fn]
  (let [session-id (:session-id payload)
        owner (ensure-live-owner! router (:session-id payload))]
    (cond
      (not (non-blank-string? session-id))
      (reply reply-fn
             (result-error :SESSION_REQUIRED
                           "a session is required for this command"
                           {:kind (:kind payload)}))

      (nil? owner)
      (reply reply-fn
             (result-error :SESSION_OWNER_UNAVAILABLE
                           "no live Owner is registered for this session"
                           {:session-id session-id}))

      :else
      (let [payload (cond
                      (= :stop-run (:kind payload))
                      (assoc payload :reason :user-cancel)

                      (= :history/edit-message (:kind payload))
                      (-> payload
                          (update :message-id #(or % (:id payload)))
                          (update :content #(or % (:text payload))))

                      :else payload)
            request (cond-> (assoc request
                                   :command-id (:command-id request)
                                   :payload (assoc payload :session-id session-id))
                      (contains? operator-kinds (:kind payload))
                      (assoc :operator? true :origin :operator-message))]
        ;; No external side effect belongs here.  The Owner applies its
        ;; business gate inside submit-command!; search and model calls start
        ;; only from the accepted, committed turn.
        (try
          (let [raw ((:submit-command! owner) request)
                result (if (contains? raw :ok?)
                         raw
                         {:ok? (= :accepted (:status raw))
                          :receipt raw
                          :error (or (:error raw)
                                     (get-in raw [:receipt :error])
                                     (get-in raw [:result :error]))})]
            (reply reply-fn result))
          (catch Throwable t
            (reply reply-fn
                   (result-error :COMMAND_SUBMIT_FAILED
                                 "Owner failed while accepting the command"
                                 {:cause (str t)}))))))))

(def ^:private fleet-mutating-kinds
  #{:fleet/fork :fleet/join :fleet/complete :fleet/abandon
    :fleet/fail :fleet/gc})

(defn- trusted-steward-request?
  "True only for the Steward main-model tool surface.

  Browser transports never set `:program-role` or `:origin`; the gateway
  also forbids every `:fleet/*` kind.  A trusted internal tool must build its
  envelope with `steward-command-envelope`."
  [request]
  (and (= :steward (:program-role request))
       (= :steward-tool (:origin request))
       (nil? (:subscription-id request))
       (nil? (:uid request))
       (nil? (:client-id request))
       (not (true? (:operator? request)))))

(defn- with-fleet-receipt
  "Attach a durable receipt to a successful fleet result.

  The immediate reply keeps the fleet shape; the stored receipt keeps the
  payload under `:result` so a replay returns the same evidence."
  [result]
  (if (and (map? result)
           (:ok? result)
           (not (contains? result :receipt)))
    (let [payload (dissoc result :ok? :receipt)
          payload (if (and (= #{:result} (set (keys payload)))
                           (contains? payload :result))
                    (:result payload)
                    payload)]
      (assoc result :receipt {:status :accepted :result payload}))
    result))

(defn steward-command-envelope
  "Build the trusted envelope a Steward main-model tool submits.

  The router accepts `:fleet/*` and `:steward/*` only from this envelope.
  The envelope is never constructed from browser payload fields.  Use
  `(app-command-epoch router)` for COMMAND-EPOCH and a fresh UUID for
  COMMAND-ID."
  [command-id command-epoch payload]
  {:command-id command-id
   :command-epoch command-epoch
   :program-role :steward
   :origin :steward-tool
   :payload payload})

(defn submit-steward-command!
  "Submit a trusted Steward envelope and wait for its receipt.

  TIMEOUT-MS 0 waits without a deadline.  The wait belongs to the calling
  Steward tool thread, never to an Owner mailbox.  A timed-out join returns
  `:outcome :unknown`; it never fabricates a result."
  ([router request]
   (submit-steward-command! router request 0))
  ([router request timeout-ms]
   (let [reply (promise)
         _ (try
             (gateway-ports/submit-command! router request #(deliver reply %))
             (catch Throwable t
               (deliver reply
                        {:ok? false
                         :error {:code :COMMAND_SUBMIT_FAILED
                                 :message "Steward command submission failed"
                                 :cause (str t)}})))]
     (if (pos? (long timeout-ms))
       (deref reply (long timeout-ms)
              {:ok? false
               :outcome :unknown
               :error {:code :COMMAND_TIMEOUT
                       :message "the Steward command did not produce a receipt before the deadline"
                       :action :resync-state}})
       @reply))))

(defn- handle-fleet!
  [router request payload reply-fn]
  (cond
    (not (trusted-steward-request? request))
    (reply reply-fn
           (result-error :FLEET_FORBIDDEN
                         "fleet commands are reserved for the Steward main-model tool surface"
                         {:kind (:kind payload)}))

    :else
    (let [service (when-let [r (:fleet-ref router)] @r)]
      (if-not service
        (reply reply-fn
               (result-error :FLEET_UNAVAILABLE
                             "fleet service is not running"
                             {:kind (:kind payload)}))
        (if (= :fleet/join (:kind payload))
          ;; Join blocks until a result, :none, or :no-results.  Run it on a
          ;; separate thread so no Owner mailbox or transport thread waits.
          (do
            (future
              (let [result (try
                             (fleet-router/handle service payload)
                             (catch Throwable t
                               {:ok? false
                                :error {:code :FLEET_COMMAND_FAILED
                                        :message (or (.getMessage ^Throwable t)
                                                     (str t))
                                        :kind (:kind payload)}}))]
                (let [result (with-fleet-receipt result)]
                  (when (:ok? result) (notify-change! router))
                  (reply reply-fn result))))
            nil)
          (let [result (try
                         (fleet-router/handle service payload)
                         (catch Throwable t
                           {:ok? false
                            :error {:code :FLEET_COMMAND_FAILED
                                    :message (or (.getMessage ^Throwable t)
                                                 (str t))
                                    :kind (:kind payload)}}))]
            (let [result (with-fleet-receipt result)]
              (when (and (:ok? result)
                         (contains? fleet-mutating-kinds (:kind payload)))
                (notify-change! router))
              (reply reply-fn result))))))))

;; ---------------------------------------------------------------------------
;; Trusted holder program commands

(defn program-command-envelope
  "Build the trusted envelope a holder program's control port submits.

  WORKSPACE is the caller's host-owned worktree.  It must come from the
  program control context, never from model arguments; the router locates
  the swarm by workspace.  PAYLOAD carries intent only, for example
  `{:kind :program/abandon :reason \"rebase conflict\"}`.  A model-supplied
  `:swarm-id` is never authoritative and is ignored."
  [command-id command-epoch workspace payload]
  {:command-id command-id
   :command-epoch command-epoch
   :program-role :holder
   :origin :program-tool
   :holder? true
   :workspace workspace
   :payload payload})

(defn- trusted-program-request?
  "True only for a holder program's control port.

  Browser transports never set `:origin` or `:program-role`, and the gateway
  rejects reserved command planes.  A worker program has `:clj-worker` but
  is not a holder, so it cannot abandon its swarm."
  [request]
  (and (contains? #{:clj-worker :holder} (:program-role request))
       (= :program-tool (:origin request))
       (true? (:holder? request))
       (nil? (:subscription-id request))
       (nil? (:uid request))
       (nil? (:client-id request))
       (not (true? (:operator? request)))))

(defn- mission-abandon-handle
  "Resolve the router's mission handle.

  The state holds an atom so the application can attach or replace the
  mission layer during startup.  The atom may hold a `MissionHost` or a
  function `(fn [request] -> result)`.  A nil/invalid value means the
  mission plane is not assembled."
  [router]
  (let [state (try @(:state router) (catch Throwable _ nil))
        ref (:mission-ref state)]
    (when ref
      (try @ref (catch Throwable _ ref)))))

(defn- tenure-abandon!
  [router payload swarm-id slice-id]
  (let [entry (mission-abandon-handle router)
        request {:swarm-id swarm-id
                 :slice-id slice-id
                 :reason (:reason payload)
                 :at (or (:at payload) (System/currentTimeMillis))}]
    (cond
      (nil? entry)
      {:ok? false
       :error {:code :TENURE_ABANDON_UNAVAILABLE
               :message "the mission layer is not attached"}}

      (ifn? entry)
      (try
        (entry request)
        (catch Throwable t
          {:ok? false
           :error {:code :TENURE_ABANDON_FAILED
                   :message (or (.getMessage ^Throwable t) (str t))}}))

      (and (map? entry)
           (instance? clojure.lang.IAtom (:state entry)))
      (try
        (mission-host/abandon-swarm! entry request)
        (catch Throwable t
          {:ok? false
           :error {:code :TENURE_ABANDON_FAILED
                   :message (or (.getMessage ^Throwable t) (str t))}}))

      :else
      {:ok? false
       :error {:code :TENURE_ABANDON_UNAVAILABLE
               :message "the mission layer handle is invalid"}})))

(defn- attach-tenure-abandon
  "Attach the mission tenure outcome to a successful fleet abandon.

  The fleet abandon already happened and is never rolled back.  A missing or
  failing mission layer is reported as a diagnostic on the same result."
  [router payload result slice-id]
  (if-not (:ok? result)
    result
    (let [abandoned (tenure-abandon! router payload (:swarm-id result)
                                     slice-id)]
      (if (:ok? abandoned)
        (assoc result
               :tenure-abandoned? true
               :tenure-id (:tenure-id abandoned)
               :tenure-abandon {:ok? true
                                :tenure-id (:tenure-id abandoned)})
        (let [error (:error abandoned)
              code (or (:code error) :TENURE_ABANDON_UNAVAILABLE)]
          (assoc result
                 :tenure-abandoned? false
                 :tenure-abandon {:ok? false :error error}
                 :diagnostics
                 (conj (vec (:diagnostics result))
                       {:code code
                        :reason :tenure-abandon-unavailable
                        :message (or (:message error)
                                     "the mission tenure was not marked abandoned")
                        :swarm-id (:swarm-id result)
                        :fleet-abandoned? true})))))))

(defn- handle-program!
  [router request payload reply-fn]
  (let [kind (:kind payload)]
    (cond
      (not (trusted-program-request? request))
      (reply reply-fn
             (result-error :PROGRAM_FORBIDDEN
                           "program commands are reserved for the holder program control port"
                           {:kind kind}))

      (not= :program/abandon kind)
      (reply reply-fn
             (result-error :COMMAND_KIND_UNKNOWN
                           "unknown command kind"
                           {:kind kind}))

      (not (non-blank-string? (:workspace request)))
      (reply reply-fn
             (result-error :PROGRAM_WORKSPACE_REQUIRED
                           "abandon needs the caller's host-owned :workspace"
                           {:kind kind}))

      :else
      (let [service (when-let [r (:fleet-ref router)] @r)]
        (if-not service
          (reply reply-fn
                 (result-error :FLEET_UNAVAILABLE
                               "fleet service is not running"
                               {:kind kind}))
          (let [slice-id (try
                           (some-> (fleet-service/runtime-for-workspace
                                    service (:workspace request))
                                   :slice-id)
                           (catch Throwable _ nil))
                fleet-result
                (try
                  (fleet-service/abandon-by-workspace!
                   service (:workspace request) (:reason payload))
                  (catch Throwable t
                    {:ok? false
                     :error {:code :ABANDON_FAILED
                             :message (or (.getMessage ^Throwable t)
                                          (str t))}}))
                result
                (if (:ok? fleet-result)
                  (try
                    (attach-tenure-abandon router payload fleet-result slice-id)
                    (catch Throwable t
                      (assoc fleet-result
                             :tenure-abandoned? false
                             :diagnostics
                             (conj (vec (:diagnostics fleet-result))
                                   {:code :TENURE_ABANDON_FAILED
                                    :reason :tenure-abandon-unavailable
                                    :message (or (.getMessage ^Throwable t)
                                                 (str t))
                                    :fleet-abandoned? true}))))
                  fleet-result)
                result (with-fleet-receipt result)]
            (when (:ok? result) (notify-change! router))
            (reply reply-fn result)))))))

;; ---------------------------------------------------------------------------
;; Rebuildable history full-text search

(defn- history-search-handlers
  "Return the handler map for one call.

  The trusted map contains the only `:history/reindex` handler whose trust
  switch is on.  Search and status always use the untrusted map; both are
  read-only app commands available to browsers and operators."
  [router trusted?]
  (let [state (try @(:state router) (catch Throwable _ nil))]
    (if trusted?
      (get state :history-search-trusted-handlers)
      (get state :history-search-handlers))))

(defn- record-history-search-diagnostic!
  "Best-effort diagnostic for an unexpected handler failure.

  The handler contract catches its own Throwable; this path only runs when
  the injected function itself violates the contract.  A diagnostics failure
  must never change the command reply."
  [router request kind code message]
  (when-let [diagnostics (:diagnostics router)]
    (try
      (diagnostics-service/record-log!
       diagnostics
       {:request-id (:command-id request)
        :session-id (or (get-in request [:payload :session-id])
                        (:session-id request))
        :service-name "history-search"
        :purpose (str kind)
        :result :failed
        :error {:code code :message message}
        :at (System/currentTimeMillis)})
      (catch Throwable _ nil))))

(defn- handle-history-search!
  [router request payload reply-fn]
  (let [kind (:kind payload)
        reindex? (= :history/reindex kind)
        trusted? (and reindex? (trusted-steward-request? request))
        handler (get (history-search-handlers router trusted?) kind)]
    (cond
      (and reindex? (not trusted?))
      (reply reply-fn
             (result-error :HISTORY_REINDEX_FORBIDDEN
                           "reindex requires a trusted server-side caller"
                           {:kind kind}))

      (not (ifn? handler))
      (reply reply-fn
             (result-error (if reindex?
                             :HISTORY_REINDEX_UNAVAILABLE
                             :HISTORY_SEARCH_UNAVAILABLE)
                           "the history search handlers are not wired"
                           {:kind kind}))

      :else
      (try
        (let [result (handler payload)]
          (cond
            (= :ok (:status result))
            (reply reply-fn
                   {:ok? true
                    :receipt {:status :accepted
                              :result result}})

            (= :error (:status result))
            (reply reply-fn {:ok? false :error (:error result)})

            :else
            (do
              (record-history-search-diagnostic!
               router request kind :HISTORY_SEARCH_FAILED
               "the history search handler returned an invalid result")
              (reply reply-fn
                     (result-error :HISTORY_SEARCH_FAILED
                                   "the history search handler returned an invalid result"
                                   {:kind kind :result result})))))
        (catch Throwable t
          (record-history-search-diagnostic!
           router request kind :HISTORY_SEARCH_FAILED (str t))
          (reply reply-fn
                 (result-error :HISTORY_SEARCH_FAILED
                               "the history search command failed"
                               {:kind kind :cause (str t)})))))))

;; ---------------------------------------------------------------------------
;; Repository Steward commands

(def steward-command-kinds
  "Application commands of the repository Steward plane.

  All are trusted-only: the browser gateway forbids them, and the router
  accepts only `steward-command-envelope` requests."
  #{:steward/state :steward/intent :steward/evidence
    :steward/decide :steward/transition})

(def ^:private steward-commit-attempts
  "A Steward mutation recomputes against the freshly loaded version.

  Every Steward command is pure, so a lost compare-and-set is retried
  instead of surfacing a conflict the caller cannot resolve by itself."
  16)

(defn- steward-store-of
  "Resolve the Steward store from the router's `:steward-ref`.

  The atom may hold the Store directly or `{:store store}`; nil means the
  repository Steward is not assembled in this process."
  [router]
  (let [entry (when-let [ref (:steward-ref router)] @ref)]
    (cond
      (nil? entry) nil
      (and (map? entry) (some? (:store entry))) (:store entry)
      :else entry)))

(defn- steward-observe
  "Apply one world observation and pull the Decision Frontier.

  The observation is committed even when the frontier cannot be pulled
  (for example an empty intent text); the refusal is reported as
  `:frontier-error` instead of discarding valid evidence."
  [state observation]
  (let [observed (steward/observe-world state observation)]
    (if-not (:ok? observed)
      observed
      (let [pulled (steward/evaluate-frontier (:state observed))]
        (cond-> observed
          (:ok? pulled)
          (-> (assoc :state (:state pulled))
              (assoc :triggered? (:triggered? pulled))
              (assoc :frontier (:frontier pulled))
              (assoc :reasons (:reasons pulled)))

          (not (:ok? pulled))
          (assoc :frontier-error (:error pulled)))))))

(defn- steward-effect
  [state payload kind]
  (let [now (or (:now payload) (System/currentTimeMillis))]
    (case kind
      :steward/intent
      (steward-observe state
                       (assoc (select-keys payload [:intent :capacity])
                              :now now))
      :steward/evidence
      (steward-observe state
                       (assoc (select-keys payload [:git-commits :capacity])
                              :now now))
      :steward/decide
      (steward/decide state
                      (select-keys payload [:request-id :candidates :policy]))
      :steward/transition
      (let [to (:to payload)]
        (steward/transition-mission state
                                    (:mission-id payload)
                                    (if (string? to) (keyword to) to)
                                    (:reason payload))))))

(defn- steward-receipt
  [kind version result]
  {:status :accepted
   :kind kind
   :version version
   :result (cond-> (select-keys
                    result
                    [:new? :evidence :invalidated-assumption-ids
                     :newly-invalidated-assumption-ids :triggered? :frontier
                     :reasons :frontier-error :decision :replayed? :mission
                     :link])
             (:state result)
             (assoc :steward (steward/snapshot (:state result))))})

(defn- steward-failure
  [result fallback-message]
  (result-error (or (get-in result [:error :code])
                    :STEWARD_COMMAND_FAILED)
                (or (get-in result [:error :message]) fallback-message)
                (get-in result [:error :details])))

(defn- handle-steward!
  [router request payload reply-fn]
  (let [kind (:kind payload)]
    (cond
      (not (trusted-steward-request? request))
      (reply reply-fn
             (result-error :STEWARD_FORBIDDEN
                           "steward commands are reserved for the Steward main-model tool surface"
                           {:kind kind}))

      (not (contains? steward-command-kinds kind))
      (reply reply-fn
             (result-error :COMMAND_KIND_UNKNOWN
                           "unknown command kind"
                           {:kind kind}))

      :else
      (let [store (steward-store-of router)]
        (if-not store
          (reply reply-fn
                 (result-error :STEWARD_UNAVAILABLE
                               "the repository Steward is not running"
                               {:kind kind}))
          (try
            (if (= :steward/state kind)
              (let [{:keys [version state]} (steward-store/load-state store)
                    state (or state (steward/initial-state))]
                (reply reply-fn
                       {:ok? true
                        :receipt {:status :accepted
                                  :kind kind
                                  :version version
                                  :steward (steward/snapshot state)
                                  :intent (:intent state)
                                  :capacity (:capacity state)}}))
              (loop [attempt 0]
                (let [{:keys [version state]} (steward-store/load-state store)
                      state (or state (steward/initial-state))
                      result (steward-effect state payload kind)]
                  (if-not (:ok? result)
                    (reply reply-fn
                           (steward-failure result
                                            "the Steward refused the command"))
                    (let [committed (steward-store/commit! store version
                                                            (:state result))]
                      (cond
                        (:ok? committed)
                        (do
                          (notify-change! router)
                          (reply reply-fn
                                 {:ok? true
                                  :receipt (steward-receipt
                                            kind (:version committed) result)}))

                        (and (= :VERSION_CONFLICT
                                (get-in committed [:error :code]))
                             (< attempt steward-commit-attempts))
                        (recur (inc attempt))

                        :else
                        (reply
                         reply-fn
                         (result-error
                          (or (get-in committed [:error :code])
                              :STEWARD_COMMIT_FAILED)
                          (or (get-in committed [:error :message])
                              "the Steward state could not be committed")
                          (get-in committed [:error :details])))))))))
            (catch Throwable t
              (reply reply-fn
                     (result-error :STEWARD_COMMAND_FAILED
                                   "the Steward command failed"
                                   {:cause (str t)})))))))))

(def ^:private resolved-config-key
  ::resolved-session-config)

(defn- resolve-config-source
  "Return the configuration value held by SOURCE.

  SOURCE is either a zero-argument function that reads the live settings
  configuration or a plain configuration map.  Maps are callable in Clojure
  (`ifn?` is true), so a map must be treated as data and never invoked as a
  resolver."
  [source]
  (if (fn? source) (source) source))

(defn- read-session-config
  "Read the live session configuration exactly once for one command."
  [router]
  (let [source (:session-config router)]
    (try
      {:ok? true
       :config (resolve-config-source source)}
      (catch Throwable t
        {:ok? false
         :error {:code :SESSION_CONFIG_UNAVAILABLE
                 :message
                 "the committed model configuration could not be read"
                 :cause (str t)
                 :action :resync-state}}))))

(defn- stored-app-receipt
  "Return the committed receipt for COMMAND-ID in the app scope, if any.

  The in-memory gate state is authoritative for the running process and is
  loaded from the durable store at construction, so this read needs no
  window sync."
  [router command-id]
  (when (and (some? command-id) (:app-gate router))
    (try
      (command-durable/receipt-for (:app-gate router) "app" command-id)
      (catch Throwable _ nil))))

(defn- resolve-create-config
  "Resolve the live model configuration once, before the durable gate
  accepts a new `:session/create`.

  A replay must not depend on a live configuration read: the committed
  session already holds the binding it copied.  A read failure is therefore
  a clean refusal with no `:dispatching` placeholder, because no command was
  accepted."
  [router request]
  (if (not= :session/create (get-in request [:payload :kind]))
    {:ok? true :request request}
    (if (stored-app-receipt router (:command-id request))
      {:ok? true :request request}
      (let [config (read-session-config router)]
        (if (:ok? config)
          {:ok? true
           :request (assoc request resolved-config-key (:config config))}
          ;; A concurrent writer may have committed the same command-id
          ;; while this read failed.  A committed receipt still wins.
          (if (stored-app-receipt router (:command-id request))
            {:ok? true :request request}
            {:ok? false :error (:error config)}))))))

(defn- dispatch!
  [router request reply-fn]
  (let [payload (:payload request)
        kind (:kind payload)]
    (cond
      (not (map? payload))
      (reply reply-fn
             (result-error :COMMAND_PAYLOAD_INVALID
                           "command payload must be a map"
                           {:payload payload}))

      (not (keyword? kind))
      (reply reply-fn
             (result-error :COMMAND_KIND_REQUIRED
                           "command payload needs a keyword :kind"
                           {:payload payload}))

      (= kind :app/issue-epoch)
      (handle-issue-app-epoch! router request reply-fn)

      (= kind :session/create)
      (let [config (if (contains? request resolved-config-key)
                     {:ok? true :config (get request resolved-config-key)}
                     (read-session-config router))]
        (if (:ok? config)
          (handle-create! router request payload (:config config) reply-fn)
          (reply reply-fn
                 (result-error (:code (:error config))
                               (:message (:error config))
                               (select-keys (:error config) [:cause :action])))))
      (= kind :session/list) (handle-list! router payload reply-fn)
      (= kind :session/ensure) (handle-ensure-owner! router payload reply-fn)
      (= kind :history/messages) (handle-history-window! router payload reply-fn)
      (= kind :history/branch) (handle-branch! router payload reply-fn)
      (= kind :session/create-from-summary)
      (handle-create-from-summary! router request payload reply-fn)
      (= kind :session/purge) (handle-purge! router request payload reply-fn)
      (= kind :session/delete) (handle-delete! router request payload reply-fn)
      (contains? metadata-kinds kind)
      (handle-metadata! router request payload reply-fn)
      (settings-kind? kind) (handle-settings! router request payload reply-fn)
      (= "model-auth" (namespace kind))
      (handle-model-auth! router payload reply-fn)
      (= "search" (namespace kind))
      (handle-search! router payload reply-fn)
      (= "documents" (namespace kind))
      (handle-documents! router payload reply-fn)
      (= "browser" (namespace kind))
      (handle-browser! router request payload reply-fn)
      (= "speech" (namespace kind))
      (handle-speech! router payload reply-fn)
      (= "extensions" (namespace kind))
      (handle-extension! router payload reply-fn)
      (= "materials" (namespace kind))
      (handle-materials! router request payload reply-fn)
      (= "diagnostics" (namespace kind))
      (handle-diagnostics! router payload reply-fn)
      (= "backup" (namespace kind))
      (handle-backup! router payload reply-fn)
      (= "scheduler" (namespace kind))
      (handle-scheduler! router payload reply-fn)
      (= "fleet" (namespace kind))
      (handle-fleet! router request payload reply-fn)
      (= "steward" (namespace kind))
      (handle-steward! router request payload reply-fn)
      (= "program" (namespace kind))
      (handle-program! router request payload reply-fn)
      ;; History search and status are owner-kind read-only commands; they
      ;; never reach a session Owner.  Reindex is app-gated and trusted-only.
      (contains? history-search-kinds kind)
      (handle-history-search! router request payload reply-fn)
      (or (contains? conversation-kinds kind)
          (= "history" (namespace kind)))
      (handle-owner-command! router request payload reply-fn)
      :else
      (reply reply-fn
             (result-error :COMMAND_KIND_UNKNOWN
                           "unknown command kind"
                           {:kind kind})))))

(def ^:private maintenance-allowed-kinds
  #{:backup/status :backup/maintenance-status
    :backup/maintenance-restore
    :backup/maintenance-restore-recovery-point})

(defn- maintenance-active?
  [router]
  (boolean (:maintenance @(:state router))))

(defn- maintenance-allowed-kind?
  [kind]
  (contains? maintenance-allowed-kinds kind))

(def ^:private sensitive-command-kinds
  "Commands whose payload may carry a password or credential even when a
  field name does not match the generic secret-key pattern."
  #{:backup/create :backup/preview :backup/verify :backup/restore
    :backup/maintenance-restore
    :backup/maintenance-restore-recovery-point})

(defn sensitive-request?
  "True when the command payload must be stored only as a digest and its
  stored receipt must be redacted."
  [request]
  (let [payload (:payload request)]
    (or (contains? sensitive-command-kinds (:kind payload))
        (command-receipts/contains-secret? payload))))

(defn maintenance-state
  "Return the maintenance gate and the number of in-flight handlers."
  [router]
  (let [{:keys [maintenance]} @(:state router)]
    {:active? (boolean maintenance)
     :since (:since maintenance)
     :reason (:reason maintenance)
     :in-flight (long @(:in-flight router))}))

(defn begin-maintenance!
  "Close the command gate for new work.  Idempotent."
  [router]
  (let [now (System/currentTimeMillis)]
    (swap! (:state router) update :maintenance
           #(or % {:since now :reason :backup-restore}))
    (maintenance-state router)))

(defn end-maintenance!
  "Reopen the command gate."
  [router]
  (swap! (:state router) assoc :maintenance nil)
  {:active? false})

(defn drain!
  "Wait until no command handler is in flight, or TIMEOUT-MS elapses.

  Returns true only when the count reaches zero before the deadline."
  [router timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (max 0 (long timeout-ms)))]
    (loop []
      (cond
        (zero? (long @(:in-flight router)))
        true

        (>= (System/currentTimeMillis) deadline)
        false

        :else
        (do
          (Thread/sleep 10)
          (recur))))))

(defrecord Router [state registry settings on-change extensions speech search
                   materials diagnostics backup scheduler-ref session-config
                   documents browser-services attachments-ref model-auth
                   app-gate gates gate-lock dedup-store-for dedup in-flight
                   fleet-ref steward-ref]
  gateway-ports/OwnerPort
  (submit-command! [this request reply-fn]
    (let [kind (get-in request [:payload :kind])]
      (if (and (boolean (:maintenance @(:state this)))
               (not (contains? maintenance-allowed-kinds kind)))
        (reply reply-fn
               (result-error :MAINTENANCE_IN_PROGRESS
                             "maintenance is stopping writers; new work is blocked"
                             {:kind kind}))
        (let [done? (atom false)
              finish! (fn []
                        (when (compare-and-set! done? false true)
                          (swap! (:in-flight this) dec)))
              reply' (fn [result]
                       (try
                         (reply reply-fn result)
                         (finally
                           (finish!))))]
          (swap! (:in-flight this) inc)
          (try
            (if (some? (:dedup this))
              (let [prepared (resolve-create-config this request)]
                (if (:ok? prepared)
                  ;; The durable wrapper calls REPLY' on every synchronous
                  ;; and asynchronous path.  An asynchronous fleet join
                  ;; therefore stays counted until its real result arrives.
                  ((:submit! (:dedup this)) (:request prepared) reply')
                  (reply'
                   (result-error (:code (:error prepared))
                                 (:message (:error prepared))
                                 (select-keys (:error prepared)
                                              [:cause :action])))))
              (let [returned (dispatch! this request reply')]
                (finish!)
                returned))
            (catch Throwable t
              (finish!)
              (reply reply-fn
                     (result-error :COMMAND_ROUTER_FAILED
                                   "command router failed"
                                   {:cause (str t)}))))))))

  (command-receipt [this query]
    (let [query (if (map? query) query {:command-id query})
          command-id (:command-id query)
          requested-scope (:scope query)
          session-id (:session-id query)
          scope (cond
                  (non-blank-string? requested-scope) requested-scope
                  (non-blank-string? session-id) session-id
                  :else "app")
          stored (try
                   (when-let [receipt-fn (:receipt (:dedup this))]
                     (receipt-fn scope command-id))
                   (catch Throwable _ nil))
          receipt (if (and (= "app" scope)
                           (map? stored)
                           (contains? #{:dispatching :outcome-unknown}
                                      (:status stored))
                           (:registry this))
                    (let [found (registry/session-by-command
                                 (:registry this)
                                 {:scope "app"
                                  :command-epoch (:command-epoch stored)
                                  :command-id command-id})]
                      (if (= :found (:status found))
                        (let [session (:session found)]
                          (if (owner-of this (:session-id session))
                            (session-creation-receipt
                             session
                             {:scope "app"
                              :command-epoch (:command-epoch stored)
                              :command-id command-id})
                            stored))
                        stored))
                    stored)]
      (or receipt
          {:status :not-found
           :command-id command-id
           :scope scope}))))

(defn router
  "Create the gateway OwnerPort router.

  Options:
    :registry        session registry
    :settings        settings service
    :on-change       optional `(fn [event])` called after a session is created
    :dedup-store     durable store for the app command gate
    :dedup-store-for `(fn [session-id] store)` for one durable session gate
    :fleet-ref       atom holding the application fleet service
    :steward-ref     atom holding the repository Steward store, or a map
                     `{:store store}`; nil when the Steward is not assembled
    :mission-ref     atom holding a MissionHost or a
                     `(fn [request] -> result)` mission abandon handle; nil
                     leaves holder abandons as fleet-only
    :history-search-handlers
                     handler map for :history/search and
                     :history/search-status (browser and owner)
    :history-search-trusted-handlers
                     handler map for trusted callers; the only map whose
                     :history/reindex handler may rebuild
    :history-search  opts passed to
                     `hc.server.app.search-commands/handlers`; the router
                     builds both maps with the trust switch fixed

  `:session-config` is the `hc.server.sessions.config` value used to bind a
  new session.  Pass a zero-argument function that reads the live settings
  configuration, or a plain configuration map.  A map is data and is never
  invoked; it is read at creation only, so a later settings save does not
  reach the next session until the caller supplies a new map.  The resolver
  runs once before the durable gate accepts a new `:session/create`; a read
  failure is a clean refusal with no placeholder or receipt.  A replay reads
  the stored receipt first and never needs the live resolver.

  Without `:dedup-store` the router uses an in-memory app gate.  Without
  `:dedup-store-for` each session uses an in-memory gate.  A production
  host supplies both so receipts survive a restart."
  [{:keys [registry settings on-change extensions speech search materials
           diagnostics backup scheduler-ref session-config documents
           browser-services attachments-ref model-auth dedup-store
           dedup-store-for fleet-ref steward-ref history-search-handlers
           history-search-trusted-handlers history-search mission-ref]}]
  (let [self (atom nil)
        app-store (or dedup-store (command-durable/memory-store))
        app-gate (ensure-app-epoch! (command-durable/gate app-store))
        untrusted-handlers
        (or history-search-handlers
            (when (map? history-search)
              (history-search/handlers
               (assoc history-search :allow-reindex? false))))
        trusted-handlers
        (or history-search-trusted-handlers
            (when (map? history-search)
              (history-search/handlers
               (assoc history-search :allow-reindex? true))))
        base (->Router (atom {:owners {}
                              :active-session-id nil
                              ;; Shared with the system publisher when it
                              ;; installs its own atoms; tests and embedded
                              ;; routers get working defaults here.
                              :published-windows (atom {})
                              :history-window-size (atom 200)
                              :history-search-handlers
                              (or untrusted-handlers {})
                              :history-search-trusted-handlers
                              (or trusted-handlers {})
                              :mission-ref mission-ref})
                       registry settings on-change extensions speech search
                       materials diagnostics backup scheduler-ref session-config
                       documents browser-services attachments-ref model-auth
                       app-gate (atom {}) (Object.) dedup-store-for nil
                       (atom 0) fleet-ref steward-ref)
        dedup (command-dispatch/wrap
               {:gate-for (fn [scope] (gate-for @self scope))
                :window-fn (fn [scope] (window-for @self scope))
                :scope-fn (fn [request] (scope-for @self request))
                :owner-kind? (fn [kind]
                               (and (not (contains? history-search-kinds kind))
                                    (or (= :app/issue-epoch kind)
                                        (command-dispatch/default-owner-kind?
                                         kind))))
                :resolve-replay (fn [request stored]
                                  (resolve-command-replay @self request stored))
                :sensitive-request? sensitive-request?
                :dispatch! (fn [request reply-fn]
                             (dispatch! @self request reply-fn))
                :fallback-receipt (fn [scope command-id]
                                    (when (not= scope "app")
                                      (command-dispatch/owner-receipt
                                       (owner-of @self scope)
                                       command-id)))})]
    (reset! self (assoc base :dedup dedup))
    @self))

(defn register-owner!
  "Register an Owner handle for an existing session (restart resume).

  The router keeps its own handle map for command dispatch; the registry map
  is the projection source `get-session`/`list-sessions` merge from.  Both
  must see a resumed Owner, or the client snapshot reads the stale persisted
  session record and loses the live model binding after a restart."
  [router session-id owner]
  (remember-owner! router session-id owner)
  (when-let [registry (:registry router)]
    (try
      (registry/register-owner! registry session-id owner)
      (catch Throwable _ nil)))
  (swap! (:state router) update :active-session-id #(or % session-id))
  owner)

(defn active-session-id
  "Return the presentation active session.  Command routing never uses this."
  [router]
  (:active-session-id @(:state router)))

(defn select-session!
  "Select the presentation active session.  Command routing never uses this."
  [router session-id]
  (swap! (:state router) assoc :active-session-id session-id)
  session-id)
