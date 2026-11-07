(ns hc.server.sessions.drafts
  "Per-session draft state.

  Authority: AGENTS.md §7.4 旅程 2.1（写草稿）.  A draft belongs to one session and one
  client.  It keeps text, pending attachments, citations, and the cursor
  position.  A draft is reported saved only after the Owner confirms that
  the enclosing session commit succeeded.

  The state is plain EDN:

    {:client-id \"window-1\"
     :text \"...\"
     :attachments [{:attachment-id \"a1\" :size 10}]
     :citations [{:url \"...\"}]
     :cursor 12
     :revision 3
     :persisted-revision 2
     :save-status :pending
     :updated-at 1700000000000
     :error nil}

  `:revision` advances on every accepted local change.  `:persisted-revision`
  advances only after a persistence acknowledgement.  `saved?` is derived,
  never stored independently, so a failed save cannot masquerade as success."
  (:require [clojure.string :as str]))

(def max-attachments
  "A single draft supports at most this many attachments."
  20)

(def max-attachment-bytes
  "A single attachment may be at most 100 MiB."
  (* 100 1024 1024))

(def save-statuses
  #{:saved :pending :error})

(defn normalize-client-id
  "Return a non-blank client ID.  A missing ID uses the single-client key
  \"default\"; multi-window clients should always send their own stable ID."
  [client-id]
  (if (and (string? client-id) (not (str/blank? client-id)))
    client-id
    "default"))

(defn new-draft
  [client-id now]
  {:client-id (normalize-client-id client-id)
   :text ""
   :attachments []
   :citations []
   :cursor 0
   :revision 0
   :persisted-revision 0
   :save-status :saved
   :updated-at now
   :error nil})

(defn saved?
  "True only when the latest revision has a matching persistence
  acknowledgement and no later save failure."
  [draft]
  (and (= (:revision draft) (:persisted-revision draft))
       (= :saved (:save-status draft))))

(defn dirty?
  [draft]
  (not= (:revision draft) (:persisted-revision draft)))

(defn blank?
  "True when the draft has no sendable content.  Cursor position alone does
  not make a draft non-blank."
  [draft]
  (and (str/blank? (str (:text draft)))
       (empty? (:attachments draft))
       (empty? (:citations draft))))

(defn public-draft
  "Return the draft with its derived save state."
  [draft]
  (assoc draft :saved? (saved? draft)))

(defn- error
  [draft code message details]
  {:ok? false
   :draft draft
   :error (cond-> {:code code :message message}
            (some? details) (assoc :details details))})

(defn- valid-attachment?
  [attachment]
  (and (map? attachment)
       (or (and (string? (:attachment-id attachment))
                (not (str/blank? (:attachment-id attachment))))
           (and (string? (:blob-id attachment))
                (not (str/blank? (:blob-id attachment)))))
       (or (nil? (:size attachment))
           (and (integer? (:size attachment))
                (not (neg? (:size attachment)))))))

(defn- attachment-error
  [draft attachments]
  (let [invalid (remove valid-attachment? attachments)
        too-large (filter #(and (integer? (:size %))
                                (> (:size %) max-attachment-bytes))
                          attachments)]
    (cond
      (not (vector? attachments))
      (error draft :DRAFT_ATTACHMENTS_INVALID
             "attachments must be a vector" {:attachments attachments})

      (> (count attachments) max-attachments)
      (error draft :ATTACHMENT_LIMIT_EXCEEDED
             (str "a draft supports at most " max-attachments " attachments")
             {:count (count attachments) :limit max-attachments})

      (seq too-large)
      (error draft :ATTACHMENT_TOO_LARGE
             (str "a single attachment may be at most " max-attachment-bytes " bytes")
             {:limit max-attachment-bytes
              :attachments (mapv #(select-keys % [:attachment-id :blob-id :size])
                                 too-large)})

      (seq invalid)
      (error draft :ATTACHMENT_INVALID
             "each attachment needs a non-blank attachment-id or blob-id"
             {:attachments invalid})

      :else nil)))

(defn update-draft
  "Apply one local draft change.

  CHANGES may contain `:text`, `:attachments`, `:citations`, `:cursor`, and
  `:expected-revision`.  `:expected-revision` is a guard, not stored.  A
  mismatch returns `:DRAFT_REVISION_CONFLICT` and leaves the draft untouched.
  An accepted change increments `:revision` and sets `:save-status :pending`;
  it never claims the new revision is saved."
  [draft changes now]
  (let [draft (or draft (new-draft (:client-id changes) now))
        changes (or changes {})
        expected (:expected-revision changes)
        text (if (contains? changes :text) (:text changes) (:text draft))
        attachments (if (contains? changes :attachments)
                      (:attachments changes)
                      (:attachments draft))
        citations (if (contains? changes :citations)
                    (:citations changes)
                    (:citations draft))
        cursor (if (contains? changes :cursor) (:cursor changes) (:cursor draft))]
    (cond
      (and (some? expected) (not= expected (:revision draft)))
      (error draft :DRAFT_REVISION_CONFLICT
             "draft was changed by a newer revision"
             {:expected-revision expected
              :current-revision (:revision draft)
              :current-draft (public-draft draft)})

      (not (or (nil? text) (string? text)))
      (error draft :DRAFT_TEXT_INVALID "draft text must be a string or nil"
             {:text text})

      (not (or (nil? attachments) (vector? attachments)))
      (error draft :DRAFT_ATTACHMENTS_INVALID "attachments must be a vector"
             {:attachments attachments})

      (not (or (nil? citations) (vector? citations)))
      (error draft :DRAFT_CITATIONS_INVALID "citations must be a vector"
             {:citations citations})

      (not (and (integer? cursor) (not (neg? cursor))))
      (error draft :DRAFT_CURSOR_INVALID
             "cursor must be a non-negative integer" {:cursor cursor})

      :else
      (let [attachments (or attachments [])
            attachment-error (attachment-error draft attachments)]
        (if attachment-error
          attachment-error
          {:ok? true
           :draft (-> draft
                      (assoc :text (or text "")
                             :attachments attachments
                             :citations (or citations [])
                             :cursor cursor
                             :save-status :pending
                             :error nil
                             :updated-at now)
                      (update :revision inc))})))))

(defn mark-saved
  "Acknowledge that REVISION was persisted.

  An older acknowledgement is stale and changes nothing.  A future revision
  cannot be acknowledged.  Only the matching current revision becomes saved."
  [draft revision now]
  (cond
    (not (integer? revision))
    (error draft :DRAFT_ACK_INVALID "saved revision must be an integer"
           {:revision revision})

    (= revision (:revision draft))
    {:ok? true
     :stale? false
     :draft (-> draft
                (assoc :persisted-revision revision
                       :save-status :saved
                       :error nil
                       :updated-at now))}

    (< revision (:revision draft))
    {:ok? true
     :stale? true
     :draft draft
     :note :older-revision-already-superseded}

    :else
    (error draft :DRAFT_ACK_FUTURE
           "cannot acknowledge a revision the client has not produced"
           {:revision revision
            :current-revision (:revision draft)})))

(defn mark-failed
  "Record that REVISION could not be persisted.

  The draft keeps its text and stays visibly unsaved.  An older failure is
  stale; a newer pending revision must not be marked failed by an old error."
  [draft revision failure now]
  (cond
    (not (integer? revision))
    (error draft :DRAFT_ACK_INVALID "failed revision must be an integer"
           {:revision revision})

    (= revision (:revision draft))
    {:ok? true
     :stale? false
     :draft (-> draft
                (assoc :save-status :error
                       :error failure
                       :updated-at now))}

    (< revision (:revision draft))
    {:ok? true
     :stale? true
     :draft draft
     :note :older-revision-already-superseded}

    :else
    (error draft :DRAFT_ACK_FUTURE
           "cannot fail a revision the client has not produced"
           {:revision revision
            :current-revision (:revision draft)})))

(defn clear-after-send
  "Clear the sendable content after a message was accepted.

  The cursor returns to zero.  The draft remains unsaved until the enclosing
  session commit is acknowledged."
  [draft now]
  (update-draft draft
                {:text ""
                 :attachments []
                 :citations []
                 :cursor 0}
                now))

(defn check-invariants
  "Return a vector of draft invariant violations."
  [draft]
  (cond-> []
    (not (map? draft))
    (conj {:invariant :draft-not-a-map})

    (and (map? draft)
         (not (string? (:client-id draft))))
    (conj {:invariant :draft-client-id-invalid
           :client-id (:client-id draft)})

    (and (map? draft)
         (not (integer? (:revision draft))))
    (conj {:invariant :draft-revision-invalid
           :revision (:revision draft)})

    (and (map? draft)
         (not (integer? (:persisted-revision draft))))
    (conj {:invariant :draft-persisted-revision-invalid
           :persisted-revision (:persisted-revision draft)})

    (and (map? draft)
         (integer? (:revision draft))
         (integer? (:persisted-revision draft))
         (> (:persisted-revision draft) (:revision draft)))
    (conj {:invariant :draft-persisted-ahead-of-revision
           :revision (:revision draft)
           :persisted-revision (:persisted-revision draft)})

    (and (map? draft)
         (not (contains? save-statuses (:save-status draft))))
    (conj {:invariant :draft-save-status-invalid
           :save-status (:save-status draft)})

    (and (map? draft)
         (= :saved (:save-status draft))
         (not (saved? draft)))
    (conj {:invariant :draft-saved-status-without-ack
           :revision (:revision draft)
           :persisted-revision (:persisted-revision draft)})))
