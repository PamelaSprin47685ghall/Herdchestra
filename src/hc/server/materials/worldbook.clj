(ns hc.server.materials.worldbook
  "Worldbook books, entries and per-session trigger counters.

  Authority: root AGENTS.md §5.4 and §7.4 旅程 5.6（世界书）.

  Counting rule: only an effective user submission advances a turn. Tool
  round-trips, streaming fragments and failed sends never call `run-turn`, so
  they never consume sticky, cooldown or delay counts.

  A turn is idempotent by :turn-id within one :session-id. Replaying the same
  turn id in that session returns the stored result without advancing
  counters; reusing it with different inputs is refused. Another session has
  its own turn count, counters and idempotency record.

  Selection never truncates an entry body. Entries that matched but did not
  fit the budget are returned in :omitted with a reason."
  (:require [clojure.string :as str]
            [hc.server.materials.errors :as errors]
            [hc.server.materials.state :as state])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat UUID)
           (java.util.regex Pattern)))

(def max-name-chars 80)
(def max-description-chars 300)
(def default-scan-depth 4)
(def max-expression-depth 16)
(def max-expression-nodes 128)

(def trigger-kinds #{:keyword :constant :expression})
(def match-kinds #{:any :all})
(def position-kinds #{:base-background :before-current :between-recent})

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- sha256
  ^String [^String value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of)
                (.digest digest (.getBytes value StandardCharsets/UTF_8)))))

(defn- non-negative-int [value]
  (and (integer? value) (not (neg? value))))

(defn- positive-int [value]
  (and (integer? value) (pos? value)))

(defn- valid-id? [value]
  (errors/non-blank-string? value))

(defn- id-or
  "Accept `:id` or the command's domain key (`:book-id`, `:entry-id`)."
  [request prefix & [id-key]]
  (or (:id request)
      (when id-key (get request id-key))
      (str prefix "-" (UUID/randomUUID))))

(defn- invalid [code message details]
  {:ok? false
   :error (errors/error code message details)})

;; ---------------------------------------------------------------------------
;; Controlled expression language

(defn- walk-expression
  [expr depth nodes]
  (let [nodes (inc nodes)]
    (cond
      (> nodes max-expression-nodes)
      (invalid :WORLDBOOK_EXPRESSION_INVALID
               "expression has too many nodes"
               {:limit max-expression-nodes})

      (> depth max-expression-depth)
      (invalid :WORLDBOOK_EXPRESSION_INVALID
               "expression is nested too deeply"
               {:limit max-expression-depth})

      (not (vector? expr))
      (invalid :WORLDBOOK_EXPRESSION_INVALID
               "expression node must be a vector"
               {:node expr})

      (empty? expr)
      (invalid :WORLDBOOK_EXPRESSION_INVALID
               "expression node must not be empty"
               {:node expr})

      :else
      (case (first expr)
        (:contains :starts-with :ends-with)
        (if (and (= 2 (count expr))
                 (errors/non-blank-string? (second expr)))
          {:ok? true :nodes nodes}
          (invalid :WORLDBOOK_EXPRESSION_INVALID
                   "text expression needs exactly one non-blank string"
                   {:node expr}))

        :not
        (if (= 2 (count expr))
          (walk-expression (second expr) (inc depth) nodes)
          (invalid :WORLDBOOK_EXPRESSION_INVALID
                   ":not needs exactly one child expression"
                   {:node expr}))

        (:all :any)
        (if (and (= 2 (count expr))
                 (vector? (second expr))
                 (seq (second expr)))
          (reduce (fn [acc child]
                    (if (:ok? acc)
                      (walk-expression child (inc depth) (:nodes acc))
                      acc))
                  {:ok? true :nodes nodes}
                  (second expr))
          (invalid :WORLDBOOK_EXPRESSION_INVALID
                   (str (name (first expr))
                        " needs a non-empty vector of child expressions")
                   {:node expr}))

        (invalid :WORLDBOOK_EXPRESSION_INVALID
                 "unknown expression operator"
                 {:operator (first expr) :node expr})))))

(defn validate-expression
  "Validate the controlled expression language.

  Supported nodes:

    [:contains \"term\"]
    [:starts-with \"prefix\"]
    [:ends-with \"suffix\"]
    [:not expr]
    [:all [expr ...]]
    [:any [expr ...]]

  There is no regular expression and no function call. The language is small
  enough to explain and bounded enough that a bad entry cannot backtrack for
  minutes."
  [expr]
  (walk-expression expr 0 0))

(defn- normalized-text
  [text case-sensitive?]
  (if case-sensitive? text (str/lower-case text)))

(defn- text-op
  [op text term case-sensitive?]
  (let [text (normalized-text text case-sensitive?)
        term (normalized-text term case-sensitive?)]
    (case op
      :contains (str/includes? text term)
      :starts-with (str/starts-with? text term)
      :ends-with (str/ends-with? text term))))

(defn eval-expression
  "Evaluate a validated expression against one scanned text block."
  [expr text case-sensitive?]
  (case (first expr)
    (:contains :starts-with :ends-with)
    (text-op (first expr) text (second expr) case-sensitive?)

    :not
    (not (eval-expression (second expr) text case-sensitive?))

    :all
    (every? #(eval-expression % text case-sensitive?) (second expr))

    :any
    (boolean (some #(eval-expression % text case-sensitive?) (second expr)))

    false))

;; ---------------------------------------------------------------------------
;; Trigger normalization

(defn- normalize-scan [trigger]
  (let [scan-depth (get trigger :scan-depth default-scan-depth)
        case-sensitive? (get trigger :case-sensitive? false)]
    (cond
      (not (positive-int scan-depth))
      (invalid :WORLDBOOK_TRIGGER_INVALID
               "scan-depth must be a positive integer"
               {:scan-depth scan-depth})

      (not (boolean? case-sensitive?))
      (invalid :WORLDBOOK_TRIGGER_INVALID
               "case-sensitive? must be a boolean"
               {:case-sensitive? case-sensitive?})

      :else
      {:ok? true
       :scan-depth scan-depth
       :case-sensitive? case-sensitive?})))

(defn normalize-trigger
  "Validate and normalize one trigger.

  Returns {:ok? true :trigger {...}} or {:ok? false :error {...}}."
  [trigger]
  (cond
    (not (map? trigger))
    (invalid :WORLDBOOK_TRIGGER_INVALID
             "trigger must be a map"
             {:trigger trigger})

    (not (contains? trigger-kinds (:kind trigger)))
    (invalid :WORLDBOOK_TRIGGER_INVALID
             "trigger kind must be keyword, constant or expression"
             {:trigger trigger})

    :else
    (let [scan (normalize-scan trigger)]
      (if-not (:ok? scan)
        scan
        (case (:kind trigger)
          :constant
          {:ok? true
           :trigger {:kind :constant
                     :valid? true
                     :scan-depth (:scan-depth scan)
                     :case-sensitive? (:case-sensitive? scan)}}

          :keyword
          (let [keywords (:keywords trigger)
                match (get trigger :match :any)]
            (cond
              (not (and (vector? keywords)
                        (seq keywords)
                        (every? errors/non-blank-string? keywords)))
              (invalid :WORLDBOOK_TRIGGER_INVALID
                       "keyword trigger needs a non-empty vector of non-blank keywords"
                       {:keywords keywords})

              (not (contains? match-kinds match))
              (invalid :WORLDBOOK_TRIGGER_INVALID
                       "keyword match must be :any or :all"
                       {:match match})

              :else
              {:ok? true
               :trigger {:kind :keyword
                         :valid? true
                         :keywords (vec keywords)
                         :match match
                         :scan-depth (:scan-depth scan)
                         :case-sensitive? (:case-sensitive? scan)}}))

          :expression
          (let [expr (:expression trigger)
                checked (validate-expression expr)]
            (if-not (:ok? checked)
              checked
              {:ok? true
               :trigger {:kind :expression
                         :valid? true
                         :expression expr
                         :scan-depth (:scan-depth scan)
                         :case-sensitive? (:case-sensitive? scan)}})))))))

(defn- raw-trigger
  [trigger]
  (if (false? (:valid? trigger))
    (:raw trigger)
    trigger))

(defn- trigger-draft
  [trigger error]
  {:kind (:kind trigger)
   :raw trigger
   :valid? false
   :validation-error error})

(defn- prepare-trigger
  "Normalize a trigger when the entry is enabled. A disabled entry may keep an
  invalid draft so the user can repair it later."
  [trigger enabled?]
  (let [normalized (normalize-trigger (raw-trigger trigger))]
    (if (:ok? normalized)
      normalized
      (if enabled?
        normalized
        {:ok? true :trigger (trigger-draft (raw-trigger trigger) (:error normalized))}))))

;; ---------------------------------------------------------------------------
;; Position

(defn normalize-position
  [position]
  (cond
    (nil? position)
    {:ok? true :position {:kind :base-background :depth 0}}

    (contains? position-kinds position)
    {:ok? true :position {:kind position :depth 0}}

    (not (map? position))
    (invalid :WORLDBOOK_POSITION_INVALID
             "position must be a keyword or map"
             {:position position})

    (not (contains? position-kinds (:kind position)))
    (invalid :WORLDBOOK_POSITION_INVALID
             "position kind is unknown"
             {:position position})

    (not (non-negative-int (get position :depth 0)))
    (invalid :WORLDBOOK_POSITION_INVALID
             "position depth must be a non-negative integer"
             {:position position})

    :else
    {:ok? true
     :position {:kind (:kind position)
                :depth (get position :depth 0)}}))

;; ---------------------------------------------------------------------------
;; Book operations

(defn- book-name-error [name]
  (cond
    (not (string? name))
    (errors/error :WORLDBOOK_NAME_INVALID
                  "worldbook name must be a string" {:name name})

    (str/blank? name)
    (errors/error :WORLDBOOK_NAME_INVALID
                  "worldbook name must not be blank" {:name name})

    (> (errors/unicode-length name) max-name-chars)
    (errors/error :WORLDBOOK_NAME_TOO_LONG
                  (str "worldbook name exceeds " max-name-chars " characters")
                  {:length (errors/unicode-length name)
                   :limit max-name-chars})

    :else nil))

(defn- book-description-error [description]
  (when (and (some? description)
             (or (not (string? description))
                 (> (errors/unicode-length description) max-description-chars)))
    (errors/error :WORLDBOOK_DESCRIPTION_INVALID
                  (str "worldbook description must be a string of at most "
                       max-description-chars " characters")
                  {:description description
                   :limit max-description-chars})))

(defn create-book
  "Create an unbound worldbook. Binding is a separate explicit operation."
  [state request]
  (let [request (or request {})
        id (id-or request "wb" :book-id)
        name (some-> (:name request) str/trim)
        description (:description request)
        name-error (book-name-error name)
        description-error (book-description-error description)
        enabled? (get request :enabled? true)
        assistant-ids (get request :assistant-ids #{})]
    (cond
      (not (map? request))
      (errors/refuse state :WORLDBOOK_REQUEST_INVALID
                     "book request must be a map" {:request request})

      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "worldbook id must be a non-blank string" {:id id})

      (contains? (get-in state [:worldbook :books]) id)
      (errors/refuse state :WORLDBOOK_ID_TAKEN
                     "worldbook id already exists" {:id id})

      name-error
      (errors/refuse state (:code name-error) (:message name-error)
                     (:details name-error))

      description-error
      (errors/refuse state (:code description-error) (:message description-error)
                     (:details description-error))

      (not (boolean? enabled?))
      (errors/refuse state :WORLDBOOK_ENABLED_INVALID
                     "enabled? must be a boolean" {:enabled? enabled?})

      (not (and (set? assistant-ids)
                (every? errors/non-blank-string? assistant-ids)))
      (errors/refuse state :WORLDBOOK_BINDING_INVALID
                     "assistant-ids must be a set of non-blank strings"
                     {:assistant-ids assistant-ids})

      :else
      (let [[state seq] (state/prepare state)
            book {:id id
                  :v 1
                  :name name
                  :description description
                  :enabled? enabled?
                  :assistant-ids (set assistant-ids)
                  :created-seq seq
                  :updated-seq seq
                  :seq seq}]
        {:ok? true
         :state (assoc-in state [:worldbook :books id] book)
         :book book
         :effects []}))))

(defn update-book
  "Change a book's name, description or enabled flag. Bindings are separate."
  [state id request]
  (let [book (get-in state [:worldbook :books id])
        request (or request {})]
    (cond
      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "worldbook id must be a non-blank string" {:id id})

      (nil? book)
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "worldbook does not exist" {:id id})

      (not (map? request))
      (errors/refuse state :WORLDBOOK_REQUEST_INVALID
                     "book request must be a map" {:request request})

      (contains? request :assistant-ids)
      (errors/refuse state :WORLDBOOK_BINDING_INVALID
                     "use bind-book or unbind-book to change bindings"
                     {:id id})

      :else
      (let [name (if (contains? request :name)
                   (some-> (:name request) str/trim)
                   (:name book))
            description (if (contains? request :description)
                          (:description request)
                          (:description book))
            enabled? (if (contains? request :enabled?)
                       (:enabled? request)
                       (:enabled? book))
            name-error (book-name-error name)
            description-error (book-description-error description)]
        (cond
          name-error
          (errors/refuse state (:code name-error) (:message name-error)
                         (:details name-error))

          description-error
          (errors/refuse state (:code description-error)
                         (:message description-error)
                         (:details description-error))

          (not (boolean? enabled?))
          (errors/refuse state :WORLDBOOK_ENABLED_INVALID
                         "enabled? must be a boolean" {:enabled? enabled?})

          (and (= name (:name book))
               (= description (:description book))
               (= enabled? (:enabled? book)))
          {:ok? true :state state :book book :replayed? true :effects []}

          :else
          (let [[state seq] (state/prepare state)
                updated (assoc book
                               :name name
                               :description description
                               :enabled? enabled?
                               :updated-seq seq)]
            {:ok? true
             :state (assoc-in state [:worldbook :books id] updated)
             :book updated
             :effects []}))))))

(defn bind-book
  "Bind a book to one assistant. Binding is idempotent."
  [state id assistant-id]
  (let [book (get-in state [:worldbook :books id])]
    (cond
      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "worldbook id must be a non-blank string" {:id id})

      (nil? book)
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "worldbook does not exist" {:id id})

      (not (errors/non-blank-string? assistant-id))
      (errors/refuse state :WORLDBOOK_BINDING_INVALID
                     "assistant-id must be a non-blank string"
                     {:assistant-id assistant-id})

      (contains? (:assistant-ids book) assistant-id)
      {:ok? true :state state :book book :replayed? true :effects []}

      :else
      (let [[state seq] (state/prepare state)
            updated (assoc book
                           :assistant-ids (conj (:assistant-ids book) assistant-id)
                           :updated-seq seq)]
        {:ok? true
         :state (assoc-in state [:worldbook :books id] updated)
         :book updated
         :effects []}))))

(defn unbind-book
  "Remove one assistant binding. Unbinding is idempotent."
  [state id assistant-id]
  (let [book (get-in state [:worldbook :books id])]
    (cond
      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "worldbook id must be a non-blank string" {:id id})

      (nil? book)
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "worldbook does not exist" {:id id})

      (not (errors/non-blank-string? assistant-id))
      (errors/refuse state :WORLDBOOK_BINDING_INVALID
                     "assistant-id must be a non-blank string"
                     {:assistant-id assistant-id})

      (not (contains? (:assistant-ids book) assistant-id))
      {:ok? true :state state :book book :replayed? true :effects []}

      :else
      (let [[state seq] (state/prepare state)
            updated (assoc book
                           :assistant-ids (disj (:assistant-ids book) assistant-id)
                           :updated-seq seq)]
        {:ok? true
         :state (assoc-in state [:worldbook :books id] updated)
         :book updated
         :effects []}))))

(defn- remove-entry-runtimes
  [state entry-ids]
  (update-in state [:worldbook :sessions]
             (fn [sessions]
               (into {}
                     (map (fn [[session-id session]]
                            [session-id
                             (update session :runtime
                                     #(apply dissoc (or % {}) entry-ids))]))
                     (or sessions {})))))

(defn delete-book
  "Delete a book and its entries.

  A bound book is refused unless :force? true. The caller should show the
  bindings first; this guard prevents an accidental silent unbinding."
  [state id request]
  (let [request (or request {})
        book (get-in state [:worldbook :books id])
        entries (->> (get-in state [:worldbook :entries])
                     (filter (fn [[_ entry]] (= id (:book-id entry))))
                     (map first)
                     vec)]
    (cond
      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "worldbook id must be a non-blank string" {:id id})

      (nil? book)
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "worldbook does not exist" {:id id})

      (and (seq (:assistant-ids book))
           (not (true? (:force? request))))
      (errors/refuse state :WORLDBOOK_BOUND
                     "worldbook is bound to assistants; unbind or force deletion"
                     {:id id :assistant-ids (vec (sort (:assistant-ids book)))})

      :else
      (let [state (-> state
                      (update-in [:worldbook :books] dissoc id)
                      (update-in [:worldbook :entries]
                                 #(apply dissoc % entries))
                      (remove-entry-runtimes entries)
                      state/bump-revision)]
        {:ok? true
         :state state
         :removed-book id
         :removed-entry-ids entries
         :effects []}))))

;; ---------------------------------------------------------------------------
;; Entry operations

(defn- entry-title-error [title]
  (cond
    (not (string? title))
    (errors/error :WORLDBOOK_TITLE_INVALID
                  "entry title must be a string" {:title title})

    (str/blank? title)
    (errors/error :WORLDBOOK_TITLE_INVALID
                  "entry title must not be blank" {:title title})

    (> (errors/unicode-length title) max-name-chars)
    (errors/error :WORLDBOOK_TITLE_TOO_LONG
                  (str "entry title exceeds " max-name-chars " characters")
                  {:length (errors/unicode-length title)
                   :limit max-name-chars})

    :else nil))

(defn- entry-body-error [body]
  (errors/invalid-body body nil))

(defn- validate-counters [request]
  (let [sticky (get request :sticky 0)
        cooldown (get request :cooldown 0)
        delay (get request :delay 0)
        order (get request :order nil)]
    (cond
      (not (non-negative-int sticky))
      (errors/error :WORLDBOOK_COUNTER_INVALID
                    "sticky must be a non-negative integer" {:sticky sticky})

      (not (non-negative-int cooldown))
      (errors/error :WORLDBOOK_COUNTER_INVALID
                    "cooldown must be a non-negative integer" {:cooldown cooldown})

      (not (non-negative-int delay))
      (errors/error :WORLDBOOK_COUNTER_INVALID
                    "delay must be a non-negative integer" {:delay delay})

      (and (some? order) (not (non-negative-int order)))
      (errors/error :WORLDBOOK_ORDER_INVALID
                    "order must be a non-negative integer" {:order order})

      :else nil)))

(defn create-entry
  "Create one entry in an existing book.

  A disabled entry may keep an invalid trigger draft. An enabled entry must
  have an executable trigger. A draft is never evaluated."
  [state request]
  (let [request (or request {})
        id (id-or request "wbe" :entry-id)
        book-id (:book-id request)
        book (get-in state [:worldbook :books book-id])
        title (some-> (:title request) str/trim)
        body (:body request)
        enabled? (get request :enabled? true)
        key? (get request :key? false)
        title-error (entry-title-error title)
        body-error (entry-body-error body)
        counter-error (validate-counters request)
        position-result (normalize-position (:position request))]
    (cond
      (not (map? request))
      (errors/refuse state :WORLDBOOK_REQUEST_INVALID
                     "entry request must be a map" {:request request})

      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "entry id must be a non-blank string" {:id id})

      (contains? (get-in state [:worldbook :entries]) id)
      (errors/refuse state :WORLDBOOK_ID_TAKEN
                     "entry id already exists" {:id id})

      (nil? book)
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "target worldbook does not exist" {:book-id book-id})

      title-error
      (errors/refuse state (:code title-error) (:message title-error)
                     (:details title-error))

      body-error
      (errors/refuse state (:code body-error) (:message body-error)
                     (:details body-error))

      (not (boolean? enabled?))
      (errors/refuse state :WORLDBOOK_ENABLED_INVALID
                     "enabled? must be a boolean" {:enabled? enabled?})

      (not (boolean? key?))
      (errors/refuse state :WORLDBOOK_KEY_INVALID
                     "key? must be a boolean" {:key? key?})

      counter-error
      (errors/refuse state (:code counter-error) (:message counter-error)
                     (:details counter-error))

      (not (:ok? position-result))
      (errors/refuse state (:code (:error position-result))
                     (:message (:error position-result))
                     (:details (:error position-result)))

      :else
      (let [trigger-result (prepare-trigger (:trigger request) enabled?)]
        (if-not (:ok? trigger-result)
          (errors/refuse state (:code (:error trigger-result))
                         (:message (:error trigger-result))
                         (:details (:error trigger-result)))
          (let [[state seq] (state/prepare state)
                order (get request :order seq)
                entry {:id id
                       :v 1
                       :book-id book-id
                       :title title
                       :body body
                       :enabled? enabled?
                       :trigger (:trigger trigger-result)
                       :key? key?
                       :position (:position position-result)
                       :sticky (get request :sticky 0)
                       :cooldown (get request :cooldown 0)
                       :delay (get request :delay 0)
                       :order order
                       :created-seq seq
                       :updated-seq seq
                       :seq seq}]
            {:ok? true
             :state (assoc-in state [:worldbook :entries id] entry)
             :entry entry
             :effects []}))))))

(defn update-entry
  "Change an entry's content or rules. Moving between books is a separate
  operation so a partial move cannot leave an entry in two places."
  [state id request]
  (let [entry (get-in state [:worldbook :entries id])
        request (or request {})]
    (cond
      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "entry id must be a non-blank string" {:id id})

      (nil? entry)
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "worldbook entry does not exist" {:id id})

      (not (map? request))
      (errors/refuse state :WORLDBOOK_REQUEST_INVALID
                     "entry request must be a map" {:request request})

      (contains? request :book-id)
      (errors/refuse state :WORLDBOOK_MOVE_REQUIRED
                     "use move-entries to change an entry's book"
                     {:id id :book-id (:book-id request)})

      :else
      (let [merged (merge (select-keys entry
                                       [:title :body :enabled? :trigger :key?
                                        :position :sticky :cooldown :delay :order])
                          (select-keys request
                                       [:title :body :enabled? :trigger :key?
                                        :position :sticky :cooldown :delay :order]))
            title (some-> (:title merged) str/trim)
            body (:body merged)
            enabled? (get merged :enabled? true)
            key? (get merged :key? false)
            title-error (entry-title-error title)
            body-error (entry-body-error body)
            counter-error (validate-counters merged)
            position-result (normalize-position (:position merged))]
        (cond
          title-error
          (errors/refuse state (:code title-error) (:message title-error)
                         (:details title-error))

          body-error
          (errors/refuse state (:code body-error) (:message body-error)
                         (:details body-error))

          (not (boolean? enabled?))
          (errors/refuse state :WORLDBOOK_ENABLED_INVALID
                         "enabled? must be a boolean" {:enabled? enabled?})

          (not (boolean? key?))
          (errors/refuse state :WORLDBOOK_KEY_INVALID
                         "key? must be a boolean" {:key? key?})

          counter-error
          (errors/refuse state (:code counter-error) (:message counter-error)
                         (:details counter-error))

          (not (:ok? position-result))
          (errors/refuse state (:code (:error position-result))
                         (:message (:error position-result))
                         (:details (:error position-result)))

          :else
          (let [trigger-result (prepare-trigger (:trigger merged) enabled?)]
            (if-not (:ok? trigger-result)
              (errors/refuse state (:code (:error trigger-result))
                             (:message (:error trigger-result))
                             (:details (:error trigger-result)))
              (let [[state seq] (state/prepare state)
                    updated (assoc entry
                                   :title title
                                   :body body
                                   :enabled? enabled?
                                   :trigger (:trigger trigger-result)
                                   :key? key?
                                   :position (:position position-result)
                                   :sticky (get merged :sticky 0)
                                   :cooldown (get merged :cooldown 0)
                                   :delay (get merged :delay 0)
                                   :order (get merged :order (:order entry))
                                   :updated-seq seq)]
                {:ok? true
                 :state (assoc-in state [:worldbook :entries id] updated)
                 :entry updated
                 :effects []}))))))))

(defn move-entries
  "Move a set of entries to another book atomically.

  All ids are validated before any change. Runtime counters move with the
  entry; the target book's bindings decide which assistants see it next."
  [state request]
  (let [request (or request {})
        entry-ids (:entry-ids request)
        book-id (:book-id request)
        entries (:entries (get-in state [:worldbook]))
        books (:books (get-in state [:worldbook]))]
    (cond
      (not (map? request))
      (errors/refuse state :WORLDBOOK_REQUEST_INVALID
                     "move request must be a map" {:request request})

      (not (and (vector? entry-ids)
                (seq entry-ids)
                (every? valid-id? entry-ids)
                (= (count entry-ids) (count (set entry-ids)))))
      (errors/refuse state :WORLDBOOK_MOVE_INVALID
                     "entry-ids must be a non-empty vector of unique ids"
                     {:entry-ids entry-ids})

      (not (valid-id? book-id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "target book id must be a non-blank string"
                     {:book-id book-id})

      (nil? (get books book-id))
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "target worldbook does not exist" {:book-id book-id})

      :else
      (let [missing (remove #(contains? entries %) entry-ids)]
        (if (seq missing)
          (errors/refuse state :WORLDBOOK_NOT_FOUND
                         "one or more entries do not exist"
                         {:missing (vec missing)})
          (let [[state seq] (state/prepare state)
                previous (into {} (map (fn [id] [id (:book-id (get entries id))])
                                       entry-ids))
                state (reduce (fn [state id]
                                (update-in state [:worldbook :entries id]
                                           assoc
                                           :book-id book-id
                                           :updated-seq seq))
                              state
                              entry-ids)]
            {:ok? true
             :state state
             :moved entry-ids
             :book-id book-id
             :undo {:entry-ids entry-ids :book-ids previous}
             :effects []}))))))

(defn delete-entry
  "Delete one entry and its runtime counters."
  [state id]
  (let [entry (get-in state [:worldbook :entries id])]
    (cond
      (not (valid-id? id))
      (errors/refuse state :WORLDBOOK_ID_INVALID
                     "entry id must be a non-blank string" {:id id})

      (nil? entry)
      (errors/refuse state :WORLDBOOK_NOT_FOUND
                     "worldbook entry does not exist" {:id id})

      :else
      (let [state (-> state
                      (update-in [:worldbook :entries] dissoc id)
                      (remove-entry-runtimes [id])
                      state/bump-revision)]
        {:ok? true
         :state state
         :removed-entry id
         :effects []}))))

;; ---------------------------------------------------------------------------
;; Trigger evaluation

(defn- ascii-word?
  [keyword]
  (boolean (re-matches #"[A-Za-z0-9_-]+" keyword)))

(defn- keyword-pattern
  [keyword case-sensitive?]
  (let [flags (if case-sensitive?
                0
                (bit-or Pattern/CASE_INSENSITIVE Pattern/UNICODE_CASE))
        quoted (Pattern/quote keyword)
        pattern (if (ascii-word? keyword)
                  (str "(?<![\\p{L}\\p{N}_])" quoted "(?![\\p{L}\\p{N}_])")
                  quoted)]
    (Pattern/compile pattern flags)))

(defn keyword-match?
  "Match one keyword or phrase against one text.

  ASCII words use letter/number boundaries so `cat` does not match
  `concatenate`. Phrases and non-ASCII keywords use substring matching, which
  keeps Chinese sentences and multi-word phrases usable."
  [text keyword case-sensitive?]
  (and (string? text)
       (string? keyword)
       (some? (re-find (keyword-pattern keyword case-sensitive?) text))))

(defn- scanned-texts
  [entry texts]
  (let [depth (:scan-depth (:trigger entry) default-scan-depth)]
    (take-last depth texts)))

(defn- combined-text
  [entry texts]
  (str/join "\n" (scanned-texts entry texts)))

(defn- trigger-hit
  [entry texts]
  (let [trigger (:trigger entry)
        case-sensitive? (:case-sensitive? trigger false)
        text (combined-text entry texts)]
    (case (:kind trigger)
      :constant
      {:hit? true :reason :constant}

      :keyword
      (let [keywords (:keywords trigger)]
        (if-not (and (vector? keywords) (seq keywords))
          {:hit? false}
          (let [matches (map #(keyword-match? text % case-sensitive?) keywords)
                hit? (case (:match trigger)
                       :all (every? true? matches)
                       :any (boolean (some true? matches))
                       false)]
            {:hit? hit? :reason :keyword})))

      :expression
      (let [expr (:expression trigger)]
        (if-not (vector? expr)
          {:hit? false}
          {:hit? (eval-expression expr text case-sensitive?)
           :reason :expression}))

      {:hit? false})))

(defn- runtime-for
  [session entry-id]
  (get-in session [:runtime entry-id] {}))

(defn- sticky?
  [runtime turn]
  (let [until (:sticky-until runtime)]
    (and (integer? until) (<= turn until))))

(defn- cooling?
  [runtime turn]
  (let [until (:cooldown-until runtime)]
    (and (integer? until) (<= turn until))))

(defn- delay-eligible?
  [entry turn]
  (let [delay (:delay entry)]
    (and (non-negative-int delay) (> turn delay))))

(defn- bound-book?
  [book assistant-id]
  (and (:enabled? book)
       (contains? (:assistant-ids book) assistant-id)))

(defn- eligible-entries
  [state {:keys [assistant-id book-ids exclude-entry-ids]}]
  (let [books (get-in state [:worldbook :books])
        entries (get-in state [:worldbook :entries])
        selected-books (when (some? book-ids) (set book-ids))
        excluded (set exclude-entry-ids)]
    (->> entries
         (vals)
         (filter (fn [entry]
                   (let [book (get books (:book-id entry))]
                     (and (:enabled? entry)
                          (true? (get-in entry [:trigger :valid?]))
                          (not (contains? excluded (:id entry)))
                          (or (nil? selected-books)
                              (contains? selected-books (:book-id entry)))
                          (bound-book? book assistant-id)))))
         (sort-by (juxt (fn [entry]
                          (get-in books [(:book-id entry) :seq] 0))
                        :order
                        :seq)))))

(defn- candidate
  [entry reason]
  {:entry entry
   :reason reason
   :chars (errors/unicode-length (:body entry))})

(declare fingerprint)

(defn- evaluate
  "Shared evaluator for run-turn and preview-turn. Returns a result map and
  the next state. When PERSIST? is false the state is returned unchanged."
  [state turn request session persist?]
  (let [texts (vec (:user-texts request))
        budget (:budget request)
        entries (eligible-entries state request)
        session-runtime (or (:runtime session) {})
        session-id (:session-id request)]
    (loop [remaining entries
           candidates []
           omitted []]
      (if-let [entry (first remaining)]
        (let [entry-runtime (runtime-for session (:id entry))]
          (cond
            (not (delay-eligible? entry turn))
            (recur (next remaining) candidates omitted)

            (sticky? entry-runtime turn)
            (recur (next remaining)
                   (conj candidates (candidate entry :sticky))
                   omitted)

            :else
            (let [{:keys [hit? reason]} (trigger-hit entry texts)]
              (cond
                (and hit? (cooling? entry-runtime turn))
                (recur (next remaining)
                       candidates
                       (conj omitted {:entry-id (:id entry)
                                      :book-id (:book-id entry)
                                      :title (:title entry)
                                      :reason :cooldown
                                      :chars (errors/unicode-length (:body entry))}))

                hit?
                (recur (next remaining)
                       (conj candidates (candidate entry reason))
                       omitted)

                :else
                (recur (next remaining) candidates omitted)))))
        (let [ordered (sort-by (fn [{:keys [entry]}]
                                 [(if (:key? entry) 0 1)
                                  (get-in state [:worldbook :books
                                                 (:book-id entry) :seq] 0)
                                  (:order entry)
                                  (:seq entry)])
                               candidates)
              {:keys [selected omitted used]}
              (loop [remaining ordered
                     used 0
                     selected []
                     omitted omitted]
                (if-let [{:keys [entry reason chars]} (first remaining)]
                  (if (or (nil? budget)
                          (<= (+ used chars) budget))
                    (recur (next remaining)
                           (+ used chars)
                           (conj selected {:entry-id (:id entry)
                                           :book-id (:book-id entry)
                                           :title (:title entry)
                                           :body (:body entry)
                                           :reason reason
                                           :key? (boolean (:key? entry))
                                           :position (:position entry)
                                           :chars chars})
                           omitted)
                    (recur (next remaining)
                           used
                           selected
                           (conj omitted {:entry-id (:id entry)
                                          :book-id (:book-id entry)
                                          :title (:title entry)
                                          :reason :budget
                                          :chars chars})))
                  {:selected selected
                   :omitted omitted
                   :used used}))
              triggered (filter (fn [item]
                                  (contains? #{:keyword :expression}
                                             (:reason item)))
                                selected)
              runtime' (reduce (fn [runtime item]
                                 (let [entry (get-in state [:worldbook :entries
                                                            (:entry-id item)])]
                                   (let [sticky (if (non-negative-int (:sticky entry))
                                                  (:sticky entry)
                                                  0)
                                         cooldown (if (non-negative-int (:cooldown entry))
                                                    (:cooldown entry)
                                                    0)]
                                     (assoc runtime (:entry-id item)
                                            {:last-trigger-turn turn
                                             :sticky-until (+ turn sticky)
                                             :cooldown-until (+ turn cooldown)}))))
                               session-runtime
                               triggered)
              result {:turn turn
                      :selected selected
                      :omitted omitted
                      :used-chars used
                      :budget budget}
              state' (if persist?
                       (-> state
                           (assoc-in [:worldbook :sessions session-id]
                                     {:turn turn
                                      :runtime runtime'
                                      :last-turn {:id (:turn-id request)
                                                  :fingerprint (fingerprint request)
                                                  :result result}})
                           state/bump-revision)
                       state)]
          {:result result
           :state state'})))))

(defn- fingerprint
  [request]
  (sha256 (pr-str [(:assistant-id request)
                   (vec (:user-texts request))
                   (:budget request)
                   (if (nil? (:book-ids request))
                     :all-books
                     (vec (sort (map str (:book-ids request)))))
                   (vec (sort (map str (:exclude-entry-ids request))))])))

(defn- validate-turn-request
  [state request require-turn-id?]
  (let [request (or request {})
        budget (:budget request)
        user-texts (:user-texts request)
        book-ids (:book-ids request)
        exclude (:exclude-entry-ids request)]
    (cond
      (not (map? request))
      (errors/refuse state :WORLDBOOK_REQUEST_INVALID
                     "turn request must be a map" {:request request})

      (not (errors/non-blank-string? (:session-id request)))
      (errors/refuse state :WORLDBOOK_SESSION_REQUIRED
                     "session-id must be a non-blank string"
                     {:session-id (:session-id request)})

      (and require-turn-id? (not (errors/non-blank-string? (:turn-id request))))
      (errors/refuse state :WORLDBOOK_TURN_ID_REQUIRED
                     "turn-id must be a non-blank string"
                     {:turn-id (:turn-id request)})

      (and (some? (:assistant-id request))
           (not (errors/non-blank-string? (:assistant-id request))))
      (errors/refuse state :WORLDBOOK_ASSISTANT_INVALID
                     "assistant-id must be a non-blank string"
                     {:assistant-id (:assistant-id request)})

      (not (and (vector? user-texts)
                (every? string? user-texts)))
      (errors/refuse state :WORLDBOOK_TEXTS_INVALID
                     "user-texts must be a vector of strings"
                     {:user-texts user-texts})

      (not (or (nil? budget) (non-negative-int budget)))
      (errors/refuse state :WORLDBOOK_BUDGET_INVALID
                     "budget must be a non-negative integer or nil"
                     {:budget budget})

      (not (or (nil? book-ids)
               (and (sequential? book-ids)
                    (every? valid-id? book-ids))))
      (errors/refuse state :WORLDBOOK_BOOKS_INVALID
                     "book-ids must be a sequence of non-blank ids"
                     {:book-ids book-ids})

      (not (or (nil? exclude)
               (and (sequential? exclude)
                    (every? valid-id? exclude))))
      (errors/refuse state :WORLDBOOK_ENTRIES_INVALID
                     "exclude-entry-ids must be a sequence of non-blank ids"
                     {:exclude-entry-ids exclude})

      :else
      {:ok? true
       :request (assoc request
                       :user-texts user-texts
                       :book-ids (when (some? book-ids) (vec book-ids))
                       :exclude-entry-ids (vec (or exclude [])))})))

(defn run-turn
  "Advance one effective user submission and select worldbook entries.

  Call exactly once per accepted user message. Never call it for a tool
  round-trip, a stream fragment or a failed send. Reusing :turn-id with the
  same inputs replays the stored result; reusing it with different inputs is
  refused."
  [state request]
  (let [validated (validate-turn-request state request true)]
    (if-not (:ok? validated)
      validated
      (let [request (:request validated)
            session (get-in state [:worldbook :sessions (:session-id request)]
                            {:turn 0 :runtime {} :last-turn nil})
            last-turn (:last-turn session)
            turn-id (:turn-id request)
            print (fingerprint request)]
        (if (and last-turn (= turn-id (:id last-turn)))
          (if (= print (:fingerprint last-turn))
            (assoc (:result last-turn)
                   :ok? true
                   :state state
                   :replayed? true
                   :effects [])
            (errors/refuse state :WORLDBOOK_TURN_CONFLICT
                           "turn-id was already used with different inputs"
                           {:turn-id turn-id}))
          (let [turn (inc (long (or (:turn session) 0)))
                {:keys [result state]} (evaluate state turn request session true)]
            (assoc result
                   :ok? true
                   :state state
                   :replayed? false
                   :effects [])))))))

(defn preview-turn
  "Evaluate the next turn without advancing counters or storing a replay.
  Used by the pre-send context check."
  [state request]
  (let [validated (validate-turn-request state request false)]
    (if-not (:ok? validated)
      validated
      (let [request (:request validated)
            session (get-in state [:worldbook :sessions (:session-id request)]
                            {:turn 0 :runtime {} :last-turn nil})
            turn (inc (long (or (:turn session) 0)))
            {:keys [result]} (evaluate state turn request session false)]
        (assoc result
               :ok? true
               :state state
               :preview? true
               :effects [])))))

;; ---------------------------------------------------------------------------
;; Invariants

(defn check
  "Return worldbook invariant violations."
  [state]
  (let [worldbook (:worldbook state)
        books (:books worldbook)
        entries (:entries worldbook)
        sessions (:sessions worldbook)
        base (cond-> []
               (not (map? worldbook))
               (conj {:invariant :worldbook-not-a-map :worldbook worldbook})

               (and (map? worldbook) (not (map? books)))
               (conj {:invariant :worldbook-books-not-a-map :books books})

               (and (map? worldbook) (not (map? entries)))
               (conj {:invariant :worldbook-entries-not-a-map :entries entries})

               (and (map? worldbook) (not (map? sessions)))
               (conj {:invariant :worldbook-sessions-not-a-map
                      :sessions sessions}))
        book-violations
        (if (and (map? books) (map? entries))
          (into []
                (mapcat
                 (fn [[id book]]
                   (cond-> []
                     (not (valid-id? id))
                     (conj {:invariant :worldbook-book-id-invalid :id id})

                     (not= id (:id book))
                     (conj {:invariant :worldbook-book-id-key-mismatch
                            :key id :id (:id book)})

                     (some? (book-name-error (:name book)))
                     (conj {:invariant :worldbook-book-name-invalid :id id})

                     (some? (book-description-error (:description book)))
                     (conj {:invariant :worldbook-book-description-invalid :id id})

                     (not (boolean? (:enabled? book)))
                     (conj {:invariant :worldbook-book-enabled-invalid :id id})

                     (not (and (set? (:assistant-ids book))
                               (every? errors/non-blank-string?
                                       (:assistant-ids book))))
                     (conj {:invariant :worldbook-book-binding-invalid :id id})

                     (some (fn [field]
                             (when-not (non-negative-int (get book field))
                               field))
                           [:created-seq :updated-seq :seq])
                     (conj {:invariant :worldbook-book-seq-invalid :id id})))
                 books))
          [])
        entry-violations
        (if (and (map? books) (map? entries))
          (into []
                (mapcat
                 (fn [[id entry]]
                   (let [book (get books (:book-id entry))]
                     (cond-> []
                       (not (valid-id? id))
                       (conj {:invariant :worldbook-entry-id-invalid :id id})

                       (not= id (:id entry))
                       (conj {:invariant :worldbook-entry-id-key-mismatch
                              :key id :id (:id entry)})

                       (nil? book)
                       (conj {:invariant :worldbook-entry-book-missing
                              :id id :book-id (:book-id entry)})

                       (some? (entry-title-error (:title entry)))
                       (conj {:invariant :worldbook-entry-title-invalid :id id})

                       (some? (entry-body-error (:body entry)))
                       (conj {:invariant :worldbook-entry-body-invalid :id id})

                       (not (boolean? (:enabled? entry)))
                       (conj {:invariant :worldbook-entry-enabled-invalid :id id})

                       (not (boolean? (:key? entry)))
                       (conj {:invariant :worldbook-entry-key-invalid :id id})

                       (and (:enabled? entry)
                            (not (true? (get-in entry [:trigger :valid?]))))
                       (conj {:invariant :worldbook-entry-trigger-invalid :id id})

                       (not (contains? #{:base-background :before-current
                                         :between-recent}
                                       (get-in entry [:position :kind])))
                       (conj {:invariant :worldbook-entry-position-invalid :id id})

                       (not (non-negative-int (get-in entry [:position :depth])))
                       (conj {:invariant :worldbook-entry-depth-invalid :id id})

                       (some (fn [field]
                               (when-not (non-negative-int (get entry field))
                                 field))
                             [:sticky :cooldown :delay :order])
                       (conj {:invariant :worldbook-entry-counter-invalid :id id})

                       (some (fn [field]
                               (when-not (non-negative-int (get entry field))
                                 field))
                             [:created-seq :updated-seq :seq])
                       (conj {:invariant :worldbook-entry-seq-invalid :id id}))))
                 entries))
          [])
        session-violations
        (if (map? sessions)
          (into []
                (mapcat
                 (fn [[session-id session]]
                   (let [runtime (:runtime session)
                         last-turn (:last-turn session)
                         shape-violations
                         (cond-> []
                           (not (valid-id? session-id))
                           (conj {:invariant :worldbook-session-id-invalid
                                  :session-id session-id})

                           (not (map? session))
                           (conj {:invariant :worldbook-session-invalid
                                  :session-id session-id})

                           (and (map? session)
                                (not (non-negative-int (:turn session))))
                           (conj {:invariant :worldbook-session-turn-invalid
                                  :session-id session-id
                                  :turn (:turn session)})

                           (and (map? session) (not (map? runtime)))
                           (conj {:invariant :worldbook-session-runtime-invalid
                                  :session-id session-id
                                  :runtime runtime})

                           (and (map? session)
                                (some? last-turn)
                                (or (not (map? last-turn))
                                    (not (errors/non-blank-string?
                                          (:id last-turn)))
                                    (not (string? (:fingerprint last-turn)))
                                    (not (map? (:result last-turn)))))
                           (conj {:invariant :worldbook-session-last-turn-invalid
                                  :session-id session-id
                                  :last-turn last-turn}))
                         runtime-violations
                         (if (and (map? entries) (map? runtime))
                           (keep (fn [[entry-id counters]]
                                   (cond
                                     (not (contains? entries entry-id))
                                     {:invariant :worldbook-runtime-orphan
                                      :session-id session-id
                                      :entry-id entry-id}

                                     (not (map? counters))
                                     {:invariant :worldbook-runtime-invalid
                                      :session-id session-id
                                      :entry-id entry-id}

                                     (some (fn [field]
                                             (when-not (non-negative-int
                                                        (get counters field))
                                               field))
                                           [:last-trigger-turn
                                            :sticky-until
                                            :cooldown-until])
                                     {:invariant :worldbook-runtime-counter-invalid
                                      :session-id session-id
                                      :entry-id entry-id}

                                     (and (non-negative-int
                                           (:last-trigger-turn counters))
                                          (non-negative-int
                                           (:sticky-until counters))
                                          (< (:sticky-until counters)
                                             (:last-trigger-turn counters)))
                                     {:invariant :worldbook-runtime-sticky-before-trigger
                                      :session-id session-id
                                      :entry-id entry-id}

                                     (and (non-negative-int
                                           (:last-trigger-turn counters))
                                          (non-negative-int
                                           (:cooldown-until counters))
                                          (< (:cooldown-until counters)
                                             (:last-trigger-turn counters)))
                                     {:invariant :worldbook-runtime-cooldown-before-trigger
                                      :session-id session-id
                                      :entry-id entry-id}

                                     :else nil))
                                 runtime)
                           [])]
                     (concat shape-violations runtime-violations))))
                 sessions)
          [])]
    (into [] (concat base book-violations entry-violations session-violations))))
