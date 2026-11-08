(ns hc.server.history.message
  "Versioned message history.

  A message owns an append-only vector of versions.  Each version carries the
  exact content, attachment references, citations, tool records, and usage
  that belong to that version.  Switching versions changes only the selected
  index; it never edits an earlier version and never starts a model request.

  The functions here are pure.  They return a new message or session and a
  small result map.  They never touch a blob, a run, or a provider."
  (:require [clojure.string :as str]
            [hc.server.history.attachments :as attachments]
            [hc.server.model-port.usage :as model-usage]))

(def message-roles #{:user :assistant :system})
(def version-sources #{:model :manual :regenerated :branch :imported})

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- error
  [code message details]
  {:ok? false
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- default-version-id
  [message-id index]
  (str message-id "-v" (inc index)))

(defn- version-index
  [message]
  (let [versions (:versions message)
        index (:current-version message)]
    (if (and (integer? index)
             (<= 0 index)
             (< index (count versions)))
      index
      0)))

(defn current-version
  [message]
  (get (:versions message) (version-index message)))

(defn version-at
  [message index]
  (when (and (integer? index) (<= 0 index) (< index (count (:versions message))))
    (get (:versions message) index)))

(defn version-by-id
  [message version-id]
  (first (filter #(= version-id (:version-id %)) (:versions message))))

(defn version-index-by-id
  [message version-id]
  (first (keep-indexed (fn [index version]
                         (when (= version-id (:version-id version)) index))
                       (:versions message))))

(defn content
  [message]
  (:content (current-version message)))

(defn normalize-version
  "Fill optional version fields from the message-level fallback.

  Existing versions are not rewritten; only missing keys are added."
  [message version]
  (let [version (if (map? version) version {})
        source (or (:source version)
                   (when (:edited? version) :manual)
                   :model)]
    (cond-> (assoc version
                   :version-id (or (:version-id version)
                                   (default-version-id (:message-id message)
                                                       (:current-version message)))
                   :content (or (:content version) "")
                   :attachments (or (:attachments version)
                                    (:attachments message)
                                    [])
                   :citations (or (:citations version)
                                  (:citations message)
                                  [])
                   :quotes (or (:quotes version)
                               (:quotes message)
                               [])
                   :tool-records (or (:tool-records version) [])
                   :source source
                   :edited? (boolean (:edited? version))
                   :manual-edit? (boolean (or (:manual-edit? version)
                                              (and (= :assistant (:role message))
                                                   (= :manual source)))))
      ;; Stored usage is a `model-port.usage` report; conversation surfaces
      ;; read the flat token+source projection.  The report itself stays
      ;; under `:usage`/`:sources`.
      (some? (:usage version))
      (assoc :usage (model-usage/flat-usage (:usage version)))
      (some? (:created-at version)) (assoc :created-at (:created-at version))
      (nil? (:created-at version)) (assoc :created-at (:created-at message)))))

(defn normalize-message
  "Return a message with complete version fields.

  Legacy messages without `:versions` get one version from their top-level
  content.  This is the only migration the history domain performs."
  [message]
  (let [message (if (map? message) message {})
        versions (cond
                   (and (vector? (:versions message))
                        (seq (:versions message)))
                   (:versions message)

                   (or (contains? message :content)
                       (contains? message :text)
                       (contains? message :body))
                   [{:version-id (default-version-id (:message-id message) 0)
                     :content (or (:content message)
                                  (:text message)
                                  (:body message)
                                  "")
                     :attachments (or (:attachments message) [])
                     :citations (or (:citations message) [])
                     :quotes (or (:quotes message) [])
                     :tool-records (or (:tool-records message) [])
                     :created-at (:created-at message)
                     :edited? false
                     :source :model}]

                   :else [])
        versions (mapv #(normalize-version message %) versions)
        index (let [index (:current-version message)]
                (if (and (integer? index)
                         (<= 0 index)
                         (< index (count versions)))
                  index
                  0))
        current (get versions index)]
    (-> message
        (assoc :versions versions
               :current-version index
               :attachments (or (:attachments current)
                                (:attachments message)
                                [])
               :citations (or (:citations current)
                              (:citations message)
                              [])
               :quotes (or (:quotes current)
                           (:quotes message)
                           [])
               :manual-edit? (boolean (or (:manual-edit? message)
                                          (some :manual-edit? versions)))))))

(defn new-message
  "Create one message with its first version.

  Options: `:attachments`, `:citations`, `:quotes`, `:tool-records`, `:usage`,
  `:version-id`, `:source`, `:now`."
  [message-id role text {:keys [attachments citations quotes tool-records usage
                                version-id source now] :as _opts}]
  (let [refs-result (attachments/normalize-refs attachments)]
    (if-not (:ok? refs-result)
      (assoc refs-result :message nil)
      (let [version-id (or version-id (default-version-id message-id 0))
            version (cond-> {:version-id version-id
                             :content (or text "")
                             :created-at now
                             :edited? false
                             :source (or source :model)
                             :attachments (:refs refs-result)
                             :citations (vec (or citations []))
                             :quotes (vec (or quotes []))
                             :tool-records (vec (or tool-records []))}
                      (some? usage) (assoc :usage usage)
                      (= :assistant role) (assoc :manual-edit? false))]
        {:ok? true
         :message {:message-id message-id
                   :role role
                   :versions [version]
                   :current-version 0
                   :attachments (:refs refs-result)
                   :citations (vec (or citations []))
                   :quotes (vec (or quotes []))
                   :created-at now
                   :updated-at now
                   :deleted? false
                   :folded? false
                   :deleted-at nil
                   :folded-at nil
                   :manual-edit? false}}))))

(defn message-view
  "Return one message with the selected version's full snapshot."
  [message]
  (let [message (normalize-message message)
        version (current-version message)]
    {:message-id (:message-id message)
     :role (:role message)
     :content (:content version)
     :attachments (vec (or (:attachments version) []))
     :citations (vec (or (:citations version) []))
     :quotes (vec (or (:quotes version) []))
     :tool-records (vec (or (:tool-records version) []))
     :usage (:usage version)
     :version-id (:version-id version)
     :version-count (count (:versions message))
     :current-version (:current-version message)
     :version-ids (mapv :version-id (:versions message))
     :edited? (> (count (:versions message)) 1)
     :manual-edit? (boolean (:manual-edit? version))
     :source (:source version)
     :nudge? (boolean (:nudge? message))
     :nudge-id (:nudge-id message)
     :run-id (:run-id message)
     :created-at (:created-at message)
     :updated-at (:updated-at message)}))

(defn message-history
  "Return every version of MESSAGE-ID, including superseded versions."
  [session message-id]
  (some-> (get-in session [:messages message-id]) normalize-message :versions))

(defn visible-messages
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (remove #(or (:deleted? %) (:folded? %)))
       (remove #(or (:nudge? %) (:nudge-id %)))
       (mapv message-view)))

(defn deleted-messages
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (filter :deleted?)
       (mapv message-view)))

(defn visible-message-ids
  [session]
  (->> (:message-order session)
       (keep #(get-in session [:messages %]))
       (remove #(or (:deleted? %) (:folded? %)))
       (remove #(or (:nudge? %) (:nudge-id %)))
       (mapv :message-id)))

(defn- unique-version-id?
  [message version-id]
  (not-any? #(= version-id (:version-id %)) (:versions message)))

(defn- version-result
  [message version index]
  {:ok? true
   :message message
   :version version
   :version-id (:version-id version)
   :version-index index
   :current-version index})

(defn- append-version*
  [message version now]
  (let [index (count (:versions message))
        message' (-> message
                     (update :versions conj version)
                     (assoc :current-version index
                            :updated-at now
                            :attachments (vec (or (:attachments version) []))
                            :citations (vec (or (:citations version) []))
                            :manual-edit? (boolean
                                           (or (:manual-edit? message)
                                               (:manual-edit? version)))))]
    (version-result message' version index)))

(defn edit-message
  "Append one manual edit as a new selected version.

  The previous versions stay byte-for-byte readable.  When `:attachments`,
  `:citations`, or `:tool-records` is omitted the current version's snapshot
  is inherited.  An omitted `:usage` is inherited and marked as inherited:
  manual text never claims a new provider usage record.

  Editing an assistant message marks the new version `:manual-edit? true`."
  [message {:keys [content attachments citations quotes tool-records usage version-id
                   now] :as opts}]
  (let [message (normalize-message message)
        current (current-version message)
        refs-result (attachments/normalize-refs
                     (if (contains? opts :attachments)
                       attachments
                       (:attachments current)))]
    (cond
      (not (string? content))
      (error :MESSAGE_CONTENT_INVALID
             "message content must be a string"
             {:message-id (:message-id message)})

      (not (:ok? refs-result))
      (assoc refs-result :message (:message message))

      :else
      (let [version-id (or version-id
                           (default-version-id (:message-id message)
                                               (count (:versions message))))]
        (if-not (unique-version-id? message version-id)
          (error :VERSION_ID_TAKEN
                 "version-id already exists on this message"
                 {:message-id (:message-id message) :version-id version-id})
          (let [inherited-usage? (not (contains? opts :usage))
                version (cond-> {:version-id version-id
                                 :content content
                                 :created-at now
                                 :edited? true
                                 :source :manual
                                 :parent-version-id (:version-id current)
                                 :attachments (:refs refs-result)
                                 :citations (vec (if (contains? opts :citations)
                                                   citations
                                                   (:citations current)))
                                 :quotes (vec (if (contains? opts :quotes)
                                                quotes
                                                (:quotes current)))
                                 :tool-records (vec (if (contains? opts :tool-records)
                                                      tool-records
                                                      (:tool-records current)))
                                 :manual-edit? (= :assistant (:role message))}
                          (contains? opts :usage)
                          (assoc :usage usage)

                          (and inherited-usage? (some? (:usage current)))
                          (assoc :usage (:usage current)
                                 :usage-inherited? true))]
            (append-version* message version now)))))))

(defn append-version
  "Append a model or regenerated version and select it.

  `:expected-version-id` guards against editing a version the caller did not
  see.  Regeneration is explicit: this function never starts a run.  The
  caller supplies the new content and its own provenance (citations, tool
  records, usage)."
  [message {:keys [content attachments citations quotes tool-records usage version-id
                   source expected-version-id now] :as opts}]
  (let [message (normalize-message message)
        current (current-version message)
        refs-result (attachments/normalize-refs attachments)
        source (or source :model)]
    (cond
      (not (string? content))
      (error :MESSAGE_CONTENT_INVALID
             "message content must be a string"
             {:message-id (:message-id message)})

      (not (contains? version-sources source))
      (error :VERSION_SOURCE_INVALID
             "version source must be one of the known history sources"
             {:source source})

      (and expected-version-id
           (not= expected-version-id (:version-id current)))
      (error :MESSAGE_VERSION_CONFLICT
             "the selected message version changed before this write"
             {:message-id (:message-id message)
              :expected-version-id expected-version-id
              :current-version-id (:version-id current)})

      (not (:ok? refs-result))
      (assoc refs-result :message (:message message))

      :else
      (let [version-id (or version-id
                           (default-version-id (:message-id message)
                                               (count (:versions message))))]
        (if-not (unique-version-id? message version-id)
          (error :VERSION_ID_TAKEN
                 "version-id already exists on this message"
                 {:message-id (:message-id message) :version-id version-id})
          (let [version (cond-> {:version-id version-id
                                 :content content
                                 :created-at now
                                 :edited? (= :regenerated source)
                                 :source source
                                 :parent-version-id (:version-id current)
                                 :attachments (:refs refs-result)
                                 :citations (vec (or citations []))
                                 :quotes (vec (or quotes []))
                                 :tool-records (vec (or tool-records []))
                                 :manual-edit? false}
                          (some? usage) (assoc :usage usage)
                          (:regenerated-from-version-id opts)
                          (assoc :regenerated-from-version-id
                                 (:regenerated-from-version-id opts)))]
            (append-version* message version now)))))))

(defn select-version
  "Select one existing version.  Selection is not content activity and never
  starts a model request."
  [message {:keys [version-id index now]}]
  (let [message (normalize-message message)
        index (or (when (some? version-id) (version-index-by-id message version-id))
                  index)
        version (version-at message index)]
    (cond
      (nil? version)
      (error :MESSAGE_VERSION_NOT_FOUND
             "message version does not exist"
             {:message-id (:message-id message)
              :version-id version-id
              :index index})

      :else
      (let [message' (-> message
                         (assoc :current-version index
                                :updated-at now
                                :attachments (vec (or (:attachments version) []))
                                :citations (vec (or (:citations version) []))
                                :manual-edit? (boolean (:manual-edit? version))))
            changed? (not= (:current-version message) index)]
        {:ok? true
         :message message'
         :version version
         :version-id (:version-id version)
         :version-index index
         :current-version index
         :changed? changed?
         :activity? false}))))

(defn check-invariants
  "Return message and version invariant violations."
  [message]
  (let [message (if (map? message) message {})
        versions (:versions message)
        current (:current-version message)
        base
        (cond-> []
          (not (contains? message-roles (:role message)))
          (conj {:invariant :message-role-invalid
                 :message-id (:message-id message)
                 :role (:role message)})

          (not (and (vector? versions) (seq versions)))
          (conj {:invariant :message-versions-invalid
                 :message-id (:message-id message)})

          (and (vector? versions)
               (not (and (integer? current)
                         (<= 0 current)
                         (< current (count versions)))))
          (conj {:invariant :message-current-version-invalid
                 :message-id (:message-id message)
                 :current-version current})

          (and (vector? versions)
               (not= (count versions) (count (set (map :version-id versions)))))
          (conj {:invariant :message-version-ids-not-unique
                 :message-id (:message-id message)}))]
    (into base
          (mapcat
           (fn [version]
             (let [refs-result (attachments/normalize-refs (:attachments version))
                   refs (when (:ok? refs-result) (:refs refs-result))]
               (cond-> []
                 (not (non-blank-string? (:version-id version)))
                 (conj {:invariant :version-id-invalid
                        :message-id (:message-id message)
                        :version-id (:version-id version)})

                 (not (string? (:content version)))
                 (conj {:invariant :version-content-invalid
                        :message-id (:message-id message)
                        :version-id (:version-id version)})

                 (not (:ok? refs-result))
                 (conj {:invariant :version-attachments-invalid
                        :message-id (:message-id message)
                        :version-id (:version-id version)
                        :error (:error refs-result)})

                 (and refs (not= (count refs) (count (set (map :attachment-id refs)))))
                 (conj {:invariant :version-attachments-duplicate
                        :message-id (:message-id message)
                        :version-id (:version-id version)})

                 (not (contains? version-sources (:source version)))
                 (conj {:invariant :version-source-invalid
                        :message-id (:message-id message)
                        :version-id (:version-id version)
                        :source (:source version)})

                 (not (vector? (:citations version)))
                 (conj {:invariant :version-citations-invalid
                        :message-id (:message-id message)
                        :version-id (:version-id version)})

                 (not (vector? (:quotes version)))
                 (conj {:invariant :version-quotes-invalid
                        :message-id (:message-id message)
                        :version-id (:version-id version)})

                 (not (vector? (:tool-records version)))
                 (conj {:invariant :version-tool-records-invalid
                        :message-id (:message-id message)
                        :version-id (:version-id version)}))))
           (or versions [])))))
