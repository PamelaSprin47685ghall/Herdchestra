(ns hc.client.materials.model
  "Pure client model for long-term memory, worldbooks and instruction cards.

  The server is the only authority.  This namespace projects the materials
  snapshot, validates user input before a command is sent, and keeps command
  envelopes in an outbox until the transport accepts them.  A local click
  never marks a domain change as applied; only a server receipt does.

  No projection invents a capability.  Per-turn instruction cards are staged
  in the UI but are not claimed as applied until the send path accepts them.
  The worldbook runtime projection reports exactly the counters the server
  snapshot contains."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn])
            #?(:cljs [cljs.reader :as reader])))

(def memory-max-chars 2000)
(def book-name-max-chars 80)
(def book-description-max-chars 300)
(def entry-title-max-chars 80)
(def card-name-max-chars 80)
(def card-description-max-chars 300)
(def card-body-max-chars 20000)

;; ClojureScript rejects keyword literals with more than one slash.  The
;; server command kinds keep their exact wire names, so construct them at
;; runtime instead of spelling them in source.
(def snapshot-kind (keyword "materials" "snapshot"))
(def settings-update-kind (keyword "materials" "settings/update"))
(def memory-remember-kind (keyword "materials" "memory/remember"))
(def memory-replace-kind (keyword "materials" "memory/replace"))
(def memory-approve-kind (keyword "materials" "memory/approve"))
(def memory-reject-kind (keyword "materials" "memory/reject"))
(def memory-archive-kind (keyword "materials" "memory/archive"))
(def memory-restore-kind (keyword "materials" "memory/restore"))
(def memory-delete-kind (keyword "materials" "memory/delete"))
(def worldbook-create-kind (keyword "materials" "worldbook/create"))
(def worldbook-update-kind (keyword "materials" "worldbook/update"))
(def worldbook-bind-kind (keyword "materials" "worldbook/bind"))
(def worldbook-unbind-kind (keyword "materials" "worldbook/unbind"))
(def worldbook-delete-kind (keyword "materials" "worldbook/delete"))
(def entry-create-kind (keyword "materials" "worldbook/entry/create"))
(def entry-update-kind (keyword "materials" "worldbook/entry/update"))
(def entry-move-kind (keyword "materials" "worldbook/entry/move"))
(def entry-delete-kind (keyword "materials" "worldbook/entry/delete"))
(def instruction-create-kind (keyword "materials" "instruction/create"))
(def instruction-update-kind (keyword "materials" "instruction/update"))
(def instruction-delete-kind (keyword "materials" "instruction/delete"))
(def instruction-session-set-kind
  (keyword "materials" "instruction/session/set"))
(def instruction-session-clear-kind
  (keyword "materials" "instruction/session/clear"))
(def memory-categories
  [:identity-background :working-style :expression-preference
   :long-term-requirement])

(def memory-category-labels
  {:identity-background "身份与背景"
   :working-style "工作方式"
   :expression-preference "表达偏好"
   :long-term-requirement "长期要求"})

(def memory-statuses [:candidate :active :archived :rejected :deleted])

(def memory-status-labels
  {:candidate "待审"
   :active "生效"
   :archived "已归档"
   :rejected "已拒绝"
   :deleted "已删除"})

(def memory-epistemic-labels
  {:fact "事实"
   :preference "偏好"
   :requirement "要求"
   :assumption "假设（未确认）"})

(def scope-kind-labels
  {:global "全局"
   :assistant "助手"
   :project "项目"})

(def trigger-kind-labels
  {:keyword "关键词"
   :constant "始终提供"
   :expression "受控表达式"})

(def match-kind-labels
  {:any "任一命中"
   :all "全部命中"})

(def position-kind-labels
  {:base-background "背景"
   :before-current "当前轮之前"
   :between-recent "近期之间"})

(def instruction-output-formats
  "Declared instruction-card output formats.  The editor only offers these
  values; validation refuses anything else before a command leaves."
  {:text "文本"
   :markdown "Markdown"
   :json "JSON"})

(def instruction-output-keys
  "Valid instruction-card output format keywords."
  (set (keys instruction-output-formats)))

(defn instruction-output-options
  "Select options `[[label value]]` for the card editor's format field."
  []
  (into [["未声明" ""]]
        (map (fn [[k label]] [label (name k)]))
        instruction-output-formats))

(def pending-statuses #{:queued :submitting})

(def omitted-reason-labels
  {:budget "上下文预算不足"
   :cooldown "冷却中"
   :delay "延迟中"
   :disabled "已停用"
   :not-selected "未入选"})

(def error-labels
  "Server refusal codes that reach the memory toolbar.  The server speaks
  English on the wire; the user-facing workbench copy stays Chinese."
  {:MEMORY_SUGGEST_MODEL_UNCONFIGURED
   "未配置任务/辅助模型，无法自动整理记忆。请先在设置中配置模型。"
   :MEMORY_SUGGEST_MODEL_UNAVAILABLE
   "任务模型当前不可用，无法自动整理记忆。"
   :MEMORY_SUGGEST_DISABLED
   "自动建议新记忆已关闭。"
   :MEMORY_SUGGEST_MODEL_FAILED
   "模型调用失败，本次未生成候选。"
   :MEMORY_SUGGEST_TIMEOUT
   "模型响应超时，本次未生成候选。"
   :MEMORY_SUGGEST_CANCELLED
   "整理已取消。"
   :MEMORY_SUGGEST_NO_MESSAGES
   "当前会话没有可整理的对话消息。"
   :MEMORY_SUGGEST_RANGE_EMPTY
   "所选范围内没有可整理的对话消息。"
   :MEMORY_TEMPORARY_SESSION
   "临时会话不读也不写长期记忆。"})

(defn error-label
  "User-facing text for a client validation string or a server error map."
  [error]
  (cond
    (string? error) error
    (map? error) (or (get error-labels (:code error))
                     (:message error)
                     "操作未完成。")
    (keyword? error) (get error-labels error (name error))
    :else "操作未完成。"))

;; ---------------------------------------------------------------------------
;; Field access and small helpers

(defn field
  "Read a field by keyword or its string form."
  [m k]
  (when (map? m)
    (if (contains? m k)
      (get m k)
      (get m (if (keyword? k) (subs (str k) 1) (keyword k))))))

(defn non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn unicode-length
  "Count Unicode code points, matching the server's user-visible limit."
  [value]
  (if (string? value)
    #?(:clj (.codePointCount ^String value 0 (.length ^String value))
       :cljs (.-length (js/Array.from value)))
    0))

(defn- trimmed [value]
  (when (string? value) (str/trim value)))

(defn- now-string []
  #?(:clj (str (java.time.Instant/now))
     :cljs (str (js/Date.))))

(defn- int-value
  "Parse a non-negative integer from a form value.  Returns nil when blank or
  invalid so the caller can show a field error instead of guessing."
  [value]
  (cond
    (and (integer? value) (not (neg? value))) value
    (and (string? value) (not (str/blank? value)))
    (try
      (let [text (str/trim value)]
        (when (re-matches #"\d+" text)
          (let [n #?(:clj (Long/parseLong text)
                     :cljs (js/parseInt text 10))]
            (when (and (integer? n) (not (neg? n))) n))))
      (catch #?(:clj Exception :cljs :default) _ nil))
    :else nil))

(defn- keyword-value
  [value]
  (cond
    (keyword? value) value
    (non-blank-string? value) (keyword (str/replace (str/trim value) #"^:" ""))
    :else nil))

(defn format-label
  "Chinese badge label for an instruction-card output format.

  Accepts keywords, strings and `:name` output.  Unknown values fall back
  to `:name`-style text so an unrecognized server format is still shown
  instead of dropped."
  [format]
  (cond
    (nil? format) "文本"
    (contains? instruction-output-formats
                (keyword-value format))
    (get instruction-output-formats (keyword-value format))
    :else (name (keyword-value format))))

(defn- read-edn [text]
  (try
    (#?(:clj edn/read-string :cljs reader/read-string) text)
    (catch #?(:clj Exception :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; Snapshot projection

(def snapshot-keys
  [:materials/ready? :materials/error :materials/revision :materials/settings
   :materials/session :memory/by-id :memory/order :memory/counts
   :worldbook/by-id :worldbook-entry/by-id :worldbook-session
   :instruction-card/by-id :instruction-session :instruction-turn
   :assistants])

(defn snapshot-from-db
  "Project the Fulcro database's server-owned materials keys into one
  snapshot map.  Missing keys stay missing; this function does not invent a
  ready library."
  [db]
  (into {}
        (keep (fn [k]
                (let [v (field db k)]
                  (when (some? v) [k v]))))
        snapshot-keys))

(defn- count-by-status
  [memories]
  (reduce (fn [counts memory]
            (update counts (:status memory) (fnil inc 0)))
          {:candidate 0 :active 0 :archived 0 :rejected 0 :deleted 0}
          (vals memories)))

(defn- memory-order
  [memories order]
  (let [ids (set (keys memories))
        listed (->> (or order [])
                    (filter #(contains? ids %))
                    distinct
                    vec)
        listed-set (set listed)
        remaining (->> (vals memories)
                       (remove #(contains? listed-set (:id %)))
                       (sort-by (fn [memory]
                                  [(- (long (or (:updated-seq memory) 0)))
                                   (str (:id memory))]))
                       (map :id)
                       vec)]
    (into listed remaining)))

(defn- keep-selected
  [id entities]
  (when (and (non-blank-string? id) (contains? entities id)) id))

(defn- first-book-id [books]
  (->> (vals books)
       (sort-by (fn [book] [(str/lower-case (str (:name book))) (str (:id book))]))
       first
       :id))

(defn- valid-card-ids
  [cards selection]
  (let [ids (vec (or (:card-ids selection) []))]
    (vec (filter #(contains? cards %) ids))))

(defn- placeholder-keys
  [cards]
  (->> cards
       (vals)
       (mapcat (fn [card] (keep :key (:placeholders card))))
       set))

(declare memory-draft book-draft entry-draft card-draft format-placeholders)

(defn apply-snapshot
  "Replace server-derived fields in STATE with SNAPSHOT.

  Local drafts, selections, commands and transport flags survive.  A snapshot
  whose materials revision is older than the current one is ignored so a
  delayed publisher cannot undo a newer command snapshot."
  [state snapshot]
  (let [ready? (true? (field snapshot :materials/ready?))
        revision (field snapshot :materials/revision)
        current-revision (:revision state)
        snapshot-session (field snapshot :materials/session)
        snapshot-session-id (get-in snapshot-session [:session-id])
        selected-session-id (:selected-session-id state)
        cross-session? (and (non-blank-string? selected-session-id)
                            (non-blank-string? snapshot-session-id)
                            (not= selected-session-id snapshot-session-id))
        stale? (and ready?
                    (integer? revision)
                    (integer? current-revision)
                    (< revision current-revision))]
    (if (or stale? cross-session?)
      state
      (let [memories (or (field snapshot :memory/by-id) {})
            books (or (field snapshot :worldbook/by-id) {})
            entries (or (field snapshot :worldbook-entry/by-id) {})
            cards (or (field snapshot :instruction-card/by-id) {})
           ;; The card table is authoritative only when the snapshot carries
           ;; it.  A partial or failed snapshot that omits the key must not
           ;; erase the session selection or the staged per-turn cards; the
           ;; next complete snapshot filters them again.
           cards-present? (some? (field snapshot :instruction-card/by-id))
            selection (or (field snapshot :instruction-session)
                          (when-not cards-present?
                            (:instruction-session state))
                          {})
            selected-book (or (keep-selected (:selected-book-id state) books)
                              (first-book-id books))
            memory-draft (if (and (= :edit (get-in state [:memory-draft :mode]))
                                  (not (contains? memories
                                                  (get-in state [:memory-draft
                                                                 :memory-id]))))
                           (memory-draft)
                           (:memory-draft state))
            book-draft (if (and (= :edit (get-in state [:book-draft :mode]))
                                (not (contains? books
                                                (get-in state [:book-draft :book-id]))))
                         (book-draft)
                         (:book-draft state))
            entry-draft (if (and (= :edit (get-in state [:entry-draft :mode]))
                                 (not (contains? entries
                                                 (get-in state [:entry-draft
                                                                :entry-id]))))
                          (entry-draft {:book-id selected-book})
                          (let [draft-book (get-in state [:entry-draft :book-id])]
                            (assoc (:entry-draft state)
                                   :book-id (if (contains? books draft-book)
                                              draft-book
                                              selected-book))))
            card-draft (if (and (= :edit (get-in state [:card-draft :mode]))
                                (not (contains? cards
                                                (get-in state [:card-draft :card-id]))))
                         (card-draft)
                         (let [selected-card-id
                               (keep-selected (:selected-card-id state)
                                              cards)]
                           (if selected-card-id
                             (let [card (get cards selected-card-id)]
                               (card-draft
                                {:mode :edit
                                 :card-id selected-card-id
                                 :name (:name card)
                                 :description (:description card)
                                 :body (:body card)
                                 :placeholders-text
                                 (format-placeholders (:placeholders card))
                                 :output-format
                                 (some-> (:output-format card) name)
                                 :enabled? (:enabled? card)}))
                             (:card-draft state))))
            state (-> state
                      (assoc :ready? ready?
                             :selected-session-id
                             (or selected-session-id snapshot-session-id)
                             :revision (when (integer? revision) revision)
                             :error (field snapshot :materials/error)
                             :settings (or (field snapshot :materials/settings)
                                           {:memory-read? false
                                            :memory-suggest? false})
                             :session (field snapshot :materials/session)
                             :memories memories
                             :memory-order (memory-order
                                            memories
                                            (field snapshot :memory/order))
                             :memory-counts (or (field snapshot :memory/counts)
                                                (count-by-status memories))
                             :books books
                             :entries entries
                             ;; A partial snapshot without the card table must
                             ;; not erase the locally known cards; the staged
                             ;; turn selection filters against them.
                             :cards (if cards-present? cards (:cards state))
                             :assistants (let [value (field snapshot
                                                            :assistants)]
                                           (if (seq value)
                                             value
                                             (:assistants state)))
                             :instruction-session
                             (assoc selection
                                    :card-ids (if cards-present?
                                                 (valid-card-ids cards selection)
                                                 (:card-ids selection)))
                             ;; A partial snapshot may omit the per-turn
                             ;; selection; keep the local value unless the
                             ;; snapshot carries the key explicitly (an
                             ;; explicit nil still clears it).
                             :instruction-turn
                             (let [present? (or (contains? snapshot :instruction-turn)
                                                (contains? snapshot "instruction-turn"))]
                               (if present?
                                 (field snapshot :instruction-turn)
                                 (:instruction-turn state)))
                             :instruction-mode (or (:instruction-mode state) :turn)
                             :temporary-read?
                             (and (true? (:temporary? snapshot-session))
                                  (or (true? (:temporary-read? state))
                                      (true? (:temporary-read? snapshot-session))))
                             :turn-card-ids
                             (if cards-present?
                               (vec (filter #(contains? cards %)
                                            (:turn-card-ids state)))
                               (:turn-card-ids state))
                             :instruction-values
                             (if cards-present?
                               (select-keys (:instruction-values state)
                                            (placeholder-keys cards))
                               (:instruction-values state))
                             :worldbook-session (field snapshot :worldbook-session)
                             :memory-draft memory-draft
                             :book-draft book-draft
                             :entry-draft entry-draft
                             :card-draft card-draft
                             :selected-memory-id
                             (keep-selected (:selected-memory-id state)
                                            memories)
                             :selected-book-id selected-book
                             :selected-card-id
                             (keep-selected (:selected-card-id state) cards)))]
        state))))

;; ---------------------------------------------------------------------------
;; Initial state and local actions

(defn memory-draft
  ([] (memory-draft {}))
  ([{:keys [mode memory-id body category scope-kind assistant-id project-id
            epistemic always?]}]
   {:mode (or mode :create)
    :nonce (str (random-uuid))
    :memory-id memory-id
    :body (or body "")
    :category (or category :identity-background)
    :scope-kind (or scope-kind :global)
    :assistant-id (or assistant-id "")
    :project-id (or project-id "")
    :epistemic (or epistemic :fact)
    :always? (boolean always?)
    :reject-reason ""
    :confirm-delete? false
    :error nil}))

(defn book-draft
  ([] (book-draft {}))
  ([{:keys [mode book-id name description enabled? assistant-id]}]
   {:mode (or mode :create)
    :nonce (str (random-uuid))
    :book-id book-id
    :name (or name "")
    :description (or description "")
    :enabled? (if (nil? enabled?) true (boolean enabled?))
    :assistant-id (or assistant-id "")
    :confirm-delete? false
    :error nil}))

(defn entry-draft
  ([] (entry-draft {}))
  ([{:keys [mode entry-id book-id title body enabled? trigger-kind keywords
            match expression sticky cooldown delay order key? position-kind
            position-depth move-book-id]}]
   {:mode (or mode :create)
    :nonce (str (random-uuid))
    :entry-id entry-id
    :book-id book-id
    :title (or title "")
    :body (or body "")
    :enabled? (if (nil? enabled?) true (boolean enabled?))
    :trigger-kind (or trigger-kind :keyword)
    :keywords (or keywords "")
    :match (or match :any)
    :expression (or expression "[:contains \"\"]")
    :sticky (or sticky 0)
    :cooldown (or cooldown 0)
    :delay (or delay 0)
    :order (or order "")
    :key? (boolean key?)
    :position-kind (or position-kind :base-background)
    :position-depth (or position-depth 0)
    :move-book-id (or move-book-id "")
    :confirm-delete? false
    :error nil}))

(defn card-draft
  ([] (card-draft {}))
  ([{:keys [mode card-id name description body placeholders-text output-format
            enabled?]}]
   {:mode (or mode :create)
    :nonce (str (random-uuid))
    :card-id card-id
    :name (or name "")
    :description (or description "")
    :body (or body "")
    :placeholders-text (or placeholders-text "")
    :output-format (or output-format "")
    :enabled? (if (nil? enabled?) true (boolean enabled?))
    :confirm-delete? false
    :error nil}))

(defn initial-state
  []
  {:ready? false
   :revision nil
   :error nil
   :settings {:memory-read? false :memory-suggest? false}
   :session nil
   :selected-session-id nil
   :memories {}
   :memory-order []
   :memory-counts {:candidate 0 :active 0 :archived 0 :rejected 0
                   :deleted 0}
   :books {}
   :entries {}
   :cards {}
   :instruction-session {:card-ids []}
   :instruction-mode :turn
   :temporary-read? false
   :turn-card-ids []
   :instruction-values {}
   :turn-selection-version 0
   :instruction-turn nil
   :worldbook-session nil
   :selected-memory-id nil
   :selected-book-id nil
   :selected-card-id nil
   :memory-filter :all
   :memory-query ""
   :memory-draft (memory-draft)
   :book-draft (book-draft)
   :entry-draft (entry-draft)
   :card-draft (card-draft)
   :commands {}
   :outbox []
   :orphan-receipts []
   :transport-available? false
   :sync {:phase :disconnected :command-epoch nil}
   :last-error nil})

;; ---------------------------------------------------------------------------
;; Labels and projections

(defn category-label [category]
  (get memory-category-labels category (name (or category :unknown))))

(defn status-label [status]
  (get memory-status-labels status (name (or status :unknown))))

(defn epistemic-label [epistemic]
  (get memory-epistemic-labels epistemic (name (or epistemic :unknown))))

(defn scope-label
  [scope]
  (when (map? scope)
    (case (:kind scope)
      :global "全局"
      :assistant (str "助手 " (:assistant-id scope))
      :project (str "项目 " (:project-id scope))
      "范围未知")))

(defn scope-kind-label
  [kind]
  (get scope-kind-labels kind (name (or kind :unknown))))

(defn trigger-kind-label
  [kind]
  (get trigger-kind-labels kind (name (or kind :unknown))))

(defn match-kind-label
  [kind]
  (get match-kind-labels kind (name (or kind :unknown))))

(defn position-kind-label
  [kind]
  (get position-kind-labels kind (name (or kind :unknown))))

(defn- memory-status-rank [status]
  (case status
    :candidate 0
    :active 1
    :archived 2
    :rejected 3
    4))

(defn memory-items
  "Project visible memories for the current filter and local query.  The
  query is a local filter over the confirmed snapshot; it is not a server
  search claim."
  [state]
  (let [query (str/lower-case (str (:memory-query state)))
        status-filter (:memory-filter state)
        matches? (fn [memory]
                   (let [text (str/lower-case
                               (str (or (:body memory) "") " "
                                    (category-label (:category memory)) " "
                                    (scope-label (:scope memory)) " "
                                    (epistemic-label (:epistemic memory)) " "
                                    (get-in memory [:source :excerpt] "")))]
                     (or (str/blank? query) (str/includes? text query))))]
    (->> (:memories state)
         (vals)
         (filter (fn [memory]
                   (and (or (= status-filter :all)
                            (= status-filter (:status memory)))
                        (matches? memory))))
         (sort-by (fn [memory]
                    [(memory-status-rank (:status memory))
                     (- (long (or (:updated-seq memory) 0)))
                     (str (:id memory))]))
         vec)))

(defn memory
  [state memory-id]
  (get-in state [:memories memory-id]))

(defn selected-memory
  [state]
  (memory state (:selected-memory-id state)))

(defn book-items
  [state]
  (let [entry-counts (reduce (fn [acc entry]
                               (update acc (:book-id entry) (fnil inc 0)))
                             {}
                             (vals (:entries state)))]
    (->> (:books state)
         (vals)
         (map (fn [book]
                (assoc book :entry-count (get entry-counts (:id book) 0))))
         (sort-by (fn [book] [(str/lower-case (str (:name book)))
                              (str (:id book))]))
         vec)))

(defn selected-book
  [state]
  (get-in state [:books (:selected-book-id state)]))

(defn- assistant-record
  [assistant fallback-id]
  (let [id (or (when (map? assistant)
                 (let [value (or (:id assistant) (:assistant-id assistant))]
                   (when (non-blank-string? value) value)))
               (when (non-blank-string? fallback-id) fallback-id))
        label (or (when (map? assistant)
                    (some (fn [key]
                            (let [value (get assistant key)]
                              (when (non-blank-string? value) value)))
                          [:name :display-name :label :title]))
                  (when (and (string? assistant)
                             (non-blank-string? assistant))
                    assistant)
                  id)]
    (when id {:id id :label label})))

(defn- assistant-pairs
  [assistants]
  (cond
    (and (map? assistants)
         (sequential? (:items assistants)))
    (keep #(assistant-record % nil) (:items assistants))

    (map? assistants)
    (keep (fn [[id assistant]] (assistant-record assistant id)) assistants)

    (or (sequential? assistants) (set? assistants))
    (keep #(assistant-record % nil) assistants)

    :else []))

(defn assistant-options
  "Return selectable assistant identities for materials forms.

  Sources, in order: an explicit `:assistant-options` projection, any
  `:assistants` published by the server, the current session's
  `:assistant-id`, other `:session/by-id` rows, and the selected worldbook's
  bound assistant ids.  The current session's assistant is first.  Labels add
  the stable id when a display name differs, so two assistants with the same
  name stay distinguishable."
  [state]
  (let [session (:session state)
        sessions (:session/by-id state)
        selected-session-id (or (:selected-session-id state)
                                (:session-id session))
        selected-row (get sessions selected-session-id)
        current-id (or (:assistant-id session)
                       (:assistant-id selected-row))
        session-ids (->> (vals sessions)
                         (keep :assistant-id)
                         (filter non-blank-string?))
        book (selected-book state)
        bound-ids (->> (:assistant-ids book)
                       (filter non-blank-string?)
                       sort)
        records (concat (assistant-pairs (:assistant-options state))
                        (assistant-pairs (:assistants state))
                        (keep (fn [id]
                                (when (non-blank-string? id)
                                  {:id id :label id}))
                              (concat [current-id] session-ids bound-ids)))
        by-id (reduce (fn [acc {:keys [id] :as record}]
                        (if (contains? acc id) acc (assoc acc id record)))
                      {}
                      records)
        ids (distinct (concat (when (non-blank-string? current-id)
                                [current-id])
                              session-ids
                              bound-ids
                              (keys by-id)))]
    (->> ids
         (keep by-id)
         (mapv (fn [{:keys [id label]}]
                 (let [suffix (str "（" id "）")]
                   {:id id
                    :label (if (and (non-blank-string? label)
                                    (not= label id)
                                    (not (str/ends-with? label suffix)))
                             (str label suffix)
                             (or label id))
                    :current? (= id current-id)}))))))

(defn selected-book-assistant-id
  "The assistant id the book bind and unbind controls act on.

  A draft selection wins.  Otherwise the first assistant already bound to the
  selected book wins so unbind stays useful, then the first selectable
  assistant (the current session's assistant is first)."
  [state]
  (let [draft-id (trimmed (get-in state [:book-draft :assistant-id]))
        book (selected-book state)
        bound-ids (->> (:assistant-ids book)
                       (filter non-blank-string?)
                       sort
                       vec)
        options (assistant-options state)
        option-ids (set (map :id options))
        draft-valid? (and (non-blank-string? draft-id)
                          (or (contains? option-ids draft-id)
                              (some #{draft-id} bound-ids)))]
    (or (when draft-valid? draft-id)
        (first bound-ids)
        (:id (first options))
        "")))

(defn entry-items
  [state book-id]
  (->> (:entries state)
       (vals)
       (filter #(= book-id (:book-id %)))
       (sort-by (fn [entry] [(or (:order entry) 0) (:seq entry) (str (:id entry))]))
       vec))

(defn entry
  [state entry-id]
  (get-in state [:entries entry-id]))

(defn card-items
  [state]
  (->> (:cards state)
       (vals)
       (sort-by (fn [card] [(str/lower-case (str (:name card)))
                            (str (:id card))]))
       vec))

(defn card
  [state card-id]
  (get-in state [:cards card-id]))

(defn session-card-ids
  [state]
  (vec (get-in state [:instruction-session :card-ids])))

(defn effective-session-id
  "The client-selected session owns materials commands, not the server's
  last active session in a possibly stale snapshot."
  [state]
  (or (:selected-session-id state)
      (get-in state [:session :session-id])))

(defn begin-session
  "Drop server-derived materials state before loading a different session.

  Commands, transport state and local drafts are preserved; the next
  authoritative snapshot fills the new session's tables."
  [state session-id]
  (assoc state
         :selected-session-id session-id
         :session (when session-id {:session-id session-id})
         :ready? false
         :revision nil
         :error nil
         :memories {}
         :memory-order []
         :memory-counts {:candidate 0 :active 0 :archived 0 :rejected 0
                         :deleted 0}
         :books {}
         :entries {}
         :cards {}
         :instruction-session {:card-ids []}
         :instruction-turn nil
         :worldbook-session nil
         :selected-memory-id nil
         :selected-book-id nil
         :selected-card-id nil
         :instruction-mode :turn
         :temporary-read? false
         ;; Per-turn card staging is local "next submission" state, not
         ;; session server state: a focus switch must not discard cards the
         ;; user just picked on the materials page.  The next snapshot still
         ;; filters them against the new session's card table.
         :turn-card-ids (:turn-card-ids state)
         :instruction-values (:instruction-values state)
         ;; Keep the version monotonic across session switches so a late
         ;; receipt from the previous session cannot match a fresh selection.
         :turn-selection-version (inc (long (or (:turn-selection-version state) 0)))
         :memory-draft (memory-draft)
         :book-draft (book-draft)
         :entry-draft (entry-draft)
         :card-draft (card-draft)))

(defn turn-card-ids
  [state]
  (vec (filter #(contains? (:cards state) %) (:turn-card-ids state))))

(defn effective-card-ids
  "The cards that will actually apply to the next user submission."
  [state]
  (if (= :session (:instruction-mode state))
    (session-card-ids state)
    (turn-card-ids state)))

(defn effective-cards
  [state]
  (->> (effective-card-ids state)
       (mapv #(card state %))
       (filterv :enabled?)))

(defn selected-cards
  "Backwards-compatible name for the effective selection."
  [state]
  (effective-cards state))

(defn- selected-placeholder-keys
  [state]
  (->> (effective-cards state)
       (mapcat (fn [card] (keep :key (:placeholders card))))
       set))

(defn instruction-values
  "Placeholder values that belong to the effective card selection."
  [state]
  (select-keys (:instruction-values state)
               (selected-placeholder-keys state)))

(defn turn-selection
  "The explicit per-turn selection to attach to the next user submission.
  Returns nil in session mode or when no turn card is staged."
  [state]
  (when (= :turn (:instruction-mode state))
    (let [ids (turn-card-ids state)]
      (when (seq ids)
        {:turn-card-ids ids
         :instruction-values (instruction-values state)
         :turn-selection-version (:turn-selection-version state)}))))

(defn temporary-session?
  [state]
  (true? (get-in state [:session :temporary?])))

(defn temporary-read-scope
  "The session scope a one-shot temporary read applies to."
  [state]
  (when (temporary-session? state)
    (select-keys (:session state) [:session-id :assistant-id :project-id])))

(defn attach-turn-selection
  "Attach the staged per-turn cards, values, and one-shot temporary read
  permission to a draft snapshot."
  [state snapshot]
  (let [snapshot (if (and (temporary-session? state)
                          (true? (:temporary-read? state)))
                   (assoc (or snapshot {}) :temporary-read? true)
                   snapshot)]
    (if-let [selection (turn-selection state)]
      (merge snapshot selection)
      snapshot)))

(defn turn-selection-matches?
  "True when STATE still carries exactly the submitted turn selection.

  A submitted selection with a version matches only the same version, so any
  later local edit (card toggle, value edit, clear) is preserved even if the
  ids happen to return to the same set.  A legacy submission without a
  version falls back to comparing card ids and instruction values."
  [state submitted]
  (let [submitted (or submitted {})
        submitted-version (:turn-selection-version submitted)]
    (if (some? submitted-version)
      (= submitted-version (:turn-selection-version state))
      (and (= (vec (:turn-card-ids submitted))
              (vec (:turn-card-ids state)))
           (= (or (:instruction-values submitted) {})
              (or (:instruction-values state) {}))))))

(defn clear-turn-selection
  "Drop the local per-turn staging after an accepted send.

  The version increments even when the selection is already empty, so a
  duplicate or late receipt for an older send cannot clear a newer selection."
  [state]
  (-> state
      (assoc :turn-card-ids [] :instruction-values {}
             :temporary-read? false)
      (update :turn-selection-version (fnil inc 0))))

(defn missing-required-placeholders
  [state]
  (->> (effective-cards state)
       (mapcat (fn [card]
                 (for [placeholder (:placeholders card)
                       :when (:required? placeholder)
                       :let [key (:key placeholder)
                             value (get (:instruction-values state) key)]
                       :when (or (nil? value) (str/blank? (str value)))]
                   {:card-id (:id card)
                    :card-name (:name card)
                    :key key})))
       vec))

(defn selection-format-conflict
  "Return the distinct output formats when selected enabled cards disagree,
  otherwise nil.  The server refuses this at context time; the UI warns
  before the user depends on the selection."
  [state]
  (let [formats (->> (selected-cards state)
                     (keep :output-format)
                     distinct
                     vec)]
    (when (> (count formats) 1) formats)))

(defn required-placeholder-keys
  [card]
  (->> (:placeholders card)
       (filter :required?)
       (map :key)
       vec))

(defn trigger-summary
  [entry]
  (let [trigger (:trigger entry)]
    (case (:kind trigger)
      :keyword (str "关键词（" (get match-kind-labels (:match trigger)
                                    (name (or (:match trigger) :any)))
                    "）：" (str/join " / " (:keywords trigger)))
      :constant "始终提供"
      :expression (str "表达式：" (pr-str (:expression trigger)))
      "触发条件未知")))

(defn worldbook-runtime-rows
  "Report exactly the per-entry runtime counters in the snapshot.  Missing
  entries are not invented."
  [state]
  (let [runtime (get-in state [:worldbook-session :runtime] {})]
    (->> runtime
         (keep (fn [[entry-id value]]
                 (when-let [entry (get-in state [:entries entry-id])]
                   {:entry-id entry-id
                    :title (:title entry)
                    :last-trigger-turn (:last-trigger-turn value)
                    :sticky-until (:sticky-until value)
                    :cooldown-until (:cooldown-until value)})))
         (sort-by (comp str :title))
         vec)))

(defn- omitted-reason-key
  [reason]
  (cond
    (keyword? reason) reason
    (string? reason) (keyword (str/lower-case reason))
    :else nil))

(defn omitted-reason-label
  [reason]
  (let [k (omitted-reason-key reason)]
    (or (get omitted-reason-labels k)
        (when k (str "未提供（" (name k) "）"))
        "原因未报告")))

(defn worldbook-omitted-rows
  "Bounded \"hit but not provided\" rows from the latest turn.

  The snapshot may carry them under `:last-turn :result :omitted`,
  `:last-turn :omitted`, or directly under `:worldbook-session :omitted`.
  A missing key is different from an empty list."
  [state]
  (let [session (:worldbook-session state)
        last-turn (:last-turn session)
        present? (or (contains? session :last-turn)
                     (contains? session :omitted))
        raw (or (:omitted last-turn)
                (get-in last-turn [:result :omitted])
                (:omitted session))
        raw (if (map? raw) (vals raw) raw)
        rows (->> (take 50 (or raw []))
                  (mapv (fn [item]
                          (let [entry-id (or (:entry-id item) (:id item))
                                entry (get-in state [:entries entry-id])]
                            {:entry-id entry-id
                             :title (or (:title item) (:title entry)
                                        (str entry-id))
                             :reason (omitted-reason-key (:reason item))
                             :reason-label (omitted-reason-label (:reason item))
                             :chars (when (number? (:chars item)) (:chars item))}))))]
    {:reported? present?
     :rows rows
     :count (count rows)}))

;; ---------------------------------------------------------------------------
;; Form parsing and validation

(defn- parse-counter
  [value label]
  (cond
    (or (nil? value) (and (string? value) (str/blank? value))) 0
    (some? (int-value value)) (int-value value)
    :else (str label " 必须是非负整数。")))

(defn- expression-error
  [expr]
  (letfn [(walk [node depth nodes]
            (let [nodes (inc nodes)]
              (cond
                (> nodes 128) "表达式节点过多。"
                (> depth 16) "表达式嵌套过深。"
                (not (vector? node)) "表达式节点必须是向量。"
                (empty? node) "表达式节点不能为空。"
                :else
                (case (first node)
                  (:contains :starts-with :ends-with)
                  (if (and (= 2 (count node))
                           (non-blank-string? (second node)))
                    nil
                    "文本表达式需要一个非空字符串。")
                  :not
                  (if (= 2 (count node))
                    (walk (second node) (inc depth) nodes)
                    ":not 需要一个子表达式。")
                  (:all :any)
                  (if (and (= 2 (count node))
                           (vector? (second node))
                           (seq (second node)))
                    (reduce (fn [error child]
                              (or error (walk child (inc depth) nodes)))
                            nil
                            (second node))
                    (str (name (first node)) " 需要一个非空子表达式向量。"))
                  "未知表达式运算符。"))))]
    (walk expr 0 0)))

(defn parse-keywords
  [text]
  (->> (str/split (or text "") #"[\n,，]+")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn parse-placeholders
  "Parse one placeholder per line:

    key
    key | label
    key | label | required

  The third field is required when it is `true`, `yes`, `1` or `!`."
  [text]
  (->> (str/split (or text "") #"\n")
       (map str/trim)
       (remove str/blank?)
       (mapv (fn [line]
               (let [[key label required] (map str/trim (str/split line #"\|"))]
                 (cond-> {:key key}
                   (not (str/blank? (str label))) (assoc :label label)
                   (contains? #{"true" "yes" "1" "!" "required"}
                              (str/lower-case (str required)))
                   (assoc :required? true)))))))

(defn format-placeholders
  [placeholders]
  (->> placeholders
       (map (fn [{:keys [key label required?]}]
              (str key
                   (when-not (str/blank? (str label)) (str " | " label))
                   (when required? " | required"))))
       (str/join "\n")))

(defn- scope-from-draft
  [{:keys [scope-kind assistant-id project-id]}]
  (case scope-kind
    :assistant {:kind :assistant :assistant-id (trimmed assistant-id)}
    :project {:kind :project :project-id (trimmed project-id)}
    {:kind :global}))

(defn validate-memory-draft
  [draft]
  (let [body (trimmed (:body draft))
        scope (scope-from-draft draft)
        scope-error (case (:scope-kind draft)
                      :assistant (when-not (non-blank-string? (:assistant-id scope))
                                   "助手范围需要助手 ID。")
                      :project (when-not (non-blank-string? (:project-id scope))
                                 "项目范围需要项目 ID。")
                      nil)]
    (cond
      (not (non-blank-string? body))
      {:ok? false :error "记忆正文不能为空。"}

      (> (unicode-length body) memory-max-chars)
      {:ok? false :error (str "记忆正文超过 " memory-max-chars " 字。")}

      (not (contains? (set memory-categories) (:category draft)))
      {:ok? false :error "记忆类别无效。"}

      (not (contains? #{:fact :preference :requirement :assumption}
                      (:epistemic draft)))
      {:ok? false :error "认识标签无效。"}

      scope-error
      {:ok? false :error scope-error}

      :else
      {:ok? true
       :payload {:body body
                 :category (:category draft)
                 :scope scope
                 :epistemic (:epistemic draft)
                 :always? (boolean (:always? draft))}})))

(defn validate-book-draft
  [draft]
  (let [name (trimmed (:name draft))
        description (:description draft)]
    (cond
      (not (non-blank-string? name))
      {:ok? false :error "世界书名称不能为空。"}

      (> (unicode-length name) book-name-max-chars)
      {:ok? false :error (str "世界书名称超过 " book-name-max-chars " 字。")}

      (and (some? description)
           (> (unicode-length description) book-description-max-chars))
      {:ok? false :error (str "说明超过 " book-description-max-chars " 字。")}

      :else
      {:ok? true
       :payload {:name name
                 :description description
                 :enabled? (boolean (:enabled? draft))}})))

(defn validate-entry-draft
  [draft]
  (let [title (trimmed (:title draft))
        body (:body draft)
        sticky (parse-counter (:sticky draft) "持续次数")
        cooldown (parse-counter (:cooldown draft) "冷却次数")
        delay (parse-counter (:delay draft) "延迟次数")
        depth (parse-counter (:position-depth draft) "位置深度")
        order (if (and (string? (:order draft)) (str/blank? (:order draft)))
                nil
                (parse-counter (:order draft) "顺序"))
        trigger (case (:trigger-kind draft)
                  :constant {:kind :constant}
                  :keyword (let [keywords (parse-keywords (:keywords draft))]
                             (when (seq keywords)
                               {:kind :keyword
                                :keywords keywords
                                :match (:match draft)}))
                  :expression (let [expr (read-edn (:expression draft))]
                                (if (nil? expr)
                                  ::invalid-expression
                                  (if-let [error (expression-error expr)]
                                    {::expression-error error}
                                    {:kind :expression :expression expr})))
                  nil)
        trigger-error (cond
                        (= trigger ::invalid-expression)
                        "表达式无法读取。"
                        (and (map? trigger) (::expression-error trigger))
                        (::expression-error trigger)
                        (nil? trigger)
                        "关键词触发至少需要一个关键词。")]
    (cond
      (not (non-blank-string? title))
      {:ok? false :error "条目标题不能为空。"}

      (> (unicode-length title) entry-title-max-chars)
      {:ok? false :error (str "条目标题超过 " entry-title-max-chars " 字。")}

      (not (non-blank-string? body))
      {:ok? false :error "条目正文不能为空。"}

      trigger-error
      {:ok? false :error trigger-error}

      (string? sticky) {:ok? false :error sticky}
      (string? cooldown) {:ok? false :error cooldown}
      (string? delay) {:ok? false :error delay}
      (string? depth) {:ok? false :error depth}
      (string? order) {:ok? false :error order}

      (not (contains? #{:base-background :before-current :between-recent}
                      (:position-kind draft)))
      {:ok? false :error "条目位置无效。"}

      :else
      {:ok? true
       :payload (cond-> {:title title
                         :body body
                         :enabled? (boolean (:enabled? draft))
                         :trigger trigger
                         :key? (boolean (:key? draft))
                         :position {:kind (:position-kind draft)
                                    :depth depth}
                         :sticky sticky
                         :cooldown cooldown
                         :delay delay}
                  (some? order) (assoc :order order))})))

(defn validate-card-draft
  [draft]
  (let [card-name (trimmed (:name draft))
        description (:description draft)
        body (:body draft)
        placeholders (parse-placeholders (:placeholders-text draft))
        keys (map :key placeholders)
        output-format (keyword-value (:output-format draft))]
    (cond
      (not (non-blank-string? card-name))
      {:ok? false :error "指令卡名称不能为空。"}

      (> (unicode-length card-name) card-name-max-chars)
      {:ok? false :error (str "指令卡名称超过 " card-name-max-chars " 字。")}

      (and (some? description)
           (> (unicode-length description) card-description-max-chars))
      {:ok? false :error (str "说明超过 " card-description-max-chars " 字。")}

      (not (non-blank-string? body))
      {:ok? false :error "指令卡正文不能为空。"}

      (> (unicode-length body) card-body-max-chars)
      {:ok? false :error (str "指令卡正文超过 " card-body-max-chars " 字。")}

      (some #(not (non-blank-string? (:key %))) placeholders)
      {:ok? false :error "每个占位符都需要一个非空 key。"}

      (not= (count keys) (count (set keys)))
      {:ok? false :error "占位符 key 必须唯一。"}

      (and output-format
           (not (contains? instruction-output-keys output-format)))
      {:ok? false
       :error (str "输出格式只能是："
                   (str/join "、" (map name (sort instruction-output-keys)))
                   "。")}

      :else
      {:ok? true
       :payload {:name card-name
                 :description description
                 :body body
                 :placeholders placeholders
                 :output-format output-format
                 :enabled? (boolean (:enabled? draft))}})))

;; ---------------------------------------------------------------------------
;; Command queue

(defn command-allowed?
  [state]
  (and (= :synced (get-in state [:sync :phase]))
       (integer? (get-in state [:sync :command-epoch]))))

(defn- command-pending?
  [state key]
  (boolean
   (some (fn [[_ command]]
           (when (and (= key (:key command))
                      (contains? pending-statuses (:status command)))
             command))
         (:commands state))))

(defn command-pending?
  "True when a command with `key` is still queued or submitting.  The view
  uses this to disable selection toggles so a pending write cannot be
  raced by a second toggle."
  [state key]
  (boolean
   (some (fn [[_ command]]
           (when (and (= key (:key command))
                      (contains? pending-statuses (:status command)))
             command))
         (:commands state))))

(defn- command-pending-kind?
  [state kind]
  (boolean
   (some (fn [[_ command]]
           (when (and (= kind (:kind command))
                      (contains? pending-statuses (:status command)))
             command))
         (:commands state))))

(defn instruction-selection-pending?
  "True while any instruction-card selection command is pending: session
  set/clear or turn clear.  The view disables the mode switch and both
  checklists while a selection write is unconfirmed."
  [state]
  (or (command-pending-kind? state instruction-session-set-kind)
      (command-pending-kind? state instruction-session-clear-kind)))

(defn- queue-command*
  [state kind payload key opts]
  (let [session-id (effective-session-id state)
        payload (cond-> payload
                  (and (string? session-id)
                       (not (str/blank? session-id)))
                  (assoc :session-id session-id))
        ;; A fresh id per action is what makes its receipt attributable.
        ;; Callers must not reuse one across two operator actions.
        command-id (or (:command-id opts) (str (random-uuid)))
        epoch (get-in state [:sync :command-epoch])
        command {:command-id command-id
                 :command-epoch epoch
                 :kind kind
                 :key key
                 :payload payload
                 :status :queued
                 :queued-at (now-string)
                 :draft (:draft opts)}]
    {:ok? true
     :state (-> state
                (assoc-in [:commands command-id] command)
                (update :outbox (fnil conj [])
                        {:command-id command-id
                         :command-epoch epoch
                         :payload (assoc payload :kind kind)}))
     :command command}))

(defn queue-command
  "Queue one command.  A second command for the same logical key is refused
  while the first is pending, so a double click cannot duplicate a write."
  ([state kind payload] (queue-command state kind payload {}))
  ([state kind payload opts]
   (let [key (or (:key opts) [kind])]
     (cond
       (not (command-allowed? state))
       {:ok? false
        :state state
        :error "连接尚未完成同步；命令未发送。"}

       (and (not= :materials/snapshot kind)
            (command-pending? state key))
       {:ok? false
        :state state
        :error "同一目标已有待确认命令；请等待回执。"}

       :else
       (queue-command* state kind payload key opts)))))

(defn pending-outbox
  [state]
  (vec (:outbox state)))

(defn mark-submitting
  [state command-id]
  (-> state
      (update-in [:commands command-id] assoc :status :submitting)
      (update :outbox
              (fn [outbox]
                (into [] (remove #(= command-id (:command-id %))) outbox)))))

(defn mark-send-failed
  [state command-id message]
  (-> state
      (update-in [:commands command-id]
                 assoc :status :failed :message message)
      (update :outbox
              (fn [outbox]
                (into [] (remove #(= command-id (:command-id %))) outbox)))
      (assoc :last-error {:message message})))

(defn unwrap-receipt
  [receipt]
  (cond
    (not (map? receipt)) receipt
    (map? (:hc/receipt receipt)) (:hc/receipt receipt)
    ;; The gateway wraps every domain receipt as
    ;; `{:ok? ... :command-id ... :receipt {...}}` — `:command-id` stays on
    ;; the outer envelope while `:status`, `:result` and `:error` live on the
    ;; inner receipt.  Returning the envelope here left `receipt-result`
    ;; reading `(:result outer)` = nil, so an accepted entry move never set
    ;; `:last-move` and its undo data was lost (D-28).  Merge the outer
    ;; identity fields under the inner receipt, matching the convention in
    ;; `hc.client.settings.commands/unwrap-receipt`.
    (map? (:receipt receipt))
    (let [outer (select-keys receipt [:ok? :error :replayed? :effects
                                      :status :command-id :command-epoch])
          inner (:receipt receipt)
          merged (merge outer inner)]
      (cond-> merged
        (and (nil? (:command-id merged)) (:command-id receipt))
        (assoc :command-id (:command-id receipt))

        (and (nil? (:command-epoch merged)) (:command-epoch receipt))
        (assoc :command-epoch (:command-epoch receipt))))
    :else receipt))

(defn receipt-command-id
  [receipt]
  (let [receipt (unwrap-receipt receipt)]
    (or (:command-id receipt)
        (get-in receipt [:receipt :command-id]))))

(defn receipt-status
  [receipt]
  (let [receipt (unwrap-receipt receipt)]
    (cond
      (not (map? receipt)) :refused
      (false? (:ok? receipt)) :refused
      (some? (:error receipt)) :refused
      :else
      (let [status (or (get-in receipt [:receipt :status])
                       (:status receipt))
            status (cond
                     (keyword? status) status
                     (string? status) (keyword status)
                     :else status)]
        ;; `:submitted` is the gateway's transport acknowledgement, not a
        ;; domain outcome.  Returning it keeps `apply-receipt` from
        ;; releasing the pending lock before the real receipt arrives.
        (cond
          (= :submitted status) :submitted
          ;; `:not-found` is the receipt sweep's answer for a command the
          ;; durable gate never accepted: the send was lost in transport.
          ;; Treating it as refused releases the pending lock so the
          ;; operator can retry, and the reconcile snapshot resyncs state.
          (= :not-found status) :refused
          :else (or status :accepted))))))

(defn receipt-result
  [receipt]
  (let [receipt (unwrap-receipt receipt)]
    (or (get-in receipt [:receipt :result])
        (:result receipt))))

(defn receipt-error
  [receipt]
  (let [receipt (unwrap-receipt receipt)]
    (or (:error receipt)
        (get-in receipt [:receipt :error])
        (get-in receipt [:receipt :result :error]))))

(defn- accepted? [status]
  (contains? #{:accepted :applied :replayed} status))

(defn merge-local-state
  "Merge the local fields of NEXT onto CURRENT.

  `transact-state!` drops a whole projected state when its revision is older
  than the database's, which protects server-derived tables from a delayed
  snapshot.  A receipt applied between the database read and that write, or
  a local action queued on top of it, would lose its local fields with it —
  the `:last-move` an entry move needs for undo, command statuses, drafts
  and selections.  This merge keeps CURRENT's server-derived fields (tables,
  revision, session, settings) and takes NEXT's local fields, so a stale
  write cannot roll back the server projection but still lands its local
  delta.
  "
  [current next]
  (let [;; Fields that can be explicitly cleared (dissoc'ed or set to nil)
        ;; must merge on key presence, not value truthiness.
        local-keys [:commands :outbox :orphan-receipts :last-move
                    :last-error :memory-suggest-result
                    :memory-draft :book-draft :entry-draft :card-draft
                    :selected-memory-id :selected-book-id :selected-card-id
                    :instruction-mode :temporary-read?
                    :turn-card-ids :instruction-values
                    :turn-selection-version]
        state (reduce (fn [acc field]
                        (if (contains? next field)
                          (assoc acc field (get next field))
                          acc))
                      current local-keys)]
    (-> state
        ;; Commands merge per id with NEXT winning: a receipt's accepted
        ;; status must survive even when CURRENT's copy is newer in
        ;; wall-clock terms, while commands only CURRENT knows stay.  A
        ;; stale NEXT must never resurrect a pending status over a terminal
        ;; one: the receipt was already consumed, so nothing would clear
        ;; the lock again and every later command on the same key would be
        ;; refused forever.
        (assoc :commands
               (merge-with
                (fn [current-command next-command]
                  (if (and (map? current-command) (map? next-command)
                           (contains? pending-statuses
                                      (:status next-command))
                           (not (contains? pending-statuses
                                           (:status current-command))))
                    current-command
                    next-command))
                (:commands current)
                (:commands next)))
        ;; The outbox unions by command id: a stale write must not drop a
        ;; snapshot command CURRENT queued after NEXT was read, and NEXT's
        ;; own entries still land.
        (assoc :outbox
               (let [seen (set (map :command-id (:outbox next)))]
                 (into (vec (:outbox next))
                       (remove (fn [item]
                                 (contains? seen (:command-id item))))
                       (:outbox current))))
        ;; The version must stay monotonic: a stale write carrying an older
        ;; selection version must not rewind it below CURRENT's.
        (assoc :turn-selection-version
               (max (long (or (:turn-selection-version current) 0))
                    (long (or (:turn-selection-version next) 0)))))))

(defn- reset-draft-for-command
  [state command]
  (let [draft (:draft command)
        kind (:kind command)]
    (cond
      (contains? #{memory-remember-kind memory-replace-kind
                   memory-approve-kind} kind)
      (if (= draft (:memory-draft state))
        (assoc state :memory-draft (memory-draft))
        state)

      (contains? #{worldbook-create-kind worldbook-update-kind} kind)
      (if (= draft (:book-draft state))
        (assoc state :book-draft (book-draft))
        state)

      (contains? #{entry-create-kind entry-update-kind
                   entry-move-kind entry-delete-kind} kind)
      (if (= draft (:entry-draft state))
        (assoc state :entry-draft
               (entry-draft {:book-id (:selected-book-id state)}))
        state)

      (contains? #{instruction-create-kind instruction-update-kind} kind)
      (if (= draft (:card-draft state))
        (assoc state :card-draft (card-draft))
        state)

      :else
      state)))

(defn apply-receipt
  "Attach one receipt to its command and return the next state.

  An accepted write queues exactly one authoritative `:materials/snapshot`
  command.  The snapshot result replaces server-derived fields.  A receipt
  for an unknown command is retained for diagnosis and changes nothing else."
  [state receipt]
  (let [receipt (unwrap-receipt receipt)
        command-id (receipt-command-id receipt)
        command (get-in state [:commands command-id])]
    (cond
      (nil? command)
      (update state :orphan-receipts (fnil conj []) receipt)

      ;; `:submitted` is the gateway's transport acknowledgement: the
      ;; command reached the wire but the Owner has not answered yet.  It
      ;; is not a terminal receipt — storing it would release the pending
      ;; lock and queue a reconcile snapshot before the domain receipt
      ;; decides.  Ignore it and keep the command in its pending status
      ;; until the real receipt or the receipt sweep answers.
      (= :submitted (receipt-status receipt))
      state

      :else
      (let [status (receipt-status receipt)
            error (receipt-error receipt)
            result (receipt-result receipt)
            command' (assoc command
                            :status status
                            :receipt receipt
                            :message (or (:message error) (:message receipt)))
            state' (-> state
                       (assoc-in [:commands command-id] command')
                       (update :outbox
                               (fn [outbox]
                                 (into [] (remove #(= command-id
                                                       (:command-id %)))
                                       outbox)))
                       (cond-> error (assoc :last-error error)))
            state' (cond-> state'
                     (and (accepted? status)
                          (= :materials/snapshot (:kind command))
                          (map? result))
                     (apply-snapshot result))
            state' (cond-> state'
                     (and (accepted? status)
                          (= (keyword "materials" "memory/suggest")
                             (:kind command))
                          (map? result))
                     (assoc :memory-suggest-result
                            (select-keys result [:candidates :skipped
                                                 :skipped-reasons
                                                 :dropped-message-count]))
                     (and (accepted? status)
                          (= entry-move-kind (:kind command))
                          (map? (:undo result))
                          (not (:undo? (:payload command))))
                     (assoc :last-move (select-keys (:undo result)
                                                    [:entry-ids :book-ids]))
                     (and (accepted? status)
                          (= entry-move-kind (:kind command))
                          (:undo? (:payload command)))
                     ;; `nil`, not `dissoc`: `merge-local-state` merges local
                     ;; keys on presence, so a removed key would resurrect the
                     ;; previous move's undo data after a stale write.
                     (assoc :last-move nil)
                     (and (accepted? status)
                          (not= :materials/snapshot (:kind command)))
                     (reset-draft-for-command command))]
        (if (and (not= :materials/snapshot (:kind command))
                 (or (accepted? status) (= :refused status)))
          (let [queued (queue-command state' :materials/snapshot {}
                                      {:key [:materials/snapshot]})]
            (if (:ok? queued)
              (:state queued)
              state'))
          state')))))

(defn drain-orphan-receipts
  "Re-apply receipts parked in `:orphan-receipts` whose command has since
  arrived in `:commands`.

  A receipt can beat the transaction that records its command; `apply-
  receipt` then parks it for diagnosis.  Callers run this once the database
  has caught up so a late command still receives its receipt — including
  the `:last-move` data an entry move needs for undo.  Receipts whose
  command is still unknown stay parked; receipts applied here keep their
  arrival order."
  [state]
  (let [orphans (:orphan-receipts state)]
    (if (empty? orphans)
      state
      (loop [pending orphans
             state state
             remaining []]
        (if (empty? pending)
          ;; `apply-receipt` cannot park a receipt whose command exists, but
          ;; keep anything it did park instead of dropping it silently.
          (update state :orphan-receipts
                  (fn [parked]
                    (into (vec remaining)
                          (remove (set orphans) (or parked [])))))
          (let [receipt (first pending)
                command-id (receipt-command-id receipt)]
            (if (contains? (:commands state) command-id)
              (recur (next pending)
                     (apply-receipt state receipt)
                     remaining)
              (recur (next pending) state (conj remaining receipt)))))))))

;; ---------------------------------------------------------------------------
;; Actions

(defn- with-error
  [state message]
  (assoc state :last-error {:message message}))

(defn- keyword-select-field?
  [form field]
  (contains?
   (case form
     :memory #{:category :scope-kind :epistemic}
     :entry #{:trigger-kind :match :position-kind}
     :card #{:output-format}
     #{})
   field))

(defn- edit-draft
  [state form field value]
  (let [value (if (keyword-select-field? form field)
                (keyword (str/replace (str value) #"^:" ""))
                value)]
    (case form
      :memory (update state :memory-draft assoc field value :error nil)
      :book (update state :book-draft assoc field value :error nil)
      :entry (update state :entry-draft assoc field value :error nil)
      :card (update state :card-draft assoc field value :error nil)
      state)))

(defn- queue-or-error
  [state kind payload key opts]
  (let [result (queue-command state kind payload (assoc opts :key key))]
    (if (:ok? result)
      (:state result)
      (with-error state (:error result)))))

(defn- memory-scope-fields
  [memory]
  (let [scope (:scope memory)]
    {:scope-kind (or (:kind scope) :global)
     :assistant-id (or (:assistant-id scope) "")
     :project-id (or (:project-id scope) "")}))

(defn- entry-form-trigger
  [entry]
  (let [trigger (:trigger entry)]
    (case (:kind trigger)
      :keyword {:trigger-kind :keyword
                :keywords (str/join "\n" (:keywords trigger))
                :match (or (:match trigger) :any)}
      :constant {:trigger-kind :constant}
      :expression {:trigger-kind :expression
                   :expression (pr-str (:expression trigger))}
      {:trigger-kind :keyword})))

(defn- select-memory
  [state memory-id]
  (if-let [memory (memory state memory-id)]
    (assoc state
           :selected-memory-id memory-id
           :memory-draft (memory-draft
                          (merge {:mode :edit
                                  :memory-id memory-id
                                  :body (:body memory)
                                  :category (:category memory)
                                  :epistemic (:epistemic memory)
                                  :always? (:always? memory)}
                                 (memory-scope-fields memory))))
    state))

(defn- select-book
  [state book-id]
  (if-let [book (get-in state [:books book-id])]
    (let [draft (:entry-draft state)
          ;; A pending move keeps its target book across a book switch; the
          ;; move is submitted from the entry editor, not the book list.
          keep-entry-draft? (and (map? draft)
                                 (non-blank-string? (:move-book-id draft)))
          entry-draft (if keep-entry-draft?
                        draft
                        (entry-draft {:book-id book-id}))]
      (assoc state
             :selected-book-id book-id
             :book-draft (book-draft {:mode :edit
                                      :book-id book-id
                                      :name (:name book)
                                      :description (:description book)
                                      :enabled? (:enabled? book)})
             :entry-draft entry-draft))
    state))

(defn- select-entry
  [state entry-id]
  (if-let [entry (entry state entry-id)]
    (assoc state
           :entry-draft
           (entry-draft
            (merge {:mode :edit
                    :entry-id entry-id
                    :book-id (:book-id entry)
                    :title (:title entry)
                    :body (:body entry)
                    :enabled? (:enabled? entry)
                    :sticky (or (:sticky entry) 0)
                    :cooldown (or (:cooldown entry) 0)
                    :delay (or (:delay entry) 0)
                    :order (or (:order entry) "")
                    :key? (:key? entry)
                    :position-kind (get-in entry [:position :kind]
                                           :base-background)
                    :position-depth (get-in entry [:position :depth] 0)
                    :move-book-id (:book-id entry)}
                   (entry-form-trigger entry))))
    state))

(defn- select-card
  [state card-id]
  (if-let [card (card state card-id)]
    (assoc state
           :selected-card-id card-id
           :card-draft (card-draft {:mode :edit
                                    :card-id card-id
                                    :name (:name card)
                                    :description (:description card)
                                    :body (:body card)
                                    :placeholders-text
                                    (format-placeholders (:placeholders card))
                                    :output-format (some-> (:output-format card)
                                                           name)
                                    :enabled? (:enabled? card)}))
    state))

(defn- save-memory
  [state]
  (let [draft (:memory-draft state)
        checked (validate-memory-draft draft)]
    (if-not (:ok? checked)
      (assoc-in state [:memory-draft :error] (:error checked))
      (let [payload (:payload checked)
            edit? (= :edit (:mode draft))
            kind (if edit?
                   memory-replace-kind
                   memory-remember-kind)
            payload (cond-> payload
                      edit? (assoc :memory-id (:memory-id draft)))
        ;; Same nonce rule as worldbook/entry creates: a fresh draft may
        ;; submit while an older create receipt is still outstanding, while
        ;; a double click on the same form shares the nonce and stays
        ;; blocked.
        key (if edit?
              [:materials/memory (:memory-id draft)]
              [:materials/memory :create (:nonce draft)])]
        (queue-or-error state kind payload
                        key
                        {:draft draft})))))

(defn- approve-memory
  [state]
  (let [draft (:memory-draft state)
        checked (validate-memory-draft draft)]
    (if-not (:ok? checked)
      (assoc-in state [:memory-draft :error] (:error checked))
      (queue-or-error state
                      memory-approve-kind
                      {:memory-id (:memory-id draft)
                       :changes (:payload checked)}
                      [:materials/memory (:memory-id draft)]
                      {:draft draft}))))

(defn- save-book
  [state]
  (let [draft (:book-draft state)
        checked (validate-book-draft draft)]
    (if-not (:ok? checked)
      (assoc-in state [:book-draft :error] (:error checked))
      (let [edit? (= :edit (:mode draft))
            kind (if edit?
                   worldbook-update-kind
                   worldbook-create-kind)
            payload (cond-> (:payload checked)
                      edit? (assoc :book-id (:book-id draft)))
        ;; A create belongs to the draft instance that produced it.  A
        ;; fresh "新建世界书" gets a fresh nonce and may submit while an
        ;; older create receipt is still outstanding; a double click on the
        ;; same form still shares the nonce and stays blocked.
        key (if edit?
              [:materials/worldbook (:book-id draft)]
              [:materials/worldbook :create (:nonce draft)])]
        (queue-or-error state kind payload
                        key
                        {:draft draft})))))

(defn- save-entry
  [state]
  (let [draft (:entry-draft state)
        checked (validate-entry-draft draft)]
    (if-not (:ok? checked)
      (assoc-in state [:entry-draft :error] (:error checked))
      (let [edit? (= :edit (:mode draft))
            kind (if edit?
                   entry-update-kind
                   entry-create-kind)
            payload (cond-> (:payload checked)
                      edit? (assoc :entry-id (:entry-id draft))
                      (not edit?) (assoc :book-id (:book-id draft)))
            ;; A create belongs to the draft instance that produced it.  A
            ;; fresh "新建条目" gets a fresh nonce and may submit while an
            ;; older create receipt is still outstanding; a double click on
            ;; the same form still shares the nonce and stays blocked.
            key (if edit?
                  [:materials/worldbook-entry (:entry-id draft)]
                  [:materials/worldbook-entry :create (:nonce draft)])]
        (queue-or-error state kind payload
                        key
                        {:draft draft})))))

(defn- save-card
  [state]
  (let [draft (:card-draft state)
        checked (validate-card-draft draft)]
    (if-not (:ok? checked)
      (assoc-in state [:card-draft :error] (:error checked))
      (let [edit? (= :edit (:mode draft))
            kind (if edit?
                   instruction-update-kind
                   instruction-create-kind)
            payload (cond-> (:payload checked)
                      edit? (assoc :card-id (:card-id draft)))
        ;; Same nonce rule as worldbook/entry creates.
        key (if edit?
              [:materials/instruction (:card-id draft)]
              [:materials/instruction :create (:nonce draft)])]
        (queue-or-error state kind payload
                        key
                        {:draft draft})))))

(defn- toggle-card-selection
  [state card-id]
  (let [selected (set (session-card-ids state))
        ids (if (contains? selected card-id)
              (vec (remove #{card-id} (session-card-ids state)))
              (conj (session-card-ids state) card-id))
        key [:materials/instruction-session
             (or (effective-session-id state) :current)]]
    (if (empty? ids)
      (let [result (queue-command state instruction-session-clear-kind {} {:key key})]
        (if (:ok? result)
          (:state result)
          (with-error state (:error result))))
      (let [result (queue-command state instruction-session-set-kind
                                  {:card-ids ids} {:key key})]
        (if (:ok? result)
          (:state result)
          (with-error state (:error result)))))))

(defn- toggle-turn-card
  [state card-id]
  (let [selected (set (turn-card-ids state))
        ids (if (contains? selected card-id)
              (vec (remove #{card-id} (turn-card-ids state)))
              (conj (turn-card-ids state) card-id))]
    (-> state
        (assoc :turn-card-ids ids)
        (update :turn-selection-version (fnil inc 0)))))

(defn- edit-instruction-value
  [state key value]
  (-> state
      (update :instruction-values assoc key value)
      (update :turn-selection-version (fnil inc 0))))

(defn reduce-action
  "Apply one local action.  Returns `{:state state' :effects [...]}`.  Only a
  queued command leaves the browser; every other action is local UI state."
  [state action]
  (let [type (:type action)
        state (or state (initial-state))]
    {:state
     (case type
       :materials/apply-snapshot
       (apply-snapshot state (:snapshot action))

       :materials/section
       (assoc state :section (:section action))

       :materials/refresh
       (queue-or-error state :materials/snapshot {} [:materials/snapshot] {})

       :materials/dismiss-error
       (assoc state :last-error nil)

       :materials/memory-suggest
       (queue-or-error state
                       (keyword "materials" "memory/suggest")
                       {:source-range {:recent-rounds 10}
                        :categories [:identity-background :working-style
                                     :expression-preference :long-term-requirement]
                        :scope :global
                        :model-binding {:role :task}}
                       [:materials/memory :suggest]
                       {})

       :materials/memory-filter
       (assoc state :memory-filter (:filter action))

       :materials/memory-query
       (assoc state :memory-query (or (:query action) (:value action) ""))

       :materials/memory-new
       (assoc state :selected-memory-id nil :memory-draft (memory-draft))

       :materials/memory-select
       (select-memory state (:memory-id action))

       :materials/memory-edit-field
       (edit-draft state :memory (:field action) (:value action))

       :materials/memory-save
       (save-memory state)

       :materials/memory-approve
       (approve-memory state)

       :materials/memory-reject
       (let [reason (or (:reason action)
                        (get-in state [:memory-draft :reject-reason])
                        "")]
         (queue-or-error state
                         memory-reject-kind
                         {:memory-id (:selected-memory-id state)
                          :reason reason}
                         [:materials/memory (:selected-memory-id state)]
                         {}))

       :materials/memory-archive
       (queue-or-error state
                       memory-archive-kind
                       {:memory-id (:selected-memory-id state)}
                       [:materials/memory (:selected-memory-id state)]
                       {})

       :materials/memory-restore
       (queue-or-error state
                       memory-restore-kind
                       {:memory-id (:selected-memory-id state)}
                       [:materials/memory (:selected-memory-id state)]
                       {})

       :materials/memory-request-delete
       (assoc-in state [:memory-draft :confirm-delete?] true)

       :materials/memory-cancel-delete
       (assoc-in state [:memory-draft :confirm-delete?] false)

       :materials/memory-delete
       (queue-or-error state
                       memory-delete-kind
                       {:memory-id (:selected-memory-id state)}
                       [:materials/memory (:selected-memory-id state)]
                       {})

       :materials/memory-settings
       (let [field (:field action)
             settings (assoc (:settings state) field (boolean (:value action)))
             result (when (contains? #{:memory-read? :memory-suggest?} field)
                      (queue-command state
                                     settings-update-kind
                                     (select-keys settings
                                                  [:memory-read? :memory-suggest?])
                                     {:key [:materials/settings]}))]
         (if (contains? #{:memory-read? :memory-suggest?} field)
           (if (:ok? result)
             (:state result)
             (with-error state (:error result)))
           state))

       :materials/book-new
       (assoc state :book-draft (book-draft {:book-id (:selected-book-id state)}))

       :materials/book-select
       (select-book state (:book-id action))

       :materials/book-edit-field
       (edit-draft state :book (:field action) (:value action))

       :materials/book-save
       (save-book state)

       :materials/book-bind
       (let [assistant-id (or (trimmed (:assistant-id action))
                              (selected-book-assistant-id state))]
         (if-not (non-blank-string? assistant-id)
           (assoc-in state [:book-draft :error] "请先创建或选择助手。")
           (queue-or-error state
                           worldbook-bind-kind
                           {:book-id (:selected-book-id state)
                            :assistant-id assistant-id}
                           [:materials/worldbook (:selected-book-id state)]
                           {})))

       :materials/book-unbind
       (let [book (selected-book state)
             bound-ids (->> (:assistant-ids book)
                            (filter non-blank-string?)
                            sort
                            vec)
             requested-id (trimmed (:assistant-id action))
             draft-id (trimmed (get-in state [:book-draft :assistant-id]))
             assistant-id (or (when (some #{requested-id} bound-ids)
                                requested-id)
                              (when (some #{draft-id} bound-ids) draft-id)
                              (first bound-ids))]
         (if-not (non-blank-string? assistant-id)
           (assoc-in state [:book-draft :error] "这本书还没有绑定助手。")
           (queue-or-error state
                           worldbook-unbind-kind
                           {:book-id (:selected-book-id state)
                            :assistant-id assistant-id}
                           [:materials/worldbook (:selected-book-id state)]
                           {})))

       :materials/book-request-delete
       (assoc-in state [:book-draft :confirm-delete?] true)

       :materials/book-cancel-delete
       (assoc-in state [:book-draft :confirm-delete?] false)

       :materials/book-delete
       (queue-or-error state
                       worldbook-delete-kind
                       {:book-id (:selected-book-id state)}
                       [:materials/worldbook (:selected-book-id state)]
                       {})

       :materials/entry-new
       (assoc state :entry-draft
              (entry-draft {:book-id (:selected-book-id state)}))

       :materials/entry-select
       (select-entry state (:entry-id action))

       :materials/entry-edit-field
       (edit-draft state :entry (:field action) (:value action))

       :materials/entry-save
       (save-entry state)

       :materials/entry-request-delete
       (assoc-in state [:entry-draft :confirm-delete?] true)

       :materials/entry-cancel-delete
       (assoc-in state [:entry-draft :confirm-delete?] false)

       :materials/entry-delete
       (queue-or-error state
                       entry-delete-kind
                       {:entry-id (:entry-id (get state :entry-draft))}
                       [:materials/worldbook-entry
                        (:entry-id (get state :entry-draft))]
                       {:draft (:entry-draft state)})

       :materials/entry-move
       (let [target (get-in state [:entry-draft :move-book-id])]
         (if (non-blank-string? target)
           (queue-or-error state
                           entry-move-kind
                           {:entry-ids [(:entry-id (:entry-draft state))]
                            :book-id target}
                           [:materials/worldbook-entry
                            (:entry-id (:entry-draft state))]
                           {:draft (:entry-draft state)})
           (assoc-in state [:entry-draft :error] "移动条目需要目标世界书。")))

       :materials/entry-undo-move
       (let [{:keys [entry-ids book-ids]} (:last-move state)
             groups (if (and (seq entry-ids) (map? book-ids))
                      (group-by book-ids entry-ids)
                      {})]
         (if (empty? groups)
           (with-error state "没有可撤销的条目移动。")
           (reduce (fn [state [book-id ids]]
                     (let [result (queue-command
                                   state entry-move-kind
                                   {:entry-ids (vec ids)
                                    :book-id book-id
                                    :undo? true}
                                   {:key [:materials/entry-undo book-id]})]
                       (if (:ok? result)
                         (:state result)
                         (with-error state (:error result)))))
                   state
                   groups)))

       :materials/card-new
       (assoc state :selected-card-id nil :card-draft (card-draft))

       :materials/card-select
       (select-card state (:card-id action))

       :materials/card-edit-field
       (edit-draft state :card (:field action) (:value action))

       :materials/card-save
       (save-card state)

       :materials/card-request-delete
       (assoc-in state [:card-draft :confirm-delete?] true)

       :materials/card-cancel-delete
       (assoc-in state [:card-draft :confirm-delete?] false)

       :materials/card-delete
       (queue-or-error state
                       instruction-delete-kind
                       {:card-id (:selected-card-id state)}
                       [:materials/instruction (:selected-card-id state)]
                       {})

       :materials/instruction-mode
       (let [mode (:mode action)
             state (assoc state :instruction-mode
                          (if (= mode :session) :session :turn))]
         state)

       :materials/temporary-read
       (if (temporary-session? state)
         (assoc state :temporary-read?
                (if (contains? action :value)
                  (boolean (:value action))
                  (not (:temporary-read? state))))
         state)

       :materials/turn-card-toggle
       (toggle-turn-card state (:card-id action))

       :materials/turn-card-clear
       (clear-turn-selection state)

       :materials/instruction-value
       (if-let [key (:key action)]
         (edit-instruction-value state key (:value action))
         state)

       :materials/card-toggle-selection
       (toggle-card-selection state (:card-id action))

       :materials/card-clear-selection
       (queue-or-error state
                       instruction-session-clear-kind
                       {}
                       [:materials/instruction-session
                        (or (effective-session-id state) :current)]
                       {})

       :materials/receive-receipt
       (apply-receipt state (:receipt action))

       :materials/sync
       (assoc state :sync (:sync action)
              :transport-available? (boolean (:transport-available? action)))

       state)
     :effects []}))

