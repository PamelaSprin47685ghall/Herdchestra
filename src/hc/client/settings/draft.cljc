(ns hc.client.settings.draft
  "Editable connection drafts.

  A draft lives outside the settings page component.  Closing or unmounting
  the page never clears it.  A save receipt clears only the exact draft
  version and digest that the server accepted; a newer edit survives."
  (:require [clojure.string :as str]
            [hc.client.settings.catalog :as catalog]
            [hc.client.settings.connection :as connection]
            [hc.client.settings.util :as util]))

(defn- new-id
  [prefix opts k]
  (or (get opts k)
      (str prefix (random-uuid))))

(defn key-from-connection
  "Return one saved auth key as an unchanged draft key."
  [key]
  {:key-id (:key-id key)
   :alias (:alias key)
   :enabled? (not (false? (:enabled? key)))
   :priority (:priority key)
   :has-key? (boolean (:has-key? key))
   :auth-revision (:auth-revision key)
   :auth-state :unchanged
   :auth-edit-id (or (:auth-revision key) (:key-id key))
   :secret nil
   :status (:status key)})

(defn draft-from-connection
  "Return a clean draft for an existing connection.

  MODELS overrides the connection's embedded model list.  The settings
  snapshot publishes connection cards and model rows as separate tables, so
  a caller that has the authoritative `:settings/models` list must pass it
  here.  Otherwise a draft could start with no rows and hide the server's
  models until the next snapshot."
  ([connection] (draft-from-connection connection nil))
  ([connection models]
   (let [models (when (some? models) (vec models))
         connection (if (some? models)
                      (assoc connection :models models)
                      connection)
         enabled-models
         (cond
           ;; The model table is authoritative when the caller has it: the
           ;; server publishes per-row `:enabled?` there.
           (some? models)
           (vec (keep (fn [model]
                        (when-not (false? (:enabled? model))
                          (str (or (:model-id model) (:id model)
                                   (:name model)))))
                      (:models connection)))

           ;; Without a model table, an explicit list (including an empty
           ;; one) wins over the per-model fallback.
           (some? (:enabled-models connection))
           (vec (:enabled-models connection))

           :else
           (vec (keep (fn [model]
                        (when-not (false? (:enabled? model))
                          (str (or (:model-id model) (:id model)
                                   (:name model)))))
                      (:models connection))))
         connection (assoc connection :enabled-models enabled-models)]
     {:draft-id (:connection-id connection)
      :connection-id (:connection-id connection)
      :mode :edit
      :version 1
      :base-digest (connection/connection-digest connection)
      :saved-connection connection
      :fields {:name (:name connection)
               :base-url (:base-url connection)
               :service-kind (:service-kind connection)
               :protocol (:protocol connection)
               :location (:location connection)
               :account-label (:account-label connection)
               :auth-ref (:auth-ref connection)
               :auth (or (:auth connection) {})
               :api-key-env (:api-key-env connection)
               :key-strategy (:key-strategy connection)
               :enabled? (:enabled? connection)}
      :keys (mapv key-from-connection (:auth-keys connection))
      :models (vec (:models connection))
      :manual-models (vec (:manual-models connection))
      :enabled-models enabled-models
      :dirty? false
      :status :clean
      :orphaned? false
      :last-error nil
      :saved-at nil})))

(defn blank-draft
  "Return a new, unsaved connection draft for PATH.

  `opts` may supply `:draft-id`, `:key-id`, and `:now` so tests are
  deterministic."
  ([path] (blank-draft path {}))
  ([path opts]
   (let [service-kind (if (contains? connection/service-kind-labels path)
                        path
                        :cloud-key)
         needs-key? (= :cloud-key service-kind)]
     {:draft-id (new-id "draft-" opts :draft-id)
      :connection-id nil
      :mode :new
      :version 1
      :base-digest nil
      :fields {:name ""
               :base-url ""
               :service-kind service-kind
               :protocol (if (= :local-service service-kind)
                           :openai-compatible
                           :openai-compatible)
               :location (if (= :local-service service-kind) :unknown :unknown)
               :account-label ""
               :auth-ref ""
               :auth {}
               :api-key-env ""
               :key-strategy :priority
               :enabled? true}
      :saved-connection nil
      :keys (if needs-key?
              [{:key-id (new-id "key-" opts :key-id)
                :alias "主密钥"
                :enabled? true
                :priority 1
                :has-key? false
                :auth-state :unchanged
                :auth-edit-id (new-id "edit-" opts :auth-edit-id)
                :secret nil
                :status :untested}]
              [])
      :models []
      :manual-models []
      :enabled-models []
      :dirty? true
      :status :new
      :orphaned? false
      :last-error nil
      :saved-at nil})))

(defn display-name
  "Return the operator-visible name for DRAFT.

  A new draft starts without a name.  The editor and the unsaved-draft list
  still need a stable identity, so they use this fallback instead of showing
  a blank label or borrowing another draft's name."
  [draft]
  (let [name (str/trim (str (or (get-in draft [:fields :name])
                                (get-in draft [:saved-connection :name])
                                "")))]
    (if (str/blank? name)
      (if (= :new (:mode draft))
        "未命名新连接"
        "未命名连接")
      name)))

(defn path-label
  "Return the connection path label for DRAFT."
  [draft]
  (get connection/service-kind-labels
       (get-in draft [:fields :service-kind])
       "连接"))

(defn short-id
  "Return a compact identity for DRAFT.

  Two unnamed new drafts share the same display name.  The editor and the
  unsaved-draft list use this suffix so the operator can tell them apart
  without reading a full UUID."
  [draft]
  (let [id (str (:draft-id draft))]
    (if (<= (count id) 12)
      id
      (str "…" (subs id (- (count id) 8))))))

(defn untouched-new?
  "True when DRAFT is a new connection that no edit has touched.

  `blank-draft` starts at version 1.  Every field, key, model, or auth edit
  increments the version, so a later version is enough to keep the draft.
  The caller must still check any UI-only input before discarding it."
  [draft]
  (and (= :new (:mode draft))
       (= 1 (:version draft))
       (not (:orphaned? draft))))

(defn- touch
  ([draft] (touch draft {}))
  ([draft opts]
   (cond-> (-> draft
               (update :version inc)
               (assoc :dirty? true
                      :status :dirty
                      :last-error nil)
               ;; A draft without an enable flag is enabled.  An explicit
               ;; false is operator intent, so a later edit must not turn it
               ;; back on.
               (update-in [:fields :enabled?]
                          (fn [enabled?] (if (nil? enabled?) true enabled?))))
     (:now opts) (assoc :updated-at (:now opts)))))

(defn apply-connection-patch
  "Merge a non-secret connection patch into one draft and touch it once."
  [draft patch opts]
  (let [fields (select-keys patch [:service-kind :base-url :account-label
                                   :auth-ref :auth])]
    (touch (update draft :fields merge fields) opts)))

(defn edit-field
  "Update one non-secret field.  Only the draft changes."
  ([draft field value] (edit-field draft field value {}))
  ([draft field value opts]
   (let [value (if (contains? #{:protocol :service-kind :location
                                :key-strategy :enabled?} field)
                 (if (= :enabled? field)
                   (not (false? value))
                   (util/normalize-keyword value))
                 value)]
     (touch (assoc-in draft [:fields field] value) opts))))

(defn set-path
  "Change the connection path.  Saved connections and other drafts are not
  touched."
  ([draft path] (set-path draft path {}))
  ([draft path opts]
   (let [path (util/normalize-keyword path)
         path (if (contains? connection/service-kind-labels path)
                path
                :cloud-key)
         needs-key? (= :cloud-key path)
         draft (assoc-in draft [:fields :service-kind] path)
         draft (if (and needs-key? (empty? (:keys draft)))
                 (assoc draft :keys
                        [{:key-id (new-id "key-" opts :key-id)
                          :alias "主密钥"
                          :enabled? true
                          :priority 1
                          :has-key? false
                          :auth-state :unchanged
                          :auth-edit-id (new-id "edit-" opts :auth-edit-id)
                          :secret nil
                          :status :untested}])
                 draft)]
     (touch draft opts))))

(defn set-location
  [draft location opts]
  (edit-field draft :location location opts))

(defn set-key-strategy
  [draft strategy opts]
  (edit-field draft :key-strategy strategy opts))

(defn- key-index
  [draft key-id]
  (first (keep-indexed (fn [index key]
                         (when (= key-id (:key-id key)) index))
                       (:keys draft))))

(defn edit-key-field
  [draft key-id field value opts]
  (if-let [index (key-index draft key-id)]
    (let [current (get-in draft [:keys index])
          value (if (= :priority field)
                  (util/parse-int value (:priority current))
                  value)]
      (touch (assoc-in draft [:keys index field] value) opts))
    draft))

(defn toggle-key-enabled
  [draft key-id opts]
  (if-let [index (key-index draft key-id)]
    (touch (update-in draft [:keys index :enabled?] not) opts)
    draft))

(defn add-key
  [draft opts]
  (let [key {:key-id (new-id "key-" opts :key-id)
             :alias (or (:alias opts) "新密钥")
             :enabled? true
             :priority (inc (count (:keys draft)))
             :has-key? false
             :auth-state :set
             :auth-edit-id (new-id "edit-" opts :auth-edit-id)
             :secret ""
             :status :untested}]
    (touch (update draft :keys conj key) opts)))

(defn remove-key
  [draft key-id opts]
  (touch (update draft :keys
                 (fn [keys]
                   (into [] (remove #(= key-id (:key-id %))) keys)))
         opts))

(defn edit-key-secret
  "Set a new secret for one key.  The value stays in the local draft until a
  save command includes it.  A new edit id distinguishes two different
  secrets without putting either value in the digest."
  [draft key-id secret opts]
  (if-let [index (key-index draft key-id)]
    (touch (-> draft
               (assoc-in [:keys index :auth-state] :set)
               (assoc-in [:keys index :secret] secret)
               (assoc-in [:keys index :auth-edit-id]
                         (new-id "edit-" opts :auth-edit-id)))
           opts)
    draft))

(defn clear-key-secret
  "Mark a saved secret for explicit removal.  An empty visible input alone
  never removes a stored secret."
  [draft key-id opts]
  (if-let [index (key-index draft key-id)]
    (touch (-> draft
               (assoc-in [:keys index :auth-state] :clear)
               (assoc-in [:keys index :secret] nil))
           opts)
    draft))

(defn add-manual-model
  [draft model-id opts]
  (let [model-id (str/trim (str model-id))]
    (if (str/blank? model-id)
      draft
      (let [present? (some (fn [model]
                             (= model-id
                                (str (or (:model-id model) (:id model)
                                         (:name model)))))
                           (:models draft))]
        (touch (cond-> (-> draft
                           (update :manual-models
                                   (fn [models]
                                     (if (some #{model-id} models)
                                       (vec models)
                                       (conj (vec models) model-id))))
                           (update :enabled-models
                                   (fn [models]
                                     (let [models (set models)]
                                       (conj models model-id)))))
                 (not present?)
                 (update :models (fnil conj [])
                         {:id model-id
                          :model-id model-id
                          :name model-id
                          :display-name model-id
                          :enabled? true
                          :source :manual
                          :availability :not-returned
                          :pricing :unknown
                          :capabilities {}
                          :capability-sources {}
                          :capability-baseline {}}))
               opts)))))

(defn remove-manual-model
  [draft model-id opts]
  (let [model-id (str model-id)]
    (touch (-> draft
               (update :manual-models
                       (fn [models] (into [] (remove #{model-id}) models)))
               (update :enabled-models
                       (fn [models] (disj (set models) model-id)))
               (update :models
                       (fn [models]
                         (into []
                               (remove (fn [model]
                                         (and (= model-id
                                                 (str (or (:model-id model)
                                                          (:id model)
                                                          (:name model))))
                                              (= :manual (:source model)))))
                               models))))
           opts)))

(defn- model-index
  [models model-id]
  (first (keep-indexed
          (fn [index model]
            (when (= (str model-id)
                     (str (or (:model-id model) (:id model) (:name model))))
              index))
          models)))

(defn- ensure-model-entry
  "Return MODELS with one manual entry for MODEL-ID when it is absent."
  [models model-id]
  (if (some? (model-index models model-id))
    (vec models)
    (conj (vec models)
          {:id model-id
           :model-id model-id
           :name model-id
           :display-name model-id
           :enabled? true
           :source :manual
           :availability :not-returned
           :pricing :unknown
           :capabilities {}
           :capability-sources {}
           :capability-baseline {}})))

(defn- replace-model-id
  "Rename MODEL-ID in the enabled and manual id lists."
  [draft old-id new-id]
  (let [rename (fn [ids]
                 (let [ids (or ids [])]
                   (into (empty ids)
                         (map (fn [id]
                                (if (= (str id) (str old-id)) new-id id)))
                         ids)))]
    (-> draft
        (update :enabled-models rename)
        (update :manual-models rename))))

(defn- number-setting
  [value empty]
  (let [n (util/parse-number value)]
    (if (nil? n) empty n)))

(defn- int-setting
  [value empty]
  (let [n (util/parse-int value nil)]
    (if (nil? n) empty n)))

(defn- pricing-map
  [model]
  (let [pricing (:pricing model)]
    (if (map? pricing) pricing {})))

(defn- set-pricing
  [model key value]
  (assoc model :pricing (assoc (pricing-map model) key value)))

(defn edit-model-field
  "Edit one non-secret model detail field.

  An empty numeric field is `:unknown` for capacity/price and `:default` for
  service-decided parameters.  A numeric `0` is kept as a real zero.  A model
  id rename also moves the enabled/manual id lists."
  [draft model-id field value opts]
  (let [model-id (str model-id)
        models (ensure-model-entry (:models draft) model-id)
        index (model-index models model-id)
        model (get models index)
        field (util/normalize-keyword field)
        reasoning (fn [model value]
                    (let [selected (or (util/normalize-keyword value)
                                       :default)
                          current (:reasoning model)
                          current (if (map? current) current {})]
                      (assoc model :reasoning
                             (assoc current :selected selected))))
        model' (case field
                 :display-name
                 (assoc model :display-name (str/trim (str value)))

                 :model-id
                 (assoc model :model-id (str/trim (str value))
                        :id (str/trim (str value)))

                 :context-window
                 (assoc model :context-window
                        (int-setting value :unknown))

                 :capacity-source
                 (assoc model :capacity-source
                        (util/normalize-keyword value))

                 :max-output
                 (assoc model :max-output
                        (int-setting value :default))

                 :temperature
                 (assoc model :temperature
                        (number-setting value :default))

                 :top-p
                 (assoc model :top-p
                        (number-setting value :default))

                 :pricing-input
                 (set-pricing model :input
                              (number-setting value :unknown))

                 :pricing-output
                 (set-pricing model :output
                              (number-setting value :unknown))

                 :pricing-currency
                 (set-pricing model :currency (str/trim (str value)))

                 :pricing-unit
                 (set-pricing model :unit
                              (util/normalize-keyword value))

                 :reasoning
                 (reasoning model value)

                 model)
        new-id (str (or (:model-id model') (:id model') model-id))
        draft' (if (= field :model-id)
                 (replace-model-id draft model-id new-id)
                 draft)]
    (touch (assoc-in draft' [:models index] model') opts)))

(defn set-model-capability
  "Set one user-declared capability state on MODEL-ID.

  The first edit records the previous value as the baseline.  The effective
  capability map carries the user's declaration; the source map marks it as
  user-set so the view can show `用户设置` and restore the known information.
  A declaration equal to the baseline removes the override instead of
  pretending the user changed it."
  [draft model-id capability value opts]
  (let [model-id (str model-id)
        capability (util/normalize-keyword capability)
        value (catalog/capability-state value)
        models (ensure-model-entry (:models draft) model-id)
        index (model-index models model-id)
        model (get models index)
        current (catalog/normalize-capabilities (:capabilities model))
        baseline0 (catalog/normalize-capabilities
                   (:capability-baseline model))
        baseline (if (contains? baseline0 capability)
                   baseline0
                   (assoc baseline0 capability
                          (get current capability :unknown)))
        baseline-value (get baseline capability :unknown)
        overridden? (not= value baseline-value)
        sources0 (catalog/normalize-capability-sources
                  (:capability-sources model))
        sources (if overridden?
                  (assoc sources0 capability :user)
                  (dissoc sources0 capability))
        baseline (if overridden? baseline (dissoc baseline capability))
        capabilities (if overridden?
                       (assoc current capability value)
                       (if (= :unknown baseline-value)
                         (dissoc current capability)
                         (assoc current capability baseline-value)))
        model' (cond-> (assoc model :capabilities
                              (catalog/server-capabilities capabilities))
                 (seq sources)
                 (assoc :capability-sources sources
                        :capability-baseline baseline)

                 (empty? sources)
                 (dissoc :capability-sources :capability-baseline))]
    (if (nil? index)
      draft
      (touch (assoc-in draft [:models index] model') opts))))

(defn restore-model-capabilities
  "Restore one capability, or all capabilities, to the recorded baseline.

  With a nil CAPABILITY the whole user override is removed.  An absent
  baseline means the capability was never known, so restoring removes the
  declaration instead of inventing support."
  ([draft model-id opts]
   (restore-model-capabilities draft model-id nil opts))
  ([draft model-id capability opts]
   (let [model-id (str model-id)
         capability (some-> capability util/normalize-keyword)
         models (ensure-model-entry (:models draft) model-id)
         index (model-index models model-id)
         model (get models index)
         current (catalog/normalize-capabilities (:capabilities model))
         sources (catalog/normalize-capability-sources
                  (:capability-sources model))
         baseline (catalog/normalize-capabilities
                   (:capability-baseline model))
         restore-keys (if capability
                        (if (contains? sources capability) #{capability} #{})
                        (set (keys sources)))
         capabilities (reduce (fn [caps cap]
                                (let [value (get baseline cap :unknown)]
                                  (if (= :unknown value)
                                    (dissoc caps cap)
                                    (assoc caps cap value))))
                              current
                              restore-keys)
         sources (apply dissoc sources restore-keys)
         baseline (apply dissoc baseline restore-keys)
         model' (cond-> (assoc model :capabilities
                               (catalog/server-capabilities capabilities))
                  (seq sources)
                  (assoc :capability-sources sources
                         :capability-baseline baseline)

                  (empty? sources)
                  (dissoc :capability-sources :capability-baseline))]
     (if (nil? index)
       draft
       (touch (assoc-in draft [:models index] model') opts)))))

(defn toggle-enabled-model
  [draft model-id opts]
  (let [model-id (str model-id)
        enabled? (not (contains? (set (:enabled-models draft)) model-id))]
    (touch (-> draft
               (update :enabled-models
                       (fn [models]
                         (let [models (set models)]
                           (if enabled?
                             (conj models model-id)
                             (disj models model-id)))))
               (update :models
                       (fn [models]
                         (mapv (fn [model]
                                 (if (= model-id
                                        (str (or (:model-id model)
                                                 (:id model)
                                                 (:name model))))
                                   (assoc model :enabled? enabled?)
                                   model))
                               models))))
           opts)))

(defn dirty?
  "True when the draft differs from its saved base."
  [draft]
  (if (:base-digest draft)
    (not= (:base-digest draft) (connection/draft-digest draft))
    (boolean (:dirty? draft))))

(defn status
  [draft]
  (cond
    (:orphaned? draft) :orphaned
    (dirty? draft) (if (= :new (:mode draft)) :new :dirty)
    :else (let [status (:status draft)]
            ;; `touch` records :dirty, but the digest is authoritative.
            ;; Reverting the last edit makes the draft clean again.
            (if (or (nil? status) (= :dirty status))
              :clean
              status))))

(defn status-label
  [draft]
  (case (status draft)
    :new "新连接（未保存）"
    :dirty "有未保存修改"
    :clean "已保存"
    :saved "已保存"
    :saved-older "已保存旧版本；仍有更新的草稿"
    :saving "正在保存"
    :refused "保存被拒；草稿保留"
    :unknown "提交结果未知；草稿保留"
    :orphaned "连接已在服务器移除；草稿仍保留"
    "状态未知"))

(defn mark-saving
  [draft]
  (assoc draft :status :saving :last-error nil))

(defn mark-refused
  [draft error]
  (assoc draft :status :refused :last-error error))

(defn mark-unknown
  [draft error]
  (assoc draft :status :unknown :last-error error))

(defn mark-orphaned
  [draft]
  (assoc draft :orphaned? true :status :orphaned))

(defn- submitted-key
  [submitted key-id]
  (some #(when (= key-id (:key-id %)) %) (:keys submitted)))

(defn- update-saved-keys
  "Turn submitted auth changes into unchanged keys with the server revision."
  [draft submitted canonical]
  (let [canonical-keys (into {} (map (juxt :key-id identity))
                             (:auth-keys canonical))]
    (update draft :keys
            (fn [keys]
              (mapv (fn [key]
                      (let [sent (submitted-key submitted (:key-id key))
                            saved (get canonical-keys (:key-id key))]
                        (if (and sent
                                 (= (:auth-edit-id key) (:auth-edit-id sent)))
                          (merge key
                                 {:auth-state :unchanged
                                  :secret nil
                                  :has-key? (boolean (:has-key? saved))
                                  :auth-revision (:auth-revision saved)
                                  :auth-edit-id (or (:auth-revision saved)
                                                    (:key-id key))
                                  :status (or (:status saved) (:status key))})
                          key)))
                  keys)))))

(defn apply-save-accepted
  "Apply one accepted save receipt to DRAFT.

  Returns `{:draft draft' :clear? boolean}`.  `clear?` is true only when the
  accepted version and digest equal the current draft.  A newer edit keeps
  the draft, rebases it on the saved configuration, and stays dirty."
  [draft receipt submitted]
  (let [current-version (:version draft)
        submitted-version (:draft-version submitted)
        current-digest (connection/draft-digest draft)
        submitted-digest (:config-digest submitted)
        exact? (and (= current-version submitted-version)
                    (= current-digest submitted-digest))
        canonical (or (some-> (:connection receipt) connection/normalize-connection)
                      (some-> (:fields submitted) connection/normalize-connection))
        canonical (if (and canonical
                           (empty? (:models canonical))
                           (seq (get-in submitted [:fields :models])))
                    (let [models (vec (get-in submitted [:fields :models]))]
                      (assoc canonical
                             :models models
                             :manual-models
                             (vec (or (:manual-models submitted)
                                      (:manual-models canonical)))
                             :enabled-models
                             (vec (or (:enabled-models submitted)
                                      (keep (fn [model]
                                              (when-not (false? (:enabled? model))
                                                (str (or (:model-id model)
                                                         (:id model)
                                                         (:name model)))))
                                            models)))))
                    canonical)
        canonical (cond-> canonical
                    (and canonical (map? submitted))
                    (assoc :manual-models
                           (vec (or (:manual-models submitted)
                                    (:manual-models canonical)))
                           :enabled-models
                           (vec (or (:enabled-models submitted)
                                    (:enabled-models canonical)))))
        base-digest (if canonical
                      (connection/connection-digest canonical)
                      (or (:saved-config-digest receipt) submitted-digest))
        draft (cond-> draft
                (:connection-id receipt)
                (assoc :connection-id (:connection-id receipt)
                       :mode :edit)

                canonical
                (-> (update-saved-keys submitted canonical)
                    (assoc :saved-connection canonical
                           :models (vec (:models canonical))))

                true
                (assoc :base-digest base-digest
                       :saved-at (or (:saved-at receipt) (:at receipt))))]
    {:draft (cond-> draft
              (not exact?) (assoc :dirty? (dirty? draft)
                                  :status (if (dirty? draft)
                                            :saved-older
                                            :saved)))
     :clear? exact?}))

(defn- export-key
  [key]
  (cond-> (dissoc key :secret :secret-ref)
    (= :set (:auth-state key))
    (assoc :auth-state :unchanged :secret-omitted? true)))

(defn export-drafts
  "Return a serializable draft envelope for local persistence.

  Raw secrets and opaque secret references are never written to storage."
  [drafts]
  {:version 1
   :drafts (into {}
                 (map (fn [[draft-id draft]]
                        [draft-id (-> draft
                                      (update :keys #(mapv export-key %))
                                      (update :saved-connection #(when % (connection/client-safe %))))]))
                 drafts)})

(defn secret-leak?
  "True when VALUE still carries a raw secret key.

  This is a persistence guard, not a general redactor: it checks map keys,
  so the export's `:secret-omitted?` marker is not treated as a leak."
  [value]
  (cond
    (map? value)
    (boolean (some (fn [[key child]]
                     (or (contains? #{:secret :secret-ref} key)
                         (secret-leak? child)))
                   value))

    (or (sequential? value) (set? value))
    (boolean (some secret-leak? value))

    :else false))

(defn import-drafts
  "Read a draft envelope.  Unknown versions return an empty map."
  [payload]
  (if (= 1 (:version payload))
    (or (:drafts payload) {})
    {}))
