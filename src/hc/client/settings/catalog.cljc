(ns hc.client.settings.catalog
  "Model rows, capability and price labels, and same-name disambiguation.

  A model row is identified by `[connection-id model-id]`.  Two connections
  may expose the same model id or the same display name; their rows are never
  merged.  A missing capability or price is `尚未确认` or `未知`, never a
  fabricated support claim or a free price."
  (:require [clojure.string :as str]
            [hc.client.copy.failures :as failures]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.util :as util]))

(def capability-order
  [:vision :audio :video :document :tools :reasoning])

(def capability-labels
  {:vision "视觉"
   :audio "音频"
   :video "视频"
   :document "文档"
   :tools "工具"
   :reasoning "推理"})

(def availability-labels
  {:available "可用"
   :unavailable "不可用"
   :not-returned "此次列表未返回"
   :disabled "已停用"
   :unknown "尚未确认"})

(def reasoning-level-labels
  {:off "关闭"
   :minimal "最低"
   :low "低"
   :medium "中"
   :high "高"
   :xhigh "极高"})

(defn capability-state
  "Normalize one capability declaration to `:known`, `:unsupported`, or
  `:unknown`.  Only an explicit declaration counts as known."
  [value]
  (case (util/normalize-keyword value)
    (:known :declared :supported :yes :true :on) :known
    (:unsupported :no :false :off :absent) :unsupported
    :unknown))

(def capability-edit-options
  "Discrete user-declaration choices for the model capability editor."
  [[:unknown "尚未确认"]
   [:known "声明支持"]
   [:unsupported "不支持"]])

(defn normalize-capability-sources
  "Return capability keyword -> `:user` for user-edited declarations."
  [sources]
  (cond
    (map? sources)
    (into {}
          (keep (fn [[cap value]]
                  (when (and value (util/normalize-keyword cap))
                    [(util/normalize-keyword cap) :user])))
          sources)

    (or (set? sources) (sequential? sources))
    (into {} (map (fn [cap] [(util/normalize-keyword cap) :user]))
          (filter util/normalize-keyword sources))

    :else {}))

(defn normalize-capabilities
  "Return a map of capability keyword -> `:known`, `:unsupported`, or
  `:unknown`.  Missing capabilities stay unknown."
  [capabilities]
  (cond
    (map? capabilities)
    (reduce-kv (fn [m k v]
                 (let [cap (util/normalize-keyword k)]
                   (if cap
                     (assoc m cap (capability-state v))
                     m)))
               {}
               capabilities)

    (or (set? capabilities) (sequential? capabilities))
    (into {}
          (map (fn [cap] [(util/normalize-keyword cap) :known]))
          (filter util/normalize-keyword capabilities))

    :else {}))

(defn server-capabilities
  "Return only explicit capability declarations as booleans.

  The settings payload crosses into the server session configuration, whose
  invariant accepts `true`, `false`, or `:unknown` and treats a missing key
  as unknown.  Dropping unknown entries keeps that contract while preserving
  the tri-state meaning."
  [capabilities]
  (into {}
        (keep (fn [[cap value]]
                (let [cap (util/normalize-keyword cap)]
                  (when cap
                    (case (capability-state value)
                      :known [cap true]
                      :unsupported [cap false]
                      nil)))))
        capabilities))

(defn capability-badges
  "Return one badge per capability.

  Every state names itself in the visible text: `视觉：已声明支持`,
  `视觉：尚未确认`, or `视觉：不支持`.  Adjacent badges render as separate
  pills, but `innerText` concatenates them without a separator; a known badge
  that showed only its label made `工具` followed by `推理：尚未确认` read as
  `工具推理：尚未确认`.  Naming the state keeps the text unambiguous for
  operators, tests, and assistive technology.

  SOURCES marks a capability as user-set; the badge text stays the same, but
  the accessible description names the provenance."
  ([capabilities] (capability-badges capabilities nil))
  ([capabilities sources]
   (let [caps (normalize-capabilities capabilities)
         sources (normalize-capability-sources sources)]
     (mapv (fn [cap]
             (let [state (get caps cap :unknown)
                   label (get capability-labels cap)
                   user? (contains? sources cap)]
               {:capability cap
                :label label
                :state state
                :user? user?
                :text (case state
                        :known (str label "：已声明支持")
                        :unsupported (str label "：不支持")
                        (str label "：尚未确认"))
                :aria-label (str (case state
                                   :known (str label "：已声明支持")
                                   :unsupported (str label "：已声明不支持")
                                   (str label "：尚未确认"))
                                 (when user? "（用户设置）"))}))
           capability-order))))

(defn capability-summary
  [capabilities]
  (let [badges (capability-badges capabilities)]
    (str/join "；" (map :text badges))))

(defn- unit-label
  [unit]
  (case (util/normalize-keyword unit)
    :per-million-tokens "/ 百万 tokens"
    :per-million "/ 百万 tokens"
    :per-request "/ 请求"
    :per-image "/ 图片"
    :per-second "/ 秒"
    ""))

(defn- amount->side
  [value currency unit]
  (cond
    (nil? value)
    {:known? false :text "未知"}

    (= :unknown (util/normalize-keyword value))
    {:known? false :text "未知"}

    (number? value)
    {:known? true
     :zero? (zero? value)
     :text (str (when currency (str currency " "))
                (util/format-integer value)
                (unit-label unit))}

    (map? value)
    (let [amount (or (:amount value) (:value value))
          currency (or (:currency value) currency)
          unit (or (:unit value) unit)]
      (cond
        (:free? value) {:known? true :zero? true :text "0"}
        (nil? amount) {:known? false :text "未知"}
        (= :unknown (util/normalize-keyword amount)) {:known? false :text "未知"}
        (number? amount)
        {:known? true
         :zero? (zero? amount)
         :text (str (when currency (str currency " "))
                    (util/format-integer amount)
                    (unit-label unit))}
        :else {:known? false :text "未知"}))

    :else
    {:known? false :text "未知"}))

(defn- side-text
  [side]
  (if (:zero? side) "0" (:text side)))

(defn pricing-label
  "Return the user-visible price label.

  `免费` appears only when the service explicitly declares a free price or
  every declared side is zero.  An unknown side is `未知` and suppresses a
  free claim."
  [pricing]
  (cond
    (nil? pricing) "未知"

    (= :free (util/normalize-keyword pricing)) "免费（服务声明）"

    (number? pricing)
    (if (zero? pricing) "免费（服务声明）" (str (util/format-integer pricing)))

    (map? pricing)
    (cond
      (:free? pricing) "免费（服务声明）"

      (or (contains? pricing :input) (contains? pricing :output))
      (let [currency (:currency pricing)
            unit (:unit pricing)
            input (amount->side (:input pricing) currency unit)
            output (amount->side (:output pricing) currency unit)
            both-known? (and (:known? input) (:known? output))
            all-zero? (and both-known? (:zero? input) (:zero? output))]
        (if all-zero?
          "免费（服务声明）"
          (str "输入 " (side-text input)
               "；输出 " (side-text output))))

      (or (contains? pricing :amount) (contains? pricing :value))
      (let [side (amount->side pricing (:currency pricing) (:unit pricing))]
        (if (:zero? side) "免费（服务声明）" (:text side)))

      :else "未知")

    :else "未知"))

(defn pricing-known?
  [pricing]
  (not (str/includes? (pricing-label pricing) "未知")))

(defn- format-amount
  "Format a non-negative number for display: two decimals, comma grouping."
  [value]
  (let [fixed #?(:clj (format "%.2f" (double value))
                 :cljs (.toFixed (double value) 2))
        [whole frac] (str/split fixed #"\.")]
    (str (util/format-integer (util/parse-int whole 0)) "." frac)))

(defn- price-amount
  "Return a numeric price side or nil when it is unknown/absent."
  [pricing side]
  (let [value (if (map? pricing) (get pricing side) nil)]
    (when (number? value) value)))

(defn price-preview
  "Estimate the cost of one sample request.

  `pricing` is the row's `:pricing` map (`:input`/`:output` numbers with
  `:currency` and `:unit`).  `counts` supplies `:input`/`:output` token
  counts.  Returns `{:known? bool :text string}`: `无法估算` when either
  side or the unit is unknown; a formatted amount otherwise."
  [pricing counts]
  (let [pricing (when (map? pricing) pricing)
        unit (some-> pricing :unit util/normalize-keyword)
        currency (:currency pricing)
        input-price (price-amount pricing :input)
        output-price (price-amount pricing :output)
        input-tokens (let [n (get counts :input)]
                       (when (number? n) (max 0 n)))
        output-tokens (let [n (get counts :output)]
                        (when (number? n) (max 0 n)))
        unit-label (case unit
                     :per-million-tokens "百万 tokens"
                     :per-request "请求"
                     :per-image "图片"
                     :per-second "秒"
                     nil)
        known? (and pricing
                    unit-label
                    (number? input-price)
                    (number? output-price)
                    (number? input-tokens)
                    (number? output-tokens))]
    (if-not known?
      {:known? false :text "无法估算"}
      (let [input-cost (* input-price (/ input-tokens 1000000.0))
            output-cost (* output-price (/ output-tokens 1000000.0))
            total (+ input-cost output-cost)
            formatted (format-amount total)]
        {:known? true
         :text (str (when currency (str currency " "))
                    formatted
                    "（按 " unit-label " 计）")
         :total total}))))

(defn- capacity-value
  [value]
  (cond
    (integer? value) value
    (map? value) (let [v (or (:tokens value) (:value value) (:context-window value))]
                   (when (integer? v) v))
    :else nil))

(defn capacity-source-label
  [value]
  (let [source (or (when (map? value) (:source value)) value)]
    (case (util/normalize-keyword source)
      (:user :user-set :manual) "用户设置"
      (:estimate :estimated :approximation) "估算"
      (:provider :server :reported) "服务返回"
      nil nil
      nil)))

(defn capacity-label
  "Return `未知` or `N tokens（来源）`; an estimate is labelled as an estimate."
  [model]
  (let [value (or (:context-window model) (:context-capacity model) (:capacity model))
        tokens (capacity-value value)
        source (capacity-source-label
                (or (:capacity-source model)
                    (when (map? value) (:source value))))]
    (if tokens
      (str (util/format-integer tokens) " tokens"
           (when source (str "（" source "）")))
      "未知")))

(defn capacity-warning
  "Compare an estimated input size with the model capacity.

  The numbers are estimates and the message says so.  History is never
  deleted by this decision; the caller offers explicit actions.  The
  `:copy` value is the shared 24-failure copy for `:input-too-large`, and
  `:block?` tells the send decision to stop before dispatch."
  ([model estimated-tokens]
   (capacity-warning model estimated-tokens {}))
  ([model estimated-tokens context]
   (let [value (or (:context-window model) (:context-capacity model) (:capacity model))
         capacity (capacity-value value)
         saved? (true? (:saved? context))]
     (cond
       (nil? capacity)
       {:over? false
        :block? false
        :unknown? true
        :message "模型容量未知，发送前无法预估；不会把未知当作 0。"}

       (nil? estimated-tokens)
       {:over? false
        :block? false
        :unknown? true
        :message "当前内容量未知（估算），发送前无法判断是否超限。"}

       (> estimated-tokens capacity)
       {:over? true
        :block? true
        :unknown? false
        :capacity capacity
        :estimate estimated-tokens
        :message (str "估算内容约 " (util/format-integer estimated-tokens)
                      " tokens，超过模型容量 " (util/format-integer capacity)
                      " tokens；发送前会阻止。历史仍完整可读，队列内容保留。")
        :actions [:compress-history :reduce-attachments :adjust-scope :switch-model]
        :copy (failures/failure-copy
               {:code :input-too-large
                :context {:estimated-tokens estimated-tokens
                          :capacity capacity
                          :saved? saved?}})}

       (>= estimated-tokens (* 0.9 capacity))
       {:over? false
        :block? false
        :near? true
        :unknown? false
        :capacity capacity
        :estimate estimated-tokens
        :message (str "内容接近模型容量（约 "
                      (util/format-integer estimated-tokens) " / "
                      (util/format-integer capacity)
                      " tokens）；仍可发送，建议压缩历史或减少附件。")
        :actions [:compress-history :reduce-attachments :switch-model]}

       :else
       {:over? false
        :block? false
        :unknown? false
        :capacity capacity
        :estimate estimated-tokens
        :message "估算内容未超过已知模型容量。"}))))

(defn reasoning-label
  "Return a discrete reasoning level.  A model that cannot disable reasoning
  shows `最低可用`, never a false `关闭`."
  [model]
  (let [reasoning (:reasoning model)
        config (if (map? reasoning) reasoning {})
        selected (or (:selected config)
                     (:current config)
                     (when (keyword? reasoning) reasoning)
                     (:default config))
        can-disable? (boolean (:can-disable? config))]
    (cond
      (nil? selected) "使用默认"
      (= :off selected) (if can-disable?
                          "关闭"
                          "最低可用（该模型不支持关闭推理）")
      (= :default selected) "使用默认"
      :else (get reasoning-level-labels selected (name selected)))))

(defn reasoning-options
  [model]
  (let [reasoning (:reasoning model)
        config (if (map? reasoning) reasoning {})
        levels (vec (distinct (or (:levels config) [])))
        levels (if (:can-disable? config)
                 levels
                 (vec (remove #{:off} levels)))
        can-disable? (boolean (:can-disable? config))]
    (cond-> (mapv (fn [level]
                    {:level level
                     :label (get reasoning-level-labels level (name level))})
                  levels)
      (not can-disable?) (conj {:level :off
                                :label "最低可用"
                                :disabled? true}))))

(defn parameter-label
  "Distinguish an explicit numeric 0 from `使用默认`."
  [value]
  (cond
    (nil? value) "使用默认"
    (= :default (util/normalize-keyword value)) "使用默认"
    (number? value) (str value)
    :else (str value)))

(defn availability-label
  [model connection]
  (cond
    (false? (:enabled? connection)) "所属连接已停用"
    (false? (:enabled? model)) "已停用"
    :else (get availability-labels (:availability model) "尚未确认")))

(defn availability-state
  [model connection]
  (cond
    (false? (:enabled? connection)) :disabled
    (false? (:enabled? model)) :disabled
    :else (or (:availability model) :unknown)))

(defn normalize-model
  "Return the display copy of one model.  No field is invented."
  [model]
  (let [model-id (str (or (:model-id model) (:id model) (:name model) ""))
        display-name (or (:display-name model) (:name model) model-id)]
    {:model-id model-id
     :display-name display-name
     :full-id (str (or (:full-id model) (:id model) model-id))
     :capabilities (normalize-capabilities (:capabilities model))
     :capability-sources (normalize-capability-sources
                          (:capability-sources model))
     :capability-baseline (normalize-capabilities
                           (:capability-baseline model))
     :availability (or (util/normalize-keyword (:availability model))
                       (util/normalize-keyword (:status model))
                       :unknown)
     :pricing (or (:pricing model) :unknown)
     :context-window (or (:context-window model)
                         (:context-capacity model)
                         (:capacity model)
                         :unknown)
     :capacity-source (or (:capacity-source model)
                          (when (map? (:context-window model))
                            (:source (:context-window model))))
     :max-output (or (:max-output model) :unknown)
     :temperature (:temperature model)
     :top-p (:top-p model)
     :reasoning (:reasoning model)
     :enabled? (not (false? (:enabled? model)))
     :alias (:alias model)
     :notes (:notes model)
     :last-seen-at (:last-seen-at model)
     :source (or (:source model) :provider)}))

(defn manual-model
  "Return an explicitly typed model.  Its capability and price are unknown."
  [model-id]
  (normalize-model {:model-id model-id
                    :display-name model-id
                    :availability :not-returned
                    :pricing :unknown
                    :capabilities {}
                    :capability-sources {}
                    :capability-baseline {}
                    :source :manual}))

(defn merge-model-list
  "Merge a refreshed model list with the existing rows.

  Existing enablement, alias, and notes survive.  A model missing from a
  successful listing stays in the catalog as `此次列表未返回`; it is not
  deleted.  When the listing itself is unavailable, return the existing rows
  unchanged."
  [existing incoming listing-status]
  (let [existing (mapv normalize-model existing)]
    (if (= :unavailable listing-status)
      existing
      (let [by-id (into {} (map (juxt :model-id identity) existing))
            incoming (mapv normalize-model incoming)
            incoming-ids (set (map :model-id incoming))
            merged (mapv (fn [model]
                           (let [old (get by-id (:model-id model))]
                             (merge model
                                    (select-keys old [:enabled? :alias :notes])
                                    (when-let [sources
                                               (seq (:capability-sources old))]
                                      (let [incoming-caps (:capabilities model)
                                            old-caps (:capabilities old)
                                            incoming-baseline
                                            (:capability-baseline model)]
                                        {:capabilities
                                         (reduce-kv
                                          (fn [caps cap _]
                                            (assoc caps cap
                                                   (get old-caps cap :unknown)))
                                          incoming-caps
                                          sources)
                                         :capability-sources
                                         (:capability-sources old)
                                         :capability-baseline
                                         (reduce-kv
                                          (fn [baseline cap _]
                                            (assoc baseline cap
                                                   (get incoming-baseline cap
                                                        (get (:capability-baseline old)
                                                             cap
                                                             (get incoming-caps cap
                                                                  :unknown)))))
                                          {}
                                          sources)}))
                                    ;; A successful listing proves the row was
                                    ;; returned, not that the service can run
                                    ;; it.  Keep an omitted/unknown value
                                    ;; unknown instead of inventing `可用`.
                                    {:availability (if (or (nil? (:availability model))
                                                           (= :unknown (:availability model)))
                                                     :unknown
                                                     (:availability model))
                                     :last-seen-at (or (:last-seen-at model)
                                                       (:last-seen-at old))})))
                         incoming)
            missing (->> existing
                         (remove (comp incoming-ids :model-id))
                         (map #(assoc % :availability :not-returned
                                        :not-returned? true))
                         (sort-by :display-name))]
        (into merged missing)))))

(defn add-manual-model
  "Add a manually typed model to a model vector when it is not present."
  [models model-id]
  (let [model-id (str/trim (str model-id))]
    (if (or (str/blank? model-id)
            (some #(= model-id (:model-id %)) models))
      (vec models)
      (conj (vec models) (manual-model model-id)))))

(defn- connection-name-counts
  [connections]
  (reduce (fn [m connection]
            (update m (:name connection) (fnil inc 0)))
          {}
          (vals connections)))

(defn- row-keyed
  "Normalize MODELS and give each row a stable, unique key."
  [connection-id models]
  (loop [remaining (seq models)
         seen {}
         out []]
    (if-let [model (first remaining)]
      (let [model (normalize-model model)
            model-id (:model-id model)
            n (get seen model-id 0)
            row-key (if (zero? n)
                      [connection-id model-id]
                      [connection-id model-id n])]
        (recur (next remaining)
               (assoc seen model-id (inc n))
               (conj out (assoc model :row-key row-key))))
      out)))

(defn- listed-with-manual
  "Combine the provider list with manual model ids that are not present."
  [connection listed]
  (let [listed (vec (or listed []))
        present (set (map (fn [model]
                            (str (or (:model-id model) (:id model) (:name model))))
                          listed))]
    (into listed
          (comp (map str)
                (remove #(contains? present %))
                (map (fn [id] {:model-id id :source :manual})))
          (:manual-models connection))))

(defn grouped-models
  "Return model rows grouped by connection.

  Arguments are the server connections map, the models map, and an optional
  model-list-status map.  Every row keeps its connection identity even when
  the display name is shared with another connection."
  ([connections models]
   (grouped-models connections models {}))
  ([connections models statuses]
   (let [connections (or connections {})
         models (or models {})
         statuses (or statuses {})
         name-counts (connection-name-counts connections)
         display-name-counts
         (frequencies
          (for [[connection-id connection] connections
                model (listed-with-manual connection (get models connection-id))]
            (:display-name (normalize-model model))))]
     (vec
      (for [[connection-id connection] (sort-by (fn [[id c]] [(:name c) id])
                                                 connections)
            :let [listing-status (get statuses connection-id :unknown)
                  listed (listed-with-manual connection (get models connection-id))
                  row-models (row-keyed connection-id listed)
                  rows (mapv (fn [model]
                               (assoc model
                                      :connection-id connection-id
                                      :connection-name (:name connection)
                                      :actual-host (:actual-host connection)
                                      :official-label (connection/official-label connection)
                                      :source-label (connection/connection-source-label connection)
                                      :connection-enabled? (:enabled? connection)
                                      :availability-label (availability-label model connection)
                                      :availability-state (availability-state model connection)
                                      :capability-badges
                                      (capability-badges (:capabilities model)
                                                         (:capability-sources model))
                                      :pricing-label (pricing-label (:pricing model))
                                      :capacity-label (capacity-label model)
                                      :reasoning-label (reasoning-label model)
                                      :user-capabilities?
                                      (boolean (seq (:capability-sources model)))
                                      :duplicate-display-name?
                                      (> (get display-name-counts (:display-name model) 0) 1)))
                             row-models)]]
            {:group-key connection-id
             :connection connection
             :connection-id connection-id
             :connection-name (:name connection)
             :connection-enabled? (not (false? (:enabled? connection)))
             :source-label (connection/connection-source-label connection)
             :actual-host (:actual-host connection)
             :official-label (connection/official-label connection)
             :duplicate-name? (> (get name-counts (:name connection) 0) 1)
             :listing-status listing-status
             :listing-unavailable? (= :unavailable listing-status)
             :no-models? (empty? rows)
             :manual-models (:manual-models connection)
             :rows rows})))))

(defn rows
  "Flatten grouped model rows."
  [groups]
  (vec (mapcat :rows groups)))

(defn selection
  [connection-id model-id]
  {:connection-id (str connection-id)
   :model-id (str model-id)})

(defn selection-key
  [selection]
  [(str (:connection-id selection)) (str (:model-id selection))])

(defn find-row
  [groups selection]
  (when selection
    (let [key (selection-key selection)]
      (some (fn [row]
              (when (= key (selection-key row)) row))
            (rows groups)))))

(defn selection-label
  "Return a label that always names the connection source."
  [groups selection]
  (if-let [row (find-row groups selection)]
    (str (:display-name row) " · " (:source-label row))
    (when selection
      (str "原模型已不可用（" (:connection-id selection) "/"
           (:model-id selection) "）；历史仍保留"))))

(defn model-snapshot
  "Freeze the model identity used for one round."
  [row captured-at]
  {:connection-id (:connection-id row)
   :model-id (:model-id row)
   :display-name (:display-name row)
   :source-label (:source-label row)
   :actual-host (:actual-host row)
   :captured-at captured-at})

(defn round-switch-plan
  "Describe how a model change applies to an in-flight reply.

  A running reply keeps its original model attribution.  The new selection
  applies to the next round; stopping the current round is a separate choice."
  ([running? selection] (round-switch-plan running? selection nil))
  ([running? selection session-id]
   (if running?
     {:mode :next-round
      :selection selection
      :label "用于下一轮"
      :attribution-note "当前回复仍归属原模型；下一轮才使用新模型。"
      :alternatives [{:mode :stop-and-switch
                      :label "停止当前轮并切换"
                      :effect {:effect :request-stop-run
                               :reason :model-switch
                               :session-id session-id}}]}
     {:mode :next-round
      :selection selection
      :label "用于下一轮"
      :attribution-note "当前没有生成中的回复；新模型用于下一轮。"})))
