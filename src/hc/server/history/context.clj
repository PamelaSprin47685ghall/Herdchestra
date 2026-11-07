(ns hc.server.history.context
  "Context boundaries and the outbound projection.

  Reading history and sending history are separate projections.  Reading
  returns every visible message.  Sending starts from the manual boundary,
  optionally trims to the most recent user rounds, then replaces an applied
  compression range with its reviewed summary.

  The projection never mutates a message and never sends a model request.
  Original messages stay readable after compression is applied or undone."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.server.history.message :as message]
            [hc.server.materials.memory :as memory])
  (:import (java.nio.charset StandardCharsets)))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- positive-integer?
  [value]
  (and (integer? value) (pos? value)))

(defn- error
  [code text details]
  {:ok? false
   :error (cond-> {:code code :message text}
            (some? details) (assoc :details details))})

(defn context
  "Return the normalized context map of SESSION."
  [session]
  (let [context (:context session)]
    (if (map? context) context {})))

(defn boundary
  [session]
  (:boundary (context session)))

(defn recent-rounds
  [session]
  (:recent-rounds (context session)))

(defn pinned-attachment-ids
  [session]
  (vec (or (:pinned-attachment-ids (context session)) [])))

(defn compressions
  [session]
  (let [compressions (:compressions (context session))]
    (if (map? compressions) compressions {})))

(defn compression-order
  [session]
  (vec (or (:compression-order (context session)) [])))

(defn applied-compressions
  "Applied compressions in apply order."
  [session]
  (let [compressions (compressions session)
        ordered (->> (compression-order session)
                     (keep #(get compressions %))
                     (filter #(= :applied (:state %))))
        ordered-ids (set (map :compression-id ordered))
        remaining (remove #(contains? ordered-ids (:compression-id %))
                          (vals compressions))]
    (vec (concat ordered
                 (->> remaining
                      (filter #(= :applied (:state %)))
                      (sort-by :applied-at)
                      vec)))))

(defn- message-records
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (mapv message/normalize-message)))

(defn- visible-records
  [session]
  (->> (message-records session)
       (remove #(or (:deleted? %) (:folded? %)))
       (remove #(or (:nudge? %) (:nudge-id %)))
       vec))

(defn- order-index
  [session]
  (into {} (map-indexed (fn [index message-id] [message-id index])
                        (:message-order session))))

(defn boundary-index
  "Return `{:index n :effective? bool :message-id id}`.

  A nil boundary is effective from the beginning.  A boundary naming a
  message that is no longer in the order is not silently expanded: it selects
  an empty range and reports `:effective? false`."
  [session]
  (let [boundary (boundary session)
        message-id (:message-id boundary)
        index (get (order-index session) message-id)]
    (cond
      (nil? boundary) {:index 0 :effective? true :message-id nil}
      (nil? message-id) {:index 0 :effective? true :message-id nil}
      (some? index) {:index index :effective? true :message-id message-id}
      :else {:index nil :effective? false :message-id message-id})))

(defn set-boundary
  "Set the manual `from here` boundary.  The message is the first candidate
  for the next send range; no message is deleted or sent."
  [session message-id {:keys [now]}]
  (cond
    (not (non-blank-string? message-id))
    (error :CONTEXT_BOUNDARY_INVALID
           "boundary needs a non-blank message-id"
           {:message-id message-id})

    (not (contains? (order-index session) message-id))
    (error :CONTEXT_BOUNDARY_MESSAGE_NOT_FOUND
           "boundary message does not exist in this session"
           {:message-id message-id})

    :else
    (let [session' (assoc-in session [:context :boundary]
                             {:message-id message-id :set-at now})]
      {:ok? true
       :session session'
       :boundary (:boundary (context session'))
       :activity? false})))

(defn clear-boundary
  "Remove the manual boundary.  The original reading history was never
  changed, so this simply restores the full candidate range."
  [session]
  {:ok? true
   :session (update session :context
                    (fn [context]
                      (if (map? context) (dissoc context :boundary) {})))
   :activity? false})

(defn set-recent-rounds
  "Keep at most N most recent user rounds for the next send.  `nil` clears
  the limit.  A round is one user message; tool traffic is not a round."
  [session rounds]
  (cond
    (nil? rounds)
    {:ok? true
     :session (update session :context
                      (fn [context]
                        (if (map? context)
                          (dissoc context :recent-rounds)
                          {})))
     :activity? false}

    (not (positive-integer? rounds))
    (error :CONTEXT_RECENT_ROUNDS_INVALID
           "recent rounds must be a positive integer or nil"
           {:recent-rounds rounds})

    :else
    {:ok? true
     :session (assoc-in session [:context :recent-rounds] rounds)
     :activity? false}))

(defn- attachment-locations
  "Map attachment-id to its first location across every message version."
  [session]
  (reduce
   (fn [locations message]
     (reduce
      (fn [locations version]
        (reduce (fn [locations ref]
                  (if (contains? locations (:attachment-id ref))
                    locations
                    (assoc locations (:attachment-id ref)
                           {:message-id (:message-id message)
                            :version-id (:version-id version)
                            :attachment ref})))
                locations
                (or (:attachments version) [])))
      locations
      (:versions message)))
   {}
   (message-records session)))

(defn attachment-known?
  [session attachment-id]
  (contains? (attachment-locations session) attachment-id))

(defn pin-attachment
  "Mark one historical attachment as preferentially retained.

  Pinning does not copy bytes and does not change any message version.  The
  pinned reference keeps the same attachment identity."
  [session attachment-id]
  (let [locations (attachment-locations session)]
    (cond
      (not (non-blank-string? attachment-id))
      (error :ATTACHMENT_ID_INVALID
             "attachment-id must be a non-blank string"
             {:attachment-id attachment-id})

      (not (contains? locations attachment-id))
      (error :ATTACHMENT_NOT_FOUND
             "attachment does not exist in this session history"
             {:attachment-id attachment-id})

      :else
      {:ok? true
       :session (update-in session [:context :pinned-attachment-ids]
                           (fn [ids]
                             (vec (distinct (conj (vec (or ids []))
                                                  attachment-id)))))
       :attachment-id attachment-id
       :activity? false})))

(defn unpin-attachment
  [session attachment-id]
  {:ok? true
   :session (update-in session [:context :pinned-attachment-ids]
                       (fn [ids] (vec (remove #{attachment-id} (or ids [])))))
   :attachment-id attachment-id
   :activity? false})

(defn pinned-attachments
  "Resolve pinned identities to their current first location.

  A pin whose attachment is no longer present is reported as missing; it is
  never silently dropped from the context panel."
  [session]
  (let [locations (attachment-locations session)]
    (reduce (fn [{:keys [refs missing] :as state} attachment-id]
              (if-let [{:keys [message-id version-id attachment]}
                       (get locations attachment-id)]
                (update state :refs conj
                        (assoc attachment
                               :attachment-id attachment-id
                               :message-id message-id
                               :version-id version-id))
                (update state :missing conj attachment-id)))
            {:refs [] :missing []}
            (pinned-attachment-ids session))))

(defn- trim-recent-rounds
  "Return `[records excluded]` after keeping the last ROUNDS user rounds."
  [records rounds]
  (if (nil? rounds)
    [records []]
    (let [user-indexes (keep-indexed (fn [index record]
                                       (when (= :user (:role record)) index))
                                     records)
          start (when (>= (count user-indexes) rounds)
                  (nth user-indexes (- (count user-indexes) rounds)))]
      (if (nil? start)
        [records []]
        [(subvec (vec records) start)
         (subvec (vec records) 0 start)]))))

(defn- compression-ranges
  [session]
  (applied-compressions session))

(defn- range-cover
  "Return `{message-id compression}` for ranges fully inside SELECTED-IDS."
  [session selected-ids]
  (let [selected (set selected-ids)]
    (reduce
     (fn [{:keys [cover warnings] :as state} compression]
       (let [ids (vec (:message-ids compression))
             inside? (every? selected ids)]
         (if (and (seq ids) inside?)
           (assoc state :cover
                  (reduce #(assoc %1 %2 compression) cover ids))
           (update state :warnings conj
                   {:code :COMPRESSION_OUTSIDE_CONTEXT
                    :compression-id (:compression-id compression)
                    :message-ids ids}))))
     {:cover {} :warnings []}
     (compression-ranges session))))

(defn- compression-stale?
  [session compression]
  (let [expected (:source-version-ids compression)]
    (boolean
     (some (fn [[message-id version-id]]
             (let [message (get-in session [:messages message-id])
                   current (some-> message message/current-version :version-id)]
               (or (nil? message)
                   (:deleted? message)
                   (:folded? message)
                   (not= version-id current))))
           expected))))

(defn- summary-entry
  [session compression]
  (let [message-ids (vec (:message-ids compression))
        stale? (compression-stale? session compression)]
    (cond-> {:kind :context-summary
             :role :system
             :compression-id (:compression-id compression)
             :content (:summary compression)
             :source-message-ids message-ids
             :source-version-ids (:source-version-ids compression)
             :created-at (:applied-at compression)}
      stale? (assoc :stale? true))))

;; ---------------------------------------------------------------------------
;; Outbound context snapshot

(def ^:private preview-limit 200)
(def ^:private memory-summary-limit 160)

(defn- unicode-length
  [^String value]
  (.codePointCount value 0 (.length value)))

(defn- unicode-prefix
  "The first LIMIT code points of VALUE.  Never splits a surrogate pair."
  [^String value limit]
  (if (<= (unicode-length value) limit)
    value
    (.substring value 0 (.offsetByCodePoints value 0 limit))))

(defn- preview
  [value limit]
  (if (string? value)
    (unicode-prefix value limit)
    nil))

(defn- section
  "Read one snapshot section from OPTS, then from its `:materials` map."
  [opts key]
  (or (get opts key) (get-in opts [:materials key])))

(defn- module-entries
  "Normalize a prompt-module collection.

  Accepts `{name text}`, `[{:name :text}]`, or `[name ...]`.  A module with
  no text is kept as a name-only entry so a caller can list enabled modules
  without copying their bodies."
  [modules]
  (cond
    (map? modules)
    (mapv (fn [[name text]]
            {:name (if (keyword? name) (name name) (str name))
             :text (when (string? text) text)})
          modules)

    (sequential? modules)
    (mapv (fn [module]
            (cond
              (map? module)
              {:name (let [name (or (:name module) (:module module))]
                       (cond
                         (keyword? name) (name name)
                         (string? name) name
                         :else nil))
               :text (:text module)}

              (keyword? module) {:name (name module)}
              (string? module) {:name module}
              :else {:name (str module)}))
          modules)

    :else []))

(defn prompt-modules-snapshot
  "Compute the prompt byte count and the non-blank module names.

  SYSTEM is `{:text ... :modules ...}` or `{:prompt-bytes ... :modules ...}`.
  An explicit `:prompt-bytes` wins.  Otherwise the bytes come from `:text`,
  then from the concatenated module texts.  A module with blank text is not
  listed; a name-only module is listed.  Without text or an explicit byte
  count the byte count is `:unknown`, never 0."
  [system]
  (let [system (or system {})
        entries (module-entries (:modules system))
        module-text (apply str (keep :text entries))
        text (or (:text system)
                 (when (seq module-text) module-text))
        bytes (:prompt-bytes system)
        prompt-bytes (cond
                       (and (integer? bytes) (not (neg? bytes))) bytes
                       (and (string? text) (not (str/blank? text)))
                       (alength (.getBytes ^String text StandardCharsets/UTF_8))
                       :else :unknown)
        modules (->> entries
                     (filter (fn [entry]
                               (let [text (:text entry)]
                                 (or (nil? text) (not (str/blank? text))))))
                     (keep :name)
                     (filter non-blank-string?)
                     distinct
                     vec)]
    {:prompt-bytes prompt-bytes
     :modules modules}))

(defn assistant-snapshot
  "Project an assistant entity to its display name and revision.

  Accepts `{:name :revision}`, a stored entity with `:v`/`:updated-seq`, or
  nil.  A missing entity or name stays `:unknown`; no name is invented."
  [assistant]
  (if (map? assistant)
    (let [name (or (:name assistant) (:display-name assistant))
          revision (or (:revision assistant)
                       (:v assistant)
                       (:updated-seq assistant)
                       :unknown)]
      {:id (or (:id assistant) (:assistant-id assistant) :unknown)
       :name (if (non-blank-string? name) name :unknown)
       :revision revision})
    {:id :unknown :name :unknown :revision :unknown}))

(defn- system-view
  [opts]
  (prompt-modules-snapshot (:system opts)))

(defn- assistant-view
  [opts]
  (assistant-snapshot (:assistant opts)))

(defn- instructions-view
  [{:keys [instructions]}]
  (let [preview (preview (:text instructions) preview-limit)
        sensitive (and (string? preview)
                       (seq (memory/sensitive-kinds preview)))]
    {:session-override? (true? (:session-override? instructions))
     :text-preview (when-not sensitive preview)
     :redacted? (boolean sensitive)}))

(defn- memory-item-view
  "Only review fields leave the server.  A memory body is never published;
  the client receives a bounded summary and the record's content version."
  [memory]
  (let [versions (:versions memory)
        version (or (:version memory)
                    (when (vector? versions) (inc (count versions)))
                    (:v memory)
                    :unknown)]
    {:id (:id memory)
     :summary (or (preview (:body memory) memory-summary-limit) :unknown)
     :category (:category memory)
     :epistemic (:epistemic memory)
     :always? (boolean (:always? memory))
     :scope (:scope memory)
     :version version}))

(defn- memory-omitted-view
  [{:keys [id memory-id summary reason chars]}]
  {:id (or id memory-id :unknown)
   :summary (or summary :unknown)
   :reason (or reason :unknown)
   :chars (if (integer? chars) chars :unknown)})

(defn- archived-count
  [memory]
  (let [explicit (:archived-excluded memory)
        memories (:memories memory)]
    (cond
      (and (integer? explicit) (not (neg? explicit))) explicit
      (map? memories) (count (filter #(= :archived (:status %))
                                     (vals memories)))
      :else :unknown)))

(defn- memory-view
  [memory]
  (let [provided (vec (or (:provided memory) []))
        omitted (vec (or (:omitted memory) []))
        reason (or (:reason memory) :unknown)
        enabled? (cond
                   (boolean? (:enabled? memory)) (:enabled? memory)
                   (boolean? (get-in memory [:settings :memory-read?]))
                   (get-in memory [:settings :memory-read?])
                   (contains? #{:memory-read-disabled :temporary-session} reason)
                   false
                   (seq provided) true
                   :else false)]
    {:enabled? enabled?
     :reason reason
     :temporary? (boolean (:temporary? memory))
     :items (mapv memory-item-view provided)
     :omitted (mapv memory-omitted-view omitted)
     :archived-excluded (archived-count memory)}))

(defn- provided-entry-view
  [{:keys [book-id entry-id reason]}]
  {:book (or book-id :unknown)
   :entry (or entry-id :unknown)
   :reason (or reason :unknown)})

(defn- omitted-entry-view
  [{:keys [entry-id reason]}]
  {:entry (or entry-id :unknown)
   :reason (or reason :unknown)})

(defn- bound-book-ids
  "Enabled books bound to ASSISTANT-ID, or every enabled book when it is nil."
  [books assistant-id]
  (->> books
       vals
       (filter :enabled?)
       (filter (fn [book]
                 (or (nil? assistant-id)
                     (contains? (set (or (:assistant-ids book) []))
                                assistant-id))))
       (mapv :id)))

(defn- selected-entries
  "Provided entries from either the history option shape or the app shape."
  [worldbook]
  (or (:provided worldbook) (:selected worldbook) []))

(defn- omitted-entries
  "Omitted entries from either the history option shape or the app shape."
  [worldbook]
  (or (:hit-not-provided worldbook) (:omitted worldbook) []))

(defn- participating-book-ids
  "Books that took part in this turn's selection, provided or omitted."
  [worldbook]
  (->> (concat (selected-entries worldbook)
               (omitted-entries worldbook))
       (keep :book-id)
       distinct
       vec))

(defn- enabled-book-ids
  "Explicit value wins.  Otherwise infer from the materials bindings, then
  from the books that participated in this turn.  Never invent a book."
  [worldbook materials opts]
  (or (:enabled-books worldbook)
      (when (map? (:books materials))
        (bound-book-ids (:books materials)
                        (or (:assistant-id materials)
                            (:assistant-id opts))))
      (let [ids (participating-book-ids worldbook)]
        (when (seq ids) ids))))

(defn- worldbook-view
  [worldbook materials opts]
  {:enabled-books (vec (or (enabled-book-ids worldbook materials opts) []))
   :provided (mapv provided-entry-view (selected-entries worldbook))
   :hit-not-provided (mapv omitted-entry-view (omitted-entries worldbook))})

(defn- instruction-card-view
  [scope card]
  (cond-> {:name (or (:name card) :unknown)
           :scope (or (:scope card) scope :turn)
           :version (or (:version card) (:v card) :unknown)}
    (string? (:body-preview card))
    (assoc :body-preview (preview (:body-preview card) preview-limit))))

(defn- instruction-cards-view
  [opts]
  (let [instruction (:instruction opts)
        cards (or (:instruction-cards opts)
                  (:cards instruction)
                  [])
        scope (or (:scope opts) (:scope instruction) :turn)]
    (mapv #(instruction-card-view scope %) cards)))

(def ^:private read-write-kinds #{:read :write :read-write})

(defn- tool-name-kind
  "Conservative capability guess from a tool name.

  Explicit metadata always wins; this is the last fallback for a role tool
  that carries no `:read-write`, `:read-only?`, annotations, or capability
  set."
  [name]
  (let [name (str/lower-case (str name))]
    (cond
      (some #(str/includes? name %) ["clj-plan" "clj-worker"]) :read-write
      (some #(str/includes? name %)
            ["write" "edit" "delete" "move" "rename" "create" "patch" "apply"])
      :write
      (some #(str/includes? name %)
            ["clj-review" "devops" "read" "list" "get" "cat" "stat"
             "ls" "where" "search" "find" "inspect"])
      :read
      :else :unknown)))

(defn- tool-read-write
  [tool]
  (let [explicit (:read-write tool)
        capabilities (set (or (:capabilities tool) []))
        annotations (or (:annotations tool) {})
        name (or (:name tool) (:id tool))]
    (cond
      (contains? read-write-kinds explicit) explicit
      (true? (:read-only? tool)) :read
      (true? (:readOnlyHint annotations)) :read
      (true? (:destructiveHint annotations)) :write
      (and (contains? capabilities :read) (contains? capabilities :write))
      :read-write
      (contains? capabilities :write) :write
      (contains? capabilities :read) :read
      :else (tool-name-kind name))))

(defn- tool-view
  [tool]
  (cond-> {:name (or (:name tool) (:id tool) :unknown)
           :source (or (:source tool) (:origin tool) (:kind tool) :unknown)
           :read-write (tool-read-write tool)
           :enabled? (not (false? (:enabled? tool)))}
    (non-blank-string? (:description tool))
    (assoc :description (preview (:description tool) preview-limit))))

(defn tools-snapshot
  "Project the role's allowed tool definitions for the context panel.

  Each entry is `{:name :source :read-write :enabled?}`.  Read/write comes
  from explicit metadata (`:read-write`, `:read-only?`, annotations,
  capability sets) and only then from a conservative name fallback.  An
  unknown capability stays `:unknown`; it is never guessed as read-only."
  [tools]
  (->> (or tools [])
       (filter map?)
       (mapv tool-view)))

(defn skills-snapshot
  "Project the skills actually available to this turn.

  Only resolved active skills reach the panel.  A blocked or globally
  disabled skill stays out; the caller may pass its own diagnostics
  separately.  Missing data is an empty list, never an invented skill."
  [skills]
  (->> (or skills [])
       (filter map?)
       (mapv (fn [skill]
               (cond-> {:id (or (:id skill) :unknown)
                        :name (or (:name skill) :unknown)}
                 (some? (:version skill))
                 (assoc :version (:version skill)))))))

(defn- attachment-pages
  [ref]
  (let [pages (or (:pages ref)
                  (get-in ref [:extraction :pages])
                  (get-in ref [:extraction :selected-pages]))]
    (cond
      (and (vector? pages)
           (seq pages)
           (every? #(and (integer? %) (pos? %)) pages))
      (vec (sort (distinct pages)))

      :else :unknown)))

(defn- attachment-page-count
  [ref]
  (let [count (or (:page-count ref)
                  (get-in ref [:extraction :page-count]))]
    (if (and (integer? count) (not (neg? count))) count :unknown)))

(defn- attachment-view
  [ref]
  {:attachment-id (or (:attachment-id ref) :unknown)
   :name (or (:name ref) :unknown)
   :kind (or (:kind ref) (:type ref) :unknown)
   :pages (attachment-pages ref)
   :page-count (attachment-page-count ref)})

(defn- sent-attachments
  "Attachments carried by the messages actually sent this turn."
  [entries]
  (->> entries
       (remove #(= :context-summary (:kind %)))
       (mapcat #(or (:attachments %) []))
       (filter map?)
       (reduce (fn [{:keys [seen] :as state} ref]
                 (let [id (:attachment-id ref)]
                   (if (contains? seen id)
                     state
                     (-> state
                         (update :seen conj id)
                         (update :views conj (attachment-view ref))))))
               {:seen #{} :views []})
       :views
       vec))

(defn estimates-snapshot
  "Estimate tokens at roughly four characters per token.

  ESTIMATES may carry an explicit `:tokens`/`:kind`, a `:text`, or a
  `:chars` count.  An explicit token count wins.  A positive character count
  yields at least one token and is marked `:estimated`.  An empty or missing
  count is `:unknown`, never 0."
  [estimates]
  (let [estimates (or estimates {})
        tokens (:tokens estimates)
        kind (:kind estimates)
        text (:text estimates)
        chars (or (:chars estimates)
                  (when (string? text) (unicode-length text)))]
    (cond
      (and (integer? tokens)
           (not (neg? tokens))
           (not= :unknown kind))
      {:tokens tokens
       :kind :estimated}

      (and (integer? chars) (pos? chars))
      {:tokens (max 1 (quot (long chars) 4))
       :kind :estimated}

      :else
      {:tokens :unknown
       :kind :unknown})))

(defn- history-view
  [{:keys [history]}]
  (let [range (:range history)
        message-ids (vec (or (:message-ids range) []))]
    {:range {:message-ids message-ids
             :summary-ids (vec (or (:summary-ids range) []))
             :rounds (or (:rounds range) :unknown)
             :boundary (:boundary range)
             :first-message-id (first message-ids)
             :last-message-id (last message-ids)}
     :compressed? (boolean (:compressed? history))
     :visible-count (or (:visible-count history) :unknown)
     :excluded-count (or (:excluded-count history) :unknown)}))

(defn context-snapshot
  "Project what this turn actually carries.

  Every section is caller-supplied plain data; this function reads no
  materials state and starts no model request.  It never publishes a memory
  body, never invents a tool capability, and never turns an unknown estimate
  into zero.  `:history` keeps the send range separate from the visible
  history count.

  The result shape is:

    {:system {:prompt-bytes n|:unknown :modules [...]}
     :assistant {:name ... :revision ...}
     :instructions {:session-override? bool :text-preview ...}
     :memory {:enabled? bool :items [...] :archived-excluded n|:unknown}
     :worldbook {:enabled-books [...] :provided [...] :hit-not-provided [...]}
     :instruction-cards [{:name :scope :version}]
     :tools [{:name :source :read-write :enabled?}]
     :history {:range ... :compressed? bool :visible-count ... :excluded-count ...}
     :attachments [{:attachment-id :name :kind :pages :page-count}]
     :estimates {:tokens n|:unknown :kind :estimated|:unknown}}"
  [opts]
  (let [opts (or opts {})
        memory (section opts :memory)
        worldbook (section opts :worldbook)
        materials (:materials opts)]
    {:system (system-view opts)
     :assistant (assistant-view opts)
     :instructions (instructions-view opts)
     :memory (memory-view memory)
     :worldbook (worldbook-view worldbook materials opts)
     :instruction-cards (instruction-cards-view opts)
     :tools (tools-snapshot (:tools opts))
     :skills (skills-snapshot (:skills opts))
     :history (history-view opts)
     :attachments (mapv attachment-view (or (:attachments opts) []))
     :estimates (estimates-snapshot (:estimates opts))}))

(defn outbound-context
  "Build the next send range without changing history.

  Options:

    :clips             optional map from Pair Hint or Scope clipping to
                       display verbatim
    :attachment-limit  optional model attachment limit; pinned attachments
                       above it produce an explicit warning, never an
                       automatic unpin

  The result has `:messages` (selected message views and summary entries),
  `:message-ids` (real messages still sent), `:summary-ids`, `:version-ids`,
  `:excluded` with reasons, `:pinned-attachments`, `:warnings`, and the
  outbound snapshot sections from `context-snapshot`.  It is a projection
  only; nothing here starts a model request."
  ([session] (outbound-context session {}))
  ([session opts]
   (let [{:keys [clips attachment-limit]} opts
         all-visible (visible-records session)
         boundary (boundary-index session)
         warnings (cond-> []
                    (not (:effective? boundary))
                    (conj {:code :CONTEXT_BOUNDARY_MISSING
                           :message-id (:message-id boundary)}))
         boundary-index (:index boundary)
         after-boundary (if (nil? boundary-index)
                          []
                          (->> all-visible
                               (filter (fn [message]
                                         (>= (get (order-index session)
                                                 (:message-id message))
                                             boundary-index)))
                               vec))
         excluded-boundary (if (nil? boundary-index)
                             (mapv (fn [message]
                                     {:message-id (:message-id message)
                                      :reason :context-boundary-missing})
                                   all-visible)
                             (let [included (set (map :message-id after-boundary))]
                               (->> all-visible
                                    (remove #(contains? included
                                                        (:message-id %)))
                                    (mapv (fn [message]
                                            {:message-id (:message-id message)
                                             :reason :before-context-boundary})))))
         rounds (recent-rounds session)
         [selected excluded-rounds] (trim-recent-rounds after-boundary rounds)
         selected (vec selected)
         selected-ids (mapv :message-id selected)
         range-result (range-cover session selected-ids)
         cover (:cover range-result)
         warnings (into warnings (:warnings range-result))
         emitted (volatile! #{})
         entries
         (reduce
          (fn [entries message]
            (let [message-id (:message-id message)
                  compression (get cover message-id)]
              (cond
                (nil? compression)
                (conj entries (message/message-view message))

                (contains? @emitted (:compression-id compression))
                entries

                :else
                (do (vswap! emitted conj (:compression-id compression))
                    (conj entries (summary-entry session compression))))))
          []
          selected)
         summary-ids (mapv :compression-id
                           (filter #(contains? @emitted (:compression-id %))
                                   (compression-ranges session)))
         real (filterv #(not= :context-summary (:kind %)) entries)
         excluded-compression
         (mapcat (fn [compression]
                   (when (contains? @emitted (:compression-id compression))
                     (mapv (fn [message-id]
                             {:message-id message-id
                              :reason :replaced-by-context-summary
                              :compression-id (:compression-id compression)})
                           (:message-ids compression))))
                 (compression-ranges session))
         pins (pinned-attachments session)
         warnings (cond-> warnings
                    (seq (:missing pins))
                    (conj {:code :PINNED_ATTACHMENT_MISSING
                           :attachment-ids (:missing pins)})

                    (and (integer? attachment-limit)
                         (not (neg? attachment-limit))
                         (> (count (:refs pins)) attachment-limit))
                    (conj {:code :PINNED_ATTACHMENT_LIMIT_EXCEEDED
                           :count (count (:refs pins))
                           :limit attachment-limit}))
         excluded (vec (concat excluded-boundary
                               (mapv (fn [message]
                                       {:message-id (:message-id message)
                                        :reason :outside-recent-rounds})
                                     excluded-rounds)
                               excluded-compression))
         round-count (count (filter #(= :user (:role %)) selected))
         history {:range {:message-ids selected-ids
                          :summary-ids summary-ids
                          :rounds round-count
                          :boundary (select-keys boundary
                                                 [:message-id :effective?])}
                  :compressed? (boolean (seq summary-ids))
                  :visible-count (count all-visible)
                  :excluded-count (count excluded)}
         snapshot (context-snapshot
                   (assoc opts
                          :attachments (sent-attachments entries)
                          :history history))]
     (merge
      {:messages entries
       :message-ids (mapv :message-id real)
       :version-ids (into {} (map (juxt :message-id :version-id) real))
       :summary-ids summary-ids
       :excluded excluded
       :rounds round-count
       :boundary (assoc boundary :recent-rounds rounds)
       :clips (or clips {})
       :pinned-attachments (:refs pins)
       :missing-pinned-attachments (:missing pins)
       :warnings (vec warnings)
       :message-count (count real)
       :summary-count (count summary-ids)}
      snapshot))))

(defn reading-context
  "The reading projection is deliberately the full visible history."
  [session]
  {:messages (message/visible-messages session)
   :message-ids (message/visible-message-ids session)
   :deleted (message/deleted-messages session)
   :pinned-attachments (:refs (pinned-attachments session))
   :warnings (let [missing (:missing (pinned-attachments session))]
               (if (seq missing)
                 [{:code :PINNED_ATTACHMENT_MISSING
                   :attachment-ids missing}]
                 []))})

(defn check-invariants
  "Return context, compression, and pinned-attachment invariant violations."
  [session]
  (let [context (context session)
        order (set (:message-order session))
        boundary (boundary session)
        compressions (compressions session)
        order-ids (compression-order session)
        applied (filter #(= :applied (:state %)) (vals compressions))
        ranges (map (fn [compression]
                      [(:compression-id compression)
                       (set (:message-ids compression))])
                    applied)
        overlap (first
                 (for [[[id-a ids-a] [id-b ids-b]] (for [a ranges b ranges
                                                         :when (not= (first a)
                                                                     (first b))]
                                                     [a b])
                       :when (seq (set/intersection ids-a ids-b))]
                   [id-a id-b]))]
    (into
     []
     (concat
      (cond-> []
        (and boundary
             (not (non-blank-string? (:message-id boundary))))
        (conj {:invariant :context-boundary-invalid
               :boundary boundary})

        (and boundary
             (not (contains? order (:message-id boundary))))
        (conj {:invariant :context-boundary-message-missing
               :message-id (:message-id boundary)})

        (and (some? (:recent-rounds context))
             (not (positive-integer? (:recent-rounds context))))
        (conj {:invariant :context-recent-rounds-invalid
               :recent-rounds (:recent-rounds context)})

        (not (map? compressions))
        (conj {:invariant :context-compressions-invalid})

        (not= (count order-ids) (count (set order-ids)))
        (conj {:invariant :context-compression-order-duplicate})

        (some #(not (contains? compressions %)) order-ids)
        (conj {:invariant :context-compression-order-missing
               :order order-ids})

        (some #(not (contains? #{:draft :applied :reverted :cancelled}
                               (:state %)))
              (vals compressions))
        (conj {:invariant :context-compression-state-invalid})

        overlap
        (conj {:invariant :context-compression-ranges-overlap
               :compressions overlap}))
      (mapcat
       (fn [[compression-id compression]]
         (let [ids (:message-ids compression)]
           (cond-> []
             (not (non-blank-string? compression-id))
             (conj {:invariant :context-compression-id-invalid
                    :compression-id compression-id})

             (not (and (vector? ids) (seq ids)))
             (conj {:invariant :context-compression-range-invalid
                    :compression-id compression-id})

             (and (vector? ids)
                  (not= (count ids) (count (set ids))))
             (conj {:invariant :context-compression-range-duplicate
                    :compression-id compression-id})

             (and (vector? ids)
                  (some #(not (contains? order %)) ids))
             (conj {:invariant :context-compression-message-missing
                    :compression-id compression-id})

             (not (string? (:summary compression)))
             (conj {:invariant :context-compression-summary-invalid
                    :compression-id compression-id})

             (and (= :applied (:state compression))
                  (not (string? (:summary compression))))
             (conj {:invariant :context-applied-summary-invalid
                    :compression-id compression-id}))))
       compressions)
      (let [missing (:missing (pinned-attachments session))]
        (when (seq missing)
          [{:invariant :context-pinned-attachment-missing
            :attachment-ids missing}]))))))

(defn model-messages
  "Convert one outbound projection to model-visible messages.

  A compression summary is a system message; replaced source messages are
  already absent from `:messages`.  Empty bodies are dropped so a tool-only
  message cannot become an empty text turn."
  [outbound]
  (->> (:messages outbound)
       (keep (fn [entry]
               (let [role (:role entry)
                     text (or (:content entry) (:text entry))]
                 (when (and role (string? text) (not (str/blank? text)))
                   (cond-> {:role role :text text}
                     (:message-id entry)
                     (assoc :message-id (:message-id entry)))))))
       vec))
