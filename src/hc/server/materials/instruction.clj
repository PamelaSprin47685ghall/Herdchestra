(ns hc.server.materials.instruction
  "Instruction cards: reusable, explicitly selected task instructions.

  Authority: root AGENTS.md §5.4 and §7.4 旅程 5.6（记忆、世界书、指令卡）.

  A card is a library object.  It never grants tool permission, never changes
  the assistant's global prompt, and never enters the user's draft.  A card
  is applied only when the caller selects it for one turn or persists the
  selection for one session.

  Two selected cards may declare different output formats.  The domain
  refuses to guess a winner; the caller shows the conflict and lets the user
  keep one card or combine them explicitly."
  (:require [clojure.string :as str]
            [hc.server.materials.errors :as errors]
            [hc.server.materials.state :as state])
  (:import (java.util UUID)))

(def max-name-chars 80)
(def max-description-chars 300)
(def max-body-chars 20000)
(def max-placeholders 32)

(defn- valid-id? [value]
  (errors/non-blank-string? value))

(defn- id-or
  "Accept `:id` or the command's domain key (`:card-id`)."
  [request prefix & [id-key]]
  (or (:id request)
      (when id-key (get request id-key))
      (str prefix "-" (UUID/randomUUID))))

(defn- invalid [code message details]
  {:ok? false
   :error (errors/error code message details)})

(defn- validate-name
  [name]
  (cond
    (not (string? name))
    (errors/error :INSTRUCTION_NAME_INVALID
                  "instruction card name must be a string" {:name name})

    (str/blank? name)
    (errors/error :INSTRUCTION_NAME_INVALID
                  "instruction card name must not be blank" {:name name})

    (> (errors/unicode-length name) max-name-chars)
    (errors/error :INSTRUCTION_NAME_TOO_LONG
                  (str "instruction card name exceeds " max-name-chars " characters")
                  {:length (errors/unicode-length name) :limit max-name-chars})

    :else nil))

(defn- validate-description
  [description]
  (when (and (some? description)
             (or (not (string? description))
                 (> (errors/unicode-length description) max-description-chars)))
    (errors/error :INSTRUCTION_DESCRIPTION_INVALID
                  (str "instruction card description must be a string of at most "
                       max-description-chars " characters")
                  {:description description :limit max-description-chars})))

(defn- validate-body
  [body]
  (cond
    (not (string? body))
    (errors/error :INSTRUCTION_BODY_INVALID
                  "instruction card body must be a string" {:body body})

    (str/blank? body)
    (errors/error :INSTRUCTION_BODY_INVALID
                  "instruction card body must not be blank" {:body body})

    (> (errors/unicode-length body) max-body-chars)
    (errors/error :INSTRUCTION_BODY_TOO_LONG
                  (str "instruction card body exceeds " max-body-chars " characters")
                  {:length (errors/unicode-length body) :limit max-body-chars})

    :else nil))

(defn- validate-placeholders
  [placeholders]
  (cond
    (not (vector? placeholders))
    (errors/error :INSTRUCTION_PLACEHOLDERS_INVALID
                  "placeholders must be a vector" {:placeholders placeholders})

    (> (count placeholders) max-placeholders)
    (errors/error :INSTRUCTION_PLACEHOLDERS_INVALID
                  "too many placeholders"
                  {:count (count placeholders) :limit max-placeholders})

    :else
    (let [bad (some (fn [placeholder]
                      (when-not (and (map? placeholder)
                                     (errors/non-blank-string? (:key placeholder))
                                     (or (nil? (:label placeholder))
                                         (string? (:label placeholder)))
                                     (or (nil? (:required? placeholder))
                                         (boolean? (:required? placeholder))))
                        placeholder))
                    placeholders)
          keys (map :key placeholders)]
      (cond
        bad
        (errors/error :INSTRUCTION_PLACEHOLDERS_INVALID
                      "placeholder needs a non-blank :key, optional string :label and boolean :required?"
                      {:placeholder bad})

        (not= (count keys) (count (set keys)))
        (errors/error :INSTRUCTION_PLACEHOLDERS_INVALID
                      "placeholder keys must be unique"
                      {:keys keys})

        :else nil))))

(defn- validate-output-format
  [format]
  (when (and (some? format) (not (keyword? format)))
    (errors/error :INSTRUCTION_FORMAT_INVALID
                  "output-format must be a keyword or nil"
                  {:output-format format})))

(defn- validate-card-fields
  [request]
  (let [name (some-> (:name request) str/trim)
        description (:description request)
        body (:body request)
        placeholders (get request :placeholders [])
        output-format (:output-format request)
        enabled? (get request :enabled? true)
        name-error (validate-name name)
        description-error (validate-description description)
        body-error (validate-body body)
        placeholder-error (validate-placeholders placeholders)
        format-error (validate-output-format output-format)]
    (cond
      name-error {:ok? false :error name-error}
      description-error {:ok? false :error description-error}
      body-error {:ok? false :error body-error}
      placeholder-error {:ok? false :error placeholder-error}
      format-error {:ok? false :error format-error}
      (not (boolean? enabled?))
      (invalid :INSTRUCTION_ENABLED_INVALID
               "enabled? must be a boolean" {:enabled? enabled?})
      :else
      {:ok? true
       :fields {:name name
                :description description
                :body body
                :placeholders (vec placeholders)
                :output-format output-format
                :enabled? enabled?}})))

(defn create-card
  "Create one instruction card.  It is not applied until selected."
  [state request]
  (let [request (or request {})
        id (id-or request "card" :card-id)
        validated (validate-card-fields request)]
    (cond
      (not (map? request))
      (errors/refuse state :INSTRUCTION_REQUEST_INVALID
                     "instruction card request must be a map" {:request request})

      (not (valid-id? id))
      (errors/refuse state :INSTRUCTION_ID_INVALID
                     "instruction card id must be a non-blank string" {:id id})

      (contains? (:instruction-cards state) id)
      (errors/refuse state :INSTRUCTION_ID_TAKEN
                     "instruction card id already exists" {:id id})

      (not (:ok? validated))
      (errors/refuse state (:code (:error validated))
                     (:message (:error validated))
                     (:details (:error validated)))

      :else
      (let [[state seq] (state/prepare state)
            card (assoc (:fields validated)
                        :id id
                        :v 1
                        :created-seq seq
                        :updated-seq seq)]
        {:ok? true
         :state (assoc-in state [:instruction-cards id] card)
         :card card
         :effects []}))))

(defn- remove-card-from-selections
  "Remove CARD-ID from every session and staged turn selection.

  A disabled or deleted card must not make the next turn fail just because a
  selection still names it."
  [state card-id]
  (-> state
      (update :instruction-sessions
              (fn [sessions]
                (into {}
                      (map (fn [[session-id selection]]
                             [session-id
                              (update selection :card-ids
                                      (fn [ids]
                                        (vec (remove #{card-id} ids))))]))
                      (or sessions {}))))
      (update :instruction-turns
              (fn [turns]
                (into {}
                      (keep (fn [[session-id selection]]
                              (let [ids (vec (remove #{card-id}
                                                     (:card-ids selection)))]
                                (when (seq ids)
                                  [session-id
                                   (assoc selection :card-ids ids)]))))
                      (or turns {}))))))

(defn update-card
  "Edit a card.  Existing selections keep the card id; future turns use the
  new body.  Already-sent turns keep their own snapshot."
  [state id request]
  (let [card (get-in state [:instruction-cards id])
        request (or request {})]
    (cond
      (not (valid-id? id))
      (errors/refuse state :INSTRUCTION_ID_INVALID
                     "instruction card id must be a non-blank string" {:id id})

      (nil? card)
      (errors/refuse state :INSTRUCTION_NOT_FOUND
                     "instruction card does not exist" {:id id})

      (not (map? request))
      (errors/refuse state :INSTRUCTION_REQUEST_INVALID
                     "instruction card request must be a map" {:request request})

      :else
      (let [merged (merge (select-keys card
                                       [:name :description :body :placeholders
                                        :output-format :enabled?])
                          (select-keys request
                                       [:name :description :body :placeholders
                                        :output-format :enabled?]))
            validated (validate-card-fields merged)]
        (if-not (:ok? validated)
          (errors/refuse state (:code (:error validated))
                         (:message (:error validated))
                         (:details (:error validated)))
          (if (= (:fields validated) (select-keys card (keys (:fields validated))))
            {:ok? true :state state :card card :replayed? true :effects []}
            (let [[state seq] (state/prepare state)
                  updated (merge card (:fields validated)
                                 {:updated-seq seq})
                  state (assoc-in state [:instruction-cards id] updated)
                  state (if (false? (:enabled? updated))
                          (remove-card-from-selections state id)
                          state)]
              {:ok? true
               :state state
               :card updated
               :effects []})))))))

(defn delete-card
  "Delete a card and remove it from every session selection."
  [state id]
  (let [card (get-in state [:instruction-cards id])]
    (cond
      (not (valid-id? id))
      (errors/refuse state :INSTRUCTION_ID_INVALID
                     "instruction card id must be a non-blank string" {:id id})

      (nil? card)
      (errors/refuse state :INSTRUCTION_NOT_FOUND
                     "instruction card does not exist" {:id id})

      :else
      (let [state (-> state
                      (update :instruction-cards dissoc id)
                      (remove-card-from-selections id)
                      state/bump-revision)]
        {:ok? true
         :state state
         :removed-card id
         :effects []}))))

(defn set-session-cards
  "Persist the ordered card selection for one session.

  Only enabled cards may be selected.  The selection is not applied to any
  turn until the caller asks for context."
  [state {:keys [session-id card-ids] :as request}]
  (let [request (or request {})
        cards (:instruction-cards state)]
    (cond
      (not (map? request))
      (errors/refuse state :INSTRUCTION_REQUEST_INVALID
                     "selection request must be a map" {:request request})

      (not (errors/non-blank-string? session-id))
      (errors/refuse state :INSTRUCTION_SESSION_REQUIRED
                     "selection needs a non-blank session-id"
                     {:session-id session-id})

      (not (and (vector? card-ids)
                (every? valid-id? card-ids)
                (= (count card-ids) (count (set card-ids)))))
      (errors/refuse state :INSTRUCTION_SELECTION_INVALID
                     "card-ids must be a vector of unique non-blank ids"
                     {:card-ids card-ids})

      (some (fn [id]
              (let [card (get cards id)]
                (or (nil? card) (not (:enabled? card)))))
            card-ids)
      (errors/refuse state :INSTRUCTION_CARD_UNAVAILABLE
                     "every selected card must exist and be enabled"
                     {:card-ids card-ids})

      :else
      (let [existing (get-in state [:instruction-sessions session-id])]
        (if (= (vec card-ids) (:card-ids existing))
          {:ok? true :state state :selection existing :replayed? true :effects []}
          (let [[state seq] (state/prepare state)
                selection {:session-id session-id
                           :card-ids (vec card-ids)
                           :updated-seq seq}]
            {:ok? true
             :state (assoc-in state [:instruction-sessions session-id] selection)
             :selection selection
             :effects []}))))))

(defn clear-session-cards
  "Remove the persisted card selection for one session.  Idempotent."
  [state session-id]
  (cond
    (not (errors/non-blank-string? session-id))
    (errors/refuse state :INSTRUCTION_SESSION_REQUIRED
                   "session-id must be a non-blank string"
                   {:session-id session-id})

    (not (contains? (:instruction-sessions state) session-id))
    {:ok? true :state state :replayed? true :effects []}

    :else
    {:ok? true
     :state (-> state
                (update :instruction-sessions dissoc session-id)
                state/bump-revision)
     :effects []}))

(defn session-card-ids
  "The persisted ordered card ids for SESSION-ID."
  [state session-id]
  (vec (get-in state [:instruction-sessions session-id :card-ids])))

(defn- key-name
  [key]
  (if (keyword? key) (name key) (str key)))

(defn- value-for
  [values key]
  (or (get values (keyword key))
      (get values key)))

(defn- render-body
  [body values]
  (reduce-kv (fn [text key value]
               (str/replace text
                            (str "{{" (key-name key) "}}")
                            (if (string? value) value (str value))))
             body
             (or values {})))

(defn select-cards
  "Resolve an ordered card selection into text.

  REQUEST:

    {:card-ids [\"card-1\" \"card-2\"]
     :values {:audience \"beginners\"}}

  Missing required placeholders are refused.  Optional placeholders keep
  their literal `{{key}}` text and are listed in `:unresolved` so the caller
  can show what the user still needs to fill.

  Two cards with different non-nil output formats are refused with
  `:INSTRUCTION_FORMAT_CONFLICT`; the caller must let the user choose."
  [state request]
  (let [{:keys [card-ids values]} (or request {})
        cards (:instruction-cards state)]
    (cond
      (not (map? request))
      (invalid :INSTRUCTION_REQUEST_INVALID
               "selection request must be a map" {:request request})

      (not (and (vector? card-ids)
                (seq card-ids)
                (every? valid-id? card-ids)
                (= (count card-ids) (count (set card-ids)))))
      (invalid :INSTRUCTION_SELECTION_INVALID
               "card-ids must be a non-empty vector of unique non-blank ids"
               {:card-ids card-ids})

      (not (or (nil? values) (map? values)))
      (invalid :INSTRUCTION_VALUES_INVALID
               "values must be a map or nil" {:values values})

      :else
      (let [selected (mapv #(get cards %) card-ids)
            missing (keep-indexed
                     (fn [index card]
                       (when (nil? card)
                         {:index index :card-id (nth card-ids index)}))
                     selected)]
        (cond
          (seq missing)
          (invalid :INSTRUCTION_NOT_FOUND
                   "one or more instruction cards do not exist"
                   {:missing missing})

          (some (fn [card] (not (:enabled? card))) selected)
          (invalid :INSTRUCTION_CARD_UNAVAILABLE
                   "a disabled card cannot be applied"
                   {:card-ids card-ids})

          :else
          (let [formats (->> selected
                             (keep :output-format)
                             distinct
                             vec)]
            (if (> (count formats) 1)
              (invalid :INSTRUCTION_FORMAT_CONFLICT
                       "selected instruction cards require different output formats"
                       {:formats formats
                        :cards (mapv (fn [card]
                                       {:id (:id card)
                                        :output-format (:output-format card)})
                                     (filter :output-format selected))})
              (let [missing-required
                    (vec (for [card selected
                               placeholder (:placeholders card)
                               :let [key (:key placeholder)
                                     value (value-for values key)]
                               :when (and (:required? placeholder)
                                          (not (errors/non-blank-string?
                                                (when (some? value) (str value)))))]
                           {:card-id (:id card) :key key}))]
                (if (seq missing-required)
                  (invalid :INSTRUCTION_PLACEHOLDER_MISSING
                           "required instruction placeholders are missing"
                           {:missing missing-required})
                  (let [unresolved (atom [])
                        rendered (mapv
                                  (fn [card]
                                    (let [text (render-body (:body card) values)
                                          leftover (->> (:placeholders card)
                                                        (keep :key)
                                                        (filter #(str/includes?
                                                                  text (str "{{" % "}}"))))]
                                      (swap! unresolved into
                                             (map (fn [key]
                                                    {:card-id (:id card) :key key})
                                                  leftover))
                                      {:id (:id card)
                                       :name (:name card)
                                       :text text
                                       :output-format (:output-format card)}))
                                  selected)]
                    {:ok? true
                     :cards rendered
                     :text (str/join "\n\n" (map :text rendered))
                     :output-format (first formats)
                     :unresolved @unresolved}))))))))))


(declare clear-turn-cards)

(def ^:private turn-receipt-limit 64)

(defn- prune-turn-receipts
  [receipts]
  (if (<= (count receipts) turn-receipt-limit)
    receipts
    (into {}
          (take-last turn-receipt-limit
                     (sort-by (fn [[_ receipt]] (:consumed-seq receipt 0))
                              receipts)))))

(defn turn-card-receipt
  "Return the consumed-turn receipt for COMMAND-ID, or nil."
  [state command-id]
  (when (errors/non-blank-string? command-id)
    (get-in state [:instruction-turn-receipts command-id])))

(defn consume-turn-selection
  "Remove the staged selection and record its command-id as consumed.

  Composition code calls this inside the same transaction that advances the
  worldbook turn, so a replay of the same send command cannot stage the card
  again and apply it to a later turn."
  [state session-id]
  (if-let [selection (get-in state [:instruction-turns session-id])]
    (let [command-id (:command-id selection)
          receipts (if (errors/non-blank-string? command-id)
                     (assoc (:instruction-turn-receipts state)
                            command-id
                            {:session-id session-id
                             :consumed-seq (:seq state 0)})
                     (:instruction-turn-receipts state))]
      (-> state
          (update :instruction-turns dissoc session-id)
          (assoc :instruction-turn-receipts (prune-turn-receipts receipts))))
    state))

(defn turn-card-selection
  "Return the staged per-turn card selection for SESSION-ID, or nil."
  [state session-id]
  (get-in state [:instruction-turns session-id]))

(defn stage-turn-cards
  "Stage the ordered card selection for the next accepted user turn.

  This is the bridge for callers that cannot attach `:turn-card-ids` to the
  model request itself.  `context-for-turn!` consumes the staged selection in
  the same transaction that advances the worldbook turn.  Staging is per
  session and overwrites the previous pending selection.

  An empty :card-ids clears the pending selection.  Values are merged into the
  instruction placeholder values at context time."
  [state {:keys [session-id card-ids values command-id] :as request}]
  (let [request (or request {})
        cards (:instruction-cards state)]
    (cond
      (not (map? request))
      (errors/refuse state :INSTRUCTION_REQUEST_INVALID
                     "turn selection request must be a map" {:request request})

      (not (errors/non-blank-string? session-id))
      (errors/refuse state :INSTRUCTION_SESSION_REQUIRED
                     "turn selection needs a non-blank session-id"
                     {:session-id session-id})

      (and (errors/non-blank-string? command-id)
           (contains? (:instruction-turn-receipts state) command-id))
      {:ok? true
       :state state
       :replayed? true
       :suppressed? true
       :reason :already-consumed
       :effects []}

      (not (and (vector? card-ids)
                (every? valid-id? card-ids)
                (= (count card-ids) (count (set card-ids)))))
      (errors/refuse state :INSTRUCTION_SELECTION_INVALID
                     "card-ids must be a vector of unique non-blank ids"
                     {:card-ids card-ids})

      (not (or (nil? values) (map? values)))
      (errors/refuse state :INSTRUCTION_VALUES_INVALID
                     "instruction values must be a map or nil"
                     {:values values})

      (some (fn [id]
              (let [card (get cards id)]
                (or (nil? card) (not (:enabled? card)))))
            card-ids)
      (errors/refuse state :INSTRUCTION_CARD_UNAVAILABLE
                     "every staged card must exist and be enabled"
                     {:card-ids card-ids})

      (empty? card-ids)
      (clear-turn-cards state session-id)

      :else
      (let [existing (get-in state [:instruction-turns session-id])
            selection {:session-id session-id
                       :card-ids (vec card-ids)
                       :values (or values {})
                       :command-id command-id}]
        (if (and existing
                 (= (:card-ids selection) (:card-ids existing))
                 (= (:values selection) (:values existing)))
          {:ok? true :state state :selection existing :replayed? true :effects []}
          (let [[state seq] (state/prepare state)
                selection (assoc selection :updated-seq seq)]
            {:ok? true
             :state (assoc-in state [:instruction-turns session-id] selection)
             :selection selection
             :effects []}))))))

(defn clear-turn-cards
  "Clear the staged per-turn selection for one session.  Idempotent."
  [state session-id]
  (cond
    (not (errors/non-blank-string? session-id))
    (errors/refuse state :INSTRUCTION_SESSION_REQUIRED
                   "session-id must be a non-blank string"
                   {:session-id session-id})

    (not (contains? (:instruction-turns state) session-id))
    {:ok? true :state state :replayed? true :effects []}

    :else
    {:ok? true
     :state (-> state
                (update :instruction-turns dissoc session-id)
                state/bump-revision)
     :effects []}))

(defn remove-turn-selection
  "Remove the staged selection without bumping the revision.

  Composition code uses this inside the same transaction that consumes the
  selection, so the worldbook turn and the removal share one revision."
  [state session-id]
  (update state :instruction-turns dissoc session-id))

(defn check
  "Return instruction-card invariant violations."
  [state]
  (let [cards (:instruction-cards state)
        sessions (:instruction-sessions state)
        turns (:instruction-turns state)
        receipts (:instruction-turn-receipts state)
        base (cond-> []
               (not (map? cards))
               (conj {:invariant :instruction-cards-not-a-map :cards cards})

               (not (map? sessions))
               (conj {:invariant :instruction-sessions-not-a-map :sessions sessions})

               (not (map? turns))
               (conj {:invariant :instruction-turns-not-a-map :turns turns})

               (not (map? receipts))
               (conj {:invariant :instruction-turn-receipts-not-a-map
                      :receipts receipts}))
        card-violations
        (if (map? cards)
          (into []
                (mapcat (fn [[id card]]
                          (cond-> []
                            (not (valid-id? id))
                            (conj {:invariant :instruction-card-id-invalid :id id})

                            (not= id (:id card))
                            (conj {:invariant :instruction-card-id-key-mismatch
                                   :key id :id (:id card)})

                            (some? (validate-name (:name card)))
                            (conj {:invariant :instruction-card-name-invalid :id id})

                            (some? (validate-description (:description card)))
                            (conj {:invariant :instruction-card-description-invalid :id id})

                            (some? (validate-body (:body card)))
                            (conj {:invariant :instruction-card-body-invalid :id id})

                            (some? (validate-placeholders (:placeholders card)))
                            (conj {:invariant :instruction-card-placeholders-invalid :id id})

                            (some? (validate-output-format (:output-format card)))
                            (conj {:invariant :instruction-card-format-invalid :id id})

                            (not (boolean? (:enabled? card)))
                            (conj {:invariant :instruction-card-enabled-invalid :id id})))
                        cards))
          [])
        turn-violations
        (if (and (map? cards) (map? turns))
          (into []
                (keep (fn [[session-id selection]]
                        (let [ids (:card-ids selection)]
                          (cond
                            (not (errors/non-blank-string? session-id))
                            {:invariant :instruction-turn-session-invalid
                             :session-id session-id}

                            (not (and (vector? ids)
                                      (seq ids)
                                      (every? valid-id? ids)
                                      (= (count ids) (count (set ids)))))
                            {:invariant :instruction-turn-selection-invalid
                             :session-id session-id :card-ids ids}

                            (some #(nil? (get cards %)) ids)
                            {:invariant :instruction-turn-missing-card
                             :session-id session-id :card-ids ids}

                            :else nil)))
                      turns))
          [])
        session-violations
        (if (and (map? cards) (map? sessions))
          (into []
                (keep (fn [[session-id selection]]
                        (let [ids (:card-ids selection)]
                          (cond
                            (not (errors/non-blank-string? session-id))
                            {:invariant :instruction-session-id-invalid
                             :session-id session-id}

                            (not (and (vector? ids)
                                      (every? valid-id? ids)
                                      (= (count ids) (count (set ids)))))
                            {:invariant :instruction-selection-invalid
                             :session-id session-id :card-ids ids}

                            (some #(nil? (get cards %)) ids)
                            {:invariant :instruction-selection-missing-card
                             :session-id session-id :card-ids ids}

                            :else nil)))
                      sessions))
          [])
        receipt-violations
        (if (map? receipts)
          (into []
                (keep
                 (fn [[command-id receipt]]
                   (cond
                     (not (errors/non-blank-string? command-id))
                     {:invariant :instruction-turn-receipt-id-invalid
                      :command-id command-id}

                     (not (map? receipt))
                     {:invariant :instruction-turn-receipt-invalid
                      :command-id command-id}

                     (not (errors/non-blank-string? (:session-id receipt)))
                     {:invariant :instruction-turn-receipt-session-invalid
                      :command-id command-id}

                     :else nil))
                 receipts))
          [])]
    (into [] (concat base card-violations session-violations turn-violations
                     receipt-violations))))
