(ns hc.server.app.system
  "Integrated application lifecycle.

  Start order follows `src/AGENTS.md` §4:

    1. resolve configuration and create owned directories;
    2. refuse an unfinished offline restore from the maintenance journal;
    3. open the current-state store, or stop at a recovery entry;
    4. scan open obligations and verify visible Owner partitions before any
       new work is dispatched;
    5. build session persistence, settings, registry and per-session Owners;
    6. start the gateway and bind the HTTP listener;
    7. accept commands.

  Shutdown reverses the order: stop accepting connections, retire the
  gateway, drain Owners, stop the model port, then close the store.

  A component that could not be constructed is reported as missing.  The
  application never substitutes a fake port or a silent no-op for a real
  dependency."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hc.compose.fleet.service :as fleet]
            [hc.compose.fleet.conflict :as fleet-conflict]
            [hc.compose.fleet.git :as fleet-git]
            [hc.compose.fleet.supervisor :as fleet-supervisor]
            [hc.compose.mission :as mission]
            [hc.compose.mission.control :as mission-control]
            [hc.compose.mission.devops :as mission-devops]
            [hc.compose.mission.model :as mission-model]
            [hc.compose.mission.worktree :as mission-worktree]
            [hc.compose.owner :as owner]
            [hc.compose.workspace :as workspace]
            [hc.core.pair-hint :as pair-hint]
            [hc.core.scope.runtime :as scope-runtime]
            [hc.core.swarm-stm :as swarm]
            [hc.core.ufss :as ufss]
            [hc.core.workspace-gate :as wg]
            [hc.server.app.backup :as backup-app]
            [hc.server.app.commands :as commands]
            [hc.server.app.config :as config]
            [hc.server.app.devops :as app-devops]
            [hc.server.app.executors :as executors]
            [hc.server.app.fleet-gc :as fleet-gc]
            [hc.server.app.health :as health]
            [hc.server.app.http :as http]
            [hc.server.app.owner-store :as owner-store]
            [hc.server.app.prompts :as prompts]
            [hc.server.app.session-config :as session-config]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.app.settings :as settings]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.app.static :as static]
            [hc.server.artifacts.integration :as artifacts-integration]
            [hc.server.blob-store :as blob-store]
            [hc.server.browser :as browser]
            [hc.server.commands.storage :as command-storage]
            [hc.server.attachments :as attachments]
            [hc.server.attachments.http :as attachments-http]
            [hc.server.attachments.model :as attachments-model]
            [hc.server.backup :as backup]
            [hc.server.backup.file-recovery :as backup-recovery]
            [hc.server.backup.file-target :as backup-target]
            [hc.server.backup.maintenance-journal :as maintenance-journal]
            [hc.server.backup.storage-adapter :as backup-domain]
            [hc.server.diagnostics.capture :as diagnostics-capture]
            [hc.server.diagnostics.integration :as diagnostics-integration]
            [hc.server.diagnostics.service :as diagnostics-service]
            [hc.server.documents :as documents]
            [hc.server.extensions :as extensions]
            [hc.server.extensions.app :as extensions-app]
            [hc.server.files :as files]
            [hc.server.files.http :as files-http]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.auth :as gateway-auth]
            [hc.server.gateway.diff :as gateway-diff]
            [hc.server.gateway.ports :as gateway-ports]
            [hc.server.history :as history]
            [hc.server.history.context :as history-context]
            [hc.server.history.run-port :as history-run-port]
            [hc.server.materials.durable-store :as materials-durable]
            [hc.server.materials.session :as materials-session]
            [hc.server.miniapps :as miniapps]
            [hc.server.model-adapter :as model-adapter]
            [hc.server.model-auth.app :as model-auth-app]
            [hc.server.model-port.contract :as model-contract]
            [hc.server.program :as program]
            [hc.server.recovery :as recovery]
            [hc.server.scheduler :as scheduler]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.credentials :as credentials]
            [hc.server.sessions.secrets :as secrets]
            [hc.server.sessions.ports :as session-ports]
            [hc.server.sessions.registry :as registry]
            [hc.server.search :as search]
            [hc.server.search.context :as search-ctx]
            [hc.server.search.integration :as search-integration]
            [hc.server.shell :as shell]
            [hc.server.speech :as speech]
            [hc.server.shell.board :as shell-board]
            [hc.server.storage :as storage]
            [hc.version :as version]
            [taoensso.sente.packers.edn :as sente-edn]
            [taoensso.sente.server-adapters.http-kit :as sente-http-kit])
  (:import (java.util.concurrent Executors ThreadFactory TimeUnit)
           (java.util.concurrent.locks ReentrantLock)
           (java.util.concurrent.atomic AtomicLong)))

(defn- thread-factory
  [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

;; ---------------------------------------------------------------------------
;; Configuration merge

(defn- persisted-config
  [persistence]
  (let [result (session-ports/read-config persistence)]
    (case (:status result)
      :found {:ok? true :config (dissoc (:config result) :revision)}
      :missing {:ok? true :config nil}
      {:ok? false
       :error (or (:error result)
                  {:code :SETTINGS_READ_FAILED
                   :message "the persisted settings document could not be read"
                   :status (:status result)})})))

(defn- without-nil-values
  "Drop nil-valued fields from one map.

  A restored settings document can carry a string where the adapter expects
  a map.  Returning it unchanged lets the adapter report an invalid model
  configuration instead of turning every character into a map entry."
  [m]
  (if (map? m)
    (into {}
          (remove (fn [[_ value]] (nil? value)))
          m)
    m))

(defn- bound-role?
  "True when ROLE carries an actual connection/model binding.

  Settings documents keep placeholders such as `:task {:mode :unset}` or
  `:task :unset` for roles the operator has not configured.  The model
  adapter requires every role it receives to name a connection and model, so
  unbound placeholders must be dropped here rather than failing the whole
  save."
  [role]
  (and (map? role)
       (not= :unset (:mode role))
       (or (some? (:connection role))
           (some? (:connection-id role)))
       (or (some? (:model role))
           (some? (:model-entry-id role)))))

(def ^:private connection-app-keys
  [:created-at :updated-at :model-order :connection-order])

(def ^:private model-app-keys
  [:created-at :updated-at :model-order :model-entry-id])

(defn- strip-app-keys
  "Remove application bookkeeping the model adapter does not accept.

  Persisted settings documents carry creation/update timestamps and display
  ordering next to provider fields.  The adapter has a closed schema, so
  these keys are stripped in the projection instead of widening the
  adapter's accepted field set."
  [m keys]
  (if (map? m) (apply dissoc m keys) m))

(defn- clean-model-section
  "Drop nil optional fields that settings documents commonly carry.

  The model adapter treats a present `:headers` key as a map and rejects
  `:headers nil`; a settings save can legitimately leave optional fields
  nil.  Cleaning them here keeps the adapter's closed schema strict without
  making the settings document unloadable."
  [models]
  (cond-> (or models {})
    (contains? models :connections)
    (update :connections
            (fn [connections]
              (mapv (fn [connection]
                      (cond-> (-> (without-nil-values connection)
                                  (strip-app-keys connection-app-keys))
                        (contains? connection :models)
                        (update :models
                                (fn [entries]
                                  (if (map? entries)
                                    (into {}
                                          (map (fn [[id model]]
                                                 [id (-> (without-nil-values model)
                                                         (strip-app-keys model-app-keys))]))
                                          entries)
                                    (mapv #(-> (without-nil-values %)
                                               (strip-app-keys model-app-keys))
                                          (or entries [])))))))
                    (or connections []))))

    (contains? models :roles)
    (update :roles
            (fn [roles]
              (into {}
                    (keep (fn [[role value]]
                            (when (bound-role? value)
                              [role (without-nil-values value)])))
                    (or roles {}))))))

(defn model-configuration
  "Return the model adapter's section of the application configuration.

  The launch configuration may carry keys the adapter does not accept: the
  shipped default `:models` was the legacy `{:main nil :task nil}` role
  placeholder, and `merge-persisted-config` folds the settings document's
  `:connections`/`:roles` into that same map.  The adapter validates a closed
  top-level schema, so forwarding the launch map unchanged makes every
  configured model look unconfigured and prevents a model port from being
  built.  This projection keeps the fields the adapter owns: connections,
  roles, and the default role."
  [models]
  (clean-model-section
   (select-keys (or models {}) [:connections :roles :default-role])))

(defn merge-persisted-config
  "Merge the persisted model configuration into CONFIG.

  Only `:models` is affected, and it keeps only the model adapter's fields
  (`model-configuration`).  The launch model configuration is the
  instance-level authorization for connection existence:
  `settings/merge-model-config` lets the persisted document override
  launch-declared connections and add UI-created ones, but hides a
  launch-declared connection the current launch configuration no longer
  lists.  The persisted document keeps that connection, so declaring it
  again restores its saved fields.  Process-level launch keys such as
  `:host`, `:port` and `:gateway` keep the values that passed
  `hc.server.app.config/normalize` before the store opened; a persisted
  document must not be able to widen the gateway's origin, host or
  remote-peer policy after validation."
  [config persisted]
  (assoc config :models
         (model-configuration
          (settings/merge-model-config
           (model-configuration (:models config))
           persisted))))

(declare normalize-blocked-error)

(defn- effective-config
  [launch-config persistence]
  (let [{:keys [ok? config error]} (persisted-config persistence)]
    (if ok?
      (merge-persisted-config launch-config config)
      (throw (ex-info "the persisted settings document could not be read"
                      {:recovery-blocked
                       {:status :blocked
                        :error (normalize-blocked-error
                                (assoc error :error (:code error)))
                        :hint
                        "The store opened but its settings document could not be read. Inspect the store or restore a backup."
                        :path (str (:db-path launch-config))
                        :recovery-entry
                        {:action :operator
                         :options [:inspect-directory
                                   :restore-from-backup
                                   :choose-another-path]}}})))))

(def ^:private gateway-limit-keys
  [:max-upload-bytes :max-sente-body :max-message-bytes
   :max-sente-message-bytes :max-state-object-bytes
   :max-state-objects])

(defn gateway-options
  "Return the gateway transport and authorization options from CONFIG.

  This is the single policy map shared by `gateway/start!`, the
  application-owned HTTP routes and `/healthz`.  Only data keys are
  selected; authorization hooks stay code-level.  CONFIG is expected to have
  passed `hc.server.app.config/normalize`, which canonicalizes
  `:allowed-origins` to a set or `:all`, `:trusted-hosts` to a set, and
  `:allow-remote?` to a boolean.  EDN validation rejects `:allow-remote? true`
  because remote peers require a real authorization hook."
  [launch-config]
  (let [gateway (or (:gateway launch-config) {})]
    (reduce (fn [options key]
              (if (some? (get gateway key))
                (assoc options key (get gateway key))
                options))
            {:allowed-origins (or (:allowed-origins gateway) :all)
             :trusted-hosts (or (:trusted-hosts gateway) #{})
             :allow-remote? (get gateway :allow-remote? false)
             :max-pending (or (:max-pending gateway)
                              (get-in config/defaults
                                      [:gateway :max-pending])
                              64)}
            gateway-limit-keys)))

;; ---------------------------------------------------------------------------
;; Per-session runtime

(defn- delegating-shell-board
  "A ShellBoard that forwards to the Owner handle's `:shell-board` port."
  [owner-ref]
  (let [port (fn [] (:shell-board @owner-ref))]
    (reify shell-board/ShellBoard
      (check-eligibility! [_ request]
        ((:check-eligibility! (port)) request))
      (begin-run! [_ request summary]
        ((:begin-run! (port)) request summary))
      (publish-finished! [_ permit result]
        ((:publish-finished! (port)) permit result))
      (publish-uncertain! [_ permit result]
        ((:publish-uncertain! (port)) permit result)))))

(defn- mission-port
  "Pure state-in/state-out mission host seam for the Owner.

  The Owner owns the committed `:mission` state; this port never creates an
  atom and never dispatches.  The Owner commits the returned state and
  dispatches the returned effects."
  []
  {:initial-state mission/initial-state
   :restore mission/restore
   :apply-command mission/apply-command
   :relay-of mission/relay-of
   :board-of mission/board-of
   :current-board mission/current-board
   :tenure mission/tenure
   :nudge-facts mission/nudge-facts
   :board-summary mission/board-summary
   :check-invariants mission/check-invariants})

(defn- last-user-text
  [messages]
  (some (fn [message]
          (when (= :user (:role message))
            (or (:text message) (:content message))))
        (reverse messages)))

(defn- user-texts
  [messages]
  (->> messages
       (keep (fn [message]
               (when (= :user (:role message))
                 (or (:text message) (:content message)))))
       vec))

(defn- optional-materials-read
  "Call a pure materials read when it exists; otherwise report unknown.

  The reads must not advance worldbook counters.  A missing reader is an
  honest `::unknown`, never an empty collection that would claim \"no
  memory/tools\"."
  [sym & args]
  (if-let [f (requiring-resolve sym)]
    (try
      (apply f args)
      (catch clojure.lang.ArityException _
        ;; Older test doubles and deployments expose the two-argument read.
        (try
          (apply f (take 2 args))
          (catch Throwable _ ::unknown)))
      (catch Throwable _ ::unknown))
    ::unknown))

(defn- session-user-texts
  "The last four user texts in SESSION, oldest first.

  Worldbook keyword scanning uses these texts only; it never reads assistant
  turns.  A message without a current version contributes no text."
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (filter #(= :user (:role %)))
       (keep (fn [message]
               (let [text (or (:content message)
                              (:text message)
                              (some-> message :versions last :content))]
                 (when (and (string? text) (not (str/blank? text)))
                   text))))
       (take-last 4)
       vec))

(defn- outbound-context-opts
  "Build the outbound context options for one session.

  All data comes from the live projection; a missing source stays unknown or
  nil.  The worldbook reader is pure and does not consume a turn.  The
  accepted send's `:turn-card-ids` are passed in so the panel reports the
  same per-turn card selection the model receives."
  [{:keys [session materials session-config payload turn-card-ids skills
           temporary-read?]}]
  (let [config (try (when (fn? session-config) (session-config))
                    (catch Throwable _ nil))
        assistant-id (materials-session/resolved-assistant-id session)
        assistant (or (get-in config [:assistants assistant-id])
                      (get-in config [:assistants "default"])
                      session-config/default-assistant)
        user-texts (session-user-texts session)
        mem (optional-materials-read
             'hc.server.materials.session/effective-memories-read
             materials session
             {:budget 2000
              :assistant-id assistant-id
              :temporary-read? temporary-read?})
        wb (optional-materials-read
            'hc.server.materials.session/worldbook-selection-read
            materials session
            {:texts user-texts
             :budget 4000
             :assistant-id assistant-id})
        cards (optional-materials-read
               'hc.server.materials.session/instruction-cards-read
               materials session
               {:turn-card-ids (vec (or turn-card-ids
                                        (:turn-card-ids payload)
                                        []))})
        card-list (cond
                    (and (map? cards) (true? (:ok? cards)))
                    (vec (:cards cards))
                    (vector? cards) cards
                    :else nil)
        card-text (when (seq card-list)
                    (str/join "\n\n"
                              (keep :body-preview card-list)))]
    {:assistant-id assistant-id
     :system (prompts/role-prompt-context)
     :skills skills
     :assistant {:id (or (:id assistant)
                         (:assistant-id assistant)
                         assistant-id)
                 :name (or (:name assistant)
                           (:display-name assistant)
                           :unknown)
                 :revision (or (:revision assistant)
                               (:v assistant)
                               :unknown)}
     :instructions {:session-override? (boolean (seq card-list))
                    :text card-text}
     :memory (if (and (map? mem) (true? (:ok? mem)))
               {:enabled? (:enabled? mem)
                :reason (:reason mem)
                :temporary? (:temporary? mem)
                :provided (mapv (fn [m]
                                  {:id (:id m)
                                   :body (:summary m)
                                   :category (:category m)
                                   :epistemic (:epistemic m)
                                   :scope (:scope m)
                                   :version (:version m)})
                                (or (:provided mem) (:memories mem)))
                :omitted (mapv (fn [m]
                                 {:id (:id m)
                                  :summary (:summary m)
                                  :reason (:reason m)
                                  :chars (:chars m)})
                               (:omitted mem))
                :archived-excluded (:archived-excluded mem)}
               {:enabled? :unknown
                :reason :unknown
                :provided nil
                :omitted nil
                :archived-excluded :unknown})
     :worldbook (if (and (map? wb) (not (false? (:ok? wb))))
                  {:enabled-books (:enabled-books wb)
                   :provided (mapv (fn [p]
                                     {:book-id (:book p)
                                      :entry-id (:entry p)
                                      :reason (:reason p)})
                                   (:provided wb))
                   :hit-not-provided (mapv (fn [p]
                                             {:entry-id (:entry p)
                                              :reason (:reason p)})
                                           (:hit-not-provided wb))}
                  {:enabled-books nil
                   :provided nil
                   :hit-not-provided nil})
     :instruction-cards card-list
     ;; materials does not own the tool surface; the Plan role's tools come
     ;; from the app surface.  tools-snapshot maps read/write conservatively.
     :tools (history-context/tools-snapshot
             [mission-devops/devops-tool-spec])
     :estimates {:tokens :unknown :kind :unknown}}))

(defn- material-injection
  [materials registry-ref session-id request]

  (if-not (and materials @registry-ref
               (materials-session/user-turn-request? request))
    request
    (let [registry @registry-ref
          session-result (try (registry/get-session registry session-id)
                              (catch Throwable _ nil))
          session (when (= :found (:status session-result))
                    (:session session-result))
          messages (:messages (:snapshot request))
          text (last-user-text messages)
          last-user (last (filter #(= :user (:role %)) messages))
          context (materials-session/context-for-turn!
                   materials
                   {:session session
                    :turn-id (or (get-in request [:snapshot :turn-id])
                                 (:call-id request)
                                 (:attempt-id request))
                    :text (or text "")
                    :user-texts (user-texts messages)
                    :memory-budget 2000
                    :worldbook-budget 4000
                    :book-ids nil
                    :exclude-entry-ids []
                    :include-session-cards? true
                    :turn-card-ids (or (:turn-card-ids request)
                                       (get-in request [:snapshot :turn-card-ids])
                                       (:turn-card-ids last-user) [])
                    :instruction-values (or (:instruction-values request)
                                            (get-in request [:snapshot :instruction-values])
                                            (:instruction-values last-user)
                                            {})
                    :temporary-read? (or (:temporary-read? request)
                                         (get-in request [:snapshot :temporary-read?]))})]
      (if (:ok? context)
        (let [injection (:injection context)
              pieces (cond-> []
                       (seq (:instructions injection))
                       (conj (str "Instructions:\n" (:instructions injection)))
                       (seq (:memory injection))
                       (conj (str "Memory:\n" (:memory injection)))
                       (seq (:worldbook injection))
                       (conj (str "Worldbook:\n" (:worldbook injection))))]
          (let [request
                (if (seq (:summary-ids (history/outbound-context session)))
                  (update-in request [:snapshot :messages]
                             (fn [_]
                               (history-context/model-messages
                                (history/outbound-context session))))
                  request)]
            (if (seq pieces)
              (update-in request [:snapshot :messages]
                         (fn [messages]
                           (into [{:role :system
                                   :text (str/join "\n\n" pieces)}]
                                 messages)))
              request)))
        (throw (ex-info "materials context refused this turn"
                        {:code :MATERIALS_CONTEXT_REFUSED
                         :error (:error context)}))))))

(def ^:private model-snapshot-keys
  #{:role :provider :connection :model :messages :tools :parameters
    :temperature :top-p :top-k :max-tokens :max-output-tokens :stop
    :frequency-penalty :presence-penalty :tool-choice :response-format
    :required-capabilities :stream? :call-id :attempt-id})

(def ^:private model-message-keys
  {:system #{:role :text}
   :user #{:role :text :attachments :quotes :citations}
   :assistant #{:role :text :thinking :tool-calls :pair-hint :pair-hint-id
                :meta}
   :tool #{:role :tool-call-id :name :text :error? :pair-hint :pair-hint-id
           :meta}})

(defn- sanitize-message
  [message]
  (let [role (keyword (:role message))
        allowed (get model-message-keys role #{:role :text})]
    (select-keys message allowed)))

(defn- sanitize-model-request
  [request stream?]
  (update request :snapshot
          (fn [snapshot]
            (-> (select-keys snapshot model-snapshot-keys)
                (assoc :stream? stream?)
                (update :messages #(mapv sanitize-message (or % [])))))))

(defn- provider-role
  [role]
  (case (keyword role)
    (:steward :plan :reviewer :main) :main
    (:plan-worker :dedicated-worker :worker :holder :task) :task
    :main))

(defn- model-supports-tools?
  [config]
  (let [role (get-in config [:models :roles :main])
        connection-id (:connection role)
        model-id (:model role)
        connection (first (filter #(= connection-id (:id %))
                                  (get-in config [:models :connections])))
        model (first (filter #(= model-id (if (map? %) (:id %) (str %)))
                             (:models connection)))]
    (mission-model/supports-tools? (:capabilities model))))

(defn- tools-capability-entry
  "Return the `:tools` entry when CAPABILITIES carries a tools fact.

  A map entry with a nil value is not a fact; `false` and `:unknown` are.
  Set and sequence capability snapshots only have a fact when they name
  `:tools`."
  [capabilities]
  (cond
    (map? capabilities)
    (let [entry (or (find capabilities :tools)
                    (find capabilities "tools"))]
      (when (and entry (some? (val entry)))
        entry))

    (set? capabilities)
    (when (contains? capabilities :tools)
      [:tools true])

    (sequential? capabilities)
    (when (some #(= :tools %) capabilities)
      [:tools true])

    :else nil))

(defn- current-tools-supported
  "Return the current tool fact for BINDING as one merged answer.

  A binding fixes model attribution; it must not freeze a capability
  declaration the operator later edits.  The live model entry is authoritative
  when it carries a `:tools` fact, including an explicit no.  When the live
  entry has no tools fact (a probe or list that cannot see tool support), the
  binding's copied declaration is the fallback, then the startup fallback."
  [current-config binding fallback]
  (let [model (try
                (when (and (fn? current-config) (map? binding))
                  (get-in (current-config)
                          [:connections (:connection-id binding)
                           :models (:model-entry-id binding)]))
                (catch Throwable _ nil))
        live-entry (when (map? model)
                     (tools-capability-entry (:capabilities model)))
        binding-supported (when (and (map? binding)
                                     (contains? binding :capabilities))
                            (mission-model/supports-tools?
                             (:capabilities binding)))]
    (cond
      (some? live-entry)
      (mission-model/supports-tools? (:capabilities model))

      (some? binding-supported)
      binding-supported

      :else fallback)))

(defn- request-model-binding
  "Return the binding identity frozen into REQUEST, when it names both halves.

  A central main-role turn and its Nudge carry the session's selected model
  identity.  The gateway must read the capability fact for that identity, not
  for a session selection the operator changed after the turn froze."
  [request]
  (let [snapshot (:snapshot request)]
    (when (and (some? (:connection snapshot))
               (some? (:model snapshot)))
      {:connection-id (:connection snapshot)
       :model-entry-id (:model snapshot)})))

(defn- live-tools-supported
  "Return a `[request session]` option that reads the session's committed
  model binding and the current capability fact at call time.

  The fallback is the restored binding, or the startup configuration before
  any binding exists; it covers the brief window before the Owner handle is
  published.  A live settings save or `:session/select-model` must reach the
  next request; freezing the fact at Owner creation would send tools to a
  tools-less model, or hide tools from a model that declares them after the
  session was created.

  Task-role requests state their own tool contract and are not governed by
  the main binding; they answer true so the pair-hint projection and the
  wire request agree.  The adapter still enforces their explicit
  `:required-capabilities`."
  ([owner-ref fallback]
   (live-tools-supported owner-ref fallback nil))
  ([owner-ref fallback current-config]
   (fn [request _session]
     (let [role (provider-role (or (get-in request [:snapshot :model-role])
                                   (get-in request [:snapshot :role])))]
       (if (= :main role)
         (let [owner (when owner-ref @owner-ref)
               snapshot (when owner ((:snapshot owner)))
               binding (or (request-model-binding request)
                           (get-in snapshot [:state :session :model-binding]))]
           (if (map? binding)
             (current-tools-supported current-config binding fallback)
             fallback))
         true)))))

(defn- resolve-tools-supported
  "Resolve the gateway's tools-supported option for one request.

  The option states the main-model fact.  Task-role requests (workers,
  holders, and the Plan dedicated worker) keep their own explicit
  `:required-capabilities [:tools]` contract: they genuinely need tools, and
  the adapter must fail explicitly when the task model cannot honour that.
  Applying the main binding's fact to them would silently strip the tool
  schema and turn a real configuration error into a text-only worker turn."
  [value request]
  (let [role (provider-role (or (get-in request [:snapshot :model-role])
                                (get-in request [:snapshot :role])))]
    (if (= :main role)
      (if (fn? value)
        (boolean (value request nil))
        (boolean value))
      true)))

(defn- gateway-error
  "Preserve one asynchronous gateway failure as an Owner-visible cause."
  [^Throwable t]
  (let [data (ex-data t)
        kind (or (:kind data) (:error/kind data) :model-start-failed)]
    (merge {:kind kind
            :message (or (ex-message t) (str t))
            :class (.getName (class t))}
           (dissoc data :error/kind)
           {:category (or (:category data) :permanent)})))

(defn- binding-availability-fn
  "Return a `[request] -> availability` reader over the live settings.

  The binding identity is frozen into the request; the connection and model
  state is read at call time.  A settings save that disables the only
  connection must explain the refused send instead of reporting a generic
  missing port (root AGENTS.md §7.7)."
  [session-config]
  (fn [request]
    (try
      (let [binding (request-model-binding request)]
        (when (and (map? binding) (fn? session-config))
          (let [config (session-config)
                connections (:connections config)]
            (snapshot/binding-availability
             connections
             (into {}
                   (map (fn [[id connection]]
                          [id (vec (vals (:models connection)))]))
                   connections)
             binding))))
      (catch Throwable _ nil))))

(defn- model-unavailable-error
  "Build the refusal for a request that has no model port.

  Root AGENTS.md §7.7 requires the operator-facing cause, not a generic
  \"not configured\" sentence.  The code stays `:MODEL_NOT_CONFIGURED`; the
  availability fact names the binding that became unusable."
  [availability]
  (let [base {:code :MODEL_NOT_CONFIGURED
              :kind :model-not-configured
              :category :permanent
              :disposition :fail
              :retryable? false
              :remedy :select-model}
        detail (case availability
                 :connection-disabled
                 {:reason :connection-disabled
                  :message (str "The model connection this session uses is "
                                "disabled; select a replacement connection.")}
                 :connection-removed
                 {:reason :connection-removed
                  :message (str "The model connection this session used was "
                                "removed; select a replacement connection.")}
                 :model-removed
                 {:reason :model-removed
                  :message (str "The selected model is no longer available; "
                                "select a replacement model. Earlier answers "
                                "remain readable.")}
                 :disabled
                 {:reason :model-disabled
                  :message (str "The selected model is disabled; select a "
                                "replacement model.")}
                 :auth-expired
                 {:reason :auth-expired
                  :message (str "The account authorization for this connection "
                                "expired; reconnect it or select another "
                                "connection.")}
                 {:reason :model-not-configured
                  :message "no model port is configured"})]
    (cond-> (merge base detail)
      availability (assoc :availability availability))))

(defn- model-gateway
  ([model-port-ref materials registry-ref session-id stream? browser-ref
    search-contexts search-ref diagnostics-ref tools-supported?]
   (model-gateway model-port-ref materials registry-ref session-id stream?
                  browser-ref search-contexts search-ref diagnostics-ref
                  tools-supported? nil))
  ([model-port-ref materials registry-ref session-id stream? browser-ref
    search-contexts search-ref diagnostics-ref tools-supported?
    model-availability-fn]
   (let [seen-turns (atom #{})]
     {:start! (fn [request deliver!]
               (let [program-role (get-in request [:snapshot :program-role])
                     mission-role (get-in request [:snapshot :mission-role])
                     tools? (resolve-tools-supported tools-supported? request)
                     tools-unavailable?
                     ;; A `:chat?` turn is an Owner-declared plain-text
                     ;; answer: it never carries the program tool, so a
                     ;; tools-less main model is a legal target and must not
                     ;; be refused here.
                     (and (mission-model/program-role-requires-tools?
                           program-role)
                          (not (true? (get-in request [:snapshot :chat?])))
                          (not tools?))
                     model-port @model-port-ref
                     search-intent (or (:search request)
                                       (get-in request [:snapshot :search]))
                     search-context (get @search-contexts session-id)
                     call-id (or (:call-id request) (:attempt-id request))
                     first? (and call-id
                                 (not (contains? @seen-turns call-id)))
                     request (if first?
                               (material-injection materials registry-ref
                                                   session-id request)
                               request)
                     ;; Mark the turn only after material injection returns.
                     ;; A refused injection has not consumed the turn; a
                     ;; later retry with the same call-id must be allowed to
                     ;; inject the materials instead of silently running
                     ;; without them.  A successful injection is already
                     ;; idempotent by turn-id, so a retry may safely skip it.
                     _ (when first? (swap! seen-turns conj call-id))
                     request (prompts/decorate-request
                              request {:tools-supported? tools?})
                     request (update-in request [:snapshot :role]
                                        provider-role)
                     request (sanitize-model-request request stream?)
                     user-text (last-user-text
                                (:messages (:snapshot request)))
                     state (atom {:cancelled? false :model-handle nil})
                     cancel! (fn []
                               (swap! state assoc :cancelled? true)
                               (when-let [handle (:model-handle @state)]
                                 (try
                                   (model-contract/cancel-request!
                                    model-port handle)
                                   (catch Throwable _ nil))))
                     worker
                     (fn []
                       (try
                         (if tools-unavailable?
                           (do
                             (swap! search-contexts dissoc session-id)
                             (deliver!
                              {:event :failed
                               :error
                               {:kind :model-tools-unavailable
                                :code :MODEL_TOOLS_UNAVAILABLE
                                :category :permanent
                                :disposition :fail
                                :retryable? false
                                :message
                                (str "The selected main model does not declare "
                                     ":tools; Plan and Reviewer require their "
                                     "program tool. Select a model with tools "
                                     "and retry.")
                                :program-role
                                program-role
                                :mission-role
                                mission-role
                                :remedy :select-model}}))
                           (let [intent-result
                               (when (and first? search-intent
                                          (not (:cancelled? @state)))
                                 (search-integration/run-intent!
                                  @search-ref search-intent
                                  {:text user-text
                                   :turn-id (or (:turn-command-id request)
                                                (get-in request
                                                        [:snapshot
                                                         :turn-command-id])
                                                call-id)
                                   :session-id session-id
                                   :cancelled? #(:cancelled? @state)
                                   :diagnostics @diagnostics-ref}))
                               search-failed? (and intent-result
                                                   (false? (:continue?
                                                            intent-result)))
                               _ (when search-failed?
                                   (swap! search-contexts dissoc session-id))
                               intent-context (:context intent-result)
                               _ (when (and first? search-intent
                                            (or (not search-failed?)
                                                (= :auto
                                                   (some-> (:mode search-intent)
                                                           name
                                                           keyword))))
                                   (if intent-context
                                     (swap! search-contexts assoc
                                            session-id
                                            (assoc intent-context
                                                   :text user-text))
                                     (swap! search-contexts dissoc
                                            session-id)))
                               matched-context
                               (or intent-context
                                   (when (and search-context
                                              (= user-text
                                                 (:text search-context)))
                                     search-context))
                               request (if matched-context
                                         (search-ctx/inject-request
                                          request
                                          (dissoc matched-context :text))
                                         request)
                               request
                               (if-let [browser-service
                                        (when tools? @browser-ref)]
                                 (let [snapshot (browser/snapshot
                                                 browser-service)]
                                   (if (get-in snapshot
                                               [:permission :enabled?])
                                     (update-in request [:snapshot :tools]
                                                (fn [tools]
                                                  (vec (concat
                                                        (or tools [])
                                                        (browser/tool-specs)))))
                                     request))
                                 request)]
                           (cond
                             (:cancelled? @state)
                             (do
                               (swap! search-contexts dissoc session-id)
                               (deliver!
                                {:event :cancelled
                                 :error {:kind :cancelled
                                         :message "attempt cancelled"}}))

                             search-failed?
                             (deliver!
                              {:event :failed
                               :kind :search-required-failed
                               :category :permanent
                               :attempt-id (:attempt-id request)
                               :call-id call-id
                               :search-error? true
                               :error (let [error (:error intent-result)]
                                        (assoc (or error {})
                                               :code (or (:code error)
                                                         :SEARCH_REQUIRED_FAILED)
                                               :kind :search-required-failed
                                               :category :permanent
                                               :disposition :fail
                                               :message (or (:message error)
                                                            "required web search failed")))})

                             :else
                             (if-not model-port
                               (do
                                 (swap! search-contexts dissoc session-id)
                                 (let [availability
                                       (when model-availability-fn
                                         (model-availability-fn request))
                                       error
                                       (model-unavailable-error availability)]
                                   (throw (ex-info (:message error) error))))
                               (let [deliver'
                                     (if matched-context
                                       (search-ctx/wrap-deliver!
                                        deliver!
                                        (dissoc matched-context :text))
                                       deliver!)
                                     deliver''
                                     (fn [event]
                                       (let [event
                                             (cond-> event
                                               (and (= :completed
                                                       (:event event))
                                                    intent-result
                                                    (not search-failed?)
                                                    (or (false?
                                                         (:ok? intent-result))
                                                        (:must-search-failed?
                                                         intent-result)))
                                               (assoc :search-degraded? true)

                                               ;; The model may stream the
                                               ;; answer and finish with an
                                               ;; empty terminal text, so
                                               ;; `wrap-deliver!` cannot
                                               ;; attach citations.  Carry
                                               ;; the bounded public context
                                               ;; to the Owner and then to
                                               ;; RUN_FINISHED: the client
                                               ;; renders its source cards
                                               ;; and numbering from these
                                               ;; facts, not from a citation
                                               ;; map that never arrived.
                                               (and matched-context
                                                    (contains? #{:completed
                                                                 :failed
                                                                 :cancelled}
                                                               (:event event)))
                                               (assoc :search-context
                                                      (search-ctx/public-context
                                                       matched-context)))
                                             ;; The receiver result must survive
                                             ;; this wrapper: the model port
                                             ;; treats `{:ok? false}` as a
                                             ;; refusal and terminates the
                                             ;; attempt.  Swallowing it would
                                             ;; hide a lost event.
                                             result
                                             (try
                                               (deliver' event)
                                               (catch Throwable t
                                                 (deliver!
                                                  (assoc event
                                                         :event :failed
                                                         :error
                                                         {:code
                                                          :SEARCH_DELIVERY_FAILED
                                                          :message
                                                          (or (.getMessage t)
                                                              (str t))}))))]
                                         (when (contains? #{:completed
                                                            :failed
                                                            :cancelled}
                                                          (:event event))
                                           (swap! search-contexts dissoc
                                                  session-id))
                                         result))
                                     handle
                                     (model-contract/start-request!
                                      model-port
                                      request
                                      (reify model-contract/Receiver
                                        (deliver! [_ event]
                                          (deliver'' event))))]
                                 (swap! state assoc :model-handle handle)
                                 (when (:cancelled? @state)
                                   (model-contract/cancel-request!
                                    model-port handle))
                                 handle)))))
                         (catch Throwable t
                           (swap! search-contexts dissoc session-id)
                           (deliver!
                            (if (:cancelled? @state)
                              {:event :cancelled
                               :error {:kind :cancelled
                                       :message "attempt cancelled"}}
                              {:event :failed
                               :error (gateway-error t)})))))]
                 (future (worker))
                 {:handle :model-gateway-pending
                  :cancel cancel!}))
     :cancel! (fn [handle]
                (when-let [cancel (:cancel handle)]
                  (cancel)))})))

(defn- session-port
  "Owner-facing session metadata port.  The Owner gate is the only command
  epoch; this port applies the business transition after that gate accepts.

  SESSION-CONFIG is `(fn [] session-config)` and returns the committed model
  configuration at call time.  It is resolved once per command, never
  captured at start: a live settings save must reach the next
  `:session/select-model` without a restart.  Existing bindings and run
  snapshots stay untouched because only the current command reads it.

  The persistence functions are the Owner's business commit barrier.  The
  Owner calls `:commit-session!` before it reports an accepted metadata
  receipt and `:reconcile-session!` before it accepts work after a restart.
  A purge reads the committed session record; it must already hold the
  accepted delete."
  [clock session-config owner-ref persistence outbound-context]
  {:apply-command (fn [session payload]
                    (sessions/apply-command
                     session payload
                     {:now (clock)
                      :config (session-config)
                      :delegate (fn [s request opts]
                                  (history/apply-command s request opts))
                      :outbound-context (or outbound-context
                                            history/outbound-context)}))
   :confirm-draft-saved (fn [session request]
                          (sessions/confirm-draft-saved-port
                           session (assoc request :now (clock))))
   :commit-session! (fn [previous session now]
                      (sessions/commit-owner-session!
                       persistence previous session (or now (clock))))
   :reconcile-session! (fn [session]
                         (sessions/reconcile-owner-session!
                          persistence session (clock)))})

(defn- session-run-port
  "Bridge explicit history regeneration runs to Owner model calls."
  [clock]
  {:start (fn [session effect opts]
            (history-run-port/start session effect
                                    (assoc opts :now (clock))))
   :accept (fn [session request]
             (history-run-port/accept session
                                      (assoc request :now (clock))))
   :fail (fn [session effect error]
           (history-run-port/fail session effect error
                                  {:now (clock)}))})

(defn- owner-command-epoch
  [owner]
  (get-in ((:snapshot owner)) [:state :commands :command-epoch]))

(defn- submit-owner-command!
  "Submit one trusted Owner command and return its synchronous receipt."
  [owner command-id payload]
  (try
    ((:submit-command! owner)
     {:command-id command-id
      :command-epoch (owner-command-epoch owner)
      :payload payload})
    (catch Throwable t
      {:status :error
       :error {:code :OWNER_COMMAND_FAILED
               :message (or (ex-message t) (str (class t)))}})))

(defn- record-plan-draft-error!
  [system-ref error]
  (when system-ref
    (swap! system-ref update :plan-draft-errors (fnil conj []) error)))

(defn- draft-claim-for
  [result path]
  (some (fn [effect]
          (when (and (= :draft/claimed (:type effect))
                     (= path (:path effect)))
            effect))
        (:effects result)))

(defn- release-plan-draft-claim!
  [owner result path]
  (when-let [claim (draft-claim-for result path)]
    (submit-owner-command!
     owner
     (str "plan-draft-release-" (random-uuid))
     {:kind :plan/release-draft
      :path path
      :request-id (:request-id claim)})))

(defn- sync-plan-drafts!
  "Submit Plan draft commands for the applied `plans/<name>.md` files.

  Only a successful `:plan` program result is synced.  The content is read
  from the same workspace root that applied the file.  Returns RESULT
  unchanged so the Owner terminal path is not altered.  TENURE-ID is the
  Plan tenure pinned on the tool call; the mission refuses a draft write
  from a retired tenure instead of replacing the successor's draft."
  [owner role root tenure-id result system-ref]
  (when (and owner
             (= :plan role)
             (= :succeeded (:status result)))
    (doseq [path (mission-control/applied-draft-paths result)]
      (let [file (java.io.File. ^String root ^String path)]
        (try
          (if (.isFile file)
            (let [receipt
                  (submit-owner-command!
                   owner
                   (str "plan-draft-" (random-uuid))
                   (mission-control/draft-command
                    path
                    (slurp file :encoding "UTF-8")
                    tenure-id))]
              (when-not (= :accepted (:status receipt))
                (release-plan-draft-claim! owner result path)
                (record-plan-draft-error!
                 system-ref
                 {:code :PLAN_DRAFT_COMMAND_REFUSED
                  :path path
                  :root root
                  :receipt receipt})))
            (do
              (release-plan-draft-claim! owner result path)
              (record-plan-draft-error!
               system-ref
               {:code :PLAN_DRAFT_FILE_MISSING
                :path path
                :root root})))
          (catch Throwable t
            (release-plan-draft-claim! owner result path)
            (record-plan-draft-error!
             system-ref
             {:code :PLAN_DRAFT_SYNC_FAILED
              :path path
              :root root
              :message (or (ex-message t) (str (class t)))}))))))
  result)

(defn- control-fallback
  "Handle program control primitives owned by the application.

  `:review` is the trusted Reviewer path: it submits an Owner
  `:review/submit` command without the browser operator marker.  `:devops`
  keeps the existing executor path.  Both primitives receive the program
  control context's pinned `:tenure-id` and `:call-id`; a model's primitive
  arguments name only intent, never the lane tenure or worker call identity.
  Other primitives are unsupported."
  [owner-ref devops-exec-ref]
  (fn [ctx {:keys [primitive request]}]
    (case primitive
      :devops
      (if-let [exec @devops-exec-ref]
        (let [call-id (or (:call-id ctx) (:call-id request))
              tenure-id (or (:tenure-id ctx) (:tenure-id request))]
          ((:run exec)
           {:kind :devops
            :call-id call-id
            :request (cond-> (assoc request
                                    :kind :devops
                                    :call-id call-id)
                       tenure-id (assoc :tenure-id tenure-id))}))
        {:ok? false
         :error {:code :DEVOPS_NOT_READY
                 :message "devops executor is not initialized"}})

      :review
      (if-let [owner @owner-ref]
        (submit-owner-command!
         owner
         (str "review-" (or (:call-id ctx) (random-uuid)))
         (mission-control/review-command ctx request))
        {:ok? false
         :error {:code :OWNER_NOT_READY
                 :message "review needs a started Owner"}})

      {:ok? false
       :error {:code :UNSUPPORTED_OPERATION
               :message "control primitive is not owned by the workspace bridge"
               :details {:primitive primitive}}})))

(defn- worktree-evaluate-factory
  "Build the program evaluator for one fleet worktree runtime.

  The worktree gets its own shell runner over the swarm board and write gate,
  so a holder's `(call ...)` runs inside that worktree.  The main workspace
  shell is a separate runner and is not shared."
  [config]
  (fn [ws-runtime]
    (let [kernel (:kernel ws-runtime)
          shell-board
          (if (and (:execute-board! ws-runtime)
                   (:board-snapshot ws-runtime))
            (shell-board/owner-board (:execute-board! ws-runtime)
                                     (:board-snapshot ws-runtime))
            (shell-board/swarm-board kernel))
          runner (shell/runner
                  {:root (:root ws-runtime)
                   :gate (:gate ws-runtime)
                   :board shell-board
                   :artifact-dir (:artifact-root config)})
          prt (program/runtime
               {:workspace (workspace/workspace-port ws-runtime)
                :control (workspace/control-port ws-runtime)
                :call (program/shell-call-port
                       {:runner runner
                        :term-fn
                        (fn [_ctx]
                          (let [snapshot
                                (if (and (:execute-board! ws-runtime)
                                         (:board-snapshot ws-runtime))
                                  ((:board-snapshot ws-runtime))
                                  (swarm/snapshot kernel))]
                            (get-in snapshot [:token-slot :term-id])))
                        :context-fn
                        (fn [ctx request]
                          {:claim-id (:claim-id ctx)
                           :actor-id (:agent-id ctx)
                           :task-id (:task-id ctx)
                           :request-id (or (:call-id ctx)
                                           (:attempt-id ctx))})})
                :call-stop!
                (fn [_ctx operation-id]
                  (shell/request-stop!
                   runner {:request-id operation-id}))
                :clock #(System/currentTimeMillis)})]
      (partial program/evaluate-program! prt))))

(defn- program-role-for-attempt
  "Map a fleet attempt to the program role it must run under.

  A swarm holder is a normal Clojure worker with the holder capability; it
  is not the Plan dedicated worker.  The shell board still enforces token,
  claim, task, and W dynamically."
  [attempt]
  (if (= :holder (:role attempt))
    :clj-worker
    (:role attempt)))

(defn- extension-session-scope
  "Trusted extension scope for one session, derived from committed state.

  Role is derived from the session capability scope and fails closed to
  `:viewer`; the browser never supplies it."
  [registry-ref session-id]
  (let [session (when (and registry-ref session-id)
                  (try
                    (let [result (registry/get-session @registry-ref session-id)]
                      (when (= :found (:status result)) (:session result)))
                    (catch Throwable _ nil)))
        caps (cond
               (set? (:capability-scope session)) (:capability-scope session)
               (map? (:capability-scope session))
               (set (or (get-in session [:capability-scope :mcp]) []))
               :else #{})
        role (cond
               (contains? caps :mcp/destructive) :admin
               (contains? caps :mcp/write) :operator
               :else :viewer)]
    {:role role
     :session-id session-id
     :assistant-id (:assistant-id session)
     :assistant-servers (set (or (:mcp-servers session) []))
     :session (or (:mcp-session-scope session) {:mode :follow})
     :capability-limit (when (seq caps) caps)}))

(defn- pair-hint-observations
  "Steward Pair Hint observations for one session Owner.

  The board is the Owner's own snapshot.  Fleet is a bounded summary from
  the application fleet service.  A fleet read failure is skipped; it must
  not remove the board observation or fail the model request."
  [owner-ref fleet-ref]
  (fn [_request _session]
    (let [board (when-let [o @owner-ref]
                  (get-in ((:snapshot o)) [:state :board]))
          fleet-observation
          (try
            (when-let [service @fleet-ref]
              (some-> (fleet/observation-summary service)
                      (assoc :kind :delta)))
            (catch Throwable _ nil))]
      (cond-> {}
        board
        (assoc :board
               {:observation
                {:kind :snapshot
                 :board-version (or (:board-version board) 0)
                 :snapshot board}
                :opts {:writer? true}})

        fleet-observation
        (assoc :fleet
               {:observation fleet-observation
                :opts {}})))))

(declare shell-stop-terminal program-stop-terminal program-terminal-message)

(def ^:private owner-broadcast-types
  "Owner advisory effects that must reach every AG-UI subscriber as CUSTOM."
  #{:hc/ask :hc/ask-resolved :worker/program-result})

(defn- owner-event-name
  "Wire name of a namespaced Owner effect: `:hc/ask` -> `\"hc/ask\"`.

  `name` alone drops the namespace, and the client dispatches CUSTOM events
  on the full wire name; `\"ask\"` would fall through to the unhandled-event
  notice."
  [type]
  (if-let [ns (namespace type)]
    (str ns "/" (name type))
    (name type)))

(defn- forward-owner-event!
  "Broadcast one Owner advisory effect to every live AG-UI subscription.

  The Owner starts before the AG-UI gateway exists, and this runs on the
  Owner mailbox, so the gateway is resolved from the live system map on each
  call.  The model gateway used for this session has a different shape: it
  has no feed, so broadcasting through it silently dropped every CUSTOM
  event (the catch below hid the shape error)."
  [system-ref session-id event]
  (when (contains? owner-broadcast-types (:type event))
    (when-let [gw (:gateway @system-ref)]
      (try
        (gateway/broadcast!
         gw
         {:type "CUSTOM"
          :name (owner-event-name (:type event))
          :value (assoc event
                        :thread-id session-id
                        :session-id (or (:session-id event) session-id))})
        (catch Throwable _ nil)))))

(defn- applied-compression-set
  "The session context's `:applied` compression records in id order, plus a
  stable signature.

  Applying or undoing a compression rewrites the outbound message prefix.
  The signature changes on either transition and is stable while the
  applied set is unchanged, so it serves as the Pair Hint `:compaction`
  boundary id and as the consumed cursor stored in the Pair Hint session."
  [context]
  (let [compressions (:compressions context)
        applied (->> (if (map? compressions) compressions {})
                     vals
                     (filter #(= :applied (:state %)))
                     (map #(select-keys % [:compression-id
                                          :applied-at
                                          :updated-at]))
                     (sort-by :compression-id))]
    {:applied applied
     :signature (pair-hint/digest {:compressions applied})}))

(defn- compaction-boundary
  "Emit a `:compaction` Pair Hint boundary when the applied compression set
  changed since the Pair Hint session last consumed one.

  Without the boundary the prefix chain rejects the rewritten request
  (`PAIR_HINT_ANCHOR_MISSING` or `PREFIX_DRIFT`) and the model call never
  dispatches.  The consumed signature lives in the Pair Hint session under
  `:compaction-cursor` and is written back by `project-request`.  A nil
  cursor with an empty applied set emits nothing: there is no rewritten
  prefix to protect yet."
  [owner-ref pair-session]
  (when-let [o @owner-ref]
    (let [{:keys [applied signature]}
          (applied-compression-set
           (get-in ((:snapshot o)) [:state :session :context]))
          cursor (:compaction-cursor pair-session)]
      (when (if (nil? cursor)
              (seq applied)
              (not= signature cursor))
        {:kind :compaction :id signature}))))

(defn- supersede-chain
  "Chain boundary facts through `:supersedes`, first element outermost.

  `project-request` merges only one pending boundary; when a Scope
  boundary, a compaction boundary, and a pending board clean-break coincide
  in one request window, chaining them keeps every durable fact instead of
  letting the first one silently discard the rest."
  [boundaries]
  (let [bs (vec (remove nil? boundaries))]
    (when (seq bs)
      (reduce (fn [inner outer] (assoc outer :supersedes inner))
              (peek bs)
              (reverse (pop bs))))))

(defn- pair-request-boundary
  "Boundary facts for one Pair Hint projection: the pending Scope fact, an
  applied-compression change, and any pending board clean-break."
  [owner-ref pair-session]
  (supersede-chain
   [(when-let [o @owner-ref]
      (some-> (scope-runtime/pending-boundary
               (:scope (:state ((:snapshot o))))
               (:scope-boundary pair-session))
              pair-hint/scope-boundary))
    (compaction-boundary owner-ref pair-session)
    (:pending-boundary pair-session)]))

(defn- session-owner-factory
  [{:keys [store persistence config model-port-ref materials-ref registry-ref
           mission-port shared-gate owners notify-ref browser-services
           search-contexts search-ref diagnostics-ref system-ref fleet-ref
           session-config extensions-ref artifacts-ref]}]
  (fn [{:keys [session-id]}]
    (let [materials @materials-ref
          extensions @extensions-ref
          owner-ref (atom nil)
          devops-exec-ref (atom nil)
          board-snapshot (fn []
                           (when-let [o @owner-ref]
                             ((:board-snapshot o))))
          execute-board (fn
                          ([command] ((:execute-board! @owner-ref) command))
                          ([command timeout-ms]
                           ((:execute-board! @owner-ref) command timeout-ms))
                          ([command timeout-ms opts]
                           ((:execute-board! @owner-ref) command timeout-ms
                            opts)))
          shell-board (shell-board/owner-board execute-board board-snapshot)
          shell-runner (shell/runner {:root (:workspace-root config)
                                      :gate shared-gate
                                      :board shell-board
                                      :artifact-dir (:artifact-root config)})
          read-only-shell-runner
          (shell/read-only-runner {:root (:workspace-root config)
                                   :gate shared-gate
                                   :artifact-dir (:artifact-root config)})
          holder-shell-call
          (program/shell-call-port
           {:runner shell-runner
            ;; The host resolves the live holder term; a worker never supplies
            ;; it.  A non-holder still fails the board's holder check.
            :term-fn (fn [_ctx]
                       (get-in (board-snapshot) [:token-slot :term-id]))
            :context-fn
            (fn [ctx request]
              {:claim (:claim-id ctx)
               :cwd "."
               :purpose (:command request)})})
          dedicated-shell-call
          (program/shell-call-port
           {:runner read-only-shell-runner
            :context-fn
            (fn [_ctx request]
              {:cwd "."
               :purpose (or (:purpose request)
                            (when (string? (:command request))
                              (:command request))
                            "Plan dedicated worker shell")})})
          evaluate-ref (atom nil)
          program-on-terminal
          (fn [result]
            ;; `cancel!` may defer the terminal while a call is in flight.
            ;; The runtime calls this exactly once when the real program
            ;; ends; a parked program has no executor task left to post it.
            ;; Only cancellation terminals need this late channel.
            (when (true? (:cancel-requested? result))
              (when-let [o @owner-ref]
                (when-let [message (program-terminal-message result)]
                  ((:submit! o) message)))))
          workspace-runtime
          (workspace/make-runtime
           {:root (:workspace-root config)
            :kernel nil
            :write-gate shared-gate
            :execute-board! execute-board
            :board-snapshot board-snapshot
            :evaluate (fn [request] (@evaluate-ref request))})
          program-runtime
          (program/runtime
           {:workspace (workspace/workspace-port workspace-runtime)
            :control (workspace/control-port workspace-runtime
                                             (control-fallback
                                              owner-ref devops-exec-ref))
            ;; The Plan dedicated worker has no board/token; it gets the
            ;; separate best-effort read-only shell path.  Swarm workers keep
            ;; the holder/token/claim board path unchanged.
            :call (fn [ctx request]
                    (if (contains? #{:dedicated-worker :plan-worker}
                                   (:role ctx))
                      (dedicated-shell-call ctx request)
                      (holder-shell-call ctx request)))
            :call-stop!
            (fn [ctx operation-id]
              (shell/request-stop!
               (if (contains? #{:dedicated-worker :plan-worker}
                              (:role ctx))
                 read-only-shell-runner
                 shell-runner)
               {:request-id operation-id}))
            :on-terminal program-on-terminal
            :clock #(System/currentTimeMillis)})
          _ (reset! evaluate-ref (partial program/evaluate-program!
                                          program-runtime))
          worktree-runtimes (atom {})
          runtime-entry
          (fn [root]
            (let [root (str (or root (:workspace-root config)))]
              (if (= root (str (:workspace-root config)))
                {:runtime workspace-runtime :program program-runtime}
                (or (get @worktree-runtimes root)
                    (let [eval-ref (atom nil)
                          rt (workspace/make-runtime
                              {:root root
                               :kernel nil
                               :write-gate (wg/write-gate)
                               :execute-board! execute-board
                               :board-snapshot board-snapshot
                               :evaluate (fn [request] (@eval-ref request))})
                          prt (program/runtime
                               {:workspace (workspace/workspace-port rt)
                                :control (workspace/control-port
                                          rt
                                          (control-fallback
                                           owner-ref devops-exec-ref))
                                :on-terminal program-on-terminal
                                :clock #(System/currentTimeMillis)})]
                      (reset! eval-ref (partial program/evaluate-program! prt))
                      (let [entry {:runtime rt :program prt}]
                        (swap! worktree-runtimes assoc root entry)
                        entry))))))
          browser-service
          (if-let [endpoint (get-in config [:browser :endpoint])]
            (try
              (let [port (browser/connect! {:endpoint endpoint})
                    service (browser/service
                             {:port port
                              :session-id session-id
                              :screenshot-store
                              (browser/ref-screenshot-store
                               (fn [in opts]
                                 (blob-store/put-stream! (:blob-root config) in opts))
                               (fn [id _opts]
                                 (when (and id store)
                                   (storage/manage-blob-refs!
                                    store
                                    {:partition (str "browser/" session-id)
                                     :op :add
                                     :refs [{:id id
                                             :kind :browser-screenshot
                                             :session-id session-id
                                             :at (System/currentTimeMillis)}]}))))
                              :publish! (fn [_event]
                                          (when-let [f @notify-ref] (f)))})]
                (swap! browser-services assoc session-id service)
                service)
              (catch Throwable t
                (swap! system-ref update :browser-errors
                       (fnil assoc {}) session-id
                       {:code :BROWSER_CONNECT_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            ;; No CDP endpoint: keep the permission/snapshot surface so the
            ;; workbench can render the address bar and the enable control.
            ;; Page effects return :browser-not-configured; the model tool
            ;; surface is not registered for this service.
            (let [service (browser/offline-service
                           {:session-id session-id
                            :publish! (fn [_event]
                                        (when-let [f @notify-ref] (f)))})]
              (swap! browser-services assoc session-id service)
              service))
          shell-exec (executors/shell-executor shell-runner board-snapshot)
          workspace-exec
          (fn [effect]
            (let [root (or (get-in effect [:request :workspace])
                           (:workspace-root config))
                  entry (runtime-entry root)]
              ((executors/workspace-executor
                (partial workspace/execute! (:runtime entry))) effect)))
          tool-exec
          (fn [effect]
            (let [request (:request effect)
                  root (or (:workspace request) (:workspace-root config))
                  fleet-service @fleet-ref
                  fleet-runtimes (when fleet-service
                                   (fleet/worktree-runtimes fleet-service))
                  owner @owner-ref
                  role (when owner
                         (get-in ((:snapshot owner))
                                 [:state :calls (:call-id effect) :role]))
                  result
                  (if (and (seq fleet-runtimes) (:workspace request))
                    ((executors/tool-executor
                      #(mission-worktree/execute-program! fleet-runtimes %))
                     effect)
                    (let [entry (runtime-entry root)]
                      ((executors/tool-executor
                        (partial workspace/run-program! (:runtime entry)))
                       effect)))]
              (sync-plan-drafts! owner role root
                                 (:tenure-id (:request effect))
                                 (or (:payload result) result)
                                 system-ref)
              result))
          base-tool-run (if (and browser-service
                                 (not (:offline? browser-service)))
                          (browser/routing-executor
                           (browser/executor browser-service) tool-exec)
                          tool-exec)
          tool-run
          (fn [effect]
            (let [request (:request effect)
                  server-id (:server-id request)
                  tool-name (or (:tool request) (:tool-name request))
                  arguments (or (:arguments request) (:tool-arguments request) {})]
              (if (and extensions server-id tool-name)
                (let [scope (extension-session-scope registry-ref session-id)
                      result (extensions-app/call-mcp-tool!
                              extensions scope server-id tool-name arguments)]
                  (cond-> {:status (if (:ok? result) :succeeded :failed)
                           :payload result}
                    (not (:ok? result))
                    (assoc :error (or (:error result)
                                      {:kind :mcp-call-failed}))))
                (base-tool-run effect))))
          cancel-tool
          (fn [{:keys [attempt-id call-id request]}]
            (let [workspace (:workspace request)
                  svc @fleet-ref
                  fleet-runtime (when (and svc workspace)
                                  (try
                                    (fleet/runtime-for-workspace svc workspace)
                                    (catch Throwable _ nil)))]
              (let [result (if-let [ws-rt (:workspace-runtime fleet-runtime)]
                             (workspace/request-stop!
                              ws-rt (or attempt-id call-id))
                             (program/cancel! program-runtime
                                              (or call-id attempt-id)))]
                (cond
                  (= :PROGRAM_UNKNOWN (:status result))
                  {:ok? false
                   :terminal nil
                   :error {:code :PROGRAM_UNKNOWN
                           :message "program is not known to the runtime"}}

                  ;; `program-stop-terminal` returns nil for a still-applying
                  ;; program and for a workspace stop acknowledgement: only a
                  ;; fixed terminal receipt is evidence of an end.
                  :else
                  {:ok? true
                   :terminal (program-stop-terminal result)}))))
          cancel-shell (fn [{:keys [call-id]}]
                         (let [result (shell/request-stop!
                                       shell-runner {:request-id call-id})]
                           ;; `shell-stop-terminal` returns nil unless the
                           ;; session reported an observed end.  A stop
                           ;; signal, a registered stop, and an unconfirmed
                           ;; stop all leave the process unproven, so the
                           ;; Owner keeps `:resource-alive?` set.
                           {:ok? true
                            :terminal (shell-stop-terminal result)}))
          cancel-workspace
          (fn [{:keys [attempt-id call-id request]}]
            (let [workspace (:workspace request)
                  svc @fleet-ref
                  fleet-runtime (when (and svc workspace)
                                  (try
                                    (fleet/runtime-for-workspace svc workspace)
                                    (catch Throwable _ nil)))]
              (let [result (workspace/request-stop!
                            (or (:workspace-runtime fleet-runtime)
                                workspace-runtime)
                            (or attempt-id call-id))]
                ;; The bridge registers a cooperative stop and reports
                ;; `:ended? false`; it never observes the action's end.  Only
                ;; the executor's own terminal is evidence, so the hook
                ;; synthesizes none here.
                {:ok? true
                 :terminal nil
                 :note (:status result)})))
          devops-command!
          (fn [command]
            (let [op (:op command)
                  payload (assoc command :kind op)]
              (loop [attempt 0]
                (if-let [owner @owner-ref]
                  (let [epoch (get-in ((:snapshot owner))
                                      [:state :commands :command-epoch] 1)
                        result ((:submit-command! owner)
                                {:command-id (str "devops-" (name op) "-"
                                                  (:call-id command))
                                 :command-epoch epoch
                                 :payload payload})]
                    (if (and (< attempt 1)
                             (contains? #{:COMMAND_EPOCH_RETIRED
                                          :COMMAND_EPOCH_UNKNOWN}
                                        (get-in result [:error :code])))
                      (recur (inc attempt))
                      result))
                  {:ok? false
                   :error {:code :OWNER_UNAVAILABLE
                           :message "Owner is not available for devops"}}))))
          executors {:shell {:run shell-exec :cancel cancel-shell}
                     :workspace {:run workspace-exec
                                 :cancel cancel-workspace}
                     :tool {:run tool-run :cancel cancel-tool}
                     :devops
                     {:run (fn [effect]
                             (if-let [exec @devops-exec-ref]
                               ((:run exec) effect)
                               {:ok? false
                                :error {:code :DEVOPS_NOT_READY
                                        :message "devops executor is not initialized"}}))
                      :cancel (fn [effect]
                                (when-let [exec @devops-exec-ref]
                                  ((:cancel exec) effect))) }}
          cancel-resource!
          (fn [{:keys [kind call-id attempt-id] :as resource}]
            (case kind
              :tool (cancel-tool resource)
              :shell (cancel-shell {:call-id call-id})
              :workspace (cancel-workspace resource)
              :devops (if-let [exec @devops-exec-ref]
                        ((:cancel exec) {:call-id call-id})
                        {:ok? true :note :devops-not-initialized})
              :model {:ok? true :note :live-model-cancel-handled-by-dispatch}
              {:ok? false :error {:code :UNSUPPORTED_CANCEL_KIND
                                  :kind kind}}))
          session-record (try
                           (:session (session-ports/read-session
                                      persistence session-id))
                           (catch Throwable _ nil))
          attachment-policy
          (fn [items]
            (let [snapshot (when-let [o @owner-ref] ((:snapshot o)))
                  binding (get-in snapshot [:state :session :model-binding])
                  connection
                  (first
                   (filter #(= (:connection-id binding) (:id %))
                           (get-in config [:models :connections])))
                  model (merge
                         (select-keys binding
                                      [:model-entry-id :provider-model-id
                                       :display-name :capabilities])
                         (select-keys connection
                                      [:attachment-limits :limits]))
                  result (if (seq model)
                           (attachments-model/prepare-send items model)
                           {:ok? false
                            :issues [{:code :ATTACHMENT_MODEL_UNKNOWN
                                      :message "no selected model binding"}]
                            :summary {:failed (count items)}})]
              (if (:ok? result)
                {:ok? true :attachments (:sendable result)}
                {:ok? false
                 :error {:code :ATTACHMENT_NOT_SENDABLE
                         :issues (:issues result)
                         :summary (:summary result)}})))
          browser-ref (atom (when-not (:offline? browser-service)
                              browser-service))
          ;; The binding is the session's committed model choice; the
          ;; startup configuration is only the pre-selection fallback.
          ;; Resolve it per request so a live settings save or a later
          ;; `:session/select-model` changes the tool surface.
          restored-tools-supported
          (if-let [binding (:model-binding session-record)]
            (mission-model/supports-tools? (:capabilities binding))
            (model-supports-tools? config))
          tools-supported (live-tools-supported owner-ref
                                                restored-tools-supported
                                                session-config)
          model-tools-supported
          (fn [binding]
            (current-tools-supported session-config binding nil))
          gateway-base (model-gateway model-port-ref materials registry-ref
                                      session-id (:model-stream? config true)
                                      browser-ref search-contexts search-ref
                                      diagnostics-ref
                                      tools-supported
                                      (binding-availability-fn session-config))
          owner-store-instance (owner-store/owner-store store session-id)
          persisted-owner (try
                            (owner-store/load-state owner-store-instance)
                            (catch Throwable _ nil))
          persisted-pair (get-in persisted-owner
                                 [:state :mission :extensions :pair-hint])
          persisted-devops (get-in persisted-owner
                                   [:state :mission :extensions
                                    :pair-hint-devops])
          initial-pair-session (pair-hint/create-session
                                 {:explicit-language
                                  (or (:language session-record) :en)})
          pair-session (if persisted-pair
                         (try
                           (pair-hint/resume-session
                            persisted-pair
                            {:render ufss/write-str})
                           (catch Throwable _ initial-pair-session))
                         initial-pair-session)
          gateway (pair-hint/wrap-model-gateway
                   gateway-base
                   {:state (atom {:session pair-session})
                    :render ufss/write-str
                    ;; A model that declares no `:tools` capability must not
                    ;; receive the synthetic tool-call/tool envelope.  The
                    ;; hint still arrives, as plain notes.
                    :tools-supported? tools-supported
                    :observations
                    (pair-hint-observations owner-ref fleet-ref)
                    :boundary
                    (fn [_request pair-session]
                      (pair-request-boundary owner-ref pair-session))
                    :persist-session
                    (fn [session _request]
                      (when-let [o @owner-ref]
                        (future
                          ((:submit! o)
                           {:type :mission/extensions-put
                            :key :pair-hint
                            :value session}))))})
          initial-devops-session (pair-hint/create-session
                                  {:explicit-language
                                   (or (:language session-record) :en)})
          devops-session (if persisted-devops
                           (try
                             (pair-hint/resume-session
                              persisted-devops
                              {:render ufss/write-str})
                             (catch Throwable _ initial-devops-session))
                           initial-devops-session)
          devops-gateway (pair-hint/wrap-model-gateway
                          gateway-base
                          {:state (atom {:session devops-session})
                           :render ufss/write-str
                           ;; Same capability fact as the main wrapper: a
                           ;; tools-less model gets notes, never a tool
                           ;; envelope.  The devops worker's own
                           ;; `:required-capabilities [:tools]` still fails
                           ;; explicitly at the adapter when tools are
                           ;; genuinely required and undeclared.
                           :tools-supported? tools-supported
                           :boundary
                           (fn [_request pair-session]
                              (pair-request-boundary owner-ref pair-session))
                           :persist-session
                           (fn [session _request]
                             (when-let [o @owner-ref]
                               (future
                                 ((:submit! o)
                                  {:type :mission/extensions-put
                                   :key :pair-hint-devops
                                   :value session}))))})
          devops-runner
          (app-devops/runner
           {:model-gateway devops-gateway
            :files-service (files/service {:root (:workspace-root config)})
            :helpers
            {:call
             (fn [arguments]
               (let [arguments (or arguments {})
                     command (:command arguments)]
                 (shell/run!
                  read-only-shell-runner
                  {:request-id (str "plan-call-" (random-uuid))
                   :purpose "Plan read-only call"
                   :command (if (string? command)
                              command
                              (pr-str command))
                   :cwd "."})))} })
          devops-exec
          {:run
           (fn [effect]
             (let [request (:request effect)]
               (try
                 (app-devops/run-devops!
                  {:runner devops-runner :command! devops-command!}
                  {:tenure-id (:tenure-id request)
                   :call-id (or (:call-id request) (:call-id effect))
                   :verbose-intent (or (:verbose-intent request)
                                       (get-in request
                                               [:arguments :verbose-intent]))})
                 (catch Throwable t
                   {:ok? false
                    :error {:code :DEVOPS_EXECUTOR_FAILED
                            :message (or (.getMessage t) (str t))}}))))
           :cancel
           (fn [effect]
             ((:cancel! devops-runner) {:call-id (:call-id effect)}))}
          _ (reset! devops-exec-ref devops-exec)
          report-bridge
          (owner/report-bridge
           (fn [] @owner-ref)
           (fn [target-session-id]
             (or (get @owners target-session-id)
                 (when-let [r @registry-ref]
                   (registry/owner-for r target-session-id))))
           {:forward-effect!
            (fn [event]
              (forward-owner-event! system-ref session-id event)
              (when-let [f @notify-ref] (f)))})
          handle (owner/start-owner!
                  (merge
                   {:session-id session-id
                    :store owner-store-instance
                    :model-gateway gateway
                    ;; Runtime opt, not app config: the provider is a
                    ;; function and must never enter the persisted state.
                    :outbound-context-fn
                    (fn [{:keys [session turn-card-ids]}]
                      (try
                        (let [scope (extension-session-scope
                                     registry-ref (:session-id session))
                              skills (when extensions
                                       (mapv (fn [skill]
                                               (select-keys skill
                                                            [:id :name :version]))
                                             (:active
                                              (extensions/resolve-skills
                                               (extensions-app/catalog extensions)
                                               scope))))]
                          {:ok? true
                           :context
                           (history/outbound-context
                            (or session {})
                            (outbound-context-opts
                             {:session session
                              :materials materials
                              :session-config session-config
                              :turn-card-ids turn-card-ids
                              :skills skills}))})
                        (catch Throwable _ {:ok? false})))
                    :config
                    ;; The Owner prepares the full Plan surface.  The app
                    ;; gateway strips every tool for a model that declares
                    ;; none, so this is safe before the session selects a
                    ;; model and stays correct if it later selects a
                    ;; tools-capable one.
                    {:workspace-root (:workspace-root config)
                     :plan-tools [mission-devops/devops-tool-spec]
}
                     :extension-tools-fn
                     (fn [{:keys [session-id]}]
                       (if extensions
                         (extensions/mcp-tool-definitions
                          (extensions-app/catalog extensions)
                          (extension-session-scope registry-ref session-id))
                         []))
                     :skill-context-fn
                     (fn [{:keys [session-id]}]
                       (if extensions
                         (extensions/active-skill-context
                          (extensions-app/catalog extensions)
                          (extension-session-scope registry-ref session-id))
                         []))
                     :on-user-send!
                     (fn [send]
                       (try
                         (when-let [d @diagnostics-ref]
                           (let [session (when (and registry-ref session-id)
                                           (try
                                             (let [r (registry/get-session
                                                      @registry-ref session-id)]
                                               (when (= :found (:status r))
                                                 (:session r)))
                                             (catch Throwable _ nil)))]
                             (diagnostics-service/record-user-send!
                              d (merge {:assistant-id (:assistant-id session)
                                        :model (or (get-in session
                                                           [:model-binding
                                                            :model-entry-id])
                                                   (get-in session
                                                           [:model-binding :model]))
                                        :temporary? (:temporary? session)}
                                       send))))
                         (catch Throwable _ nil)))
                     :on-call-terminal!
                     (fn [terminal]
                       (try
                         (when-let [d @diagnostics-ref]
                           (let [session (when (and registry-ref session-id)
                                           (try
                                             (let [r (registry/get-session
                                                      @registry-ref session-id)]
                                               (when (= :found (:status r))
                                                 (:session r)))
                                             (catch Throwable _ nil)))]
                             (diagnostics-service/record-tool-call!
                              d (merge {:assistant-id (:assistant-id session)
                                        :temporary? (:temporary? session)}
                                       terminal))))
                         (catch Throwable _ nil))
                       (try
                         (when-let [a @artifacts-ref]
                           (artifacts-integration/register-executor-result!
                            a
                            {:session-id (:session-id terminal)
                             :call-id (:tool-call-id terminal)
                             :executor (:name terminal)
                             :result (:result terminal)
                             :publish! (fn [_]
                                         (when-let [f @notify-ref] (f)))}))
                         (catch Throwable _ nil)))
                    :mission-port mission-port
                    :tool-result-renderer
                    program/render-tool-result
                    :attachment-contract {:resolve? true}
                    :attachment-policy attachment-policy
                    :model-tools-supported model-tools-supported
                    ;; Test/E2E seam: `:nudge-enabled? false` (config key or
                    ;; HC_NUDGE_ENABLED=false) suppresses every enabled Nudge
                    ;; decision.  The Owner records `:nudge-suppressed` in
                    ;; `:turn-history` and clears the turn, which gives the
                    ;; session an observable idle boundary instead of an
                    ;; endless "unattended" run while the Plan ledger is
                    ;; still open.  Production default keeps §2.5 semantics.
                    :nudge-suppress
                    (when (false? (:nudge-enabled? config))
                      (fn [_state _turn _observation]
                        :nudge-disabled-by-config))
                    :session-port (session-port
                                   #(System/currentTimeMillis)
                                   session-config
                                   owner-ref
                                   persistence
                                   (fn
                                     ([session]
                                      (history/outbound-context
                                       session
                                       (outbound-context-opts
                                        {:session session
                                         :materials materials
                                         :session-config session-config})))
                                     ([session opts]
                                      (history/outbound-context
                                       session
                                       (outbound-context-opts
                                        {:session session
                                         :materials materials
                                         :session-config session-config
                                         :payload opts})))))
                    :session-run-port (session-run-port
                                       #(System/currentTimeMillis))
                    :initial-session session-record
                    ;; A summary or branch session already carries visible
                    ;; history; seed the fresh Owner conversation so the
                    ;; first model request does not drop it.
                    :initial-conversation
                    (sessions/initial-conversation session-record)
                    :executors executors
                    :worker-run-program-fn
                    (fn [{:keys [tenure-id workspace program-source intent
                                 task-id claim-id agent-id holder?
                                 command-id]}]
                      (let [svc @fleet-ref
                            fleet-runtime
                            (when (and svc workspace)
                              (try
                                (fleet/runtime-for-workspace svc workspace)
                                (catch Throwable _ nil)))
                            ws-rt (or (:workspace-runtime fleet-runtime)
                                      workspace-runtime)]
                        (if-not ws-rt
                          {:status :failed
                           :error {:code :WORKSPACE_RUNTIME_UNAVAILABLE
                                   :message
                                   "no workspace runtime for the worker program"}}
                          (workspace/run-program!
                           ws-rt
                           {:agent-id agent-id
                            :claim-id claim-id
                            :task-id task-id
                            :attempt-id command-id
                            :call-id command-id
                            :source program-source
                            :intent intent
                            :role :clj-worker
                            :lane :execution
                            :tenure-id tenure-id
                            :holder? holder?
                            ;; The Owner resolves the live holder term; a
                            ;; client or model never supplies it.  The shell
                            ;; port still revalidates holder/term/claim.
                            :context {:token-term
                                      (get-in (board-snapshot)
                                              [:token-slot :term-id])}}))))
                    :blob-store
                    (let [root (:blob-root config)]
                      {:put! (fn [text]
                               (let [bytes (.getBytes ^String text "UTF-8")
                                     result
                                     (blob-store/put-stream!
                                      root
                                      (java.io.ByteArrayInputStream. bytes)
                                      {:content-type "text/plain"})]
                                 {:ref (:id result)
                                  :hash (or (:sha256 result) (:id result))
                                  :bytes (count text)}))
                       :get (fn [ref]
                              (String. ^bytes
                                       (blob-store/read-bytes root ref)
                                       "UTF-8"))})
                    :compact-threshold 1000000
                    :cancel-resource! cancel-resource!
                    :report-sink! (:report-sink! report-bridge)
                    :on-effect! (:on-effect! report-bridge)
                    :close-store? false}
                   ;; A restored Owner must seed the relay with the first
                   ;; visible user turn, not only with session metadata.
                   ;; `initial-user-input` returns nil for a session with no
                   ;; visible user text; merge then leaves the base opts
                   ;; unchanged.
                   (sessions/initial-user-input session-record)))]
      (reset! owner-ref handle)
      (doseq [[gateway extension-key]
              [[gateway :pair-hint]
               [devops-gateway :pair-hint-devops]]]
        (when-let [persisted (get-in ((:snapshot handle))
                                     [:state :mission :extensions
                                      extension-key])]
          (try
            (reset! (:state gateway)
                    {:session (pair-hint/resume-session
                               persisted
                               {:render ufss/write-str})})
            (catch Throwable _ nil))))
      (swap! owners assoc session-id handle)
      (when-let [f @notify-ref] (f))
      handle)))

;; ---------------------------------------------------------------------------
;; Recovery

(defn- blocked-retries-entry
  [store result]
  {:status :blocked
   :error {:error :STORAGE_RETRY_UNSUPPORTED
           :message "storage retry obligations have no claimer or dispatcher"
           :data {:obligations (recovery/blocked-retries result)}}
   :hint (str "The application did not start. The listed :retry-wait "
              "obligations were left unchanged because no component can "
              "claim or dispatch them. Inspect them or restore a compatible "
              "backup before starting.")
   :path (str (storage/store-path store))
   :recovery-entry {:action :operator
                    :options [:inspect-directory
                              :restore-from-backup
                              :choose-another-path]}})

(defn- recover-all!
  [store generation]
  (let [result (recovery/recover-all!
                store
                {:current-generation generation
                 :now-ms (System/currentTimeMillis)})]
    (when-not (= :ok (:status result))
      (try (storage/close-store! store) (catch Throwable _ nil))
      (throw (ex-info "startup recovery failed; refusing to dispatch work"
                      {:error/kind :recovery-failed
                       :recovery result
                       :recovery-blocked (recovery/blocked-entry result)
                       :path (:path result)})))
    (when (recovery/blocked-retries? result)
      (try (storage/close-store! store) (catch Throwable _ nil))
      (throw (ex-info "startup recovery found unclaimed storage retries; refusing to dispatch work"
                      {:error/kind :recovery-failed
                       :recovery result
                       :recovery-blocked (blocked-retries-entry store result)
                       :path (str (storage/store-path store))})))
    result))

(defn- owner-state-blocked-entry
  [store session-id error]
  (let [data (ex-data error)]
    {:status :blocked
     :error {:error :OWNER_STORE_CORRUPT
             :message (or (ex-message error)
                          "a session Owner partition is corrupt")
             :data (-> data
                       (dissoc :code)
                       (assoc :session-id session-id))}
     :hint (str "The application did not start. Session " session-id
                " has a committed partition version but no Owner state "
                "document. The store was not repaired or replaced. Inspect "
                "the directory or restore from a backup.")
     :path (str (storage/store-path store))
     :recovery-entry {:action :operator
                      :options [:inspect-directory
                                :restore-from-backup
                                :choose-another-path]}}))

(defn- owner-state-read-blocked-entry
  [store session-id error]
  {:status :blocked
   :error {:code :OWNER_STATE_UNREADABLE
           :error :OWNER_STATE_UNREADABLE
           :message (or (:message error)
                        "a session record or Owner partition could not be read")
           :data {:session-id session-id :cause error}}
   :hint (str "The application did not start because session " session-id
              " could not be read with certainty. The store was not repaired "
              "or replaced. Inspect the directory or restore from a backup.")
   :path (str (storage/store-path store))
   :recovery-entry {:action :operator
                    :options [:inspect-directory
                              :restore-from-backup
                              :choose-another-path]}})

(defn- verify-owner-state!
  "Prove one session's Owner partition is readable.

  Only a partition that already has an authoritative version can be corrupt.
  A newly created session has no `session/<id>` partition yet (version 0), so
  it is a legitimate empty session and passes.  A positive version without
  the `:owner-state` document is the corruption the adapters refuse to treat
  as an empty state."
  [store session-id]
  (try
    (owner-store/state-from-view
     (storage/read-session-view store
                                {:partition (str "session/" session-id)
                                 :session-id session-id}))
    (catch clojure.lang.ExceptionInfo e
      (if (= :OWNER_STORE_CORRUPT (:code (ex-data e)))
        (do
          (try (storage/close-store! store) (catch Throwable _ nil))
          (throw (ex-info
                  "a session Owner state is missing; refusing to start"
                  {:error/kind :owner-store-corrupt
                   :session-id session-id
                   :recovery-blocked
                   (owner-state-blocked-entry store session-id e)})))
        (throw e)))))

(defn- verify-owner-states!
  "Prove every visible session has a readable Owner state before any
  component starts.

  A read that is neither `:found` nor `:missing` is not an empty session.
  It stops startup with a recovery entry, because opening the gateway would
  accept commands for a session whose Owner cannot be proven readable."
  [store persistence]
  (let [listed (session-ports/list-session-ids persistence)]
    (when-not (= :ok (:status listed))
      (try (storage/close-store! store) (catch Throwable _ nil))
      (throw (ex-info "startup session list could not be read for Owner preflight"
                      {:recovery-blocked
                       (assoc (owner-state-read-blocked-entry
                               store nil
                               (or (:error listed)
                                   {:code :SESSION_LIST_FAILED
                                    :message "the committed session list could not be read"}))
                              :error (normalize-blocked-error
                                      (or (:error listed)
                                          {:code :SESSION_LIST_FAILED
                                           :message "the committed session list could not be read"})))})))
    (doseq [session-id (:ids listed)]
      (let [result (session-ports/read-session persistence session-id)]
        (case (:status result)
          :missing nil
          :found (when (not= :deleted (get-in result [:session :status]))
                   (verify-owner-state! store session-id))
          (do
            (try (storage/close-store! store) (catch Throwable _ nil))
            (throw (ex-info
                    "a session record could not be read for Owner preflight"
                    {:recovery-blocked
                     (owner-state-read-blocked-entry
                      store session-id
                      (or (:error result)
                          {:code :OWNER_STATE_UNREADABLE
                           :message "the session record read did not return found or missing"}))}))))))))

(defn- cancel-fleet-attempt!
  "Forward one `:model/cancel` effect to the fleet model gateway.

  The attempt index holds the real gateway handle.  A missing handle means
  the attempt already reached a terminal event or was already cancelled, so
  this is an idempotent no-op.  Cancellation is best effort: a thrown cancel
  is recorded as evidence, but it never becomes a call terminal and never
  clears a resource."
  [fleet-gateway fleet-attempts system-ref swarm-id attempt-id]
  (let [key [swarm-id attempt-id]
        handle (get @fleet-attempts key)]
    (if handle
      (do
        (try
          ((:cancel! fleet-gateway) handle)
          (catch Throwable t
            (swap! system-ref update :fleet-cancel-errors
                   (fnil conj [])
                   {:attempt-id attempt-id
                    :swarm-id swarm-id
                    :error {:message (or (.getMessage t) (str t))
                            :class (.getName (class t))}})))
        (swap! fleet-attempts dissoc key)
        {:ok? true :cancelled? true})
      {:ok? true
       :cancelled? false
       :reason :already-terminal})))

(defn- start-retry-owners!
  "Start the owning session for every owner-owned `:retry-call` index.

  The Owner runtime owns the request, effects, timer, and terminal outcome.
  This function only ensures the Owner exists and submits a wake.  A failed
  start or refused wake leaves the index open and is returned as evidence; it
  never fabricates a terminal outcome."
  [registry pending-retries]
  (reduce
   (fn [errors obligation]
     (if (= :retry-call (:obligation/kind obligation))
       (let [session-id (or (:storage/session-id obligation)
                            (:session-id obligation))
             result (if (and (string? session-id)
                             (not (str/blank? session-id)))
                      (registry/ensure-owner-for! registry session-id)
                      {:ok? false
                       :error {:code :RETRY_OWNER_SESSION_MISSING
                               :message "retry-call obligation has no session id"}})]
         (if (:ok? result)
           (let [wake-result (try
                               (when-let [submit (:submit! (:owner result))]
                                 (submit {:type :wake
                                          :reason :retry-recovery
                                          :obligation-id
                                          (:obligation/id obligation)}))
                               (catch Throwable t
                                 {:ok? false
                                  :error {:code :RETRY_OWNER_WAKE_FAILED
                                          :message (or (.getMessage t)
                                                       (str t))}}))]
             (if (false? (:ok? wake-result))
               (conj errors
                     {:obligation-id (:obligation/id obligation)
                      :session-id session-id
                      :error (or (:error wake-result)
                                 {:code :RETRY_OWNER_WAKE_FAILED
                                  :message "the Owner refused the recovery wake"})})
               errors))
           (conj errors
                 {:obligation-id (:obligation/id obligation)
                  :session-id session-id
                  :error (:error result)})))
       errors))
   []
   pending-retries))

(defn- ensure-session-list!
  "Return the committed session summaries, or fail startup without creating
  a default session.

  A read failure is not an empty session list.  Treating it as empty would
  create a new session over a store whose existing sessions could not be
  read, hiding the failure and changing the scene."
  [store listed]
  (if (= :ok (:status listed))
    (:sessions listed)
    (let [path (str (storage/store-path store))]
      (try (storage/close-store! store) (catch Throwable _ nil))
      (throw (ex-info "startup session listing failed; refusing to create a default session"
                      {:error/kind :startup-session-list-failed
                       :session-list listed
                       :path path
                       :recovery-blocked
                       {:status :blocked
                        :error (or (:error listed)
                                   {:code :SESSION_LIST_FAILED
                                    :message "the committed session list could not be read"})
                        :hint (str "The application did not start and did not "
                                   "create a default session because the "
                                   "committed session list could not be read. "
                                   "Inspect the store or restore a backup.")
                        :path path
                        :recovery-entry
                        {:action :operator
                         :options [:inspect-directory
                                   :restore-from-backup
                                   :choose-another-path]}}})))))

;; ---------------------------------------------------------------------------
;; Publisher

(defn- router-owner-handles
  "Live Owner handles registered in ROUTER, or nil when ROUTER is not a
  started router.

  The publisher also runs against test doubles and partial routers: a plain
  map, a router whose :state is missing, or state that is not an atom yet.
  Those inputs contribute no handles instead of failing the snapshot."
  [router]
  (let [state (when (map? router) (:state router))]
    (when (instance? clojure.lang.IAtom state)
      (let [value (try @state (catch Throwable _ nil))]
        (when (map? value)
          (:owners value))))))

(defn- owner-snapshot-revision
  [snapshot]
  (long (or (get-in snapshot [:state :session :revision]) -1)))

(defn- owner-snapshot-context?
  [snapshot]
  (boolean (get-in snapshot [:state :session :context])))

(defn- compression-record-newer?
  "True when `candidate` was written after `current`.

  Compression records carry `:updated-at` from the history command clock;
  the fresher record wins a merge so a lagging Owner snapshot cannot
  resurrect an `:applied` state over a newer `:reverted` one.  Unreadable or
  missing timestamps keep the base record: the base snapshot already holds
  the newer session revision."
  [candidate current]
  (let [candidate-at (:updated-at candidate)
        current-at (:updated-at current)]
    (cond
      (nil? candidate-at) false
      (nil? current-at) true
      :else (try (pos? (compare candidate-at current-at))
                 (catch Throwable _ false)))))

(defn- merge-compression-record
  [base-record other-record]
  (cond
    (nil? other-record) base-record
    (nil? base-record) other-record
    (compression-record-newer? other-record base-record) other-record
    :else base-record))

(defn- ordered-ids
  "The stored `:compression-order` value as a vector; anything that is not
  sequential counts as no stored order."
  [context]
  (let [order (:compression-order context)]
    (if (sequential? order) (vec order) [])))

(defn- merged-compression-order
  "Reconcile `:compression-order` across the two snapshots.

  The base snapshot carries the newer session revision, so its stored order
  stays authoritative.  Ids the base never saw keep the other snapshot's
  relative order, and applied ids missing from both orders fall back to
  apply time.  Ids whose merged record is no longer `:applied` drop out."
  [base-context other-context merged-compressions]
  (let [applied-ids (->> (vals merged-compressions)
                         (filter #(= :applied (:state %)))
                         (map :compression-id)
                         set)
        stored (into []
                      (comp (filter applied-ids) (distinct))
                      (concat (ordered-ids base-context)
                              (ordered-ids other-context)))
        known (set stored)
        missing (->> (vals merged-compressions)
                     (filter #(and (contains? applied-ids (:compression-id %))
                                   (not (contains? known (:compression-id %)))))
                     (sort-by (fn [compression]
                                [(if (number? (:applied-at compression)) 0 1)
                                 (:applied-at compression)
                                 (:compression-id compression)]))
                     (map :compression-id))]
    (into stored missing)))

(defn- merge-owner-context
  "Merge the two snapshots' session contexts, keeping both compression maps.

  The app-level and router handles can lag each other in different fields.
  The selected base keeps its newer session revision; the other snapshot's
  context is merged in so a compression applied on one handle is not lost.
  Compression records merge per id by `:updated-at`, and
  `:compression-order` is rebuilt from the merged records, so a lagging
  `:applied` record cannot overwrite a newer `:reverted` one."
  [base other]
  (let [base-context (or (get-in base [:state :session :context]) {})
        other-context (or (get-in other [:state :session :context]) {})
        base-compressions (let [value (:compressions base-context)]
                             (if (map? value) value {}))
        other-compressions (let [value (:compressions other-context)]
                             (if (map? value) value {}))
        merged-compressions (merge-with merge-compression-record
                                        base-compressions
                                        other-compressions)
        merged-order (merged-compression-order base-context other-context
                                               merged-compressions)
        merged (merge base-context other-context
                      (cond-> {:compressions merged-compressions}
                        ;; An explicit empty order is authoritative (every
                        ;; compression reverted); a context that never had the
                        ;; key keeps it absent so readers can fall back to the
                        ;; compression map itself.
                        (or (seq merged-order)
                            (contains? base-context :compression-order)
                            (contains? other-context :compression-order))
                        (assoc :compression-order merged-order)))]
    (assoc-in base [:state :session :context] merged)))

(defn- prefer-owner-snapshot
  "Choose the fresher of the app-level and router Owner snapshots.

  The router may hold a live branch Owner that the app owners atom does not
  know, and it may also hold a newer handle after a command.  The higher
  session revision wins; on a tie the app owner wins.  Either way the other
  snapshot's context is merged in so compressions are not lost when one
  handle lags."
  [app router]
  (let [base (if (> (owner-snapshot-revision router)
                    (owner-snapshot-revision app))
               router
               app)
        other (if (identical? base router) app router)]
    (let [chosen (if (or (seq (get-in base [:state :session :context]))
                         (seq (get-in other [:state :session :context])))
                   (merge-owner-context base other)
                   base)]
      ;; A router handle can be newer by revision but carry only the session
      ;; context.  The worker program receipts live in the app owner's state;
      ;; dropping them made the Board publish zero :worker-programs even
      ;; though the protected commit had finished.
      (cond-> chosen
        (and (empty? (get-in chosen [:state :worker-jobs]))
             (seq (get-in other [:state :worker-jobs])))
        (assoc-in [:state :worker-jobs]
                  (get-in other [:state :worker-jobs]))))))

(defn- baseline
  ([registry owners router config sync]
   (baseline registry owners router config sync nil nil))
  ([registry owners router config sync known-sessions]
   (baseline registry owners router config sync known-sessions nil))
  ([registry owners router config sync known-sessions outbound-opts-fn]
   (baseline registry owners router config sync known-sessions
             outbound-opts-fn nil))
  ([registry owners router config sync known-sessions outbound-opts-fn
    session-cache]
  (let [sessions (or known-sessions
                     (let [listed (registry/list-sessions
                                   registry {:include-deleted? false})]
                       (if (= :ok (:status listed))
                         (:sessions listed)
                         (throw (ex-info "session listing failed during snapshot"
                                         {:error/kind :session-list-failed
                                          :session-list listed
                                          :error (:error listed)})))))
        active (let [candidate (or (commands/active-session-id router)
                                   (:session-id (first sessions)))]
                 (if (some #(= candidate (:session-id %)) sessions)
                   candidate
                   (:session-id (first sessions))))
        ;; The app-level owners atom is populated at startup and resume.
        ;; A branch created after startup registers its Owner in the router
        ;; and registry only; merge those live handles so the branch summary
        ;; publishes its command epoch.  Without the epoch the settings
        ;; panel cannot submit :session/select-model for the branch.
        ;; App owners win on a duplicate session id: the router handle can be
        ;; a partial or older view (for example without the compression
        ;; :context), while the app owner is the publisher's live source.
        router-owners (router-owner-handles router)
        app-snapshots (into {}
                            (keep (fn [[session-id handle]]
                                    (try
                                      [session-id ((:snapshot handle))]
                                      (catch Throwable _ nil))))
                            @owners)
        router-snapshots (into {}
                               (keep (fn [[session-id handle]]
                                       (try
                                         [session-id ((:snapshot handle))]
                                         (catch Throwable _ nil))))
                               router-owners)
        snapshots (merge-with prefer-owner-snapshot
                              app-snapshots router-snapshots)
        session-records (keep (fn [session]
                                (let [session-id (:session-id session)]
                                  (or (get-in snapshots
                                              [session-id :state :session])
                                      (try
                                        (let [result (registry/get-session
                                                      registry session-id)]
                                          (when (= :found (:status result))
                                            (:session result)))
                                        (catch Throwable _ nil)))))
                              sessions)]
    {:snapshot (snapshot/baseline
                {:sessions sessions
                 :session-records session-records
                 :owner-snapshots snapshots
                 :active-session active
                 :config config
                 :outbound-opts-fn outbound-opts-fn
                 :session-cache session-cache
                 :sync (merge {:phase :synced
                               :command-epoch
                               (commands/app-command-epoch router)}
                              (or sync {}))})
     :sessions sessions
     :active-session active})))

(defn- baseline-cache-key
  "Inputs that can change the baseline projection.

  Owner and session versions cover committed state; the settings hash and
  materials revision cover the active session's outbound context.  A change
  in any of them rebuilds the projection; otherwise the publisher reuses the
  previous map instead of walking every session again."
  [sessions owner-versions active-session command-epoch config-models
   materials-revision]
  [(mapv (juxt :session-id :revision) sessions)
   owner-versions
   active-session
   command-epoch
   config-models
   materials-revision])

(defn- cached-baseline
  "Return the cached baseline for KEY, or build and cache one.

  The cached value is the complete baseline map (without component
  snapshots); callers may merge fresh component data on every tick."
  [cache key build]
  (let [{cached-key :key cached :snapshot} @cache]
    (if (= key cached-key)
      cached
      (let [snapshot (build)]
        (reset! cache {:key key :snapshot snapshot})
        snapshot))))

(defn- cached-component
  "Return the cached component value for KIND when VERSION matches.

  A nil version means the component has no cheap revision accessor; its
  snapshot is rebuilt rather than caching a value that cannot be validated."
  [cache kind version build]
  (if (nil? version)
    (build)
    (let [entry (get @cache kind)]
      (if (and entry (= version (:version entry)))
        (:value entry)
        (let [value (build)]
          (swap! cache assoc kind {:version version :value value})
          value)))))

(defn- component-snapshot
  ([components registry active-session]
   (component-snapshot components registry active-session nil))
  ([{:keys [materials diagnostics extensions speech search scheduler
            documents artifacts fleet model-auth] :as _components}
    registry active-session component-cache]
  (let [session (when active-session
                  (try
                    (let [result (registry/get-session registry active-session)]
                      (when (= :found (:status result)) (:session result)))
                    (catch Throwable _ nil)))]
    (merge
     (when materials
       (try (if component-cache
              (cached-component
               component-cache [:materials active-session]
               (materials-session/revision materials)
               #(materials-session/snapshot materials session))
              (materials-session/snapshot materials session))
            (catch Throwable _ {:materials/ready? false})))
     (when diagnostics
       {:diagnostics (try (if component-cache
                            (cached-component
                             component-cache :diagnostics
                             (diagnostics-service/revision diagnostics)
                             #(diagnostics-integration/baseline diagnostics))
                            (diagnostics-integration/baseline diagnostics))
                          (catch Throwable _ {:read-error true}))})
     (when extensions
       {:extensions (try (if component-cache
                           (cached-component
                            component-cache :extensions
                            (some-> (:version extensions) deref)
                            #(extensions-app/view extensions))
                           (extensions-app/view extensions))
                         (catch Throwable _ nil))})
     (when speech
       (try (if component-cache
              (cached-component component-cache :speech
                                (:revision @(:config speech))
                                #(speech/snapshot speech))
              (speech/snapshot speech))
            (catch Throwable _ nil)))
     (when search
       (try (if component-cache
              (cached-component
               component-cache :search
               [(:config search) (some-> (:tests search) deref)]
               #(search/snapshot search))
              (search/snapshot search))
            (catch Throwable _ nil)))
     (when model-auth
       (try (model-auth-app/snapshot model-auth) (catch Throwable _ nil)))
     (when documents
       {:documents {:ocr (try (documents/ocr-status)
                              (catch Throwable _ nil))}})
     (when (and artifacts active-session)
       (try {:artifacts (:artifacts ((:snapshot artifacts) active-session))}
            (catch Throwable _ {:artifacts []})))
     (when fleet
       (try (fleet/snapshot-tables fleet)
            (catch Throwable _ {:fleet/error true})))
     (when scheduler
       {:scheduler (try (scheduler/snapshot scheduler)
                        (catch Throwable _ nil))})))))

(defn- nudge-call?
  [call]
  (boolean
   (or (:nudge? call)
       (:nudge-id call)
       (get-in call [:request :snapshot :nudge-id]))))

(defn- server-clock-change
  [previous snapshot]
  (let [before (:server/now previous)
        after (:server/now snapshot)]
    (when (not= before after)
      (if (contains? previous :server/now)
        ;; `:server/now` is a namespaced keyword; its JSON Pointer segment
        ;; is `server~1now`, not `server/now` (which would be two segments).
        {"op" "replace" "path" "/server~1now" "value" after}
        {"op" "add" "path" "/server~1now" "value" after}))))

(def ^:private small-delta-bytes
  "A delta below this size is published without serializing the complete
  snapshot just to compare the two.  The delta is always a valid state
  event; the comparison is only a payload-size preference."
  (* 16 1024))

(defn publish-state!
  "Publish SNAPSHOT as the next client state boundary.

  PREVIOUS is the tree the feed currently holds, or nil for the first
  publish.  A changed tree is sent as an RFC 6902 `STATE_DELTA` when that
  event is not larger than the complete snapshot; otherwise the complete
  `STATE_SNAPSHOT` is sent.  An unchanged tree, including one whose only
  difference is `:server/now`, does not consume a feed sequence.

  Returns `{:ok? boolean :mode :noop|:snapshot|:delta :result map}`.  A
  failed publish keeps its gateway error in `:result`; this function never
  turns an oversized event into a silent success."
  [gateway previous snapshot]
  (let [previous-tree (when (map? previous) (dissoc previous :server/now))
        snapshot-tree (when (map? snapshot) (dissoc snapshot :server/now))
        state-changed? (and (map? previous)
                            (map? snapshot)
                            (not= previous-tree snapshot-tree))]
    (cond
      (nil? previous)
      (let [result (gateway/publish!
                    gateway {:type :state-snapshot :snapshot snapshot})]
        {:ok? (:ok? result) :mode :snapshot :result result})

      (and (map? previous) (map? snapshot) (not state-changed?))
      {:ok? true :mode :noop :result {:ok? true}}

      (or (not state-changed?) (not (map? snapshot)))
      (let [result (gateway/publish!
                    gateway {:type :state-snapshot :snapshot snapshot})]
        {:ok? (:ok? result) :mode :snapshot :result result})

      :else
      (let [state-changes (gateway-diff/diff previous-tree snapshot-tree)
            clock-change (server-clock-change previous snapshot)
            changes (cond-> (vec (or state-changes []))
                      clock-change (conj clock-change))
            delta-event {:type :state-delta :delta changes}
            delta-size (gateway/event-bytes delta-event)]
        (if (<= delta-size small-delta-bytes)
          ;; Common streaming tick: one size estimate, one publish.  The
          ;; publisher no longer prints the complete snapshot to compare.
          (let [result (gateway/publish! gateway delta-event delta-size)]
            (if (:ok? result)
              {:ok? true :mode :delta :result result}
              (let [fallback (gateway/publish!
                              gateway
                              {:type :state-snapshot :snapshot snapshot})]
                {:ok? (:ok? fallback)
                 :mode :snapshot
                 :result fallback
                 :delta-result result})))
          (let [snapshot-event {:type :state-snapshot :snapshot snapshot}
                snapshot-size (gateway/event-bytes snapshot-event)]
            (if (>= delta-size snapshot-size)
              (let [result (gateway/publish!
                            gateway snapshot-event snapshot-size)]
                {:ok? (:ok? result) :mode :snapshot :result result})
              (let [result (gateway/publish!
                            gateway delta-event delta-size)]
                (if (:ok? result)
                  {:ok? true :mode :delta :result result}
                  ;; A delta that cannot be published is retried as a
                  ;; complete baseline.  If the baseline is also too large,
                  ;; the error stays in the returned result for the caller
                  ;; to record.
                  (let [fallback (gateway/publish!
                                  gateway snapshot-event snapshot-size)]
                    {:ok? (:ok? fallback)
                     :mode :snapshot
                     :result fallback
                     :delta-result result}))))))))))

(defn- text-delta
  "Return the appended suffix of TEXT after PREVIOUS-LENGTH, or nil.

  The publisher keeps only the previous length of each assistant message,
  not a second copy of its text.  An unchanged, shortened or same-length
  replacement produces no delta, matching the old text comparison for the
  append-only streaming path."
  [text previous-length]
  (let [text (or text "")
        previous-length (long (or previous-length 0))]
    (when (> (count text) previous-length)
      (subs text previous-length))))

(defn- prune-to-live
  "Return M restricted to LIVE-IDS without copying an already-live map.

  A deleted session must not leave its last calls or message lengths in the
  publisher forever."
  [m live-ids]
  (if (and (= (count m) (count live-ids))
           (every? #(contains? m %) live-ids))
    m
    (select-keys m live-ids)))

(defn- start-publisher
  [{:keys [registry owners router settings-config gateway system-ref components
           stop-timeout-ms]}]
  (let [executor (Executors/newSingleThreadScheduledExecutor
                  (thread-factory "hc-publisher"))
        timeout-ms (long (or stop-timeout-ms 10000))
        stopped? (atom false)
        ;; One lock covers every state read.  The scheduled executor is
        ;; single-threaded, but Owner notifications call `publish-now!`
        ;; directly from their own threads.  `stop!` sets `stopped?`, then
        ;; waits for the executor and for this lock, so no read can cross
        ;; `close-store!`.
        publish-lock (ReentrantLock.)
        scheduled? (atom false)
        subscribed (atom {})
        unsubscribe-all!
        (fn []
          (doseq [[_ f] @subscribed]
            (try (f) (catch Throwable _ nil)))
          (reset! subscribed {}))
        subscribe-owner!
        (fn [[session-id handle]]
          (when (and handle (not (contains? @subscribed session-id)))
            (try
              (let [unsubscribe
                    ((:subscribe! handle)
                     (fn [_notification]
                       (when-let [f (:publish-now! @system-ref)] (f))))]
                (swap! subscribed assoc session-id unsubscribe))
              (catch Throwable _ nil))))
        previous-calls (atom {})
        previous-message-lengths (atom {})
        baseline-cache (atom nil)
        baseline-session-cache (atom {})
        component-cache (atom {})
        published-snapshot (atom (:snapshot (gateway/snapshot gateway)))
        terminal-call-states #{:completed :succeeded :failed :cancelled
                               :outcome-unknown}
        active-call-states #{:ready :dispatching :running :streaming
                             :retry-wait}
        publish-run-events!
        (fn []
          (let [live-ids (volatile! #{})]
            (doseq [[session-id handle] @owners
                    :when (and handle (not (keyword? session-id)))]
              (vswap! live-ids conj session-id)
              (try
                (let [state (:state ((:snapshot handle)))
                    calls (:calls state)
                    messages (:conversation state)
                    old-calls (get @previous-calls session-id {})
                    old-lengths (get @previous-message-lengths
                                     session-id {})]
                (doseq [[call-id call] calls]
                  (let [old-state (get old-calls call-id)
                        new-state (:state call)]
                    (when (and (not= old-state new-state)
                               (contains? active-call-states new-state)
                               (not (contains? active-call-states old-state)))
                      (gateway/broadcast! gateway
                                          {:type "RUN_STARTED"
                                           :run-id (str call-id)
                                           :thread-id session-id
                                           :session-id session-id
                                           :nudge? (nudge-call? call)}))
                    (when (and (not= old-state new-state)
                               (contains? terminal-call-states new-state)
                               (not (contains? terminal-call-states old-state)))
                      (gateway/broadcast! gateway
                                          {:type "RUN_FINISHED"
                                           :run-id (str call-id)
                                           :thread-id session-id
                                           :session-id session-id
                                           :status new-state
                                           :nudge? (nudge-call? call)
                                           :search-status
                                           (or (:search-status call)
                                               (get-in call [:result :search-status])
                                               (get-in call [:terminal :search-status]))
                                           ;; The bounded public search
                                           ;; context travels with the
                                           ;; terminal so the client can
                                           ;; render source cards and
                                           ;; numbering even when the
                                           ;; completed text carried no
                                           ;; citation labels.
                                           :search-context
                                           (or (:search-context call)
                                               (get-in call [:result :search-context])
                                               (get-in call [:terminal :search-context]))
                                           :search-degraded?
                                           (boolean
                                            (or (:search-degraded? call)
                                                (get-in call [:result :search-degraded?])
                                                (get-in call [:terminal :search-degraded?])))
                                           :error (or (:error call)
                                                      (:last-error call)
                                                      (:error (:result call)))}))
                    (when (and (not= old-state new-state)
                               (= :failed new-state)
                               (not= :failed old-state))
                      (gateway/broadcast!
                       gateway
                       {:type "RUN_ERROR"
                        :run-id (str call-id)
                        :thread-id session-id
                        :session-id session-id
                        :nudge? (nudge-call? call)
                        :error (or (:error call)
                                   (:last-error call)
                                   (:error (:result call)))}))))
                (doseq [[message-index message] (map-indexed vector messages)
                        :when (and (= :assistant (:role message))
                                   (not (:nudge? message))
                                   (not (:nudge-id message))
                                   (not (:message/nudge? message))
                                   (not (:message/nudge-id message)))]
                  (let [message-id (or (:message-id message)
                                       (:id message)
                                       (:call-id message)
                                       (str session-id "-assistant-"
                                            message-index))
                        text (or (:text message) (:content message) "")
                        old-length (long (or (get old-lengths message-id) 0))
                        delta (text-delta text old-length)]
                    (when (seq delta)
                      (gateway/broadcast! gateway
                                          {:type "TEXT_MESSAGE_CONTENT"
                                           :message-id message-id
                                           :thread-id session-id
                                           :session-id session-id
                                           :delta delta}))))
                (swap! previous-calls
                       assoc session-id
                       (into {}
                             (map (fn [[call-id call]]
                                    [call-id (:state call)]))
                             calls))
                (swap! previous-message-lengths
                       assoc session-id
                       (into {}
                             (keep-indexed
                              (fn [message-index message]
                                (when (= :assistant (:role message))
                                  [(or (:message-id message)
                                       (:id message)
                                       (:call-id message)
                                       (str session-id "-assistant-"
                                            message-index))
                                   (count (or (:text message)
                                              (:content message)
                                              ""))])))
                             messages)))
              (catch Throwable _ nil)))
            (let [live @live-ids]
              (swap! previous-calls prune-to-live live)
              (swap! previous-message-lengths prune-to-live live))))
        publish-body!
        (fn []
          (try
            (let [listed (registry/list-sessions
                          registry {:include-deleted? false})
                  sessions (if (= :ok (:status listed))
                             (:sessions listed)
                             (throw (ex-info
                                     "session listing failed during snapshot"
                                     {:error/kind :session-list-failed
                                      :session-list listed
                                      :error (:error listed)})))
                  candidate (or (commands/active-session-id router)
                                (:session-id (first sessions)))
                  active (if (some #(= candidate (:session-id %)) sessions)
                           candidate
                           (:session-id (first sessions)))
                  config (settings-config)
                  components-snapshot
                  (component-snapshot components registry active
                                      component-cache)
                  ;; The owner snapshot version does not move when a history
                  ;; command changes only the live session (for example an
                  ;; applied compression).  Include the session revision and
                  ;; a bounded context fingerprint so the baseline cache is
                  ;; invalidated for those changes without bypassing the cache
                  ;; on every publish.
                  owner-versions
                  (into {}
                        (keep (fn [[session-id handle]]
                                (when (and handle
                                           (not (keyword? session-id)))
                                  (try
                                    (let [snapshot ((:snapshot handle))
                                          session (get-in snapshot
                                                          [:state :session])
                                          context (:context session)]
                                      [session-id
                                       [(:version snapshot)
                                        (:revision session)
                                        (hash (select-keys
                                               context
                                               [:compressions :boundary
                                                :recent-rounds]))]])
                                    (catch Throwable _ nil)))))
                        @owners)
                  epoch (commands/app-command-epoch router)
                  key (baseline-cache-key
                       sessions owner-versions active epoch
                       (:models config)
                       (:materials/revision components-snapshot))
                  outbound-opts-fn
                  (fn [session]
                    (outbound-context-opts
                     {:session session
                      :materials (:materials components)
                      :session-config (constantly config)}))
                  build-baseline
                  (fn []
                    (:snapshot
                     (baseline registry owners router config
                               {:phase :synced :command-epoch epoch}
                               sessions
                               outbound-opts-fn
                               baseline-session-cache)))
                  ;; The active session's outbound panel depends on live Owner
                  ;; context (compressions, boundary, materials).  A cache key
                  ;; cannot see every context mutation cheaply, so rebuild
                  ;; when the publisher supplies the live projection.  The
                  ;; per-session session-cache still bounds non-active work.
                  snapshot
                  (if outbound-opts-fn
                    (build-baseline)
                    (cached-baseline baseline-cache key build-baseline))
                  snap (merge snapshot components-snapshot)]
              (publish-run-events!)
              (let [outcome (publish-state! gateway @published-snapshot snap)]
                (if (:ok? outcome)
                  (do
                    (when (not= :noop (:mode outcome))
                      (reset! published-snapshot snap))
                    (swap! system-ref assoc :publish-error nil)
                    (when-let [delta-error (:delta-result outcome)]
                      (swap! system-ref assoc
                             :publish-fallback
                             {:message (or (get-in delta-error
                                                   [:error :message])
                                           "state delta publish failed")
                              :data (get-in delta-error [:error])})))
                  (swap! system-ref assoc
                         :publish-error
                         {:message (or (get-in outcome
                                              [:result :error :message])
                                       "state publish failed")
                          :class "hc.server.gateway"
                          :mode (:mode outcome)
                          :delta-error (get-in outcome
                                               [:delta-result :error])
                          :data (get-in outcome [:result :error])}))
                outcome))
            (catch Throwable t
              (swap! system-ref assoc :publish-error
                     {:message (or (.getMessage t) (str t))
                      :class (.getName (class t))
                      :data (ex-data t)})
              nil)))
        publish-now!
        (fn []
          (reset! scheduled? false)
          (when-not @stopped?
            (.lock publish-lock)
            (try
              (when-not @stopped?
                (publish-body!))
              (finally
                (.unlock publish-lock)))))
        schedule!
        (fn []
          (when (and (not @stopped?)
                     (compare-and-set! scheduled? false true))
            (try
              (.schedule executor ^Runnable publish-now! 25
                         TimeUnit/MILLISECONDS)
              (catch java.util.concurrent.RejectedExecutionException _
                (reset! scheduled? false)))))]
    (swap! system-ref assoc :schedule-publish! schedule!
           :publish-now! publish-now!)
    ;; Level-triggered fallback: even if an Owner notification is lost, the
    ;; browser baseline converges within one tick.  This is a state read, not
    ;; a fabricated domain event.
    (.scheduleWithFixedDelay executor ^Runnable publish-now!
                             0 500 TimeUnit/MILLISECONDS)
    ;; Lightweight recovery checkpoint.  Clients use this to re-query pending
    ;; command receipts without receiving a complete state snapshot; it does
    ;; not consume a feed sequence and carries no state payload.
    (.scheduleWithFixedDelay
     executor
     ^Runnable (fn []
                 (try
                   (gateway/broadcast!
                    gateway
                    {:type "CUSTOM"
                     :name "hc/sync-heartbeat"
                     :at (System/currentTimeMillis)})
                   (catch Throwable _ nil)))
     5000 5000 TimeUnit/MILLISECONDS)
    (doseq [entry @owners] (subscribe-owner! entry))
    (add-watch owners ::publisher
               (fn [_ _ _ new-owners]
                 (doseq [entry new-owners] (subscribe-owner! entry))
                 (schedule!)))
    {:executor executor
     :schedule! schedule!
     :publish-now! publish-now!
     :unsubscribes unsubscribe-all!
     :stopped? (fn [] @stopped?)
     :stop! (fn []
              (let [deadline (+ (System/nanoTime)
                                (* timeout-ms 1000000))
                    remaining-ms
                    (fn []
                      (max 0 (long (/ (- deadline (System/nanoTime))
                                      1000000))))
                    publisher-thread?
                    (.isHeldByCurrentThread publish-lock)]
                ;; Refuse new reads first.  A read that already passed the
                ;; check holds `publish-lock`; the executor wait below covers
                ;; scheduled reads and the lock wait covers direct calls.
                (compare-and-set! stopped? false true)
                (remove-watch owners ::publisher)
                (unsubscribe-all!)
                (if publisher-thread?
                  ;; A read cannot wait for itself, and interrupting the
                  ;; thread that owns it would not finish the native call.
                  ;; Report failure so the store stays open.
                  {:ok? false
                   :stopped? false
                   :terminated? false
                   :reentrant? true
                   :timeout-ms timeout-ms
                   :message
                   "publisher stop was called from its own read thread"}
                  (let [cancelled (.shutdownNow executor)
                        terminated?
                        (.awaitTermination executor (remaining-ms)
                                           TimeUnit/MILLISECONDS)
                        read-drained?
                        (when terminated?
                          (.tryLock publish-lock (remaining-ms)
                                    TimeUnit/MILLISECONDS))]
                    (when read-drained?
                      (.unlock publish-lock))
                    (let [stopped? (and terminated?
                                         (boolean read-drained?))]
                      {:ok? stopped?
                       :stopped? stopped?
                       :terminated? terminated?
                       :read-drained? (boolean read-drained?)
                       :timeout? (not stopped?)
                       :timeout-ms timeout-ms
                       :cancelled-tasks (count cancelled)})))))}))

;; ---------------------------------------------------------------------------
;; Start

(def ^:private critical-component-error-keys
  [:model-auth-error :model-error :speech-error :extension-error
   :materials-error :diagnostics-error :backup-error :artifacts-error
   :fleet-error :fleet-gc-error :search-error :scheduler-error
   :default-session-error])

(defn- fleet-unavailable?
  [error]
  (contains? #{:GIT_UNAVAILABLE :MASTER_REF_UNAVAILABLE} (:code error)))

(defn- normalize-blocked-error
  [error]
  (cond-> error
    (and (map? error) (:error error) (not (:code error)))
    (assoc :code (:error error))))

(defn- component-errors
  [system]
  (let [errors (into {}
                     (remove (comp nil? val))
                     (select-keys (or system {}) critical-component-error-keys))
        owners (:owner-errors system)
        retries (:retry-owner-errors system)]
    (cond-> errors
      (seq owners) (assoc :owner-errors owners)
      (seq retries) (assoc :retry-owner-errors retries))))

(defn- component-presence
  [system]
  {:model {:configured? (boolean (or (:model-port system) (:model-port-ref system)))}
   :model-auth {:started? (some? (:model-auth system))}
   :speech {:started? (some? (:speech system))}
   :extensions {:started? (some? (:extensions system))}
   :materials {:started? (some? (:materials system))}
   :diagnostics {:started? (some? (:diagnostics system))}
   :backup {:started? (some? (:backup system))}
   :artifacts {:started? (some? (:artifacts system))}
   :fleet (cond
            (:fleet system)
            {:configured? true :available? true :status :started}

            (:fleet-error system)
            {:configured? true :available? false :status :failed
             :reason (get-in system [:fleet-error :code])}

            (:fleet-unavailable system)
            {:configured? false :available? false :status :unavailable
             :reason (get-in system [:fleet-unavailable :code])}

            :else
            {:configured? false :available? false :status :unavailable})
   :fleet-gc (cond
               (:fleet-gc system)
               {:configured? true :started? true :status :started}

               (:fleet-gc-error system)
               {:configured? true :started? false :status :failed
                :reason (get-in system [:fleet-gc-error :code])}

               :else
               {:configured? false :started? false :status :unavailable})
   :search {:started? (some? (:search system))}
   :scheduler {:started? (some? (:scheduler system))}})

(defn- live-system
  [system]
  (or (some-> (:system-ref system) deref) system))

(defn- blocked-result
  [config entry]
  (cond-> {:blocked? true
           :config config
           :error (normalize-blocked-error (:error entry))
           :hint (:hint entry)
           :recovery-entry (:recovery-entry entry)
           :path (:path entry)}
    (:cleanup entry) (assoc :cleanup (:cleanup entry))
    (:partial-system entry) (assoc :partial-system (:partial-system entry))))

(defn- attachment-loader
  [config]
  (fn [attachment]
    (when-let [id (or (:send-blob-id attachment) (:blob-id attachment))]
      {:bytes (blob-store/read-bytes (:blob-root config) id)})))

(defn- resolve-credentials
  [secret-store models]
  (update models :connections
          (fn [connections]
            (mapv (fn [connection]
                    (if (seq (:credentials connection))
                      (let [resolved (credentials/resolve-credential
                                      secret-store connection
                                      (System/currentTimeMillis))]
                        (if (:ok? resolved)
                          (assoc connection :api-key (:secret resolved))
                          connection))
                      connection))
                  (or connections [])))))

(defn- credential-resolver
  [secret-store]
  (fn [{:keys [connection]}]
    (let [resolved (credentials/resolve-credential
                    secret-store connection (System/currentTimeMillis))]
      (if (:ok? resolved)
        {:ok? true :secret (:secret resolved)
         :connection (:connection resolved)}
        {:ok? false :error (:error resolved)}))))

(defn- model-config
  [config secret-store]
  (assoc (model-configuration (get config :models {}))
         :attachment-loader (attachment-loader config)
         :credential-resolver (credential-resolver secret-store)))

(defn- diagnostics-identity
  "Project one model request's diagnostics identity.

  The request snapshot carries `:connection` and `:model`, not the
  diagnostics `:service-id`/`:service-name`; without this projection every
  request had no service and the price form had no selectable service."
  [session-config]
  (fn [request _event]
    (let [snapshot (:snapshot request)
          connection-id (:connection snapshot)
          model-id (:model snapshot)
          config (try (when (fn? session-config) (session-config))
                      (catch Throwable _ nil))
          connection (get-in config [:connections connection-id])
          model (get-in connection [:models model-id])]
      (cond-> (select-keys snapshot
                           [:session-id :assistant-id :model :purpose
                            :temporary?])
        (some? connection-id)
        (assoc :service-id (str connection-id)
               :service-name (or (:name connection)
                                 (str connection-id)))
        (some? model-id)
        (assoc :model (or (:display-name model)
                          (:name model)
                          (str model-id)))))))

(defn- apply-models!
  [model-port-ref model-ports diagnostics-ref system-ref loader secret-store
   model-auth-ref next-models]
  (let [;; `settings/service` passes its committed document, which carries a
        ;; top-level `:revision` for compare-and-set.  The model adapter has a
        ;; closed schema and must not see it.  Accept both the flat settings
        ;; document and a caller that already wrapped it under `:models`.
        settings-models (or (:models next-models) next-models)
        base (assoc (model-configuration (dissoc settings-models :revision))
                    :attachment-loader loader
                    :credential-resolver (credential-resolver secret-store))
        {:keys [models errors]}
        (if-let [auth @model-auth-ref]
          (model-auth-app/inject-models auth base)
          {:models base :errors {}})
        next-models models]
    (swap! system-ref assoc :last-apply
           {:configured? (model-adapter/model-configured? next-models)
            :models (-> next-models
                        (dissoc :credential-resolver :attachment-loader)
                        (update :connections
                                (fn [connections]
                                  (mapv #(dissoc % :api-key) connections))))
            :auth-errors errors})
    (if (model-adapter/model-configured? next-models)
      (try
        (let [raw (model-adapter/create-model-port next-models)
              capture (diagnostics-capture/recording-port
                       raw
                       #(when-let [d @diagnostics-ref]
                          (diagnostics-service/record-request! d %)))]
          (swap! model-ports conj {:raw raw :capture capture})
          (reset! model-port-ref (:port capture))
          {:ok? true :applied? true})
        (catch Throwable t
          {:ok? false
           :error {:code :MODEL_RELOAD_FAILED
                   :message (or (.getMessage t) (str t))}}))
      (do
        ;; Removing every connection must also remove the live provider.
        ;; Keeping the old port would let the next turn silently reach a
        ;; model the operator just deleted.  The retired port stays in
        ;; `model-ports` so in-flight attempts keep their own handle and the
        ;; ordered stop path can close it.
        (reset! model-port-ref nil)
        {:ok? true :applied? true :cleared? true}))))

(defn- shell-stop-terminal
  "Map one `shell/request-stop!` receipt to a call terminal, or nil.

  Only an observed session end is evidence that the managed process ended.
  A stop signal, a registered stop, an unconfirmed stop, or a missing result
  proves nothing about the process, so no terminal is returned and the
  Owner keeps `:resource-alive?` set until the executor's own terminal
  arrives.

  Within `:SESSION_ENDED`, `:UNCERTAIN` is an unknown outcome, never a
  settlement.  Any other inner status is also unknown: the previous
  catch-all treated it as `:cancelled`, which let a live process be recorded
  as ended.

  `:published? false` is a pending board publication, not a live process:
  the session already observed the exit, so the process resource ends here
  and the board action keeps its own publish retry."
  [result]
  (let [inner (:result result)]
    (when (and (= :SESSION_ENDED (:status result))
               (map? inner))
      (let [exit (:exit-code inner)
            cancelled? (:cancelled? inner)
            status (case (:status inner)
                     :UNCERTAIN :outcome-unknown
                     :COMPLETED (if (and (integer? exit)
                                         (zero? exit)
                                         (not cancelled?))
                                  :succeeded
                                  :cancelled)
                     nil)]
        (when status
          {:status status
           :payload inner
           :error (:error inner)})))))

(defn- program-terminal-message
  "Map one real program terminal to an Owner `:call/terminal` message.

  `:cancel-requested?` receipts are the ones `cancel!` may have deferred
  while a call was in flight.  An `:OUTCOME_UNKNOWN` result maps to
  `:outcome-unknown`, which keeps `:resource-alive?` set; it is never a
  settlement.  A pending cancellation acknowledgement is not a terminal and
  yields nil."
  [result]
  (when (and (map? result)
             (some? (:call-id result))
             (not (true? (:cancel-pending? result))))
    (let [status (case (:status result)
                   (:COMPLETED :completed :APPLIED :applied) :succeeded
                   (:CANCELLED :cancelled) :cancelled
                   (:FAILED :failed) :failed
                   (:OUTCOME_UNKNOWN :outcome-unknown
                    :UNCERTAIN :uncertain) :outcome-unknown
                   :failed)]
      (cond-> {:type :call/terminal
               :call-id (:call-id result)
               :status status
               :payload result
               :source :program-runtime}
        (some? (:attempt-id result))
        (assoc :attempt-id (:attempt-id result))

        (some? (:error result))
        (assoc :error (:error result))))))

(defn- program-stop-terminal
  "Map one `program/cancel!` receipt to a call terminal, or nil.

  A program still inside a protected apply has not ended: its
  `:cancel-after-apply?` receipt acknowledges the stop while the minimal
  batch finishes, so it yields no terminal.  A `:CANCELLING` receipt and an
  `:OUTCOME_UNKNOWN` receipt also yield no terminal: the call has not
  returned or its end was not observed.  Only a fixed `:CANCELLED` receipt
  with no pending operation is evidence of an end; a deferred terminal
  arrives through the program runtime's `:on-terminal`."
  [result]
  (when (and (= :CANCELLED (:status result))
             (not (true? (:cancel-after-apply? result)))
             (not (true? (:cancel-pending? result)))
             (not (true? (:cancel-unconfirmed? result)))
             (empty? (:pending-operations result)))
    {:status :cancelled
     :payload result
     :error nil}))

(declare stop!)

(defn- cleanup-partial!
  [partial-system]
  (try
    (stop! @partial-system)
    (catch Throwable t
      {:stopped? false
       :drained? false
       :store-closed? false
       :failures [{:component :cleanup
                   :error {:message (or (ex-message t) (str t))}}]})))

(defn- start-failure-blocked
  [config t partial-system]
  (let [data (or (ex-data t) {})
        blocked (or (:recovery-blocked data)
                    {:status :blocked
                     :error {:code :START_FAILED
                             :message (or (ex-message t) (str t))
                             :class (.getName (class t))}
                     :hint "Inspect the data directory and the log, then retry."
                     :recovery-entry {:action :operator
                                      :options [:inspect-directory :restart]}
                     :path (str (get-in config [:data-dir]))})]
    (assoc blocked
           :cleanup (cleanup-partial! partial-system)
           :partial-system @partial-system)))

(defn- rethrow-start-failure!
  [config t partial-system]
  (throw (ex-info (or (ex-message t) "application start failed")
                  (assoc (or (ex-data t) {})
                         :recovery-blocked
                         (start-failure-blocked config t partial-system))
                  t)))

(defn- initialized-marker-path
  [config]
  (str (:data-dir config) "/initialized.edn"))

(defn- marker-present?
  [config]
  (.exists (io/file (initialized-marker-path config))))

(defn- prior-state-evidence?
  "True when the data directory carries evidence that a store existed before.

  An empty first-run data directory is not evidence.  A maintenance journal,
  a fleet state file, a non-empty blob directory or a non-empty worktree
  directory is."
  [config]
  (or (marker-present? config)
      (.exists (io/file (:data-dir config) "maintenance" "state.edn"))
      (.exists (io/file (:data-dir config) "fleet" "state.edn"))
      (let [blobs (io/file (:data-dir config) "blobs")]
        (and (.isDirectory blobs) (pos? (count (.listFiles blobs)))))
      (let [worktrees (io/file (:worktree-root config))]
        (and (.isDirectory worktrees) (pos? (count (.listFiles worktrees)))))))

(defn- check-store-presence!
  [config]
  (when (and (not (.exists (io/file (:db-path config))))
             (prior-state-evidence? config))
    (throw (ex-info
            "the current-state store is missing but this data directory was initialized"
            {:recovery-blocked
             {:status :blocked
              :error {:code :STORE_MISSING_AFTER_INIT
                      :error :STORE_MISSING_AFTER_INIT
                      :message
                      "the store directory is missing although this data directory contains prior state"
                      :data {:db-path (:db-path config)
                             :data-dir (:data-dir config)}}
              :hint
              "Do not initialize a fresh store over a data directory that already holds sessions or backups. Restore the store or choose another data directory."
              :path (:db-path config)
              :recovery-entry
              {:action :operator
               :options [:inspect-directory
                         :restore-from-backup
                         :choose-another-path]}}}))))

(defn- write-initialized-marker!
  [config store]
  (let [path (initialized-marker-path config)
        file (io/file path)
        value {:format-version 1
               :store-id (get-in store [:opts :store-id])
               :created-at (str (java.time.Instant/now))}]
    (when-not (.exists file)
      (io/make-parents file)
      (spit file (pr-str value))
      (try
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath file)
         (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
        (catch Exception _ nil)))
    value))

(defn- start-internal!
  "Start the integrated application from a normalized CONFIG map.

  Returns a system map.  When the store cannot be opened, the map has
  `:blocked? true` and no gateway, Owner, or listener was started."
  [config maintenance supervisor-restart-id]
  (let [config (config/ensure-directories! config)
        blocked-journal (try
                          (maintenance-journal/startup-blocking-state
                           config
                           {:supervisor-restart-id supervisor-restart-id})
                          (catch clojure.lang.ExceptionInfo e
                            (throw (ex-info (or (ex-message e)
                                                "maintenance journal is invalid")
                                            {:recovery-blocked
                                             {:error (or (ex-data e)
                                                         {:code
                                                          :BACKUP_MAINTENANCE_JOURNAL_INVALID
                                                          :message
                                                          "maintenance journal is invalid"})
                                              :hint
                                              "Inspect <data-dir>/maintenance/state.edn and the store before starting."
                                              :recovery-entry
                                              {:action :operator
                                               :options [:inspect-restore-journal]}
                                              :path
                                              (str (get-in config [:data-dir])
                                                   "/maintenance/state.edn")}}))))
        partial-system (atom {:blocked? true :partial? true :config config})]
    (try
      (if blocked-journal
      (let [offline? (maintenance-journal/offline-record? blocked-journal)
            unfinished? (maintenance-journal/active-status?
                         (:status blocked-journal))
            needs-recovery? (true? (:needs-recovery? blocked-journal))
            commit-unproven? (true? (:commit-unproven? blocked-journal))
            source-label (if offline? "offline" "supervised")
            incomplete-code (if offline?
                              :BACKUP_OFFLINE_RESTORE_INCOMPLETE
                              :BACKUP_MAINTENANCE_RESTORE_FAILED)
            failed-code (if offline?
                          :BACKUP_OFFLINE_RESTORE_FAILED
                          :BACKUP_MAINTENANCE_RESTORE_FAILED)
            acknowledge-code
            (if (or needs-recovery?
                    (= :failed-needs-recovery
                       (keyword (:status blocked-journal))))
              "inspect-and-accept-partial"
              "inspect-accept-unknown-commit")
            failure-detail
            (cond
              needs-recovery?
              " after storage was committed; the store may hold restored data"

              commit-unproven?
              "; the storage transaction could be neither proven nor refuted"

              (= :failed-needs-attention (keyword (:status blocked-journal)))
              "; the restore stopped before its commit evidence was recorded"

              :else
              "; no commit evidence was recorded")]
        (blocked-result
         config
         {:error (cond-> {:code (if unfinished? incomplete-code failed-code)
                          :message
                          (if unfinished?
                            (str "A " source-label
                                 " restore did not finish; the application will not open a router until an operator reconciles it")
                            (str "A " source-label " restore failed"
                                 failure-detail
                                 ". The application stays blocked until an operator acknowledges the result"))
                          :maintenance-id (:maintenance-id blocked-journal)
                          :status (:status blocked-journal)
                          :needs-recovery? needs-recovery?
                          :commit-unproven? commit-unproven?})
          :hint
          (if unfinished?
            "Inspect the restore result in <data-dir>/maintenance/state.edn. Do not re-run the restore automatically."
            (str "Compare <data-dir>/maintenance/state.edn with the store and any recovery points, "
                 "then record the comparison: `pixi run backup status --data-dir <dir> --db-path <db>`, "
                 "then `pixi run backup acknowledge --data-dir <dir> --maintenance-id <id> --operator <name> "
                 "--acknowledge " acknowledge-code
                 " --state-digest <digest> --restore-digest <digest> --store-changed true`. "
                 "The CLI requires --store-changed true after you compare or repair the store; "
                 "do not edit state.edn by hand and do not re-run the restore automatically."))
          :recovery-entry
          {:action :operator
           :options (if unfinished?
                      [:inspect-restore-journal
                       :restore-from-another-backup
                       :choose-another-path]
                      [:inspect-restore-journal
                       :acknowledge-restore-failure
                       :restore-from-another-backup
                       :choose-another-path])}
          :path (str (get-in config [:data-dir]) "/maintenance/state.edn")}))
      (let [process-generation (recovery/new-generation)
            _ (check-store-presence! config)
            entry (recovery/recovery-entry {:path (:db-path config)
                                            :create? true})]
        (if (recovery/blocked? entry)
          (blocked-result config entry)
          (let [store (:store entry)
                ;; From this point the application owns the close order.  If
                ;; the JVM starts shutting down before the hc-shutdown hook
                ;; runs, Datalevin's hook waits for `close-store!` instead of
                ;; closing the environment under a live publisher read.
                _ (storage/expect-ordered-close! store)
                restore-safety (backup-domain/restore-safety store)
                _ (write-initialized-marker! config store)
                _ (swap! partial-system assoc :store store)
                _ (when-let [open-hook (get-in maintenance [:store-opened!])]
                    (let [rotation (try
                                     (open-hook store)
                                     (catch Throwable t
                                       {:ok? false
                                        :error {:code :COMMAND_EPOCH_ROTATE_FAILED
                                                :message
                                                (or (ex-message t) (str t))}}))]
                      (when-not (:ok? rotation)
                        (try (storage/close-store! store)
                             (catch Throwable _ nil))
                        (throw (ex-info
                                "the restored command epoch could not be fenced"
                                {:recovery-blocked
                                 {:status :blocked
                                  :error (normalize-blocked-error
                                          (or (:error rotation)
                                              {:code
                                               :COMMAND_EPOCH_ROTATE_FAILED
                                               :message
                                               "command epoch rotation failed"}))
                                  :hint
                                  "The store opened but its restored command window could not be fenced. Inspect the maintenance journal and retry."
                                  :path (str (storage/store-path store))
                                  :recovery-entry
                                  {:action :operator
                                   :options [:inspect-restore-journal
                                             :inspect-directory
                                             :restart]}}})))))
                persistence (session-persistence/persistence store)
                ;; Keep the launch model configuration separate from the
                ;; effective one: the settings service needs the launch
                ;; connection set to decide which persisted connections an
                ;; operator has hidden by editing the launch file.
                launch-config config
                config (effective-config config persistence)
                _ (swap! partial-system assoc :store store :persistence persistence
                         :config config)
                ;; Recovery and the Owner-state preflight run before any
                ;; component or external listener starts.  A failure closes
                ;; the store and returns the blocked recovery entry without
                ;; leaving a model port, Owner, or gateway behind.
                session-recovery (recover-all! store process-generation)
                _ (verify-owner-states! store persistence)
                gateway-opts (gateway-options config)
                system-ref partial-system
            secret-store (secrets/file-secret-store
                          {:directory (str (:data-dir config) "/secrets")})
            model-auth-component
            (try (model-auth-app/start! config)
                 (catch Throwable t
                   (swap! system-ref assoc :model-auth-error
                          {:code :MODEL_AUTH_START_FAILED
                           :message (or (.getMessage ^Throwable t) (str t))})
                   nil))
            model-port-ref (atom nil)
            model-ports (atom [])
            diagnostics-ref (atom nil)
            model-auth-ref (atom model-auth-component)
            settings-service (settings/service
                              {:persistence persistence
                               :config (model-configuration
                                        (get launch-config :models {}))
                               :secret-store secret-store
                               :apply! (clojure.core/partial apply-models!
                                                model-port-ref model-ports
                                                diagnostics-ref system-ref
                                                (attachment-loader config)
                                                secret-store model-auth-ref)})
            _ (swap! partial-system assoc :model-auth model-auth-component
                     :settings settings-service :model-port-ref model-port-ref
                     :model-ports model-ports)
            ;; One reader for both session-creation binding and the Owner
            ;; `:session/select-model` path.  It reads the committed settings
            ;; configuration at call time, so a live save reaches the next
            ;; command without a restart; existing bindings and run snapshots
            ;; are never rewritten.
            live-session-config (fn []
                                  (session-config/session-config
                                   (settings/config settings-service)
                                   (:assistants
                                    (settings/document settings-service))))
            ;; The scheduler runs for the whole process lifetime.  It must
            ;; read the same committed settings body as session creation, not
            ;; the launch snapshot, or a model saved after start leaves its
            ;; preflight at MODEL_UNAVAILABLE until the next restart.
            live-scheduler-config (fn []
                                    {:models (model-configuration
                                              (settings/config settings-service))
                                     :language (:language config :en)})
            ;; Snapshots must project the committed settings configuration,
            ;; not the launch config captured at startup.  A save updates
            ;; the settings service; a later publish must show the new
            ;; connections and roles.
            live-settings-config (fn []
                                   {:models (settings/config settings-service)})
            shared-gate (wg/write-gate)
            documents-component (documents/service)
            owners (atom {})
            fleet-ref (atom nil)
            browser-services (atom {})
            extensions-ref (atom nil)
            artifacts-ref (atom nil)
            attachments-ref (atom nil)
            search-contexts (atom {})
            search-ref (atom nil)
            notify-ref (atom nil)
            registry-ref (atom nil)
            materials-ref (atom nil)
            raw-model-port (try
                             (when (model-adapter/model-configured?
                                    (model-configuration
                                     (get config :models {})))
                               (let [base (model-config config secret-store)
                                     {:keys [models]}
                                     (if model-auth-component
                                       (model-auth-app/inject-models
                                        model-auth-component base)
                                       {:models base})]
                                 (model-adapter/create-model-port models)))
                             (catch Throwable t
                               (swap! system-ref assoc :model-error
                                      {:code :MODEL_PORT_FAILED
                                       :message (.getMessage t)})
                               nil))
            diagnostics-capture
            (when raw-model-port
              (diagnostics-capture/recording-port
               raw-model-port
               #(when-let [d @diagnostics-ref]
                  (diagnostics-service/record-request! d %))
               {:identity-fn
                (diagnostics-identity live-session-config)}))
            model-port (or (:port diagnostics-capture) raw-model-port)
            _ (swap! partial-system assoc :model-port model-port
                     :diagnostics-capture diagnostics-capture)
            _ (reset! model-port-ref model-port)
            _ (when diagnostics-capture
                (swap! model-ports conj {:raw raw-model-port
                                         :capture diagnostics-capture}))
            owner-factory (session-owner-factory
                           {:store store
                            :persistence persistence
                            :config config
                            ;; The session port resolves the committed model
                            ;; configuration per command through this one
                            ;; reader, never a copy of `config`.
                            :session-config live-session-config
                            :model-port-ref model-port-ref
                            :materials-ref materials-ref
                            :registry-ref registry-ref
                            :mission-port (mission-port)
                            :shared-gate shared-gate
                            :owners owners
                            :browser-services browser-services
                            :search-contexts search-contexts
                            :search-ref search-ref
                            :diagnostics-ref diagnostics-ref
                            :system-ref system-ref
                            :fleet-ref fleet-ref
                            :extensions-ref extensions-ref
                            :artifacts-ref artifacts-ref
                            :notify-ref notify-ref})
            session-registry (registry/registry
                              {:persistence persistence
                               :owner-factory owner-factory})
            _ (reset! registry-ref session-registry)
            _ (swap! partial-system assoc :registry session-registry
                     :owners owners :browser-services browser-services
                     :attachments-ref attachments-ref
                     :search-contexts search-contexts :search-ref search-ref
                     :notify-ref notify-ref :materials-ref materials-ref)
            speech-component
            (try
              (speech/start! {:data-dir (:data-dir config)
                              :config (:speech config)
                              :usage-port
                              (fn [usage]
                                (when-let [d @diagnostics-ref]
                                  (diagnostics-service/record-service-usage!
                                   d usage)))})
              (catch Throwable t
                (swap! system-ref assoc :speech-error
                       {:code :SPEECH_START_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            extension-component
            (try
              (extensions-app/start! config)
              (catch Throwable t
                (swap! system-ref assoc :extension-error
                       {:code :EXTENSION_COMPONENT_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            _ (reset! extensions-ref extension-component)
            materials-service
            (try
              (let [service (materials-session/service
                             (materials-durable/durable-store store)
                             ;; Follow live settings saves; the materials
                             ;; service must see the port rebuilt by
                             ;; `apply-models!`, not a startup snapshot.
                             (fn [] @model-port-ref))
                    initialized (materials-session/initialize! service)]
                (if (:ok? initialized)
                  (do (reset! materials-ref service) service)
                  (do (swap! system-ref assoc :materials-error
                             {:code :MATERIALS_INIT_FAILED
                              :error (:error initialized)})
                      nil)))
              (catch Throwable t
                (swap! system-ref assoc :materials-error
                       {:code :MATERIALS_INIT_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            _ (swap! partial-system assoc :speech speech-component
                     :extensions extension-component
                     :materials materials-service)
            diagnostics-component
            (try
              (let [service (diagnostics-integration/attach
                             {:store store
                              :blob-root (:blob-root config)
                              :app-version version/version})]
                (reset! diagnostics-ref service)
                service)
              (catch Throwable t
                (swap! system-ref assoc :diagnostics-error
                       {:code :DIAGNOSTICS_ATTACH_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            backup-service
            (try
              (backup-app/service {:store store
                                   :data-dir (:data-dir config)
                                   :maintenance maintenance
                                   :config config})
              (catch Throwable t
                (swap! system-ref assoc :backup-error
                       {:code :BACKUP_ATTACH_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            artifacts-component
            (try
              (artifacts-integration/attach
               {:store store
                :blob-root (:blob-root config)
                :config config})
              (catch Throwable t
                (swap! system-ref assoc :artifacts-error
                       {:code :ARTIFACTS_ATTACH_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            _ (reset! artifacts-ref artifacts-component)
            _ (swap! partial-system assoc :diagnostics diagnostics-component
                     :backup backup-service
                     :artifacts artifacts-component)
            fleet-attempts (atom {})
            fleet-supervisor-ref (atom nil)
            fleet-start-ref (atom nil)
            fleet-start-effect! (fn [effect] (@fleet-start-ref effect))
            fleet-gateway (model-gateway model-port-ref nil registry-ref
                                          "fleet" true (atom nil)
                                          search-contexts search-ref
                                          diagnostics-ref
                                          (model-supports-tools? config))
            fleet-holder-resolver
            (fleet-conflict/make-board-resolver
             {:git (fleet-git/git-cli (:workspace-root config))
              :service-ref fleet-ref
              :start-effects!
              (fn [effects]
                (if-let [supervisor @fleet-supervisor-ref]
                  ((:start-effects! supervisor) effects)
                  {:ok? false
                   :error {:code :FLEET_SUPERVISOR_UNAVAILABLE
                           :message "supervisor is not ready to start holder effects"}}))
              :timeout-ms 300000})
            fleet-supervisor-instance
            (fleet-supervisor/make
             {:service-ref fleet-ref
              :start-effect! fleet-start-effect!
              :cancel-effect! fleet-start-effect!
              :execute-tool!
              (fn [{:keys [runtime attempt board-snapshot event]}]
                (let [svc @fleet-ref
                      runtimes (when svc (fleet/worktree-runtimes svc))
                      request (merge (or (:arguments event) {})
                                     {:workspace (:workspace runtime)
                                      :tenure-id (:tenure-id attempt)
                                      :agent-id (:agent-id attempt)
                                      :role (program-role-for-attempt attempt)
                                      :program-role
                                      (program-role-for-attempt attempt)
                                      :holder? (= :holder (:role attempt))
                                      :s1? false
                                      :write-unlocked? false
                                      :board-snapshot board-snapshot
                                      :tool-call-id (:call-id event)})]
                  (mission-worktree/execute-program! runtimes request)))
              :resolve-conflict! fleet-holder-resolver})
            _ (reset! fleet-supervisor-ref fleet-supervisor-instance)
            fleet-models (when (and (get-in config [:models :roles :main :model])
                                    (get-in config [:models :roles :task :model]))
                           {:main (get-in config [:models :roles :main :model])
                            :task (get-in config [:models :roles :task :model])})
            _ (reset!
               fleet-start-ref
               (fn [effect]
                 (cond
                   (= :model/start (:type effect))
                   (let [swarm-id (:swarm-id effect)
                         attempt-id (:attempt-id effect)
                         actor-request (:request effect)
                         request {:attempt-id attempt-id
                                  :call-id attempt-id
                                  :snapshot (assoc actor-request
                                                   :session-id "fleet"
                                                   :role :task
                                                   :model (:model-id actor-request)
                                                   :stream? true)}
                         handle
                         (try
                           ((:start! fleet-gateway)
                            request
                            (fn [event]
                              (when (contains? #{:completed :failed :cancelled}
                                               (:event event))
                                (swap! fleet-attempts dissoc
                                       [swarm-id attempt-id]))
                              (when-let [supervisor @fleet-supervisor-ref]
                                ((:handle-event! supervisor) swarm-id event))))
                           (catch Throwable t
                             {:fleet-start-error
                              {:code :FLEET_MODEL_START_FAILED
                               :message (or (.getMessage t) (str t))
                               :swarm-id swarm-id
                               :attempt-id attempt-id}}))]
                     (if (and (map? handle) (:fleet-start-error handle))
                       {:ok? false :error (:fleet-start-error handle)}
                       (if (nil? handle)
                         {:ok? false
                          :error {:code :FLEET_MODEL_START_FAILED
                                  :message "the model gateway returned no handle"
                                  :swarm-id swarm-id
                                  :attempt-id attempt-id}}
                         (do
                           (swap! fleet-attempts assoc
                                  [swarm-id attempt-id] handle)
                           {:ok? true}))))

                   (= :model/cancel (:type effect))
                   (cancel-fleet-attempt! fleet-gateway fleet-attempts
                                          system-ref
                                          (:swarm-id effect)
                                          (:attempt-id effect))

                   :else
                   {:ok? false
                    :error {:code :UNSUPPORTED_FLEET_EFFECT
                            :message "no real handler is wired for this fleet effect"
                            :type (:type effect)}})))
            fleet-service
            (try
              (let [started
                    (fleet/start!
                     {:mission-id "steward"
                      :repo (:workspace-root config)
                      :worktree-root (:worktree-root config)
                      :master-ref (or (get-in config [:fleet :master-ref])
                                      "master")
                      :retention-ms (get-in config [:fleet :retention-ms])
                      :preflight? (get-in config [:fleet :preflight?] true)
                      :state-file (get-in config [:fleet :state-file])
                      :models fleet-models
                      :slot-ids [:slot-a :slot-b]
                      :evaluate-factory (worktree-evaluate-factory config)
                      :launch-swarm! (:launch fleet-supervisor-instance)
                      :stop-swarm!
                      (fn [{:keys [swarm-id]}]
                        (doseq [[[sid attempt-id] handle] @fleet-attempts
                                :when (= sid swarm-id)]
                          (when-let [port @model-port-ref]
                            (try
                              (model-contract/cancel-request! port handle)
                              (catch Throwable _ nil)))
                          (swap! fleet-attempts dissoc [sid attempt-id]))
                        {:ok? true})
                      :resolve-conflict! (:resolve-conflict! fleet-supervisor-instance)})]
                (if (:ok? started)
                  (do (reset! fleet-ref (:service started))
                      (:service started))
                  (let [error (:error started)]
                    (swap! system-ref assoc
                           (if (fleet-unavailable? error)
                             :fleet-unavailable
                             :fleet-error)
                           error)
                    nil)))
              (catch Throwable t
                (swap! system-ref assoc :fleet-error
                       {:code :FLEET_START_FAILED
                        :message (or (.getMessage ^Throwable t) (str t))})
                nil))
            fleet-gc-instance
            (when fleet-service
              (try
                (let [started
                      (fleet-gc/start!
                       {:service fleet-service
                        :interval-ms (or (get-in config [:fleet :gc-interval-ms])
                                         fleet-gc/default-interval-ms)
                        :clock (:clock fleet-service)
                        :run-immediately?
                        (not (false? (get-in config
                                             [:fleet :gc-run-immediately?])))})]
                  (if (:ok? started)
                    (:gc started)
                    (do
                      (swap! system-ref assoc :fleet-gc-error (:error started))
                      nil)))
                (catch Throwable t
                  (swap! system-ref assoc :fleet-gc-error
                         {:code :FLEET_GC_START_FAILED
                          :message (or (.getMessage ^Throwable t) (str t))})
                  nil)))
            search-service (try
                             (search/service {:config (:search config)
                                              :env (System/getenv)})
                             (catch Throwable t
                               (swap! system-ref assoc :search-error
                                      {:code :SEARCH_START_FAILED
                                       :message (or (.getMessage ^Throwable t) (str t))})
                               nil))
            _ (reset! search-ref search-service)
            _ (swap! partial-system assoc :fleet-supervisor fleet-supervisor-instance
                     :fleet-attempts fleet-attempts
                     :fleet fleet-service :fleet-gc fleet-gc-instance
                     :search search-service)
            scheduler-ref (atom nil)
            startup-publish-error (atom nil)
            components {:materials materials-service
                        :diagnostics diagnostics-component
                        :extensions extension-component
                        :speech speech-component
                        :search search-service
                        :documents documents-component
                        :artifacts artifacts-component
                        :fleet fleet-service
                        :model-auth model-auth-component
                        :scheduler nil}
            router (commands/router {:registry session-registry
                                     :settings settings-service
                              :secret-store secret-store
                              :extensions extension-component
                                     :speech speech-component
                                     :materials materials-service
                                     :diagnostics diagnostics-component
                                     :backup backup-service
                                     :search search-service
                                     :scheduler-ref scheduler-ref
                                     :session-config live-session-config
                                     :documents documents-component
                                     :model-auth model-auth-component
                                     :browser-services browser-services
                                     :attachments-ref attachments-ref
                                     :fleet-ref fleet-ref
                                     :dedup-store
                                     (command-storage/store
                                      store {:partition "commands/app" :id "app"})
                                     :dedup-store-for
                                     (fn [session-id]
                                       (command-storage/store
                                        store
                                        {:partition (str "commands/session/"
                                                         session-id)
                                         :id session-id}))
                                     :on-change (fn [_]
                                                  (when-let [f @notify-ref] (f)))})
            _ (swap! partial-system assoc :router router)
            listed (registry/list-sessions session-registry
                                           {:include-deleted? false})
            existing (ensure-session-list! store listed)
            owner-errors (atom {})
            _ (doseq [session existing]
                (try
                  (let [handle (owner-factory {:session-id (:session-id session)})]
                    ;; The registry registration serves command dispatch;
                    ;; the application `owners` atom is the baseline,
                    ;; publisher and health view.  A restored session must
                    ;; appear in both, or the client never receives its
                    ;; command epoch.
                    (commands/register-owner! router (:session-id session) handle)
                    (when handle
                      (swap! owners assoc (:session-id session) handle)))
                  (catch Throwable t
                    (println :owner-start-failed (:session-id session)
                             (str (class t)) (str t))
                    (swap! owner-errors assoc
                           (:session-id session)
                           {:code :OWNER_START_FAILED
                            :message (or (.getMessage ^Throwable t) (str t))
                            :class (.getName (class t))}))))
            default-session-error (atom nil)
            _ (when (empty? existing)
                (try
                  (let [created (registry/create-session!
                                 session-registry
                                 ;; The first session must bind the live
                                 ;; default main model, exactly like a
                                 ;; client-created session.  Omitting the
                                 ;; config left the startup "New session"
                                 ;; unbound even when a model was configured.
                                 {:title "New session"
                                  :config (live-session-config)})
                        session (:session created)
                        error (:error created)]
                    (if (and (= :created (:status created))
                             (:owner created))
                      (commands/register-owner!
                       router (:session-id session) (:owner created))
                      (let [failure (or error
                                        {:code :DEFAULT_SESSION_FAILED
                                         :message
                                         "session creation returned no Owner"})]
                        (reset! default-session-error failure)
                        (when session
                          (swap! owner-errors assoc
                                 (:session-id session)
                                 (assoc failure
                                        :cause error))))))
                  (catch Throwable t
                    (reset! default-session-error
                            {:code :DEFAULT_SESSION_FAILED
                             :message (or (.getMessage t) (str t))
                             :class (.getName (class t))}))))
            _ (when (and (empty? existing) @default-session-error)
                (throw (ex-info
                        "no session Owner could be created for a fresh store"
                        {:recovery-blocked
                         {:status :blocked
                          :error (assoc @default-session-error
                                        :error :DEFAULT_SESSION_FAILED)
                          :hint
                          "The store opened but the first session Owner could not be created. Inspect the store or restore a backup."
                          :path (str (storage/store-path store))
                          :recovery-entry
                          {:action :operator
                           :options [:inspect-directory
                                     :restore-from-backup
                                     :choose-another-path]}}})))
            _ (when-let [e @default-session-error]
                (swap! system-ref assoc :default-session-error e))
            retry-owner-errors
            (start-retry-owners! session-registry
                                 (:pending-retries session-recovery))
            baseline-snapshot
            (baseline session-registry owners router (live-settings-config)
                      {:phase :synced} existing
                      (fn [session]
                        (outbound-context-opts
                         {:session session
                          :materials materials-service
                          :session-config live-settings-config})))
            initial-snapshot
            (merge (:snapshot baseline-snapshot)
                   (component-snapshot
                    {:materials materials-service
                     :diagnostics diagnostics-component
                     :extensions extension-component
                     :speech speech-component
                     :search search-service
                     :documents documents-component
                     :artifacts artifacts-component
                     :fleet fleet-service
                     :model-auth model-auth-component
                     :scheduler nil}
                    session-registry
                    (:active-session baseline-snapshot)))
            blob-port (gateway-ports/blob-store-port (:blob-root config))
            attachment-extractor
            {:extract!
             (fn [{:keys [source stream selection max-bytes password]}]
               (documents/extract!
                (cond-> {:source source
                         :stream stream
                         :selection selection}
                  max-bytes (assoc :max-bytes max-bytes)
                  password (assoc :password password))))}
            attachments-service (attachments/service
                                 {:blob-store blob-port
                                  :extractor attachment-extractor
                                  :workspace-root (:workspace-root config)})
            _ (reset! attachments-ref attachments-service)
            gateway-handle
            (gateway/start! (merge gateway-opts
                                   {:sch-adapter
                                    (sente-http-kit/get-sch-adapter)
                                    :owner router
                                    :store blob-port
                                    :fallback
                                    (static/handler (:static-root config))
                                    :snapshot initial-snapshot
                                    :content-type-fn
                                    (:content-type-fn artifacts-component)
                                    :now-fn #(System/currentTimeMillis)}))
            _ (swap! partial-system assoc :gateway gateway-handle)
            scheduler-start
            (try
              (scheduler/start! {:storage store
                                 :registry session-registry
                                 :config live-scheduler-config
                                 :gateway gateway-handle
                                 :generation process-generation
                                 :tick-ms (get-in config [:scheduler :tick-ms] 1000)})
              (catch Throwable t
                {:ok? false
                 :error {:code :SCHEDULER_START_FAILED
                         :message (or (.getMessage ^Throwable t) (str t))}}))
            _ (when (:ok? scheduler-start)
                (reset! scheduler-ref (:scheduler scheduler-start)))
            _ (swap! partial-system assoc :scheduler (:scheduler scheduler-start)
                     :scheduler-error (when-not (:ok? scheduler-start)
                                        (:error scheduler-start)))
            _ (when (:ok? scheduler-start)
                (try
                  (let [outcome
                        (publish-state!
                         gateway-handle
                         initial-snapshot
                         (merge initial-snapshot
                                (component-snapshot
                                 {:materials materials-service
                                  :diagnostics diagnostics-component
                                  :extensions extension-component
                                  :speech speech-component
                                  :search search-service
                                  :documents documents-component
                                  :artifacts artifacts-component
                                  :fleet fleet-service
                                  :model-auth model-auth-component
                                  :scheduler (:scheduler scheduler-start)}
                                 session-registry
                                 (:active-session baseline-snapshot))))]
                    (when-not (:ok? outcome)
                      (reset! startup-publish-error
                              {:message (or (get-in outcome
                                                   [:result :error :message])
                                            "startup state publish failed")
                               :class "hc.server.gateway"
                               :mode (:mode outcome)
                               :data (get-in outcome [:result :error])})))
                  (catch Throwable t
                    (reset! startup-publish-error
                            {:message (or (.getMessage t) (str t))
                             :class (.getName (class t))
                             :data (ex-data t)}))))
            health-system (fn []
                            {:store-open? #(storage/store-open? store)
                             :session-count (fn []
                                              (let [r (registry/list-sessions
                                                       session-registry
                                                       {:include-deleted? false})]
                                                (when (= :ok (:status r))
                                                  (count (:sessions r)))))
                             :gateway-stats #(gateway/stats gateway-handle)
                             :model-configured? (some? model-port)
                             :owners (merge (:owner-errors @system-ref)
                                            (into {}
                                           (map (fn [[session-id handle]]
                                                  [session-id
                                                   (try
                                                     (let [snapshot ((:snapshot handle))
                                                           state (:state snapshot)]
                                                       {:version (:version snapshot)
                                                        :conversation (count (:conversation state))
                                                        :pending-turns (count (:pending-turns state))
                                                        :calls (into {}
                                                                     (map (fn [[call-id call]]
                                                                            [call-id (:state call)]))
                                                                     (:calls state))
                                                        :turn? (some? (:turn state))})
                                                     (catch Throwable t
                                                       {:error (or (.getMessage t) (str t))}))]))
                                           @owners))
                             :browser (into {}
                                            (map (fn [[session-id service]]
                                                   [session-id
                                                    (try (browser/health service)
                                                         (catch Throwable _ nil))]))
                                            @browser-services)
                             :browser-errors (:browser-errors @system-ref)
                             :publish-error (:publish-error @system-ref)
                             :components (component-presence @system-ref)
                             :component-errors (component-errors @system-ref)
                             :config config
                             :started-at (System/currentTimeMillis)})
            health-handler
            (gateway-auth/wrap (health/handler (health-system))
                               gateway-opts)
            files-service (files/service {:root (:workspace-root config)})
            files-handler (files-http/handler files-service)
            model-auth-handler
            (when (and model-auth-component
                       (= :external (get-in config [:model-auth :callback-mode])))
              ;; OAuth callbacks are top-level cross-origin navigations.  The
              ;; one-shot random state is the protection; gateway origin
              ;; checks would reject the provider's Referer.
              (model-auth-app/ring-handler model-auth-component))
            ring-handler (fn [request]
                           (or (when (and model-auth-handler
                                          (= "/model-auth/callback" (:uri request)))
                                 (model-auth-handler request))
                               ((gateway-auth/wrap
                                 #(attachments-http/handle attachments-service %)
                                 gateway-opts) request)
                               ((gateway-auth/wrap
                                 (miniapps/handler config model-port-ref shared-gate)
                                 gateway-opts) request)
                               ((gateway-auth/wrap files-handler
                                                   gateway-opts) request)
                               (when speech-component
                                 ((gateway-auth/wrap
                                   (speech/handler speech-component)
                                   gateway-opts) request))
                               (if (= "/healthz" (:uri request))
                                 (health-handler request)
                                 ((gateway/handler gateway-handle) request))))
            listener (http/start! {:handler ring-handler
                                   :host (:host config)
                                   :port (:port config)
                                   :max-body (+ attachments/max-attachment-bytes
                                                 (* 1024 1024))})
            _ (swap! partial-system assoc :http listener :attachments attachments-service)
            system (-> config
                       (assoc :blocked? false
                              :partial? false
                              :system-ref system-ref
                              :store store
                              :persistence persistence
                              :settings settings-service
                              :secret-store secret-store
                              :model-auth model-auth-component
                              :registry session-registry
                              :extensions extension-component
                              :speech speech-component
                              :attachments attachments-service
                              :documents documents-component
                              :artifacts artifacts-component
                              :fleet fleet-service
                              :fleet-gc fleet-gc-instance
                              :fleet-supervisor fleet-supervisor-instance
                              :fleet-attempts fleet-attempts
                              :files files-service
                              :materials materials-service
                              :diagnostics diagnostics-component
                              :backup backup-service
                              :search search-service
                              :scheduler (:scheduler scheduler-start)
                              :scheduler-recovery (:recovery scheduler-start)
                              :session-recovery session-recovery
                              :process-generation process-generation
                              :restore-safety restore-safety
                              :retry-owner-errors retry-owner-errors
                              :scheduler-error (when-not (:ok? scheduler-start)
                                                 (:error scheduler-start))
                              :router router
                              :owners owners
                              :owner-errors @owner-errors
                              :publish-error @startup-publish-error
                              :browser-services browser-services
                              :model-port model-port
                              :model-port-ref model-port-ref
                              :model-ports model-ports
                              :raw-model-port raw-model-port
                              :diagnostics-capture diagnostics-capture
                              :gateway gateway-handle
                              :http listener
                              :url (http/url listener)
                              :started-at (System/currentTimeMillis)
                              :shared-gate shared-gate))]
      (reset! system-ref (merge @system-ref system))
      (let [publisher (start-publisher {:registry session-registry
                                        :owners owners
                                        :router router
                                        :settings-config live-settings-config
                                        :gateway gateway-handle
                                        :system-ref system-ref
                                        :stop-timeout-ms
                                        (get-in config
                                                [:maintenance
                                                 :publisher-stop-timeout-ms])
                                        :components {:materials materials-service
                                                     :diagnostics diagnostics-component
                                                     :extensions extension-component
                                                     :speech speech-component
                                                     :search search-service
                                                     :documents documents-component
                                     :model-auth model-auth-component
                                     :browser-services browser-services
                                     :attachments-ref attachments-ref
                                     :search-contexts search-contexts
                                                     :artifacts artifacts-component
                                                     :scheduler (:scheduler scheduler-start)}})]
        (reset! notify-ref (:publish-now! publisher))
        (swap! system-ref assoc :publisher publisher)
        @system-ref)))))
      (catch Throwable t
        (rethrow-start-failure! config t partial-system)))))

(defn start!
  "Start the application, converting a failed recovery scan into the same
  blocked operator entry used for an unopenable store.

  CONFIG must have passed `hc.server.app.config/normalize`; the gateway
  policy is read from its validated `:gateway` section.

  The optional second argument carries process-level services.  `:maintenance`
  is the supervised restore handler map passed to the backup service.
  `:maintenance-id` is supplied only by the supervisor's own in-process
  restart; it lets that one `:restarting` record through the start gate.
  Omitting the map starts an honest offline-only backup adapter; it never
  pretends an in-process restore supervisor exists."
  ([config]
   (start! config {}))
  ([config {:keys [maintenance maintenance-id]}]
   (try
     (start-internal! config maintenance maintenance-id)
     (catch clojure.lang.ExceptionInfo e
       (if-let [blocked (:recovery-blocked (ex-data e))]
         (blocked-result config blocked)
         (throw e))))))

(defn health
  "Return the health snapshot for a started system.

  Reads the live system atom so publish/browser/component failures that
  happened after startup are visible to `--check` and the maintenance
  restart gate."
  [system]
  (let [live (live-system system)]
    (health/snapshot
     {:store-open? (fn [] (storage/store-open? (:store live)))
      :session-count (fn []
                       (let [r (registry/list-sessions (:registry live)
                                                       {:include-deleted? false})]
                         (when (= :ok (:status r))
                           (count (:sessions r)))))
      :gateway-stats (fn [] (gateway/stats (:gateway live)))
      :model-configured? (some? (:model-port live))
      :browser (into {}
                     (map (fn [[session-id service]]
                            [session-id (try (browser/health service)
                                             (catch Throwable _ nil))]))
                     @(or (:browser-services live) (atom {})))
      :browser-errors (:browser-errors live)
      :owners (merge (:owner-errors live)
                     (into {}
                           (map (fn [[session-id handle]]
                                  [session-id
                                   (try
                                     (let [snapshot ((:snapshot handle))
                                           state (:state snapshot)]
                                       {:version (:version snapshot)
                                        :conversation (count (:conversation state))
                                        :pending-turns (count (:pending-turns state))
                                        :calls (into {}
                                                     (map (fn [[call-id call]]
                                                            [call-id (:state call)]))
                                                     (:calls state))
                                        :turn? (some? (:turn state))})
                                     (catch Throwable t
                                       {:error (or (.getMessage t) (str t))}))]))
                           @(or (:owners live) (atom {}))))
      :publish-error (:publish-error live)
      :components (component-presence live)
      :component-errors (component-errors live)
      :config live
      :started-at (:started-at live)})))

;; ---------------------------------------------------------------------------
;; Stop

(defn- stop-evidence
  [result]
  (cond
    (nil? result) {:ok? true}
    (map? result) (select-keys result
                               [:ok? :stopped? :drained? :closed?
                                :terminated? :persisted? :status :path])
    :else {:ok? (boolean result)}))

(defn- stop-failed?
  [result]
  (and (map? result)
       (or (false? (:ok? result))
           (false? (:stopped? result))
           (false? (:drained? result))
           (false? (:closed? result))
           (false? (:terminated? result))
           (false? (:persisted? result)))))

(defn- stop-one!
  [components failures component f]
  (try
    (let [result (f)]
      (swap! components assoc component (stop-evidence result))
      (when (stop-failed? result)
        (swap! failures conj {:component component :error result}))
      result)
    (catch Throwable t
      (swap! components assoc component
             {:ok? false :error {:message (or (.getMessage t) (str t))}})
      (swap! failures conj
             {:component component
              :error {:message (or (.getMessage t) (str t))}})
      nil)))

(defn- store-closed?
  [system]
  (try
    (if-let [store (:store system)]
      (not (storage/store-open? store))
      true)
    (catch Throwable _ false)))

(defn- drain-commands!
  [system components failures]
  (when-let [router (:router system)]
    (try
      (commands/begin-maintenance! router)
      (let [timeout (long (or (get-in system [:maintenance :drain-timeout-ms])
                              5000))
            drained? (commands/drain! router timeout)
            state (commands/maintenance-state router)]
        (swap! components assoc :drain
               {:drained? (boolean drained?)
                :in-flight (:in-flight state)})
        (when-not drained?
          (swap! failures conj
                 {:component :drain
                  :error {:code :MAINTENANCE_DRAIN_TIMEOUT
                          :message "in-flight command handlers did not finish"
                          :in-flight (:in-flight state)}})))
      (catch Throwable t
        (swap! components assoc :drain
               {:drained? false
                :error {:message (or (.getMessage t) (str t))}})
        (swap! failures conj
               {:component :drain
                :error {:message (or (.getMessage t) (str t))}})))))

(defn stop!
  "Stop the application in dependency order.  Idempotent.

  First close the command gate and drain in-flight handlers, then stop
  accepting connections, stop the publisher and wait for its in-flight store
  read, retire the gateway, drain Owners, stop their dependencies, close the
  model ports, and finally close the store.  A
  component that throws or explicitly reports `:ok? false`, `:stopped?
  false`, `:drained? false`, `:closed? false`, `:terminated? false` or
  `:persisted? false` is a failure.  The store is closed only when every
  earlier component stopped and the close is verified; otherwise the result
  keeps `:stopped? false` so maintenance cannot restore over live writers."
  [system]
  (when (and system
             (or (not (:blocked? system))
                 (:partial? system)
                 (:store system)))
    (let [failures (atom [])
          components (atom {})]
      ;; Idempotent: a partial system or an older start may not have marked
      ;; the store yet.  Datalevin's JVM hook must wait for this close.
      (when-let [store (:store system)]
        (storage/expect-ordered-close! store))
      (drain-commands! system components failures)
      (when-let [http (:http system)]
        (stop-one! components failures :http
                   ;; http-kit can report `:stopping` after the drain
                   ;; timeout while its accept loop finishes.  A later stop
                   ;; observes the final state; retry before calling the
                   ;; listener failed, otherwise a maintenance restart cannot
                   ;; bind the same port.
                   #(loop [attempt 0
                           result ((:stop! http) {:timeout-ms 5000})]
                      (if (or (true? (:stopped? result)) (>= attempt 2))
                        result
                        (do (Thread/sleep 100)
                            (recur (inc attempt)
                                   ((:stop! http) {:timeout-ms 5000})))))))
      (when-let [publisher (:publisher system)]
        (stop-one! components failures :publisher #((:stop! publisher))))
      (when-let [gw (:gateway system)]
        (stop-one! components failures :gateway #(gateway/stop! gw)))
      (when-let [gc (:fleet-gc system)]
        (stop-one! components failures :fleet-gc #(fleet-gc/stop! gc)))
      (when-let [sched (:scheduler system)]
        (stop-one! components failures :scheduler #(scheduler/stop! sched)))
      (doseq [[session-id handle] @(or (:owners system) (atom {}))]
        (stop-one! components failures [:owner session-id] #((:stop! handle))))
      (doseq [[session-id service] @(or (:browser-services system) (atom {}))]
        (stop-one! components failures [:browser session-id]
                   #(browser/shutdown! service)))
      (when-let [extensions (:extensions system)]
        (stop-one! components failures :extensions
                   #(extensions-app/stop! extensions)))
      (when-let [speech-component (:speech system)]
        (stop-one! components failures :speech
                   #(speech/stop! speech-component)))
      (when-let [model-auth (:model-auth system)]
        (stop-one! components failures :model-auth
                   #(model-auth-app/stop! model-auth)))
      (when-let [supervisor (:fleet-supervisor system)]
        (stop-one! components failures :fleet-supervisor
                   #(fleet-supervisor/stop!
                     supervisor
                     {:cancel-attempt!
                      (fn [attempt-id]
                        (when-let [handle
                                   (some (fn [[[_ aid] h]]
                                           (when (= aid attempt-id) h))
                                         @(or (:fleet-attempts system)
                                              (atom {})))]
                          (when-let [port @(or (:model-port-ref system)
                                               (atom nil))]
                            (model-contract/cancel-request! port handle))))})))
      (when-let [fleet-service (:fleet system)]
        (stop-one! components failures :fleet #(fleet/stop! fleet-service)))
      (when-let [capture (:diagnostics-capture system)]
        (stop-one! components failures :diagnostics-capture
                   #((:stop! capture))))
      (doseq [{:keys [capture raw]} @(or (:model-ports system) (atom []))]
        (when capture
          (stop-one! components failures :model-capture #((:stop! capture))))
        (when raw
          (stop-one! components failures :model-port
                     #(model-adapter/stop-port! raw))))
      ;; A component that reports `:stopped? true` has no live writer even
      ;; when its mailbox still holds an unprocessed message (`:drained?`
      ;; false).  Close the store in that case too; the result still reports
      ;; the drain failure and keeps `:stopped? false`, so maintenance never
      ;; treats the stop as confirmed.
      (let [writers-stopped?
            (every? (fn [[_ evidence]]
                      (not (false? (:stopped? evidence))))
                    @components)]
        (when (and (seq @failures) writers-stopped?)
          (when-let [store (:store system)]
            (stop-one! components failures :store
                       #(if (storage/store-open? store)
                          (storage/open-close-store!
                           store
                           {:drain-fn (fn [] true)
                            :timeout-ms
                            (long (or (get-in system
                                              [:maintenance :drain-timeout-ms])
                                      5000))})
                          {:closed? true})))))
      (if (seq @failures)
        {:stopped? false
         :drained? false
         :failures @failures
         :components @components
         :store-closed? (store-closed? system)}
        (do
          (when-let [store (:store system)]
            ;; The command gate and handler drain already ran above.  The
            ;; store close also uses the ordered intake barrier, so a late
            ;; commit sees :STORAGE_CLOSING instead of a closed engine.
            (stop-one! components failures :store
                       #(if (storage/store-open? store)
                          (storage/open-close-store!
                           store
                           {:drain-fn (fn [] true)
                            :timeout-ms
                            (long (or (get-in system
                                              [:maintenance :drain-timeout-ms])
                                      5000))})
                          {:closed? true})))
          (if (or (seq @failures) (not (store-closed? system)))
            (do
              (when-not (store-closed? system)
                (swap! failures conj
                       {:component :store
                        :error {:code :STORE_NOT_CLOSED
                                :message "the store remained open after close"}}))
              {:stopped? false
               :drained? false
               :failures @failures
               :components @components
               :store-closed? (store-closed? system)})
            {:stopped? true
             :drained? true
             :failures []
             :components @components
             :store-closed? true}))))))
