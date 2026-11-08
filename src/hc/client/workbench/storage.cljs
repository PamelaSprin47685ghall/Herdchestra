(ns hc.client.workbench.storage
  "Browser-local draft and appearance persistence.

  Drafts are the operator's unsent work, not server state.  The store keeps
  only serializable fields and never DOM nodes, editor instances, or component
  handles.  When localStorage is unavailable the workbench must say so; it must
  not claim the draft was saved."
  (:require [hc.client.conversation.draft :as draft]))

(def storage-key "hc.workbench.v1")

(def ^:private settings-keys
  [:theme :font-scale :contrast :reduced-motion
   :language :background :bubble])

(defonce storage-status
  (atom :unknown))

(defn available?
  []
  (try
    (let [probe (str storage-key ".probe")]
      (.setItem js/localStorage probe "1")
      (.removeItem js/localStorage probe)
      true)
    (catch :default _
      false)))

(defn- read-payload
  []
  (try
    (when-let [raw (.getItem js/localStorage storage-key)]
      (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
        (when (map? parsed) parsed)))
    (catch :default e
      (reset! storage-status :unavailable)
      nil)))

(defn load!
  "Return `{:drafts {session-id draft} :settings {...} :status ...}`."
  []
  (if-not (available?)
    (do (reset! storage-status :unavailable)
        {:drafts {} :settings {} :status :unavailable})
    (let [payload (read-payload)]
      (reset! storage-status :available)
      {:drafts (draft/restore-all (:drafts payload))
       :scroll (into {}
                     (keep (fn [[session-id top]]
                             (when (number? top)
                               [(str session-id) (max 0 (long top))])))
                     (:scroll payload))
       :settings (select-keys (:settings payload) settings-keys)
       :status :available})))

(defn- serializable-draft
  [d]
  (-> d
      (select-keys [:text :cursor :selection :attachments :quotes
                    :version :saved-version])
      (assoc :composing? false
             :save-state :saved)))

(def ^:private signature-fields
  "Persisted draft fields whose change must schedule a save.

  Derived acknowledgement fields (`:saved-version`, `:save-state`) and
  transient fields (`:composing?`, `:undo`, `:updated-at`) are excluded:
  `mark-saved!` changes them after a successful save and must not schedule
  another one."
  [:text :cursor :selection :attachments :quotes :version])

(defn draft-signature
  "A stable value that changes whenever any persisted draft field changes.

  The workbench compares this before scheduling a save.  Cursor-only,
  attachment, and quote changes therefore persist even when the text version
  is stable."
  [drafts]
  (->> (or drafts {})
       (map (fn [[session-id d]]
              [session-id (select-keys d signature-fields)]))
       (sort-by first)
       vec))

(defn save!
  "Persist drafts and settings.  Returns `{:ok? bool :reason keyword}`."
  ([drafts settings] (save! drafts settings {}))
  ([drafts settings scroll]
   (if-not (available?)
     (do (reset! storage-status :unavailable)
         {:ok? false :reason :storage-unavailable})
     (try
       (let [payload {:version 1
                      :drafts (into {}
                                    (map (fn [[session-id d]]
                                           [session-id (serializable-draft d)]))
                                    (or drafts {}))
                      :scroll (into {}
                                    (keep (fn [[session-id top]]
                                            (when (number? top)
                                              [(str session-id) (max 0 (long top))])))
                                    (or scroll {}))
                      :settings (select-keys settings settings-keys)
                      :saved-at (str (js/Date.))}]
         (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js payload)))
         (reset! storage-status :available)
         {:ok? true})
       (catch :default e
         (reset! storage-status :unavailable)
         {:ok? false :reason :storage-unavailable :error (str e)})))))

(defn save-drafts!
  "Persist drafts and settings, returning the same shape as `save!`."
  ([drafts settings] (save! drafts settings))
  ([drafts settings scroll] (save! drafts settings scroll)))

(defn clear!
  []
  (try
    (.removeItem js/localStorage storage-key)
    {:ok? true}
    (catch :default e
      {:ok? false :error (str e)})))
