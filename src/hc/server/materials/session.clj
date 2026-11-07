(ns hc.server.materials.session
  "Session-facing materials service.

  This namespace is the seam between the pure materials domain and the
  application.  It owns a `hc.server.materials.store/Store` and serializes
  domain transactions in one process lock.  It performs no search request;
  callers supply the real user text, ids and timestamps.

  `suggest-memories!` is the one exception to \"no model call\": it starts
  exactly one low-level `ModelPort` attempt, outside the service lock, to
  propose memory candidates.  No other command contacts a provider.

  The application should construct one service per process:

      (def service (session/service durable-store))
      (session/initialize! service)

  Per user submission call `context-for-turn!` exactly once.  It advances the
  session's worldbook counters, selects memory and instruction text, and
  returns the pieces the model adapter should inject.  Tool round-trips and
  failed sends must not call it."
  (:require [clojure.string :as str]
            [hc.server.materials :as materials]
            [hc.server.materials.errors :as errors]
            [hc.server.materials.memory-suggest :as memory-suggest]
            [hc.server.materials.store :as store]))

(defrecord Service [store lock model-port])

(defn service
  "Create a materials service around STORE.  The store lifecycle stays with
  the application; this service never closes it.

  MODEL-PORT is optional.  It is used only by `suggest-memories!`, which
  starts exactly one low-level model attempt.  Passing nil keeps every other
  command independent of the model configuration."
  ([store] (service store nil))
  ([store model-port]
   (->Service store (Object.) model-port)))

(defn initialize!
  "Create the materials library when absent, or load the existing one."
  [service]
  (store/initialize! (:store service)))

(defn- expected-store-error?
  [e]
  (let [data (ex-data e)]
    (or (:storage/expected? data)
        (:materials/expected? data))))

(defn- store-error
  [e]
  (let [data (ex-data e)]
    {:ok? false
     :error (cond-> {:code (or (:error data) :MATERIALS_STORE_ERROR)
                     :message (or (ex-message e) "materials store failed")}
              (seq (dissoc data :error :storage/expected? :materials/expected?))
              (assoc :details
                     (dissoc data :error :storage/expected? :materials/expected?)))
     :effects []}))

(defn- load-state
  [service]
  (try
    (store/load-state (:store service))
    (catch clojure.lang.ExceptionInfo e
      (if (expected-store-error? e)
        (assoc (store-error e) :found? false)
        (throw e)))))

(defn- ensure-loaded
  "Load the materials state, creating an empty library once when absent.

  A seed or restored store can legitimately have no materials partition.
  Initialization is compare-and-set guarded by `store/initialize!`; a
  concurrent creator wins and this caller reads that state.  A damaged
  state is still refused, never silently replaced."
  [service]
  (let [loaded (load-state service)]
    (if (and (:ok? loaded) (not (:found? loaded)))
      (let [initialized (try
                          (store/initialize! (:store service))
                          (catch clojure.lang.ExceptionInfo e
                            (if (expected-store-error? e)
                              (store-error e)
                              (throw e))))]
        (if (:ok? initialized) initialized loaded))
      loaded)))

(defn- transact
  [service f]
  (try
    (locking (:lock service)
      (store/transact! (:store service) f))
    (catch clojure.lang.ExceptionInfo e
      (if (expected-store-error? e)
        (store-error e)
        (throw e)))))

(def built-in-assistant-id
  "The built-in assistant every new session starts with.

  `hc.server.app.session-config` always exposes this identity, even when the
  settings document has no user assistants.  A session record from an older
  build can lack `:assistant-id`; materials still need a stable scope, so the
  read side resolves this identity instead of leaving the worldbook unbound."
  "default")

(defn resolved-assistant-id
  "The assistant identity for SESSION, or the built-in default.

  A blank string is treated as missing.  This function only reads; it never
  writes the session record or creates an assistant."
  [session]
  (let [materials-config (:materials session)]
    (or (let [value (:assistant-id session)]
          (when (and (string? value) (not (str/blank? value))) value))
        (let [value (:assistant-id materials-config)]
          (when (and (string? value) (not (str/blank? value))) value))
        built-in-assistant-id)))

(defn material-session
  "Derive the domain session context from one session record.

  A session may carry `:materials` overrides.  The assistant identity falls
  back to the built-in default so a legacy session can still bind and inject
  a worldbook.  Other missing fields stay nil."
  [session]
  (let [materials-config (:materials session)]
    (into {}
          (remove (fn [[_ value]] (nil? value)))
          {:session-id (:session-id session)
           :assistant-id (resolved-assistant-id session)
           :project-id (or (:project-id session)
                           (:project-id materials-config))
           :temporary? (boolean (or (:temporary? session)
                                    (:temporary? materials-config)
                                    (= :temporary (:status session))))
           :temporary-read? (boolean (or (:temporary-read? session)
                                         (:temporary-read? materials-config)))
           :scope-chain (:scope-chain materials-config)})))

;; ---------------------------------------------------------------------------
;; Context assembly

(defn- memory-context
  [state session memory-budget]
  (let [effective (materials/effective-memories state
                                                (material-session session))]
    (if-not (:ok? effective)
      {:ok? false :error (:error effective)}
      (let [selected (materials/select-memories (:memories effective)
                                                {:budget memory-budget})]
        (if-not (:ok? selected)
          {:ok? false :error (:error selected)}
          {:ok? true
           :provided (:provided selected)
           :omitted (:omitted selected)
           :used-chars (:used-chars selected)
           :budget memory-budget
           :reason (:reason effective)
           :scope-chain (:scope-chain effective)
           :text (materials/render-memory-injection (:provided selected))})))))

(defn- instruction-context
  [state session request]
  (let [session-ids (if (get request :include-session-cards? true)
                      (materials/instruction-session-card-ids
                       state (:session-id session))
                      [])
        turn-ids (vec (:turn-card-ids request))
        card-ids (vec (distinct (concat session-ids turn-ids)))]
    (if (empty? card-ids)
      {:ok? true :cards [] :text "" :output-format nil :unresolved []}
      (materials/select-instruction-cards
       state
       {:card-ids card-ids
        :values (:instruction-values request)}))))

(defn- worldbook-request
  [session request]
  (let [material (material-session session)]
    {:session-id (:session-id material)
   :turn-id (:turn-id request)
   :assistant-id (:assistant-id material)
   :user-texts (vec (or (:user-texts request)
                        (when (some? (:text request)) [(:text request)])
                        []))
   :budget (:worldbook-budget request)
   :book-ids (:book-ids request)
   :exclude-entry-ids (:exclude-entry-ids request)}))

(defn- worldbook-injection
  [selected]
  (str/join "\n\n" (map :body selected)))

(defn context-for-turn!
  "Assemble one user turn and advance its worldbook counters durably.

  REQUEST:

    {:session           session record
     :turn-id           stable id for this accepted user submission
     :text              current user text
     :user-texts        recent user texts oldest -> newest (optional)
     :memory-budget     characters for memory injection (nil = no limit)
     :worldbook-budget  characters for worldbook injection (nil = no limit)
     :book-ids          optional worldbook subset
     :exclude-entry-ids optional entries to exclude this turn
     :include-session-cards? default true
     :turn-card-ids     instruction cards selected for this turn only
     :instruction-values placeholder values
     :temporary-read?   optional one-shot read override for a temporary session}

  Returns:

    {:ok? true
     :session-id ...
     :turn n
     :memory {:provided [...] :omitted [...] :text ... :used-chars n
              :budget n :reason ... :scope-chain [...]}
     :worldbook {:selected [...] :omitted [...] :used-chars n :budget n}
     :instruction {:cards [...] :text ... :output-format ... :unresolved [...]}
     :injection {:instructions text :memory text :worldbook text
                 :worldbook-entries selected}
     :state-revision n}

  If :turn-card-ids is empty and the session has a staged per-turn selection
  (`stage-instruction-turn-cards`), the staged cards are used and consumed in
  the same transaction as the worldbook turn.  A non-empty :turn-card-ids
  overrides the staged selection for this turn and still consumes it, so an
  explicit override cannot leak the staged cards into the following turn.

  Instruction conflicts and memory-scope errors are refused before the
  worldbook turn is committed, so a bad context does not consume a turn.
  A worldbook commit failure returns its own refusal."
  [service request]
  (let [request (or request {})
        session (:session request)]
    (cond
      (not (map? request))
      {:ok? false
       :error {:code :MATERIALS_REQUEST_INVALID
               :message "context request must be a map"}}

      (not (and (map? session)
                (materials/non-blank-string? (:session-id session))))
      {:ok? false
       :error {:code :MATERIALS_SESSION_REQUIRED
               :message "context needs a session with :session-id"}}

      (not (materials/non-blank-string? (:turn-id request)))
      {:ok? false
       :error {:code :MATERIALS_TURN_ID_REQUIRED
               :message "context needs a non-blank :turn-id"}}

      :else
      (locking (:lock service)
        (let [loaded (ensure-loaded service)]
          (cond
            (not (:ok? loaded))
          {:ok? false :error (:error loaded)}

          (not (:found? loaded))
          {:ok? false
           :error {:code :MATERIALS_NOT_INITIALIZED
                   :message "materials library is not initialized"}}

          :else
          (let [state (:state loaded)
                session (if (and (true? (:temporary? (material-session session)))
                                 (true? (:temporary-read? request)))
                          (assoc session :temporary-read? true)
                          session)
                staged (materials/instruction-turn-card-selection
                        state (:session-id session))
                explicit-turn-ids (vec (:turn-card-ids request))
                consume-staged? (and (true? (get request :use-staged? false))
                                     (seq (:card-ids staged)))
                use-staged? (and consume-staged?
                                 (empty? explicit-turn-ids))
                instruction-request
                (if use-staged?
                  (-> request
                      (assoc :turn-card-ids (:card-ids staged))
                      (update :instruction-values
                              #(merge (:values staged) %)))
                  request)
                instruction (instruction-context state session instruction-request)]
            (if-not (:ok? instruction)
              {:ok? false
               :error (:error instruction)
               :state-revision (:revision state)}
              (let [memory (memory-context state session (:memory-budget request))]
                (if-not (:ok? memory)
                  {:ok? false
                   :error (:error memory)
                   :state-revision (:revision state)}
                  (let [turn-result
                        (transact
                         service
                         (fn [state]
                           (let [result (materials/run-worldbook-turn
                                         state (worldbook-request session request))]
                             (if (:ok? result)
                               (assoc result
                                      :state
                                      (if consume-staged?
                                        (materials/instruction-consume-turn-selection
                                         (:state result) (:session-id session))
                                        (:state result)))
                               result))))]
                    (if-not (:ok? turn-result)
                      (dissoc turn-result :state)
                      (let [selected (vec (:selected turn-result))
                            state' (:state turn-result)]
                        {:ok? true
                         :session-id (:session-id session)
                         :turn (:turn turn-result)
                         :memory (dissoc memory :ok?)
                         :worldbook {:selected selected
                                     :omitted (vec (:omitted turn-result))
                                     :used-chars (:used-chars turn-result)
                                     :budget (:budget turn-result)}
                         :instruction (dissoc instruction :ok?)
                         :injection {:instructions (:text instruction)
                                     :memory (:text memory)
                                     :worldbook (worldbook-injection selected)
                                     :worldbook-entries selected}
                         :state-revision (:revision state')})))))))))))))

;; ---------------------------------------------------------------------------
;; Memory suggestion

(defn- complete-and-parse
  "Run one model attempt and parse it; retry once with a repair prompt.

  Returns the parsed result with `:attempts` (1 or 2) and `:text`, or a
  structured failure.  A provider/transport failure is not a parse failure
  and is not retried.  A repair that also fails returns `:status
  :parse-failed` and `:remedy :retry` so the caller can offer a manual
  retry instead of guessing.

  A task/utility binding that the configured port does not define is
  reported as an unavailable role.  It never falls back to the main model:
  memory suggestion is a utility task, and the operator must configure that
  role explicitly."
  [port prepared timeout]
  (let [completion (try
                     (memory-suggest/complete port prepared timeout)
                     (catch Throwable t
                       {:ok? false
                        :error {:code :MEMORY_SUGGEST_MODEL_UNAVAILABLE
                                :message (or (ex-message t) (str t))}}))]
    (if-not (:ok? completion)
      {:ok? false :error (:error completion) :attempts 1}
      (let [parsed (memory-suggest/parse prepared (:text completion))]
        (if (:ok? parsed)
          (assoc parsed :attempts 1 :text (:text completion))
          (let [repair (memory-suggest/repair-prepared
                        prepared (:text completion) (:error parsed))
                completion2 (try
                              (memory-suggest/complete port repair timeout)
                              (catch Throwable t
                                {:ok? false
                                 :error {:code :MEMORY_SUGGEST_MODEL_UNAVAILABLE
                                         :message (or (ex-message t) (str t))}}))]
            (if-not (:ok? completion2)
              {:ok? false
               :status :parse-failed
               :remedy :retry
               :attempts 2
               :error (:error completion2)
               :first-parse-error (:error parsed)}
              (let [parsed2 (memory-suggest/parse repair (:text completion2))]
                (if (:ok? parsed2)
                  (assoc parsed2 :attempts 2 :text (:text completion2))
                  {:ok? false
                   :status :parse-failed
                   :remedy :retry
                   :attempts 2
                   :error (:error parsed2)
                   :first-parse-error (:error parsed)})))))))))

(defn- resolve-model-port
  "Resolve the model port from an explicit request value, then the service.

  The service value may be a port, a zero-argument provider, or a derefable
  reference.  The provider form lets the application follow a live settings
  save without rebuilding the materials service."
  [service request]
  (let [candidate (or (:model-port request) (:model-port service))]
    (cond
      (fn? candidate) (try (candidate) (catch Throwable _ nil))
      (instance? clojure.lang.IDeref candidate) @candidate
      :else candidate)))

(defn- resolve-model-binding
  "Resolve the memory-suggestion binding without borrowing the main model.

  Memory suggestion is a utility task.  An explicit binding wins; otherwise
  the adapter's task role is required.  There is deliberately no session or
  main-role fallback: a missing utility/task model must be reported to the
  operator instead of silently charging the conversation model."
  [request]
  (or (:model-binding request)
      {:role :task}))

(def ^:private unconfigured-role-kinds
  "Adapter failures that mean the requested utility/task role is absent."
  #{:unknown-role :role-required :unknown-model :model-missing
    :connection-missing :role-model-mismatch :role-connection-mismatch})

(defn- role-unavailable?
  [outcome]
  (contains? unconfigured-role-kinds
             (get-in outcome [:error :kind])))

(defn- unconfigured-utility-error
  [outcome]
  {:ok? false
   :error {:code :MEMORY_SUGGEST_MODEL_UNCONFIGURED
           :message (str "Configure a utility/task model for memory "
                         "suggestions. The main model is not used silently.")
           :details {:action :configure-utility-model
                     :role :task
                     :cause (get-in outcome [:error :kind])}}
   :effects []})

(defn suggest-memories!
  "Generate review candidates from the caller-supplied conversation range.

  REQUEST is the `memory-suggest/prepare` request plus:

    :messages    optional explicit message vector; when absent, the visible
                 messages of `:session` are read oldest-first
    :session     session record with `:message-order` and `:messages`
    :model-port  optional explicit port; defaults to the service port
    :timeout-ms  optional model attempt deadline

  The model binding resolves to an explicit `:model-binding`, or otherwise to
  the adapter's task role.  A binding may use `:connection`/`:model`,
  `:connection-id`/`:model-entry-id`, or the `:utility` role (mapped to the
  adapter's task role).  The session's conversation model is never borrowed
  silently.  When the port or the utility/task role is absent, the refusal
  tells the caller which role to configure.

  A non-empty explicit `:messages` vector overrides the session read; an
  empty vector is treated as absent so a client without a message projection
  still gets the server-side read.  `:source-range` still chooses the
  recent-round window and `:max-transcript-chars` still bounds the prompt;
  the number of messages dropped by that bound is returned as
  `:dropped-message-count`.

  The model attempt runs outside the service lock.  Only candidate review
  and persistence run inside the store transaction, so a slow provider never
  blocks another materials command.  A revision conflict is returned, never
  retried: the caller re-reads and decides again.

  The `:memory-suggest?` switch gates generation, exactly like
  `memory/propose`.  Candidates enter `:candidate` and never change a future
  request until explicitly approved."
  [service request]
  (let [request (or request {})
        port (resolve-model-port service request)
        binding (resolve-model-binding request)
        messages (or (seq (:messages request))
                     (memory-suggest/messages-from-session (:session request)))
        prepared (memory-suggest/prepare
                  (assoc request
                         :messages messages
                         :model-binding binding))
        timeout (when (and (integer? (:timeout-ms request))
                           (pos? (:timeout-ms request)))
                  {:timeout-ms (:timeout-ms request)})]
    (cond
      (not (:ok? prepared))
      {:ok? false :error (:error prepared) :effects []}

      :else
      (let [loaded (ensure-loaded service)]
        (cond
          (not (:ok? loaded))
          {:ok? false :error (:error loaded) :effects []}

          (not (:found? loaded))
          {:ok? false
           :error {:code :MATERIALS_NOT_INITIALIZED
                   :message "materials library is not initialized"}
           :effects []}

          (not (materials/memory-suggest-enabled? (:state loaded)))
          {:ok? false
           :error {:code :MEMORY_SUGGEST_DISABLED
                   :message "automatic memory suggestions are disabled"
                   :details {:setting :memory-suggest?}}
           :effects []}

          (nil? port)
          {:ok? false
           :error {:code :MEMORY_SUGGEST_MODEL_UNCONFIGURED
                   :message (str "Configure a utility/task model for memory "
                                 "suggestions. The main model is not used "
                                 "silently.")
                   :details {:action :configure-utility-model
                             :role :task}}
           :effects []}

          :else
          (let [outcome (complete-and-parse port prepared timeout)]
            (if-not (:ok? outcome)
              (if (role-unavailable? outcome)
                (unconfigured-utility-error outcome)
                (dissoc outcome :text))
              (try
                (locking (:lock service)
                  (store/transact!
                   (:store service)
                   (fn [state]
                     (let [result (memory-suggest/review-parsed
                                   state prepared outcome)]
                       ;; A run that skipped every candidate changed nothing.
                       ;; Commit exactly one revision only when a record was
                       ;; actually written; otherwise report a replay.
                       (if (and (:ok? result)
                                (= (:revision (:state result))
                                   (:revision state)))
                         (assoc result :replayed? true)
                         result)))))
                (catch clojure.lang.ExceptionInfo e
                  (if (expected-store-error? e)
                    (store-error e)
                    (throw e)))))))))))

;; ---------------------------------------------------------------------------
;; Read-only outbound views

(defn- read-preview
  [text limit]
  (when (string? text)
    (if (<= (errors/unicode-length text) limit)
      text
      (subs text 0 (.offsetByCodePoints ^String text 0 limit)))))

(defn- memory-view-item
  [memory]
  {:id (:id memory)
   :summary (or (read-preview (:body memory) 160) :unknown)
   :category (:category memory)
   :epistemic (:epistemic memory)
   :scope (:scope memory)
   :version (or (:version memory)
                (inc (count (:versions memory)))
                (:v memory)
                :unknown)})

(defn effective-memories-read
  "Read active memories for the outbound context without changing state.

  REQUEST may carry `:budget` (characters) and `:assistant-id`/`:project-id`
  scope overrides.  Returns

    {:ok? true
     :enabled? bool
     :reason :ok|:memory-read-disabled|:temporary-session
     :provided [{:id :summary :category :epistemic :scope :version}]
     :omitted [{:id :summary :reason :chars}]
     :memories [...] ; backwards-compatible alias of :provided
     :archived-excluded n
     :state-revision n}

  Only a bounded summary leaves the server; the body never does.  An absent
  library returns empty collections and does not initialize or commit.  The
  `:memory-read?` switch and the temporary-session rule still gate the
  effective set, and their reason is published instead of being flattened
  into an empty list."
  ([service session] (effective-memories-read service session {}))
  ([service session request]
   (let [request (or request {})
         loaded (load-state service)]
     (cond
       (not (:ok? loaded))
       {:ok? false :error (:error loaded)}

       (not (:found? loaded))
       {:ok? true :enabled? false :reason :materials-not-initialized
        :provided [] :omitted [] :memories []
        :archived-excluded 0 :state-revision nil}

       :else
       (let [state (:state loaded)
             effective (materials/effective-memories
                        state (merge (material-session session)
                                     (select-keys request
                                                  [:assistant-id :project-id
                                                   :scope-chain
                                                   :temporary-read?])))
             enabled? (materials/memory-read-enabled? state)]
         (if-not (:ok? effective)
           {:ok? false :error (:error effective)}
           (let [selection (materials/select-memories
                            (:memories effective)
                            {:budget (:budget request)})
                 provided (mapv memory-view-item (:provided selection))
                 omitted (mapv (fn [item]
                                 (let [memory (some #(when (= (:id %)
                                                             (:memory-id item))
                                                       %)
                                                    (:memories effective))]
                                   {:id (:memory-id item)
                                    :summary (when memory
                                               (read-preview (:body memory) 160))
                                    :reason (:reason item)
                                    :chars (:chars item)}))
                               (:omitted selection))]
             (if-not (:ok? selection)
               {:ok? false :error (:error selection)}
               {:ok? true
                :enabled? enabled?
                :reason (or (:reason effective) :ok)
                :temporary-read? (true? (:temporary-read? effective))
                :provided provided
                :omitted omitted
                :memories provided
                :archived-excluded (count (filter #(= :archived (:status %))
                                                  (vals (:memories state))))
                :state-revision (:revision state)}))))))))

(defn worldbook-selection-read
  "Preview the worldbook selection for one turn without advancing counters.

  REQUEST may carry `:texts`, `:budget`, `:book-ids`, and
  `:exclude-entry-ids`.  The result is

    {:ok? true
     :enabled-books [book-id ...]
     :provided [{:book :entry :reason} ...]
     :hit-not-provided [{:entry :reason} ...]
     :state-revision n}

  This uses `worldbook/preview-turn`: it reads the current turn counters but
  never writes `:turn`, `:runtime`, `:last-turn`, or the state revision.  Use
  `context-for-turn!` when a real user turn must be consumed."
  [service session request]
  (let [request (or request {})
        loaded (load-state service)]
    (cond
      (not (:ok? loaded))
      {:ok? false :error (:error loaded)}

      (not (:found? loaded))
      {:ok? true :enabled-books [] :provided [] :hit-not-provided []
       :state-revision nil}

      :else
      (let [state (:state loaded)
            material (material-session session)
            assistant-id (:assistant-id material)
            books (get-in state [:worldbook :books])
            enabled-books (->> books
                               vals
                               (filter :enabled?)
                               (filter (fn [book]
                                         (or (nil? assistant-id)
                                             (contains? (set (or (:assistant-ids book)
                                                                 []))
                                                        assistant-id))))
                               (mapv :id))
            preview (materials/preview-worldbook-turn
                     state
                     {:session-id (:session-id material)
                      :assistant-id assistant-id
                      :user-texts (vec (or (:texts request) []))
                      :budget (:budget request)
                      :book-ids (:book-ids request)
                      :exclude-entry-ids (:exclude-entry-ids request)})]
        (if-not (:ok? preview)
          {:ok? false :error (:error preview)}
          {:ok? true
           :enabled-books enabled-books
           :provided (mapv (fn [item]
                             {:book (:book-id item)
                              :entry (:entry-id item)
                              :reason (:reason item)})
                           (:selected preview))
           :hit-not-provided (mapv (fn [item]
                                     {:entry (:entry-id item)
                                      :reason (:reason item)})
                                   (:omitted preview))
           :state-revision (:revision state)})))))

(defn instruction-cards-read
  "Read the turn cards and the session card selection without writes.

  REQUEST may carry `:turn-card-ids`; those are the cards the accepted send
  payload actually attached.  They are merged with the staged turn cards so
  the outbound panel reports the same selection the model receives.  A turn
  card is listed with `:scope :turn` and a persisted session card with
  `:scope :session`; a card present in both appears once as a turn card.
  Returns `{:ok? true :cards [{:name :scope :version :body-preview}]}`.  An
  absent library returns an empty vector."
  ([service session] (instruction-cards-read service session {}))
  ([service session request]
   (let [request (or request {})
         loaded (load-state service)]
     (cond
       (not (:ok? loaded))
       {:ok? false :error (:error loaded)}

       (not (:found? loaded))
       {:ok? true :cards [] :state-revision nil}

       :else
       (let [state (:state loaded)
             session-id (:session-id (material-session session))
             cards (:instruction-cards state)
             explicit-ids (vec (or (:turn-card-ids request) []))
             staged-ids (vec (get-in state [:instruction-turns session-id
                                            :card-ids] []))
             ;; An accepted send payload is the authority for this turn.  A
             ;; staged selection is only the fallback when the payload did
             ;; not name cards.
             turn-ids (vec (distinct (if (seq explicit-ids)
                                       explicit-ids
                                       staged-ids)))
             session-ids (vec (get-in state [:instruction-sessions session-id
                                             :card-ids] []))
             turn-set (set turn-ids)
             view (fn [id scope]
                    (when-let [card (get cards id)]
                      {:name (or (:name card) :unknown)
                       :scope scope
                       :version (or (:v card) :unknown)
                       :body-preview (read-preview (:body card) 200)}))
             turn (keep #(view % :turn) turn-ids)
             session-cards (keep #(when-not (contains? turn-set %)
                                    (view % :session))
                                 session-ids)]
         {:ok? true
          :cards (vec (concat turn session-cards))
          :state-revision (:revision state)})))))

;; ---------------------------------------------------------------------------
;; Snapshot projection

(defn- memory-counts
  [memories]
  (reduce (fn [counts memory]
            (update counts (:status memory) (fnil inc 0)))
          {:candidate 0 :active 0 :archived 0 :rejected 0 :deleted 0}
          (vals memories)))

(defn- omitted-entry-view
  "Only review fields leave the server; entry bodies stay out of the snapshot."
  [item]
  (select-keys item [:entry-id :book-id :title :reason :chars]))

(defn- worldbook-session-view
  "Bounded last-turn omission evidence for the client.

  The client uses the absence of `:last-turn` to say \"the server did not
  report\" instead of inventing an empty result.  At most 50 omitted entries
  are sent; the full turn result stays on the server."
  [state session-id]
  (let [wb-session (get-in state [:worldbook :sessions session-id])
        last-turn (:last-turn wb-session)
        omitted (or (:omitted last-turn)
                    (get-in last-turn [:result :omitted]))]
    (cond-> (select-keys wb-session [:turn :runtime])
      last-turn
      (assoc :last-turn
             {:id (:id last-turn)
              :omitted (->> (or omitted [])
                            (filter map?)
                            (take 50)
                            (mapv omitted-entry-view))}))))

(defn revision
  "Cheap materials state revision for the publisher's component cache.

  Returns nil when the store cannot be read; the caller then rebuilds the
  snapshot instead of caching a failed read."
  [service]
  (try
    (let [loaded (ensure-loaded service)]
      (when (:ok? loaded)
        (get-in loaded [:state :revision])))
    (catch Throwable _ nil)))

(defn snapshot
  "Project the materials state for the client.

  Deleted tombstones are counted but never listed.  Session-specific
  worldbook runtime and instruction-card selection are included for SESSION."
  [service session]
  (let [loaded (ensure-loaded service)]
    (if-not (:ok? loaded)
      {:materials/ready? false
       :materials/error (:error loaded)}
      (if-not (:found? loaded)
        {:materials/ready? false}
      (let [state (:state loaded)
            material-session (material-session session)
            session-id (:session-id material-session)
            temporary? (true? (:temporary? material-session))
            memories (:memories state)
            visible (into {}
                          (remove (fn [[_ memory]] (= :deleted (:status memory))))
                          memories)
            order (->> visible
                       (sort-by (fn [[_ memory]] [(- (long (or (:updated-seq memory) 0)))
                                                  (:id memory)]))
                       (mapv first))
            visible (if temporary? {} visible)
            order (if temporary? [] order)
            counts (if temporary?
                     (zipmap [:candidate :active :archived :rejected :deleted]
                             (repeat 0))
                     (memory-counts memories))
            cards (:instruction-cards state)]
        (cond-> {:materials/ready? true
                 :materials/revision (:revision state)
                 :materials/settings (:settings state)
                 :materials/session material-session
                 :memory/by-id visible
                 :memory/order order
                 :memory/counts counts
                 :worldbook/by-id (:books (:worldbook state))
                 :worldbook-entry/by-id (:entries (:worldbook state))
                 :instruction-card/by-id cards
                 :instruction-session
                 (get-in state [:instruction-sessions session-id])
                 :instruction-turn
                 (get-in state [:instruction-turns session-id])}
          (get-in state [:worldbook :sessions session-id])
          (assoc :worldbook-session
                 (worldbook-session-view state session-id))))))))

;; ---------------------------------------------------------------------------
;; Commands

(defn- command-error
  [request result]
  (cond-> {:ok? false
           :error (:error result)
           :receipt {:status :refused
                     :command-id (:command-id request)
                     :command-epoch (:command-epoch request)}
           :effects []}
    (:status result) (assoc :status (:status result))
    (:remedy result) (assoc :remedy (:remedy result))
    (:attempts result) (assoc :attempts (:attempts result))))

(defn- command-ok
  [request result]
  (let [summary (select-keys result
                             [:memory :book :entry :card :selection
                              :removed-card :removed-book :removed-entry
                              :removed-entry-ids :moved :undo :count
                              :memories :provided :omitted :used-chars
                              :settings :reason :scope-chain
                              :revision :candidates :skipped
                              :skipped-reasons :included-message-ids
                              :dropped-message-count :attempts
                              :truncated? :dropped-candidates])]
    {:ok? true
     :receipt (cond-> {:status :accepted
                       :command-id (:command-id request)
                       :command-epoch (:command-epoch request)
                       :result summary}
                (:replayed? result) (assoc :replayed? true))
     :state-revision (get-in result [:state :revision])
     :effects []}))

(defn- with-temporary
  [session request]
  (assoc request :temporary? (:temporary? (material-session session))))

(defn- source-for-session
  [session payload]
  (let [source (:source payload)]
    (if (map? source)
      (assoc payload :source
             (update source :session-id #(or % (:session-id session))))
      payload)))


(declare handle-command!)

(defn stage-turn-from-send!
  "Stage per-turn cards carried by a `:send-message` payload.

  Call this from the command router before forwarding the message to the
  Owner.  `context-for-turn!` consumes the staged selection when the model
  request starts.  If the Owner later refuses the message, the caller should
  clear the staged selection so it cannot leak into a later turn.

  A payload without `:turn-card-ids` is a no-op; explicit empty vector also
  clears nothing, because the caller may still be using the staged path."
  ([service session payload]
   (stage-turn-from-send! service session payload {}))
  ([service session payload {:keys [command-id]}]
   (let [card-ids (vec (:turn-card-ids payload))]
     (if (empty? card-ids)
       {:ok? true :skipped? true :effects []}
       (handle-command!
        service
        {:session session
         :command-id (or command-id (:command-id payload))
         :payload {:kind :materials/instruction/turn/stage
                   :card-ids card-ids
                   :instruction-values (:instruction-values payload)}})))))


(defn- request-field
  "Read K from the request envelope, then from its snapshot.

  The Owner stamps `:user-turn?` and `:turn-command-id` on both levels and
  keeps `:turn-id` on the snapshot.  Reading either level keeps this gate tied
  to the request the provider actually receives."
  [request k]
  (if (contains? request k)
    (get request k)
    (get-in request [:snapshot k])))

(defn user-turn-request?
  "True when a model request is the call that answers a user message.

  An explicit `:user-turn?` marker wins over the last message role:
  `false` always means a continuation, and `true` is a user turn when the
  last snapshot message is `:user`.  A `true` marker with a `:tool`-last
  snapshot is a user turn only when the request carries the same non-blank
  `:turn-command-id` and `:turn-id` that the Owner stamped on the accepted
  submission.  A lone or mismatched id stays a continuation.  Without a
  marker the gate falls back to last message is `:user`.  Nudge and
  regenerate requests never consume a turn."
  [request]
  (let [snapshot (:snapshot request)
        messages (:messages snapshot)
        explicit? (or (contains? request :user-turn?)
                      (contains? snapshot :user-turn?))
        marker (request-field request :user-turn?)
        command-id (request-field request :turn-command-id)
        turn-id (request-field request :turn-id)]
    (and (not (contains? snapshot :nudge-id))
         (not (true? (:regenerate? snapshot)))
         (sequential? messages)
         (seq messages)
         (let [last-role (:role (last messages))]
           (if explicit?
             (cond
               (not (true? marker)) false
               (= :user last-role) true
               (= :tool last-role)
               (and (materials/non-blank-string? command-id)
                    (= command-id turn-id))
               :else false)
             (= :user last-role))))))

(defn materials-command?
  "True when KIND belongs to the materials command namespace."
  [kind]
  (and (keyword? kind)
       (str/starts-with? (name kind) "materials/")))

(defn- command-request
  [request]
  (let [payload (:payload request)]
    (if (map? payload) payload {})))

(def ^:private memory-command-kinds
  "Commands that read or write memory records.

  A temporary session may not use any of them.  The domain functions keep
  their own guards where they accept a request map; this set is the
  application seam so every command gets the same refusal."
  #{:materials/memory/remember
    :materials/memory/propose
    :materials/memory/approve
    :materials/memory/reject
    :materials/memory/replace
    :materials/memory/archive
    :materials/memory/restore
    :materials/memory/delete
    :materials/memory/search
    :materials/memory/suggest})

(defn handle-command!
  "Handle one `:materials/*` command payload.

  REQUEST:

    {:session       session record (preferred; supplies assistant/temp scope)
     :session-id    fallback session id
     :payload       {:kind :materials/... ...}
     :command-id    optional transport receipt id
     :command-epoch optional transport epoch}

  The function serializes writes through the service lock.  It returns a
  receipt-shaped map; it never returns the full materials state."
  [service request]
  (let [request (or request {})
        session (or (:session request)
                    {:session-id (:session-id request)})
        payload (command-request request)
        kind (:kind payload)
        state-result (ensure-loaded service)]
    (cond
      (not (:ok? state-result))
      {:ok? false
       :error (:error state-result)
       :effects []}

      (not (:found? state-result))
      {:ok? false
       :error {:code :MATERIALS_NOT_INITIALIZED
               :message "materials library is not initialized"}
       :effects []}

      (not (keyword? kind))
      {:ok? false
       :error {:code :MATERIALS_COMMAND_INVALID
               :message "materials command needs a keyword :kind"}
       :effects []}

      (and (contains? memory-command-kinds kind)
           (true? (:temporary? (material-session session))))
      (command-error request
                     {:error {:code :MEMORY_TEMPORARY_SESSION
                              :message "temporary sessions do not read or write memories"
                              :details {:action :temporary-session}}})

      :else
      (let [state (:state state-result)
            write! (fn [f] (transact service f))
            read! (fn [f] (f state))
            temporary-request (with-temporary session payload)]
        (case kind
          :materials/settings/update
          (let [result (write! #(materials/update-settings % (select-keys payload
                                                                        [:memory-read?
                                                                         :memory-suggest?])))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/remember
          (let [result (write! #(materials/remember % temporary-request))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/propose
          (let [payload (source-for-session session payload)
                result (write! #(materials/propose-memory %
                                                          (assoc payload
                                                                 :temporary?
                                                                 (:temporary? (material-session session)))))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/approve
          (let [id (or (:memory-id payload) (:id payload))
                changes (or (:changes payload) {})
                result (write! #(materials/approve-memory % id changes))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/reject
          (let [id (or (:memory-id payload) (:id payload))
                result (write! #(materials/reject-memory % id payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/replace
          (let [id (or (:memory-id payload) (:id payload))
                result (write! #(materials/replace-memory % id payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/archive
          (let [id (or (:memory-id payload) (:id payload))
                result (write! #(materials/archive-memory % id payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/restore
          (let [id (or (:memory-id payload) (:id payload))
                result (write! #(materials/restore-memory % id))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/delete
          (let [id (or (:memory-id payload) (:id payload))
                result (write! #(materials/delete-memory % id payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/search
          (let [result (read! #(materials/search-memories % payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/suggest
          (let [result (suggest-memories!
                        service
                        (assoc (select-keys payload
                                            [:messages :source-range :categories
                                             :scope :model-binding :model-port
                                             :timeout-ms
                                             :max-transcript-chars
                                             :max-candidates])
                               :session-id (:session-id session)
                               :session session))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/memory/effective
          (let [material (material-session session)
                temporary? (true? (:temporary? material))
                context (merge material
                               (select-keys payload
                                            [:assistant-id :project-id
                                             :scope-chain])
                               {:temporary? temporary?
                                :temporary-read?
                                (and temporary?
                                     (true? (or (:temporary-read? payload)
                                                (:temporary-read? material))))})
                result (read! #(materials/effective-memories % context))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/create
          (let [result (write! #(materials/create-worldbook % payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/update
          (let [result (write! #(materials/update-worldbook % (:book-id payload) payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/bind
          (let [result (write! #(materials/bind-worldbook %
                                                          (:book-id payload)
                                                          (:assistant-id payload)))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/unbind
          (let [result (write! #(materials/unbind-worldbook %
                                                            (:book-id payload)
                                                            (:assistant-id payload)))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/delete
          (let [result (write! #(materials/delete-worldbook % (:book-id payload) payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/entry/create
          (let [result (write! #(materials/create-worldbook-entry % payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/entry/update
          (let [changes (dissoc payload :kind :entry-id :book-id)
                result (write! #(materials/update-worldbook-entry % (:entry-id payload) changes))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/entry/move
          (let [result (write! #(materials/move-worldbook-entries % payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/worldbook/entry/delete
          (let [result (write! #(materials/delete-worldbook-entry % (:entry-id payload)))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/instruction/create
          (let [result (write! #(materials/create-instruction-card % payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/instruction/update
          (let [result (write! #(materials/update-instruction-card % (:card-id payload) payload))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/instruction/delete
          (let [result (write! #(materials/delete-instruction-card % (:card-id payload)))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/instruction/session/set
          (let [result (write! #(materials/set-instruction-session-cards
                                 % {:session-id (:session-id session)
                                    :card-ids (vec (:card-ids payload))}))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/instruction/session/clear
          (let [result (write! #(materials/clear-instruction-session-cards
                                 % (:session-id session)))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/instruction/turn/stage
          (let [result (write! #(materials/stage-instruction-turn-cards
                                 % {:session-id (:session-id session)
                                    :card-ids (vec (:card-ids payload))
                                    :values (:instruction-values payload)
                                    :command-id (:command-id request)}))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/instruction/turn/clear
          (let [result (write! #(materials/clear-instruction-turn-cards
                                 % (:session-id session)))]
            (if (:ok? result) (command-ok request result) (command-error request result)))

          :materials/snapshot
          {:ok? true
           :receipt {:status :accepted
                     :command-id (:command-id request)
                     :command-epoch (:command-epoch request)
                     :result (snapshot service session)}
           :effects []}

          {:ok? false
           :error {:code :MATERIALS_COMMAND_UNKNOWN
                   :message "unknown materials command"
                   :details {:kind kind}}
           :effects []})))))
