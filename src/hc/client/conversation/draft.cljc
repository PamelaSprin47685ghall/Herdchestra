(ns hc.client.conversation.draft
  "Per-session composer drafts for the conversation surface.

  A draft is the browser-local answer to one question: what did this operator
  type in this session, and how much of it has the browser admitted to save?
  Drafts never contain DOM nodes, editor instances, or server message state.

  The version discipline is the important part.  Every text-changing edit
  increments `:version`.  A send captures that version in its command.  When
  the command is admitted, `clear-submitted` clears the draft only when the
  version still matches.  Text typed while a send is in flight therefore
  survives, and a rejected send can restore the exact admitted snapshot
  without overwriting newer work.

  This namespace is pure CLJC.  It has no I/O and no knowledge of the
  transport or of Fulcro."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Text and attachment predicates

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(def ^:private invisible-pattern
  "Whitespace plus the common zero-width and bidi control characters.  These
  characters must not make an otherwise empty draft sendable."
  #"[\s\u00A0\u00AD\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]+")

(defn meaningful-text?
  "True when `text` contains at least one visible, non-whitespace character."
  [text]
  (boolean (and (string? text)
                (not (str/blank? text))
                (not (re-matches invisible-pattern text)))))

(defn attachment-ready?
  "An attachment may be sent when it has reached a ready state.  A caller may
  mark a failed attachment `:excluded? true` after the operator explicitly
  excludes it."
  [attachment]
  (and (not (:excluded? attachment))
       (or (true? (:ready? attachment))
           (contains? #{:ready :ready-to-send :complete :completed}
                      (keywordize (:status attachment))))))

(defn attachment-blocking?
  "True for attachments that still need work or an explicit exclusion."
  [attachment]
  (and (not (:excluded? attachment))
       (not (attachment-ready? attachment))))

(defn blocking-attachments
  "Return the attachments that prevent sending."
  [draft]
  (filterv attachment-blocking? (:attachments draft)))

(defn active-attachments
  "Attachments that belong in the next send.  Explicitly excluded failed
  items stay visible in the draft but do not travel with the command."
  [draft]
  (filterv #(not (:excluded? %)) (:attachments draft)))

(defn blank?
  "True when the draft has no text, no attachments, and no quote cards."
  [draft]
  (and (not (meaningful-text? (:text draft)))
       (empty? (:attachments draft))
       (empty? (:quotes draft))))

(defn sendable?
  "True when the draft contains something worth submitting and every
  non-excluded attachment is ready."
  [draft]
  (and (or (meaningful-text? (:text draft))
           (seq (active-attachments draft))
           (seq (:quotes draft)))
       (empty? (blocking-attachments draft))))

;; ---------------------------------------------------------------------------
;; Draft shape

(def empty-draft
  "A fresh draft.  `:version` and `:saved-version` advance together; the gap
  between them is the unsaved region."
  {:text ""
   :cursor 0
   :selection {:start 0 :end 0}
   :attachments []
   :quotes []
   :version 0
   :saved-version 0
   :save-state :saved
   :save-error nil
   :composing? false
   :updated-at nil
   :restored? false
   :undo nil
   :rejected-submission nil})

(defn draft
  "Return the draft for `session-id`, or an empty draft.

  Accepts either the drafts map itself or the reducer-shaped
  `{:drafts {...}}` wrapper.  The wrapper tolerance keeps older workbench
  call sites from silently reading an empty draft; session IDs are strings,
  so a real `:drafts` entry cannot be mistaken for one."
  [drafts session-id]
  (let [drafts (if (and (map? drafts)
                        (contains? drafts :drafts)
                        (not (contains? drafts session-id)))
                 (:drafts drafts)
                 drafts)]
    (merge empty-draft (get drafts session-id))))

(defn- clamp
  [n low high]
  (max low (min high n)))

(defn normalize-selection
  "Clamp a selection to `text`.  The selection stays an ordered pair; the
  optional `:direction` is preserved for shift-selection behaviour."
  [selection text]
  (let [n (count text)
        start (clamp (or (:start selection) 0) 0 n)
        end (clamp (or (:end selection) start) 0 n)]
    (cond-> {:start (min start end) :end (max start end)}
      (:direction selection) (assoc :direction (:direction selection)))))

(defn- normalize-draft
  [draft]
  (let [text (if (string? (:text draft)) (:text draft) "")
        cursor (clamp (or (:cursor draft) (count text)) 0 (count text))
        selection (normalize-selection (or (:selection draft)
                                           {:start cursor :end cursor})
                                       text)
        version (max 0 (long (or (:version draft) 0)))
        saved-version (max 0 (long (or (:saved-version draft) version)))]
    (-> (merge empty-draft draft)
        (assoc :text text
               :cursor cursor
               :selection selection
               :version version
               :saved-version saved-version
               :attachments (vec (or (:attachments draft) []))
               :quotes (vec (or (:quotes draft) []))
               :composing? (boolean (:composing? draft))))))

(defn restore
  "Sanitize one persisted draft.  Composition state cannot survive a restart;
  it is cleared.  A draft whose saved version lags its version is unsaved, not
  silently declared saved."
  [draft]
  (let [draft (-> draft
                  normalize-draft
                  (assoc :composing? false
                         :restored? true))]
    (if (< (:saved-version draft) (:version draft))
      (assoc draft :save-state :unsaved :save-error nil)
      (assoc draft :save-state :saved :save-error nil))))

(defn restore-all
  "Sanitize a persisted `{session-id draft}` map.

  JSON persistence reads its object keys through `js->clj` with
  `:keywordize-keys true`, so a stored session id can come back as a keyword.
  The runtime addresses sessions by string; normalize the key instead of
  dropping the draft.  Dropping it silently loses every restored draft while
  the stored blob still looks healthy."
  [drafts]
  (into {}
        (keep (fn [[session-id draft]]
                (let [session-id (cond
                                   (string? session-id) session-id
                                   ;; `str` keeps a namespaced keyword's full
                                   ;; text; `name` would drop its prefix.
                                   (keyword? session-id) (subs (str session-id) 1)
                                   :else nil)]
                  (when (and (string? session-id)
                             (not (str/blank? session-id))
                             (map? draft))
                    [session-id (restore draft)]))))
        (or drafts {})))

;; ---------------------------------------------------------------------------
;; Editing

(defn edit
  "Apply one input edit.

  `change` may carry `:text`, `:cursor`, `:selection`, `:composing?`, and
  `:at`.  Only a changed text bumps `:version` and marks the draft unsaved.
  Cursor-only and selection-only changes keep the version stable, so moving the
  caret cannot defeat the submitted-version guard."
  ([draft change]
   (edit draft change {}))
  ([draft {:keys [text cursor selection composing? at] :as change} _options]
   (let [text (if (string? text) text (:text draft))
         changed? (not= text (:text draft))
         cursor (if (number? cursor)
                  cursor
                  (if changed? (count text) (:cursor draft)))
         selection (normalize-selection
                    (or selection
                        (when (and (not (contains? (or change {}) :selection))
                                   (= cursor (:cursor draft)))
                          (:selection draft))
                        {:start cursor :end cursor})
                    text)]
     (cond-> (assoc draft
                    :text text
                    :cursor cursor
                    :selection selection
                    :composing? (boolean
                                 (if (contains? (or change {}) :composing?)
                                   composing?
                                   (:composing? draft))))
       changed? (assoc :version (inc (:version draft))
                       :save-state :unsaved
                       :save-error nil
                       :updated-at at)))))

(defn set-composing
  "Track IME composition without touching the text version."
  [draft composing?]
  (assoc draft :composing? (boolean composing?)))

(defn set-cursor
  "Move the caret and optionally the selection."
  [draft cursor selection]
  (let [cursor (clamp (or cursor 0) 0 (count (:text draft)))]
    (assoc draft
           :cursor cursor
           :selection (normalize-selection
                       (or selection {:start cursor :end cursor})
                       (:text draft)))))

(defn set-save-state
  "Record persistence feedback.

  `:save-state` is one of `:saved`, `:saving`, `:unsaved`, `:failed`, or
  `:storage-unavailable`.  Only a successful save advances
  `:saved-version`."
  [draft save-state {:keys [saved-version error]}]
  (let [saved-version (cond
                        (integer? saved-version) saved-version
                        (= :saved save-state) (:version draft)
                        :else (:saved-version draft))]
    (assoc draft
           :save-state save-state
           :saved-version (max (:saved-version draft) saved-version)
           :save-error (when (contains? #{:failed :storage-unavailable} save-state)
                         error))))

(defn set-attachments
  [draft attachments]
  (let [attachments (vec attachments)]
    (if (= attachments (:attachments draft))
      draft
      (-> draft
          (assoc :attachments attachments)
          (update :version inc)
          (assoc :save-state :unsaved :save-error nil)))))

(defn add-attachment
  [draft attachment]
  (-> draft
      (update :attachments conj attachment)
      (update :version inc)
      (assoc :save-state :unsaved :save-error nil)))

(defn remove-attachment
  [draft attachment-id]
  (let [attachments (filterv #(not= attachment-id (:id %)) (:attachments draft))]
    (if (= attachments (:attachments draft))
      draft
      (-> draft
          (assoc :attachments attachments)
          (update :version inc)
          (assoc :save-state :unsaved :save-error nil)))))

(defn set-quotes
  [draft quotes]
  (let [quotes (vec quotes)]
    (if (= quotes (:quotes draft))
      draft
      (-> draft
          (assoc :quotes quotes)
          (update :version inc)
          (assoc :save-state :unsaved :save-error nil)))))

(defn add-quote
  [draft quote]
  (-> draft
      (update :quotes conj quote)
      (update :version inc)
      (assoc :save-state :unsaved :save-error nil)))

(defn remove-quote
  [draft quote-id]
  (let [quotes (filterv #(not= quote-id (:id %)) (:quotes draft))]
    (if (= quotes (:quotes draft))
      draft
      (-> draft
          (assoc :quotes quotes)
          (update :version inc)
          (assoc :save-state :unsaved :save-error nil)))))

;; ---------------------------------------------------------------------------
;; Submission and recovery

(defn snapshot
  "Capture the exact content a send command carries."
  [draft]
  (assoc (select-keys draft [:text :cursor :selection :quotes :version
                             :turn-card-ids :instruction-values
                             :temporary-read? :turn-selection-version])
         :attachments (active-attachments draft)))

(defn clear-submitted
  "Clear the draft only when `submitted-version` is still current.

  If the operator typed while the command was in flight, the newer draft stays
  and records which version was admitted.  The command already carries the
  older snapshot, so neither copy is lost."
  [draft submitted-version]
  (if (= (:version draft) submitted-version)
    (-> empty-draft
        (assoc :version (inc submitted-version)
               :saved-version (inc submitted-version)
               :save-state :saved
               :updated-at (:updated-at draft)))
    (assoc draft :submitted-version submitted-version)))

(defn restore-submission
  "Put a rejected submission back into a blank draft.

  When the draft already contains newer work, keep that work and retain the
  rejected snapshot under `:rejected-submission` for an explicit restore
  action.  Never overwrite newer text silently."
  [draft snapshot]
  (if (blank? draft)
    (merge empty-draft
           snapshot
           {:version (inc (max (:version draft) (long (or (:version snapshot) 0))))
            :saved-version (:saved-version draft)
            :save-state :unsaved
            :restored? true})
    (assoc draft :rejected-submission snapshot)))

(defn accept-rejected
  "Explicitly restore a rejected submission, replacing the current draft."
  [draft]
  (if-let [snapshot (:rejected-submission draft)]
    (-> draft
        (merge (dissoc snapshot :version :saved-version))
        (assoc :version (inc (:version draft))
               :save-state :unsaved)
        (dissoc :rejected-submission))
    draft))

(defn clear
  "Clear the draft and keep one undo snapshot.  A blank draft is left alone."
  [draft at]
  (if (blank? draft)
    draft
    (assoc draft
           :undo {:draft (dissoc draft :undo) :at at}
           :text ""
           :cursor 0
           :selection {:start 0 :end 0}
           :attachments []
           :quotes []
           :version (inc (:version draft))
           :save-state :unsaved
           :composing? false
           :updated-at at)))

(defn undo-clear
  "Restore the last cleared draft, if any."
  [draft]
  (if-let [undo (:undo draft)]
    (-> (merge draft (:draft undo))
        (assoc :version (inc (:version draft))
               :save-state :unsaved
               :undo nil)
        (dissoc :submitted-version))
    draft))

;; ---------------------------------------------------------------------------
;; Presentation helpers

(defn saved?
  [draft]
  (and (not (contains? #{:failed :storage-unavailable}
                       (keywordize (:save-state draft))))
       (>= (:saved-version draft) (:version draft))))

(defn save-label
  "Return a visible label only when the operator must know about storage.
  Normal saves stay quiet."
  [draft]
  (case (keywordize (:save-state draft))
    :failed "草稿暂未保存"
    :storage-unavailable "草稿暂未保存"
    nil))

(defn save-actions
  "Actions offered when persistence is not available."
  [draft]
  (when (contains? #{:failed :storage-unavailable}
                   (keywordize (:save-state draft)))
    [:copy :export]))
