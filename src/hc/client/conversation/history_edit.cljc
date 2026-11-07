(ns hc.client.conversation.history-edit
  "Pure state for the independent inline history-message editor.

  The editor owns a per-message text and attachment draft.  It never reads or
  writes the composer draft, and it never deletes an attachment blob.  A save
  sends the explicit attachment vector for the new version, including an empty
  vector when every attachment was removed; the server appends a version and
  keeps the original version and its files intact."
  (:require [clojure.string :as str]))

(defn attachment-id
  "Return the stable identity a version reference uses.

  History references normally carry `:attachment-id`.  Older client copies
  may only carry `:id` or `:blob-id`; the server accepts the same fallback."
  [attachment]
  (or (:attachment-id attachment) (:id attachment) (:blob-id attachment)))

(defn attachment-name
  [attachment]
  (or (:name attachment) (:filename attachment) "附件"))

(defn refs
  "Return the raw attachment references that belong to MESSAGE's current
  version.  `:attachment-refs` is the lossless timeline copy; `:attachments`
  is the display fallback for older projections."
  [message]
  (vec (or (:attachment-refs message) (:attachments message) [])))

(defn initial-state
  [message]
  {:content (or (:body message) "")
   :attachments (refs message)})

(defn attachment-rows
  "Project raw references for the editor without changing their identity."
  [attachments]
  (mapv (fn [attachment]
          {:id (attachment-id attachment)
           :name (attachment-name attachment)
           :remove-label (str "移除附件 " (attachment-name attachment))})
        (or attachments [])))

(defn remove-attachment
  "Remove only the selected reference from this version draft.  The original
  version vector and every stored file stay untouched."
  [attachments id]
  (filterv (fn [attachment]
             (not= id (attachment-id attachment)))
           (or attachments [])))

(defn payload
  "Build the edit command payload.

  `:attachments` is always present, even when empty.  Omitting it would ask
  the server to inherit the current version's attachments and would make
  removing the last image impossible."
  [state]
  {:content (or (:content state) "")
   :attachments (vec (or (:attachments state) []))})

(defn saveable?
  "A version must keep visible text or at least one attachment.

  An attachment-only message is a real message; refusing to save it would make
  its text uneditable.  Whitespace-only text with no attachment is not a
  version."
  [state]
  (or (not (str/blank? (or (:content state) "")))
      (boolean (seq (:attachments state)))))
