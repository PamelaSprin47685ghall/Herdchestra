(ns hc.server.app.snapshot
  "Build the client-facing AG-UI state tree from committed domain state.

  The server is the single authority.  This namespace is a projection: it
  reads the session registry and the per-session Owner snapshot, then emits
  the normalized tables the workbench queries.  It never mutates domain
  state and never invents a terminal result.  A missing optional table is
  simply absent; the client must render an unknown value as unknown."
  (:require [clojure.string :as str]
            [hc.server.history.snapshot :as history-snapshot]))

;; ---------------------------------------------------------------------------
;; Redaction

(def ^:private secret-key?
  #{"api-key" "apikey" "api_key" "secret" "client-secret" "token"
    "access-token" "refresh-token" "password" "passphrase" "authorization"
    "bearer" "cookie" "set-cookie" "credential-value" "private-key"})

(defn- secret-name?
  [key]
  (if (or (keyword? key) (string? key) (symbol? key))
    (let [name (str/lower-case (name key))]
      (or (contains? secret-key? name)
          (str/ends-with? name "-token")
          (str/ends-with? name "-secret")
          (str/ends-with? name "-api-key")
          (str/includes? name "authorization")))
    false))

(defn redact
  "Recursively remove secret values from a configuration tree."
  [value]
  (cond
    (map? value)
    (into (empty value)
          (keep (fn [[k v]]
                  (when-not (secret-name? k)
                    [k (redact v)])))
          value)

    (vector? value) (mapv redact value)
    (set? value) (into #{} (map redact) value)
    :else value))

(def ^:private connection-secret-fields
  #{:api-key :secret :token :password :client-secret :access-token
    :refresh-token :private-key})

(defn- sanitize-credential
  [credential]
  (if (map? credential)
    (apply dissoc credential
           [:secret :api-key :token :password :access-token :refresh-token
            :client-secret])
    credential))

(defn- sanitize-credentials
  [credentials]
  (cond
    (map? credentials)
    (into {}
          (map (fn [[id credential]]
                 [id (sanitize-credential credential)]))
          credentials)

    (sequential? credentials)
    (mapv sanitize-credential credentials)

    :else credentials))

(defn sanitize-connection
  "Remove inline secrets from a connection before persistence or display.

  `:auth`, `:headers`, and `:options` are redacted recursively because they
  may carry provider tokens or authorization headers.  Credential records
  keep only their opaque `:secret-ref` and display mask."
  [connection]
  (-> (apply dissoc connection connection-secret-fields)
      (update :auth redact)
      (update :headers redact)
      (update :options redact)
      (update :credentials sanitize-credentials)))

;; ---------------------------------------------------------------------------
;; Board projection

(defn- by-id
  [records id-key]
  (into {}
        (keep (fn [record]
                (when-let [id (or (get record id-key) (:id record))]
                  [id record])))
        records))

(defn- entity-table-key?
  "True for a normalized `*/by-id` table key in either keyword or string
  form.  Mirrors `hc.client.sync.normalize/table-key?`."
  [k]
  (cond
    (keyword? k)
    (and (some? (namespace k)) (= "by-id" (name k)))

    (string? k)
    (and (str/includes? k "/")
         (str/ends-with? k "/by-id"))

    :else false))

(defn- entity-id-key
  "Return the wire form of one entity table key.

  AGENTS.md §7.3 requires entity IDs to cross the boundary as non-blank
  strings.  Board records carry keyword IDs (`:task-1`); publishing them
  verbatim makes the client's `normalize-table` reject the whole baseline
  (`:invalid-entity`), which drops every session and swarm row."
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (symbol? k) (subs (str k) 1)
    (string? k) k
    :else (str k)))

(defn- normalize-entity-tables
  "Rewrite every `*/by-id` table's keys to strings, recursively one level.

  The feed snapshot and every later delta are diffed from this same tree,
  so normalizing here keeps snapshot and patch paths consistent."
  [tree]
  (if-not (map? tree)
    tree
    (into {}
          (map (fn [[k v]]
                 (if (and (entity-table-key? k) (map? v))
                   [k (into {}
                            (map (fn [[ek ev]] [(entity-id-key ek) ev]))
                            v)]
                   [k v])))
          tree)))

(defn board-view
  "Project a swarm board into normalized tables.

  The application currently has no fleet coordinator attached, so it must
  not invent a local swarm row, worktree, status, queue depth, or master
  version.  Only the board records that actually exist are projected; fleet
  rows remain absent until a real fleet adapter supplies them."
  ([board] (board-view board nil))
  ([board gate-status]
   (board-view board gate-status nil))
  ([board gate-status swarm-id]
   (let [tasks (vals (:tasks board))
         actions (vals (:actions board))
         actors (vals (:actors board))
         token (:token-slot board)
         slots (vals (:slots board))
         base {:task/by-id (by-id tasks :task-id)
               :action/by-id (by-id actions :action-id)
               :agent/by-id (by-id actors :agent-id)
               :board/version (:board-version board)}]
     (cond-> base
       (and swarm-id token)
       (assoc-in [:token-slot/by-id swarm-id]
                 (assoc token :swarm-id swarm-id))
       (and swarm-id gate-status)
       (assoc-in [:write-gate/by-id swarm-id]
                 (assoc gate-status :swarm-id swarm-id))
       (seq slots)
       (assoc :slot/by-id (by-id slots :slot-id))))))

;; ---------------------------------------------------------------------------
;; Conversation projection

(defn- message-id
  [message index]
  (or (:id message)
      (:message-id message)
      (some-> (:seq message) str)
      (str "message-" index)))

(defn- message-session-id
  [message]
  (or (:session-id message)
      (:message/session-id message)
      (:session message)))

(defn- normalize-message
  [message index]
  (let [id (message-id message index)]
    (-> message
        (assoc :id id)
        (cond-> (not (contains? message :message-id))
          (assoc :message-id id))
        (cond-> (not (contains? message :content))
          (assoc :content (or (:text message) ""))))))

(defn- stamp-session-id
  "Give an active-conversation message its explicit owner.

  The live conversation belongs to one session, but the owner record does not
  always carry `:session-id`.  Stamping it here lets the client filter by
  session without guessing from a shared active pointer."
  [message session-id]
  (cond-> message
    (and (string? session-id)
         (not (str/blank? session-id))
         (not (contains? message :session-id)))
    (assoc :session-id session-id)))

(defn conversation-view
  "Project an ordered conversation vector into `:message/by-id` and
  `:message/order`.  Roles and bodies pass through unchanged.  When
  SESSION-ID is supplied, every message without an explicit owner is stamped
  with it; the client never infers ownership from the shared active pointer."
  ([conversation] (conversation-view conversation nil))
  ([conversation session-id]
   (let [visible (remove #(or (:nudge? %) (:nudge-id %)
                              (:message/nudge? %) (:message/nudge-id %))
                         (or conversation []))
         messages (mapv (fn [message index]
                          (stamp-session-id
                           (normalize-message message index)
                           session-id))
                        visible (range))]
     {:message/by-id (by-id messages :id)
      :message/order (mapv :id messages)})))

;; ---------------------------------------------------------------------------
;; Session projection

(defn session-summary
  "A list-friendly view of one session record."
  [session]
  (let [summary (select-keys session
                             [:session-id :owner-id :status :title :language
                              :assistant-id :created-at :updated-at
                              :activity-at :pinned? :deleted-at :temporary?
                              :model-binding :model :current-run-id
                              :origin-session-id])]
    (cond-> summary
      (and (:model summary) (not (:model-binding summary)))
      (assoc :model-binding (:model summary))

      ;; The settings UI reads the per-session model identity from
      ;; :model-snapshot; publish the immutable binding under both names so
      ;; a configured default is visible without another round trip.
      (:model-binding summary)
      (assoc :model-snapshot (:model-binding summary)))))

(defn session-view
  ([sessions] (session-view sessions {}))
  ([sessions command-epochs]
   (into {}
         (map (fn [session]
                [(:session-id session)
                 (cond-> (session-summary session)
                   (contains? command-epochs (:session-id session))
                   (assoc :command-epoch
                          (get command-epochs (:session-id session))))]))
         sessions)))

(defn- summary-row
  [session epochs history]
  (let [id (:session-id session)]
    [id (merge
         (session-summary session)
         (when-let [epoch (get epochs id)]
           {:command-epoch epoch})
         (history-snapshot/session-summary-fields
          (get-in history [:session/history id])))]))

(def ^:private call-view-keys
  "Call fields safe to publish to the browser.

  Program sources, requests, model snapshots and credentials stay in the
  Owner state.  The operator needs identity, role, stage, and the
  acknowledgement evidence to decide what to check."
  [:call-id :kind :role :lane :tenure-id :agent-id :member :task-id
   :created-at :finished-at :state :ack])

(defn- call-view
  [session-id call]
  (let [state (:state call)]
    (cond-> (select-keys (assoc call :session-id session-id) call-view-keys)
      (= :outcome-unknown state)
      (assoc :outcome-unknown? true)

      (map? (:ack call))
      (assoc :ack (select-keys (:ack call)
                               [:outcome :by :at :note :command-id]))

      true
      (assoc :phase (or (get-in call [:retry :phase]) state)
             :last-known-stage state))))

(defn- unresolved-call-summaries
  "Publish the unknown calls and their operator acknowledgements per session.

  A terminal call without an acknowledgement is not an open obligation and
  stays out of the snapshot.  At most the newest 20 rows are published; the
  session summary is a working view, not a call history."
  [owners]
  (into {}
        (keep (fn [[session-id owner]]
                (let [calls (->> (get-in owner [:state :calls])
                                 vals
                                 (filter map?)
                                 (filter #(or (= :outcome-unknown (:state %))
                                              (map? (:ack %))))
                                 (sort-by :created-at)
                                 (take-last 20)
                                 (mapv #(call-view session-id %)))]
                  (when (seq calls)
                    [session-id {:unresolved-calls calls}]))))
        owners))

(defn- same-stamp?
  "Compare the three fields the protected commit actually checks."
  [a b]
  (and (map? a) (map? b)
       (= (:mtime-sec a) (:mtime-sec b))
       (= (:mtime-nsec a) (:mtime-nsec b))
       (= (:size a) (:size b))))

(defn- worker-program-view
  "Publish one worker program's protected-commit result without its source.

  A conflict is the useful fact: path, original stamp, current stamp and the
  status.  The workspace settle receipt keeps the stamps in `:observations`
  and `:expected`/`:actual`; both shapes are projected.  The request, program
  source and full receipt stay server-side."
  [job]
  (let [result (:result job)
        receipts (or (:receipts result) [])
        settle (some #(when (= :workspace/settle (:type %)) %) receipts)
        settle-result (:result settle)
        value (or (:value result) settle-result)
        commit (or (:commit value) (:commit result))
        error (or (:error value) (:error result))
        raw-conflicts (or (:conflicts commit) (:conflicts error) [])
        raw-conflicts (cond
                        (map? raw-conflicts) [raw-conflicts]
                        (sequential? raw-conflicts) (filterv map? raw-conflicts)
                        :else [])
        settle-conflict
        (when (= :FILE_CONFLICT (:status settle-result))
          (let [observations (:observations settle-result)
                actual (or (:actual settle-result)
                           (:stamp (second observations)))
                ;; The read-set may hold both the original read and a later
                ;; observation of the same path.  The user-facing "original
                ;; stamp" is the one that differs from the current stamp; the
                ;; first entry can already be the external edit.
                expected (or (some #(when-not (same-stamp? % actual) %)
                                   (:expected settle-result))
                             (first (:expected settle-result)))]
            [{:path (:path settle-result)
              :reason (or (:code settle-result) :stamp-mismatch)
              :expected (or expected (:stamp (first observations)))
              :actual actual}]))
        conflicts (or (seq raw-conflicts) settle-conflict [])]
    (cond-> (select-keys job [:job-id :task-id :claim-id :agent-id
                              :state :outcome :created-at])
      (map? value)
      (assoc :program-status
             (let [status (or (:status settle-result) (:status value))]
               ;; A board timeout is published as a status map.  The client
               ;; projects `:program-status` with `keyword`; a map there
               ;; crashes the whole dashboard render and hides every card.
               (cond
                 (keyword? status) status
                 (string? status) (keyword status)
                 (map? status) (or (:code status) :failed)
                 :else :failed))
             :file-changed? (:file-changed? value)
             :outcome-kind (:outcome value))

      (seq conflicts)
      (assoc :conflicts
             (mapv #(select-keys % [:path :reason :expected :actual])
                   conflicts))

      (map? error)
      (assoc :error (select-keys error [:code :message :reason]))

      (:path value)
      (assoc :file-path (:path value)))))

(defn- worker-program-summaries
  "Publish the newest worker program results per session, newest last.

  At most 10 rows.  A job that is still scheduled is published with its
  state; the client must not infer a terminal result."
  [owners]
  (into {}
        (keep (fn [[session-id owner]]
                (let [jobs (->> (get-in owner [:state :worker-jobs])
                                vals
                                (filter map?)
                                (sort-by :created-at)
                                (take-last 10)
                                (mapv worker-program-view))]
                  (when (seq jobs)
                    [session-id {:worker-programs jobs}]))))
        owners))

;; ---------------------------------------------------------------------------
;; Settings projection

(def ^:private settings-model-keys
  "Model fields safe to publish in the settings projection.

  The client reads display, identity, capability, capacity, price, and
  lifecycle fields from this map.  Provider transport fields (`:options`,
  `:chat-model`, callbacks) stay server-side.  `:name` is kept for older
  clients and configs; `:display-name` is the preferred label."
  [:id :model-id :model-name :provider-model-id :name :display-name :full-id
   :capabilities :capability-sources :capability-baseline
   :context-window :max-output-tokens :max-output
   :capacity :context-capacity :capacity-source :reasoning
   :pricing :availability :status :enabled? :removed? :alias :notes
   :source :last-seen-at])

(defn settings-model-view
  "Return the client-facing copy of one configured model.

  Both `:display-name` and `:name` survive the projection: the client prefers
  the explicit display name and falls back to the legacy name.  Connection
  identity stays outside this map, so two connections may publish the same
  model id or display name without merging."
  [model]
  (if (map? model)
    (select-keys model settings-model-keys)
    {:id (str model)}))

(defn settings-models-view
  "Project one connection's `:models` value.

  The config accepts either a sequence of model maps or a map keyed by model
  id.  A map key supplies the id when the value omits it; the result is
  always a vector so the client can keep one row per model."
  [models]
  (cond
    (map? models)
    (mapv (fn [[id model]]
            (let [view (settings-model-view model)]
              (assoc view :id (str (or (:id view) id)))))
          models)

    (sequential? models) (mapv settings-model-view models)

    :else []))

(defn- assistant-view
  [id assistant]
  (let [id (str (or (:id assistant) (:assistant-id assistant) id))
        default-model (when (map? (:default-model assistant))
                        (let [model (:default-model assistant)]
                          (cond-> (select-keys model [:connection-id
                                                    :model-id
                                                    :model-entry-id])
                            (and (nil? (:model-id model))
                                 (some? (:model-entry-id model)))
                            (assoc :model-id (:model-entry-id model)))))]
    (cond-> {:id id
             :assistant-id (str (or (:assistant-id assistant) id))
             :name (str (or (:name assistant) (:display-name assistant) id))
             :revision (long (or (:revision assistant) 1))
             :enabled? (not (false? (:enabled? assistant)))}
      (:display-name assistant)
      (assoc :display-name (str (:display-name assistant)))
      default-model
      (assoc :default-model default-model))))

(defn- assistant-views
  [assistants]
  (let [entries (cond
                  (map? assistants) assistants
                  (sequential? assistants)
                  (into {} (keep (fn [assistant]
                                   (when (map? assistant)
                                     [(or (:id assistant)
                                          (:assistant-id assistant))
                                      assistant])))
                        assistants)
                  :else {})]
    (let [views (into {}
                      (keep (fn [[id assistant]]
                              (when (map? assistant)
                                (let [id (str (or (:id assistant)
                                                  (:assistant-id assistant)
                                                  id))]
                                  [id (assistant-view id assistant)]))))
                      entries)]
      (if (seq views)
        views
        {"default" (assistant-view
                    "default"
                    {:id "default" :name "通用助手" :revision 1})}))))

(defn settings-view
  "Project model/provider configuration without any secret value.

  The role map is normalized to `:connection-id`/`:model-id` so the client
  does not depend on the config file's short names.  Model rows keep the
  display metadata the client renders; connection identity remains the map
  key, so same-named connections stay separate."
  [config]
  (let [connections (get config :connections [])
        roles (get config :roles {})
        assistants (assistant-views (:assistants config))
        connection-views
        (into {}
              (map (fn [connection]
                     (let [id (:id connection)
                           key-present? (or (:api-key connection)
                                            (:api-key-env connection))
                           connection (sanitize-connection connection)
                           models (settings-models-view
                                   (:models connection))]
                       [id (let [credentials
                                 (mapv (fn [credential]
                                         (select-keys credential
                                                      [:credential-id :alias
                                                       :enabled? :masked-key
                                                       :priority :use-count
                                                       :rate-limited-until
                                                       :auth-failed?]))
                                       (:credentials connection))]
                             (-> connection
                                 (dissoc :api-key :secret :token :password
                                         :credentials)
                                 (assoc :has-key? (boolean
                                                   (or key-present?
                                                       (seq credentials)))
                                        :credentials credentials
                                        :models models)))])))
              connections)
        role-views
        (into {}
              (map (fn [[role binding]]
                     [role (cond-> {}
                             (or (:connection binding) (:connection-id binding))
                             (assoc :connection-id
                                    (or (:connection binding)
                                        (:connection-id binding)))
                             (or (:model binding) (:model-id binding)
                                 (:model-entry-id binding))
                             (assoc :model-id
                                    (or (:model binding) (:model-id binding)
                                        (:model-entry-id binding)))
                             (:mode binding)
                             (assoc :mode (:mode binding))
                             (contains? binding :confirmed?)
                             (assoc :confirmed? (:confirmed? binding)))]))
              roles)]
    {:settings/by-id connection-views
     :settings/models (into {}
                            (map (fn [[id connection]]
                                   [id (:models connection)]))
                            connection-views)
     :settings/roles role-views
     :settings/assistants assistants
     :settings/assistant-order (vec (sort (keys assistants)))}))

(defn- model-view-by-id
  [models connection-id model-entry-id]
  (some (fn [model]
          (when (= (str (or (:id model) (:model-id model)
                            (:model-entry-id model) (:name model)))
                   (str model-entry-id))
            model))
        (get models connection-id)))

(defn binding-availability
  "Project the live availability of a stored model binding.

  A session keeps its binding snapshot when the connection or model changes.
  The client needs the current fact so a disabled or removed connection can
  explain the blocked send instead of presenting the binding as usable."
  [connections models binding]
  (when (and (map? binding) (some? (:connection-id binding)))
    (let [connection-id (:connection-id binding)
          connection (get connections connection-id)
          model (model-view-by-id models connection-id
                                  (:model-entry-id binding))]
      (cond
        (or (nil? connection) (:removed? connection)) :connection-removed
        (false? (:enabled? connection)) :connection-disabled
        (= :failed (keyword (:auth-status connection))) :auth-expired
        (or (nil? model) (:removed? model)) :model-removed
        (false? (:enabled? model)) :disabled
        :else (or (:availability model) :unconfirmed)))))

(defn- tools-capability-fact?
  "True when CAPABILITIES carries a `:tools` fact in any accepted shape:
  a map entry, a set member, or a sequence member."
  [capabilities]
  (cond
    (map? capabilities)
    (boolean (or (find capabilities :tools)
                 (find capabilities "tools")))

    (set? capabilities)
    (contains? capabilities :tools)

    (sequential? capabilities)
    (boolean (some #(= :tools %) capabilities))

    :else false))

(defn- tools-declared?
  "True when CAPABILITIES carries a `:tools` fact: a map entry with a
  non-nil value, a set member, or a sequence member.  `:unknown` counts —
  it is a declaration, not support — while an absent or nil value is not."
  [capabilities]
  (cond
    (map? capabilities)
    (boolean (some (fn [[k v]]
                     (and (#{:tools "tools"} k) (some? v)))
                   capabilities))

    (set? capabilities)
    (contains? capabilities :tools)

    (sequential? capabilities)
    (boolean (some #(= :tools %) capabilities))

    :else false))

(defn binding-capabilities-source
  "Project which declaration answers the binding's `:tools` question.

  `:live-model` when the live model entry carries a `:tools` fact,
  `:binding` when only the binding's copied `:capabilities` declare one,
  and `:none` when neither does.  `MODEL_TOOLS_UNAVAILABLE` reports this so
  the UI can tell 「the model cannot do tools」 from 「nobody declared it」."
  [connections models binding]
  (when (and (map? binding) (some? (:connection-id binding)))
    (let [connection-id (:connection-id binding)
          model (model-view-by-id models connection-id
                                  (:model-entry-id binding))
          binding-declared? (and (map? binding)
                                 (contains? binding :capabilities)
                                 (tools-declared?
                                  (:capabilities binding)))]
      (cond
        (and (map? model)
             (tools-capability-fact? (:capabilities model)))
        :live-model

        binding-declared? :binding

        :else :none))))

(defn- with-model-availability
  [summary connections models]
  (if (or (nil? connections) (nil? models))
    summary
    (let [binding (:model-binding summary)]
      (let [availability (binding-availability connections models binding)
            capabilities-source (binding-capabilities-source
                                 connections models binding)]
        (cond-> summary
          availability
          (assoc :model-availability availability)

          capabilities-source
          (assoc :capabilities-source capabilities-source))))))

(defn- cached-entry
  "Return the cached VALUE for KIND/ID when VERSION matches, else build it.

  The cache is a plain atom map; the caller prunes it to the visible session
  ids after each projection, so a deleted session cannot stay retained."
  [cache kind id version build]
  (let [entry (get-in @cache [kind id])]
    (if (and entry (= version (:version entry)))
      (:value entry)
      (let [value (build)]
        (swap! cache assoc-in [kind id] {:version version :value value})
        value))))

(defn- prune-session-cache!
  [cache visible-ids]
  (swap! cache
         (fn [c]
           (reduce (fn [c kind]
                     (update c kind #(select-keys % visible-ids)))
                   c
                   [:conversation :history :summary]))))

(defn- history-window-tail-ids
  "The newest HISTORY-WINDOW-SIZE visible message ids of SESSION, or nil
  when no tail window applies.  Fetched windows are unioned on top by the
  caller; this is only the default tail."
  [session history-window-size]
  (when (and (integer? history-window-size) (pos? history-window-size))
    (let [visible-ids (->> (or (:message-order session) [])
                           (keep #(get-in session [:messages %]))
                           (remove #(or (:deleted? %) (:folded? %)))
                           (map :message-id)
                           vec)]
      (when (pos? (count visible-ids))
        (vec (take-last history-window-size visible-ids))))))

(defn- history-window-ids
  "Return the `:ids` set for one session's message window, or nil for all.

  The window is the union of the newest HISTORY-WINDOW-SIZE visible ids and
  any ids the client already fetched through `:history/messages` (tracked in
  PUBLISHED-WINDOWS).  A nil window publishes every visible message, which is
  the pre-windowing behavior."
  [session session-id published-windows history-window-size]
  (let [published (when published-windows
                    (get @published-windows session-id))]
    (when (or published
              (and (integer? history-window-size)
                   (pos? history-window-size)))
      (let [visible (set (map :message-id
                              (filter (fn [m]
                                        (not (or (:deleted? m)
                                                 (:folded? m)
                                                 (:nudge? m)
                                                 (:nudge-id m))))
                                      (vals (:messages session)))))
            tail (history-window-tail-ids session history-window-size)
            ids (into #{}
                      (comp (filter visible) (distinct))
                      (concat published tail))]
        (not-empty ids)))))

(defn- history-window-key
  "Cache fingerprint for one session's window: the window id set is stable
  for a given revision plus fetched set, so the sorted vector is enough."
  [session session-id published-windows history-window-size]
  (let [ids (history-window-ids session session-id published-windows
                                 history-window-size)]
    (if ids
      (vec (sort ids))
      :all)))

;; ---------------------------------------------------------------------------
;; Baseline
;; The projection removes hidden messages and non-visible sessions before
;; publishing the client tree.

(defn baseline
  "Build one complete client baseline.

  Options:

    :sessions        sequence of session summaries
    :session-records full session records with :messages/:message-order,
                     used for the history projection
    :owner-snapshots map session-id -> owner snapshot
    :active-session  server presentation session id; it selects the live
                     conversation projection but never authorizes a command
    :board           swarm board map (defaults to the active owner board)
    :gate-status     write-gate status
    :swarm-id        default \"local\"
    :config          application config for settings
    :sync            `{:phase .. :epoch .. :seq .. :command-epoch ..}`
    :session-cache   optional atom reused across ticks; caches per-session
                     conversation, history and summary blocks by version
    :published-windows atom map session-id -> set of message ids the client
                      has already fetched; merged into each session's window
    :history-window-size newest message entities published per session when
                      no fetched window exists (default: all messages)
    :now             epoch ms

  Returns a plain map ready for `STATE_SNAPSHOT`."
  [{:keys [sessions session-records owner-snapshots active-session board
           gate-status swarm-id config sync now outbound-opts-fn
           session-cache published-windows history-window-size]
    :or {sessions [] session-records [] owner-snapshots {} swarm-id nil
         config {}}}]
  (let [visible-ids (set (map :session-id sessions))
        active-session (if (contains? visible-ids active-session)
                         active-session
                         (:session-id (first sessions)))
        active-owner (get owner-snapshots active-session)
        active-state (:state active-owner)
        visible-owners (select-keys owner-snapshots visible-ids)
        board (or board (:board active-state))
        command-epoch (get-in sync [:command-epoch])
        ;; The publisher passes `{:models <settings-config> :assistants ...}`;
        ;; assistants live beside the model section, not inside it.
        settings (settings-view
                  (merge (get config :models)
                         (select-keys config [:assistants])))
        settings-present? (contains? config :models)
        settings-connections (when settings-present? (:settings/by-id settings))
        settings-models (when settings-present? (:settings/models settings))
        conversations (into {}
                            (keep (fn [[session-id owner]]
                                    (when-let [state (:state owner)]
                                      [session-id
                                       (if session-cache
                                         (cached-entry
                                          session-cache :conversation session-id
                                          (:version owner)
                                          #(conversation-view
                                            (remove (fn [message]
                                                      (or (:nudge? message)
                                                          (:nudge-id message)))
                                                    (:conversation state))
                                            session-id))
                                         (conversation-view
                                          (remove #(or (:nudge? %)
                                                       (:nudge-id %))
                                                  (:conversation state))
                                          session-id))])))
                            visible-owners)
        messages {:message/by-id
                  (into {}
                        (mapcat (comp :message/by-id val)
                                conversations))
                  :message/order
                  (vec (distinct
                        (mapcat (comp :message/order val)
                                conversations)))}
        hidden-message-ids
        (set
         (for [record session-records
               [_ message] (:messages record)
               :when (or (:deleted? message)
                         (:folded? message)
                         (:nudge? message)
                         (:nudge-id message))]
           (:message-id message)))
        histories
        (mapv (fn [session]
                (let [session-id (:session-id session)
                      active? (= session-id active-session)
                      ;; The message window bounds every session, active
                      ;; included: `:session/history` keeps the full visible
                      ;; id list while older entities are fetched through
                      ;; `:history/messages`.
                      window-ids (history-window-ids session session-id
                                                      published-windows
                                                      history-window-size)
                      window-opts {:messages-window
                                   (when window-ids {:ids window-ids})}]
                  (if (and session-cache (not active?))
                    (cached-entry
                     session-cache :history session-id
                     [(:revision session)
                      (history-window-key session session-id
                                          published-windows
                                          history-window-size)]
                     #(history-snapshot/session-history
                       session
                       (merge window-opts
                              (if (and outbound-opts-fn active?)
                                (or (outbound-opts-fn session) {})
                                {}))))
                    (history-snapshot/session-history
                     session
                     (merge
                      (if (and outbound-opts-fn active?)
                        (or (outbound-opts-fn session) {})
                        {})
                      window-opts)))))
              session-records)
        history
        (let [entities (mapcat :messages histories)
              by-id (into {} (map (juxt :id identity)) entities)
              order (->> histories (mapcat :message-ids) vec distinct)
              session-history-map
              (into {}
                    (map (fn [{:keys [session-id] :as h}]
                           [session-id (dissoc h :messages)]))
                    histories)]
          {:message/by-id by-id
           :message/order order
           :session/history session-history-map
           :session/history-active-id active-session
           :session/history-active (get session-history-map active-session)})
        history-by-id (:message/by-id history)
        terminal-states #{:finished :completed :succeeded :failed
                          :cancelled :outcome-unknown}
        runs-by-session (into {}
                              (keep (fn [[session-id owner]]
                                      (when-let [state (:state owner)]
                                        [session-id (:runs state)])))
                              visible-owners)
        base-by-id
        (->> (merge-with
              (fn [conversation history]
                (let [history-content
                      (let [content (or (:content history)
                                        (some-> (:versions history)
                                                first :content))]
                        (when-not (str/blank? (str content))
                          content))
                      conversation-content
                      (or (when-not (str/blank?
                                     (str (:content conversation)))
                            (:content conversation))
                          (:text conversation)
                          (:body conversation))]
                  (merge conversation history
                         {:content (or history-content
                                       conversation-content)
                          :text (or (:text conversation)
                                    (:text history))
                          :versions (:versions history)
                          :current-version (:current-version history)
                          :version-id (:version-id history)})))
              (:message/by-id messages)
              history-by-id)
             (remove (fn [[id _]] (contains? hidden-message-ids id)))
             (remove (fn [[_ message]]
                       (or (:nudge? message)
                           (:nudge-id message)
                           (:message/nudge? message)
                           (:message/nudge-id message))))
             (remove (fn [[_ message]]
                       (and (= :assistant (:role message))
                            (not (seq (:content message)))
                            (not (seq (:text message)))
                            (not (some #(seq (:content %))
                                       (:versions message))))))
             (into {}))
        ;; Resolve streaming state from the visible owner runs.
        by-id (into {}
                    (map (fn [[id message]]
                           [id (let [runs (get runs-by-session
                                               (message-session-id message))]
                                 (if (and (:streaming? message)
                                          (some (fn [[_ run]]
                                                  (and (= id (:message-id run))
                                                       (contains? terminal-states
                                                                  (:status run))))
                                                runs))
                                   (assoc message :streaming? false)
                                   message))]))
                    base-by-id)
        order (->> (concat (mapcat :message/order (vals conversations))
                           (:message/order history)
                           (keys by-id))
                   (filter #(contains? by-id %))
                   distinct
                   vec)
        epochs (into {}
                     (keep (fn [[session-id snapshot]]
                             (when-let [epoch (get-in snapshot
                                                      [:state :commands
                                                       :command-epoch])]
                               [session-id epoch])))
                     visible-owners)
        summaries (into {}
                        (map (fn [session]
                               (let [id (:session-id session)
                                     version [(:revision session)
                                              (get epochs id)]
                                     ;; The active session's summary carries
                                     ;; the live outbound context, which a
                                     ;; session revision need not cover (an
                                     ;; applied compression changes context
                                     ;; without changing the message list).
                                     ;; Rebuild it with the active history;
                                     ;; the cache still bounds inactive rows.
                                     summary
                                     (if (and session-cache
                                              (not= id active-session))
                                       (cached-entry
                                        session-cache :summary id version
                                        #(second (summary-row
                                                  session epochs history)))
                                       (second (summary-row
                                                session epochs history)))]
                                 [id (with-model-availability
                                      summary
                                      settings-connections
                                      settings-models)])))
                        sessions)
        summaries (merge-with merge
                              summaries
                              (unresolved-call-summaries visible-owners)
                              (worker-program-summaries visible-owners))]
    (when session-cache
      (prune-session-cache! session-cache visible-ids))
    (normalize-entity-tables
     (merge
     {:session/by-id summaries
      :session/active-id active-session
      :session/history (:session/history history)
      :session/history-active (:session/history-active history)
      :message/by-id by-id
      :message/order order
      :swarm/by-id {}
      :task/by-id {}
      :action/by-id {}
      :agent/by-id {}
      :token-slot/by-id {}
      :settings/by-id {}
      :settings/models {}
      :settings/roles {}
      :sync (merge {:phase :synced
                    :command-epoch command-epoch}
                   (or sync {}))
      :server/now (or now (System/currentTimeMillis))}
     (when board
       (board-view board gate-status swarm-id))
     settings
     (when (and active-state
                (contains? summaries active-session))
       (let [run-error
             (some (fn [[_ call]]
                     (when (= :failed (:state call))
                       (or (:error call) (:last-error call))))
                   (:calls active-state))
             live-calls
             (remove (fn [[_ call]]
                       (contains? #{:completed :succeeded :failed :cancelled
                                    :outcome-unknown}
                                  (:state call)))
                     (:calls active-state))
             nudge-active?
             (boolean
              (some (fn [[_ call]]
                      (or (:nudge? call)
                          (:nudge-id call)
                          (get-in call [:request :snapshot :nudge-id])))
                    live-calls))
             turn (:turn active-state)
             turn-active? (some? turn)
             actor-active?
             (boolean
              (some (fn [[_ tenure]]
                      (some (fn [[_ slot]]
                              (or (= :running (:status slot))
                                  (some? (:attempt-id slot))))
                            (:slots tenure)))
                    (:tenures (:actors active-state))))
             has-conversation? (boolean (seq (:conversation active-state)))
             ;; D-13: a settled turn whose last answer is `:round-boundary`
             ;; is an idle boundary, not an unfinished run.  The ledger may
             ;; still be open -- the host stopped injecting Nudge calls on
             ;; purpose -- so actor slots alone must not keep the session
             ;; reporting `:running`/`unattended?` forever.  A live call or
             ;; a live turn still wins: the boundary only answers a settled
             ;; stop.
             round-boundary?
             (let [entries (vec (:turn-history active-state))]
               (and (seq entries)
                    (= :round-boundary (:reason (last entries)))
                    (not turn-active?)
                    (empty? live-calls)))
             run-active? (boolean
                          (or (seq live-calls)
                              turn-active?
                              (and has-conversation?
                                   actor-active?
                                   (not round-boundary?))))
             ;; A freshly created session has empty conversation, no calls,
             ;; and no turn.  Actor slots may still be initializing; they
             ;; must not make the composer claim an unattended run before
             ;; the operator has sent anything.
             run-status (if (or run-active?
                                (and has-conversation? actor-active?
                                     (not round-boundary?)))
                          :running
                          :finished)
             unattended? (boolean
                          (and (or has-conversation? run-active?)
                               (not round-boundary?)
                               (or nudge-active? turn-active? actor-active?)))
             current-run-id
             (or (get-in active-state [:session :current-run-id])
                 (:run-id (second (first live-calls)))
                 (:call-id turn))]
         {:session/by-id
          (update summaries active-session
                  (fn [summary]
                    (with-model-availability
                     (merge summary
                            (select-keys active-state
                                         [:session-id :relay :board])
                            (select-keys (:session active-state)
                                         [:model-binding :model :title
                                          :assistant-id :language])
                            {:run-status run-status
                             :unattended? unattended?
                             :nudge-active? (boolean (or nudge-active?
                                                        turn-active?))
                             :actor-active? actor-active?
                             :turn-active? turn-active?
                             :current-run-id current-run-id}
                            (when run-error
                              {:last-error run-error}))
                     settings-connections
                     settings-models)))
          :run-error run-error}))))))
