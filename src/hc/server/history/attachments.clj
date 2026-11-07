(ns hc.server.history.attachments
  "Attachment identity for versioned history.

  An attachment reference is an EDN map with a stable `:attachment-id`.  The
  history domain never copies, rewrites, or deletes the reference or the
  stored bytes.  Editing and branching reuse the same maps; removing an
  attachment from a version only removes it from that version's reference
  vector."
  (:require [clojure.string :as str]))

(defn non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn normalize-refs
  "Validate and normalize one attachment reference vector.

  Returns `{:ok? true :refs refs}` or a refusal.  The maps are returned
  unchanged so a caller cannot accidentally change attachment identity."
  [refs]
  (cond
    (nil? refs)
    {:ok? true :refs []}

    (not (sequential? refs))
    {:ok? false
     :error {:code :ATTACHMENTS_INVALID
             :message "attachments must be a sequence of maps"
             :details {:attachments refs}}}

    :else
    (let [refs (vec refs)
          refs (mapv (fn [ref]
                       (if-not (map? ref)
                         ref
                         (let [id (or (:attachment-id ref)
                                      (:id ref)
                                      (:blob-id ref))]
                           (if (non-blank-string? id)
                             (assoc ref :attachment-id id)
                             ref))))
                     refs)
          invalid (first (remove #(and (map? %)
                                       (non-blank-string? (:attachment-id %)))
                                 refs))]
      (cond
        invalid
        {:ok? false
         :error {:code :ATTACHMENT_REF_INVALID
                 :message "each attachment needs a non-blank string :attachment-id"
                 :details {:attachment invalid}}}

        :else
        (let [ids (map :attachment-id refs)
              duplicate (first (keep (fn [[id n]] (when (> n 1) id))
                                     (frequencies ids)))]
          (if duplicate
            {:ok? false
             :error {:code :ATTACHMENT_ID_DUPLICATE
                     :message "one version cannot reference the same attachment twice"
                     :details {:attachment-id duplicate}}}
            {:ok? true :refs refs}))))))

(defn ids
  [refs]
  (mapv :attachment-id (or refs [])))

(defn index-by-id
  "Index references by attachment ID, keeping the first descriptor for each
  identity.  A later differing descriptor is reported separately."
  [refs]
  (reduce (fn [{:keys [by-id conflicts] :as state} ref]
            (let [id (:attachment-id ref)]
              (cond
                (nil? id) state
                (not (contains? by-id id)) (assoc-in state [:by-id id] ref)
                (= (get by-id id) ref) state
                :else (update state :conflicts conj
                              {:attachment-id id
                               :first (get by-id id)
                               :later ref})))
          {:by-id {} :conflicts []}
          (or refs []))))

(defn same-identity?
  [left right]
  (= (ids left) (ids right)))

(defn missing-ids
  "IDs in BEFORE that are not present in AFTER."
  [before after]
  (let [after (set (ids after))]
    (vec (remove after (ids before)))))
