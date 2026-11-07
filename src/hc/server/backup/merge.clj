(ns hc.server.backup.merge
  "Pure merge/replace planning.

  No storage is touched here.  The planner reads a neutral local state and a
  decrypted backup payload, then produces the final category vectors and the
  audit data a preview can show.

  Conflict rules follow AGENTS.md §7.2-11..13 and §10.5:

  - identical source content is skipped;
  - the same ID with different content is a conflict, default keep both;
  - the same display name with different content is a conflict for
    name-identified categories, default keep both with a dated suffix;
  - memories never revive a local archived/deleted decision;
  - repeated imports are idempotent through deterministic copy IDs and
    provenance, even without an import ledger."
  (:require [clojure.string :as str]
            [hc.server.backup.error :as err]
            [hc.server.backup.model :as model]))

(def conflict-resolutions
  #{:keep-local :use-backup :keep-both})

(defn- normalize-resolution
  [x]
  (let [x (cond
            (keyword? x) x
            (string? x) (keyword x)
            :else x)]
    (when-not (contains? conflict-resolutions x)
      (err/fail! :BACKUP_CONFLICT_RESOLUTION_INVALID
                 "Unknown conflict resolution"
                 {:resolution x
                  :allowed conflict-resolutions}))
    x))

(defn normalize-resolutions
  "Accept `[category id]` or `\"category/id\"` keys."
  [resolutions]
  (reduce-kv (fn [m k v]
               (let [[category id]
                     (cond
                       (and (vector? k) (= 2 (count k)))
                       k

                       (string? k)
                       (let [i (str/index-of k "/")]
                         (when-not i
                           (err/fail! :BACKUP_CONFLICT_RESOLUTION_INVALID
                                      "Conflict key needs category/id"
                                      {:key k}))
                         [(keyword (subs k 0 i)) (subs k (inc i))])

                       :else
                       (err/fail! :BACKUP_CONFLICT_RESOLUTION_INVALID
                                  "Conflict key must be [category id] or \"category/id\""
                                  {:key k}))]
                 (when-not (model/category? category)
                   (err/fail! :BACKUP_CATEGORY_UNKNOWN
                              "Conflict key names an unknown category"
                              {:category category}))
                 (assoc m [category id] (normalize-resolution v))))
             {}
             (or resolutions {})))

(defn- resolution-for
  [resolutions category id]
  (get resolutions [category id] :keep-both))

(defn- safe-tool-permissions
  [source]
  (merge {:external-write? false
          :device-control? false
          :full-access? false
          :background? false
          :network? false
          :file-write? false}
         {:source (or source {})}))

(defn- has-secret?
  [item]
  (boolean (some model/secret-key? (keys item))))

(defn sanitize-item
  "Apply the no-implicit-authority policy to one imported entity."
  [category item {:keys [memory-active?]}]
  (let [item (assoc item :restored-from-backup? true)]
    (case category
      :schedules
      (assoc item
             :enabled? false
             :paused? true
             :next-run nil
             :restored-pending-review? true)

      :tools
      (assoc item
             :enabled? false
             :restored-pending-review? true
             :permissions (safe-tool-permissions (:permissions item))
             :connection {:status :unconnected
                          :checked-at nil
                          :duration-ms nil
                          :server-info nil
                          :protocol-version nil
                          :error nil}
             :auth {:state :none}
             :tools (into {}
                          (map (fn [[name tool]]
                                 [name (assoc tool
                                              :enabled? false
                                              :review-required? true)]))
                          (:tools item)))

      :skills
      (assoc item
             :enabled? false
             :installed? true
             :needs-review? true)

      :connections
      (cond-> (assoc item
                     :verified? false
                     :needs-verification? true)
        (has-secret? item) (assoc :secrets-present? true))

      :workspaces
      (let [external? (model/external? item)]
        (assoc item
               :write-enabled? false
               :authorized? (if external? false (:authorized? item false))
               :relink-required? (boolean external?)))

      :memories
      (let [status (:status item)
            status (cond
                     (= :deleted status) :deleted
                     (= :archived status) :archived
                     (= :rejected status) :rejected
                     (= :candidate status) :candidate
                     (= :active status) (if memory-active? :active :candidate)
                     memory-active? :active
                     :else :candidate)]
        (assoc item
               :status status
               :enabled? (boolean (and memory-active? (= :active status)))
               :pending-review? (not (and memory-active? (= :active status)))))

      :assistants
      (cond-> (assoc item :restored? true)
        (contains? item :permissions) (assoc :permissions {})
        (contains? item :full-access?) (assoc :full-access? false)
        (contains? item :auto-run?) (assoc :auto-run? false))

      :conversations
      (assoc item :restored? true)

      :worldbooks
      (assoc item :restored? true)

      :appearance
      item

      item)))

(defn- mark-imported
  [item backup-id source-id now]
  (assoc item :imported-from {:backup-id backup-id
                              :source-id source-id
                              :imported-at now}))

(defn- unique-id
  [items preferred]
  (loop [candidate preferred n 2]
    (if (some #(= candidate (:id %)) items)
      (recur (str preferred "-" n) (inc n))
      candidate)))

(defn- copy-item
  [category item items backup-id date now opts]
  (let [source-id (model/item-id item)
        preferred (model/copy-id source-id backup-id)
        id (unique-id items preferred)
        item (-> (sanitize-item category item
                                (assoc opts :memory-active? false))
                 (model/display-name-with-suffix date)
                 (assoc :id id :conflict :kept-both)
                 (mark-imported backup-id source-id now))]
    (if (= category :memories)
      (assoc item :enabled? false :pending-review? true)
      item)))

(defn- replace-by-id
  [items id f]
  (mapv (fn [item] (if (= id (:id item)) (f item) item)) items))

(defn- by-id
  [items]
  (into {} (map (juxt model/item-id identity)) items))

(defn- by-name
  [items]
  (->> items
       (keep (fn [item]
               (when-let [n (model/item-name item)]
                 [n item])))
       (group-by first)
       (map (fn [[n xs]] [n (mapv second xs)]))
       (into {})))

(defn- provenance-index
  [backup-id items]
  (into {}
        (keep (fn [item]
                (let [p (:imported-from item)]
                  (when (and (map? p)
                             (= backup-id (:backup-id p))
                             (string? (:source-id p)))
                    [(:source-id p) item]))))
        items))

(defn- existing-provenance?
  [index source-id]
  (contains? index source-id))

(defn- expired-skip?
  [item opts]
  (and (model/deleted? item)
       (not (:include-expired? opts))
       (model/expired-deleted? item (:now opts) (:retention-days opts 30))))

(defn- memory-disabled-import
  [category item local-item]
  (and (= category :memories)
       (model/memory-disabled? local-item)
       (not (model/memory-disabled? item))))

(defn- conflict-entry
  [category local-item imported-item reason resolution]
  (let [local (if (map? local-item)
                (:value (model/strip-secrets local-item false))
                local-item)
        imported (if (map? imported-item)
                   (:value (model/strip-secrets imported-item false))
                   imported-item)]
    {:category category
     :id (model/item-id local-item)
     :imported-id (model/item-id imported-item)
     :name (or (model/item-name imported-item) (model/item-name local-item))
     :reason reason
     :default-resolution :keep-both
     :resolution resolution
     :choices [:keep-local :use-backup :keep-both]
     :local local
     :imported imported}))

(defn- empty-summary
  [local-count imported-count]
  {:local local-count
   :imported imported-count
   :added 0
   :replaced 0
   :removed 0
   :skipped 0
   :conflicts 0
   :expired-skipped 0
   :memory-pending 0})

(defn- skip-acc
  [acc]
  (update-in acc [:summary :skipped] inc))

(defn- expired-acc
  [acc category iid deleted-at]
  (-> acc
      (update-in [:summary :expired-skipped] inc)
      (update :expired-skipped conj
              {:category category :id iid :deleted-at deleted-at})))

(defn- add-copy-acc
  [acc category imported items backup-id date now opts]
  (let [copy (copy-item category imported items backup-id date now opts)]
    [(-> acc
         (update :items conj copy)
         (update-in [:summary :added] inc))
     copy]))

(defn- add-imported-acc
  [acc category imported opts]
  (let [iid (model/item-id imported)
        memory-active? (or (not= category :memories)
                           (and (= :replace (:mode opts))
                                (:confirm-memory-reactivation? opts)
                                (not (model/memory-disabled? imported))))
        item (-> (sanitize-item category imported
                                (assoc opts :memory-active? memory-active?))
                 (mark-imported (:backup-id opts) iid (:now opts)))]
    (cond-> (-> acc
                (update :items conj item)
                (update-in [:summary :added] inc))
      (and (= category :memories) (not memory-active?))
      (-> (update-in [:summary :memory-pending] inc)
          (update :memory-pending conj
                  {:category category :id iid :reason :pending-review})))))

(defn- resolution-acc
  [acc category imported local items resolution reason replace-id opts]
  (let [backup-id (:backup-id opts)
        now (:now opts)
        date (:date opts)]
    (case resolution
      :keep-local
      (-> acc
          (skip-acc)
          (update :conflicts conj
                  (assoc (conflict-entry category local imported reason :keep-local)
                         :kept-id (:id local))))

      :use-backup
      (let [replacement (-> (sanitize-item category
                                           (assoc imported :id replace-id)
                                           opts)
                            (mark-imported backup-id (model/item-id imported) now))]
        (-> acc
            (assoc :items (replace-by-id items replace-id (constantly replacement)))
            (update-in [:summary :replaced] inc)
            (update :conflicts conj
                    (assoc (conflict-entry category local imported reason :use-backup)
                           :kept-id replace-id))))

      :keep-both
      (let [[acc copy] (add-copy-acc acc category imported items backup-id date now opts)]
        (-> acc
            (update-in [:summary :conflicts] inc)
            (update :conflicts conj
                    (assoc (conflict-entry category local imported reason :keep-both)
                           :kept-id (:id copy))))))))

(defn- merge-one
  [{:keys [items] :as acc} category imported opts local-index name-index provenance]
  (let [iid (model/item-id imported)
        iname (model/item-name imported)
        local (get local-index iid)
        candidates (when (and (get-in model/category-info [category :name-identified?])
                              iname)
                     (get name-index iname))
        resolution (resolution-for (:resolutions opts) category iid)]
    (cond
      (expired-skip? imported opts)
      (expired-acc acc category iid (:deleted-at imported))

      (existing-provenance? provenance iid)
      (skip-acc acc)

      local
      (cond
        (model/same-source? local imported)
        (skip-acc acc)

        (memory-disabled-import category imported local)
        (let [[acc copy] (add-copy-acc acc category imported items (:backup-id opts)
                                       (:date opts) (:now opts) opts)
              copy (assoc copy
                          :conflict :local-disabled-memory
                          :enabled? false
                          :pending-review? true)]
          (-> acc
              (update-in [:summary :memory-pending] inc)
              (update :memory-pending conj
                      {:category category :id iid :local-id iid
                       :reason :local-disabled})
              (update :conflicts conj
                      (assoc (conflict-entry category local imported
                                             :local-disabled-memory :keep-both)
                             :kept-id (:id copy)))))

        :else
        (resolution-acc acc category imported local items resolution
                        :same-id-different-content iid opts))

      (seq candidates)
      (if (some #(when (model/same-source? % imported) %) candidates)
        (skip-acc acc)
        (resolution-acc acc category imported (first candidates) items resolution
                        :same-name-different-content (:id (first candidates)) opts))

      :else
      (add-imported-acc acc category imported opts))))

(defn- plan-merge-category
  [category local-items imported-items opts]
  (let [local-index (by-id local-items)
        name-index (by-name local-items)
        provenance (provenance-index (:backup-id opts) local-items)
        initial {:items local-items
                 :conflicts []
                 :expired-skipped []
                 :memory-pending []
                 :summary (empty-summary (count local-items) (count imported-items))}]
    (reduce (fn [acc imported]
              (merge-one acc category imported opts local-index name-index provenance))
            initial
            imported-items)))

(defn- plan-replace-category
  [category local-items imported-items opts]
  (let [local-index (by-id local-items)
        initial {:items []
                 :conflicts []
                 :expired-skipped []
                 :memory-pending []
                 :summary (empty-summary (count local-items) (count imported-items))}
        result (reduce (fn [acc imported]
                         (let [iid (model/item-id imported)]
                           (if (expired-skip? imported opts)
                             (expired-acc acc category iid (:deleted-at imported))
                             (add-imported-acc acc category imported opts))))
                       initial
                       imported-items)
        ;; `:imported-from` records provenance, not the current run.  Keeping
        ;; the first import time for the same backup source makes a repeated
        ;; replace of an unchanged backup plan identical items, so it is a
        ;; `:no-changes` no-op instead of a re-apply with a new recovery
        ;; point (root AGENTS.md §7.2-13).
        result (update result :items
                       (fn [items]
                         (mapv (fn [item]
                                 (let [local (get local-index
                                                  (model/item-id item))
                                       imported (:imported-from item)
                                       previous (:imported-from local)]
                                   (if (and (map? imported)
                                            (map? previous)
                                            (= (:backup-id opts)
                                               (:backup-id imported))
                                            (= (:backup-id imported)
                                               (:backup-id previous))
                                            (= (:source-id imported)
                                               (:source-id previous)))
                                     (assoc item :imported-from
                                            (assoc imported
                                                   :imported-at
                                                   (:imported-at previous)))
                                     item)))
                               items)))
        imported-ids (set (keep model/item-id (:items result)))
        removed (count (remove #(contains? imported-ids (:id %)) local-items))]
    (assoc-in result [:summary :removed] removed)))

(defn- category-result
  [category local-items imported-items opts]
  (let [result (case (:mode opts)
                 :replace (plan-replace-category category local-items imported-items opts)
                 :merge (plan-merge-category category local-items imported-items opts)
                 (err/fail! :BACKUP_MODE_INVALID
                            "Restore mode must be :merge or :replace"
                            {:mode (:mode opts)}))]
    (assoc result :category category :local-items local-items :imported-items imported-items)))

(defn- placeholder-assistant
  [assistant-id original-name backup-id now]
  {:id assistant-id
   :name (or (not-empty original-name) "待配置的导入助手")
   :display-name (or (not-empty original-name) "待配置的导入助手")
   :source-note (if (not-empty original-name)
                  (str "Imported assistant " original-name
                       "; configure it before use.")
                  "Imported assistant; configure it before use.")
   :pending? true
   :needs-configuration? true
   :original-assistant-id assistant-id
   :restored? true
   :restored-from-backup? true
   :imported-from {:backup-id backup-id
                   :source-id assistant-id
                   :imported-at now}})

(defn- ensure-assistant-relation
  [working local payload opts]
  (let [backup-id (:backup-id opts)
        now (:now opts)
        conversations (get-in working [:conversations :items] [])
        assistants (or (get-in working [:assistants :items])
                       (model/items local :assistants))
        payload-assistants (into {}
                                 (keep (fn [a]
                                         (when-let [id (model/item-id a)]
                                           [id a])))
                                 (get-in payload [:categories :assistants :items] []))
        assistant-ids (set (keep model/item-id assistants))
        imported-conversation-ids (set (keep model/item-id
                                             (get-in payload [:categories :conversations :items] [])))
        relevant (filter #(contains? imported-conversation-ids (:id %)) conversations)
        needed (->> relevant
                    (keep :assistant-id)
                    distinct
                    (remove assistant-ids))]
    (if (empty? needed)
      [working []]
      (let [new-assistants (mapv (fn [id]
                                   (placeholder-assistant
                                    id (:name (get payload-assistants id))
                                    backup-id now))
                                 needed)
            all-assistants (into (vec assistants) new-assistants)
            placeholders (mapv (fn [a]
                                 {:category :assistants
                                  :id (:id a)
                                  :original-assistant-id (:original-assistant-id a)
                                  :reason :missing-assistant-relation})
                               new-assistants)]
        [(assoc-in working [:assistants :items] all-assistants)
         placeholders]))))

(defn- ensure-workspace-relation
  [working local payload]
  (if-not (contains? working :conversations)
    [working []]
    (let [workspaces (or (get-in working [:workspaces :items])
                         (model/items local :workspaces))
          workspace-ids (set (keep model/item-id workspaces))
          imported-conversation-ids (set (keep model/item-id
                                               (get-in payload [:categories :conversations :items] [])))
          conversations (get-in working [:conversations :items] [])
          missing (atom [])
          updated (mapv (fn [c]
                          (if (and (contains? imported-conversation-ids (:id c))
                                   (string? (:workspace-id c))
                                   (not (contains? workspace-ids (:workspace-id c))))
                            (do
                              (swap! missing conj
                                     {:category :conversations
                                      :id (:id c)
                                      :missing :workspace
                                      :workspace-id (:workspace-id c)})
                              (assoc c :workspace-relink-required? true))
                            c))
                        conversations)]
      [(assoc-in working [:conversations :items] updated) @missing])))

(defn- summarize
  [results]
  (let [sum (fn [k] (reduce + 0 (map #(get-in % [:summary k] 0) results)))]
    {:local (sum :local)
     :imported (sum :imported)
     :added (sum :added)
     :replaced (sum :replaced)
     :removed (sum :removed)
     :skipped (sum :skipped)
     :conflicts (sum :conflicts)
     :expired-skipped (sum :expired-skipped)
     :memory-pending (sum :memory-pending)}))

(defn- category-changed?
  [local-items final-items]
  (not= (vec local-items) (vec final-items)))

(defn- already-imported-plan
  [local selected payload opts]
  (let [category-summaries
        (into {}
              (map (fn [c]
                     (let [local-count (count (model/items local c))
                           imported-count (count (get-in payload [:categories c :items] []))]
                       [c {:items (model/items local c)
                           :summary (assoc (empty-summary local-count imported-count)
                                           :skipped imported-count)}])))
              selected)
        summary (reduce (fn [m [_ {:keys [summary]}]]
                          (merge-with + m summary))
                        (empty-summary 0 0)
                        category-summaries)]
    {:backup-id (:backup-id opts)
     :mode (:mode opts)
     :selected-categories selected
     :apply-categories {}
     :categories category-summaries
     :attachments nil
     :imports (:imports local)
     :permissions (model/safe-permission-map nil)
     :conflicts []
     :placeholders []
     :missing-relations []
     :relink-required []
     :needs-review []
     :memory-reactivation []
     :expired-skipped []
     :summary summary
     :no-changes? true
     :already-imported? true
     :retry-external? false
     :replace-all? false
     :memory-reactivation-required? false
     :warnings [{:reason :already-imported
                 :backup-id (:backup-id opts)}]}))

(defn build-plan
  "Build a complete, side-effect-free restore plan.

  `local` is a neutral state map; `payload` is the decrypted payload; `opts`
  must contain `:mode`, `:categories`, `:backup-id`, `:backup-digest`, `:now`,
  `:date`, `:retention-days`, `:resolutions`, `:include-expired?` and
  `:confirm-memory-reactivation?`."
  [local payload opts]
  (let [local (model/normalize-state local)
        selected (vec (:categories opts))
        _ (when (empty? selected)
            (err/fail! :BACKUP_CATEGORY_REQUIRED
                       "At least one category must be selected"
                       {}))
        missing (remove #(contains? (:categories payload) %) selected)]
    (when (seq missing)
      (err/fail! :BACKUP_CATEGORY_MISSING
                 "The backup does not contain every selected category"
                 {:categories (vec missing)
                  :available (vec (keys (:categories payload)))}))
    (let [ledger (get-in local [:imports (:backup-id opts)])
          digest-match? (and (string? (:backup-digest opts))
                             (= (:backup-digest opts) (:digest ledger)))
          ;; A ledger entry written before this flag existed belongs to a
          ;; completed import; only an explicit false asks for an external
          ;; retry.
          external-applied? (not (false? (:external-applied? ledger)))
          ;; A repeated replace of the same backup and the same categories
          ;; cannot add anything: the first import already stored exactly
          ;; that selection.  Requiring the recorded category set keeps a
          ;; later, wider replace from being skipped; a ledger written
          ;; before categories were recorded stays eligible only for merge.
          same-selection? (or (not= :replace (:mode opts))
                              (= (set selected)
                                 (set (or (:categories ledger) []))))
          already-imported? (and digest-match? external-applied?
                                 same-selection?)
          retry-external? (and digest-match? (not external-applied?))]
      (if already-imported?
        (already-imported-plan local selected payload opts)
        (let [results (mapv (fn [category]
                              (category-result category
                                               (model/items local category)
                                               (get-in payload [:categories category :items] [])
                                               opts))
                            selected)
              result-map (into {} (map (fn [r] [(:category r) r])) results)
              working (into {}
                            (map (fn [[category result]]
                                   [category {:items (:items result)}]))
                            result-map)
              [working placeholders] (ensure-assistant-relation
                                      working local payload opts)
              [working workspace-missing] (ensure-workspace-relation
                                           working local payload)
              apply-categories (into {}
                                     (keep (fn [[category {:keys [items]}]]
                                             (let [local-items (model/items local category)]
                                               (when (category-changed? local-items items)
                                                 [category items]))))
                                     working)
              resolved-missing? (fn [{:keys [missing assistant-id workspace-id]}]
                                  (case missing
                                    :assistant
                                    (some #(and (= assistant-id (:id %))
                                                (not (:needs-configuration? %)))
                                          (get-in working [:assistants :items] []))
                                    :workspace
                                    (some #(= workspace-id (:id %))
                                          (get-in working [:workspaces :items] []))
                                    false))
              final-missing (remove resolved-missing? (:missing-references payload))
              relink-required (vec
                               (concat
                                (for [w (get-in working [:workspaces :items] [])
                                      :when (:relink-required? w)]
                                  {:category :workspaces
                                   :id (:id w)
                                   :reason :relink-required})
                                (for [c (get-in working [:conversations :items] [])
                                      :when (:workspace-relink-required? c)]
                                  {:category :conversations
                                   :id (:id c)
                                   :reason :workspace-relink-required})))
              needs-review (vec
                            (for [category model/category-order
                                  item (get-in working [category :items] [])
                                  :when (or (:needs-review? item)
                                            (:needs-verification? item)
                                            (:restored-pending-review? item)
                                            (:pending-review? item)
                                            (:needs-configuration? item)
                                            (:missing? item))]
                              {:category category
                               :id (:id item)
                               :reason (cond
                                         (:needs-verification? item) :verify-connection
                                         (:needs-configuration? item) :configure-placeholder
                                         (:needs-review? item) :review-imported
                                         (:restored-pending-review? item) :enable-explicitly
                                         (:missing? item) :workspace-files-missing
                                         :else :memory-pending-review)}))
              conversations-changed? (contains? apply-categories :conversations)
              local-attachments (:attachments local)
              imported-attachments (or (:attachments payload) {})
              final-attachments
              (when conversations-changed?
                (case (:mode opts)
                  :replace imported-attachments
                  :merge (merge local-attachments imported-attachments)
                  nil))
              attachments-changed? (and conversations-changed?
                                        (not= local-attachments final-attachments))
              no-changes? (and (empty? apply-categories)
                               (not attachments-changed?))
              all-conflicts (vec (mapcat :conflicts results))
              all-expired (vec (mapcat :expired-skipped results))
              all-memory-pending (vec (mapcat :memory-pending results))
              summary (assoc (summarize results)
                             :conflicts (count all-conflicts)
                             :expired-skipped (count all-expired)
                             :memory-pending (count all-memory-pending)
                             :placeholders (count placeholders))
              imports (if no-changes?
                        (:imports local)
                        (assoc (:imports local) (:backup-id opts)
                               {:digest (:backup-digest opts)
                                :imported-at (:now opts)
                                :mode (:mode opts)
                                :categories (vec selected)
                                :summary summary
                                ;; The external files/catalog are applied
                                ;; after the storage transaction.  A crash in
                                ;; that window must be retryable, so the
                                ;; ledger records the storage part only.
                                :external-applied? false}))
              categories (into {}
                               (map (fn [[category {:keys [items]}]]
                                      [category {:items items
                                                 :summary (or (get-in result-map [category :summary])
                                                              (empty-summary (count (model/items local category))
                                                                             (count items)))}]))
                               working)
              replace-all? (and (= :replace (:mode opts))
                                (= (set selected) (set model/category-order)))
              memory-reactivation-required?
              (and (= :replace (:mode opts))
                   (contains? (set selected) :memories)
                   (not (:confirm-memory-reactivation? opts))
                   (some #(not (model/memory-disabled? %))
                         (get-in payload [:categories :memories :items] [])))
              warnings (cond-> []
                         (seq all-expired)
                         (conj {:reason :expired-deleted-skipped
                                :count (count all-expired)})
                         (seq all-memory-pending)
                         (conj {:reason :memory-pending-review
                                :count (count all-memory-pending)}))]
          {:backup-id (:backup-id opts)
           :mode (:mode opts)
           :no-prior-app-commands? (boolean (:no-prior-app-commands? opts))
           :selected-categories selected
           :apply-categories apply-categories
           :categories categories
           :attachments (when attachments-changed? final-attachments)
           :imports imports
           :permissions (model/safe-permission-map (:permissions payload))
           :conflicts all-conflicts
           :placeholders placeholders
           :missing-relations (vec (distinct (concat final-missing workspace-missing)))
           :relink-required relink-required
           :needs-review needs-review
           :memory-reactivation all-memory-pending
           :expired-skipped all-expired
           :summary summary
           :no-changes? no-changes?
           :already-imported? false
           :retry-external? retry-external?
           :replace-all? replace-all?
           :memory-reactivation-required? memory-reactivation-required?
           :warnings warnings})))))
