(ns hc.server.backup.snapshot
  "Build a coherent, self-contained payload from one domain snapshot.

  The storage port promises that `read-snapshot` observes one sampling instant.
  This namespace enforces the consequences:

  - temporary sessions, deleted sessions and rebuildable caches are excluded by
    default and counted in the manifest;
  - secrets are removed unless explicitly included, and login sessions are
    never exported;
  - every attachment referenced by an included conversation is either packaged
    with its bytes or removed from the conversation and listed as incomplete;
  - an attachment digest mismatch is corruption, not a silently incomplete
    item."
  (:require [clojure.string :as str]
            [hc.server.backup.codec :as codec]
            [hc.server.backup.error :as err]
            [hc.server.backup.model :as model]
            [hc.server.backup.package :as package]))

(defn strip-secrets
  "Recursively remove secret keys from an EDN value.

  Returns {:value v :removed {:secret n :non-exportable n}}.  With
  `include-secrets?` true, ordinary API keys remain but non-portable login and
  system-authorization values are still removed.

  The key classifier lives in `hc.server.backup.model` so merge/preview use
  exactly the same normalization (case, `-` and `_` separators)."
  [value include-secrets?]
  (model/strip-secrets value include-secrets?))

(defn- exclusion
  [reason category n]
  (when (pos? n)
    {:reason reason :category category :count n}))

(defn- aggregate-exclusions
  [entries]
  (->> entries
       (remove nil?)
       (group-by (juxt :reason :category))
       (map (fn [[[reason category] xs]]
              {:reason reason
               :category category
               :count (reduce + (map :count xs))}))
       (sort-by (juxt (comp str :category) (comp str :reason)))
       vec))

(defn- keep-item?
  [category item opts]
  (cond
    (and (model/temporary? item) (not (:include-temp? opts)))
    {:keep? false :reason :temporary}

    (and (model/deleted? item) (not (:include-deleted? opts)))
    {:keep? false :reason :deleted}

    (and (model/cache? item) (not (:include-cache? opts)))
    {:keep? false :reason :cache}

    :else
    {:keep? true}))

(defn- prepare-item
  [category item opts]
  (let [{:keys [value removed]} (strip-secrets item (:include-secrets? opts))
        value (if (and (= category :workspaces)
                       (model/external? value)
                       (not (:include-external-files? opts)))
                (-> value
                    (assoc :external-files-included? false)
                    (dissoc :files))
                value)]
    {:item value
     :removed removed}))

(defn- prepare-attachments
  [conversations source-attachments opts]
  (let [source-attachments (or source-attachments {})
        max-bytes (:max-attachment-bytes opts)
        max-total (:max-attachment-total-bytes opts)
        declared-total (reduce + 0
                               (keep #(when (integer? (:size %)) (:size %))
                                     (vals source-attachments)))
        _ (when (and (integer? max-total)
                     (> (long declared-total) (long max-total)))
            (err/fail! :BACKUP_ATTACHMENTS_TOO_LARGE
                       "Declared attachment sizes exceed the total backup limit"
                       {:size declared-total :limit max-total}))
        state (atom {:attachments {}
                     :incomplete []
                     :conversations []
                     :bytes 0})]
    (doseq [conversation conversations]
      (let [cid (model/item-id conversation)
            ids (:attachment-ids conversation)
            ids (cond
                  (nil? ids) []
                  (sequential? ids) (vec ids)
                  :else (err/fail! :BACKUP_FORMAT_INVALID
                                   "Conversation attachment list must be sequential"
                                   {:conversation-id cid
                                    :attachment-ids ids}))
            unavailable (vec (distinct (or (:unavailable-attachment-ids conversation)
                                           [])))]
        (loop [remaining ids
               kept []
               unavailable unavailable]
          (if-let [aid (first remaining)]
            (let [a (get source-attachments aid)
                  reason (cond
                           (nil? a) :missing
                           (false? (:ready? a)) :not-ready
                           (and (:external? a)
                                (not (:include-external-files? opts)))
                           :external-not-included
                           (not (string? (:bytes-base64 a))) :missing-bytes
                           :else nil)]
              (if reason
                (do
                  (swap! state update :incomplete conj
                         {:conversation-id cid
                          :attachment-id aid
                          :reason reason})
                  (recur (rest remaining)
                         kept
                         (conj unavailable aid)))
                (let [declared-size (:size a)
                      _ (when (and (integer? max-bytes)
                                   (integer? declared-size)
                                   (> (long declared-size) (long max-bytes)))
                          (err/fail! :BACKUP_ATTACHMENT_TOO_LARGE
                                     "An attachment exceeds the backup attachment limit"
                                     {:attachment-id aid
                                      :size declared-size
                                      :limit max-bytes}))
                      ^bytes bytes (codec/base64-decode (:bytes-base64 a))
                      actual-size (alength bytes)
                      actual-sha (codec/sha256-hex bytes)
                      expected-size declared-size
                      expected-sha (:sha256 a)]
                  (when (and (integer? max-bytes)
                             (> (long actual-size) (long max-bytes)))
                    (err/fail! :BACKUP_ATTACHMENT_TOO_LARGE
                               "An attachment exceeds the backup attachment limit"
                               {:attachment-id aid
                                :size actual-size
                                :limit max-bytes}))
                  (when (and (integer? expected-size)
                             (not= (long expected-size) actual-size))
                    (err/fail! :BACKUP_INTEGRITY
                               "Attachment size does not match its metadata"
                               {:attachment-id aid
                                :expected-size expected-size
                                :actual-size actual-size}))
                  (when (and (string? expected-sha)
                             (not= expected-sha actual-sha))
                    (err/fail! :BACKUP_INTEGRITY
                               "Attachment digest does not match its metadata"
                               {:attachment-id aid
                                :expected-sha256 expected-sha
                                :actual-sha256 actual-sha}))
                  (swap! state
                         (fn [s]
                           (-> s
                               (assoc-in [:attachments aid]
                                         {:id aid
                                          :size actual-size
                                          :sha256 actual-sha
                                          :bytes-base64 (codec/base64-encode bytes)
                                          :content-type (:content-type a)})
                               (update :bytes + actual-size))))
                  (when (and (integer? max-total)
                             (> (long (:bytes @state)) (long max-total)))
                    (err/fail! :BACKUP_ATTACHMENTS_TOO_LARGE
                               "Attachments exceed the total backup attachment limit"
                               {:size (:bytes @state) :limit max-total}))
                  (recur (rest remaining)
                         (conj kept aid)
                         unavailable))))
            (swap! state update :conversations conj
                   (cond-> conversation
                     (or (contains? conversation :attachment-ids)
                         (seq kept))
                     (assoc :attachment-ids kept)
                     (seq unavailable)
                     (assoc :unavailable-attachment-ids unavailable)))))))
    @state))

(defn- missing-references
  [prepared-categories selected]
  (let [assistant-ids (set (keep model/item-id (get-in prepared-categories [:assistants :items])))
        workspace-ids (set (keep model/item-id (get-in prepared-categories [:workspaces :items])))
        conversations (get-in prepared-categories [:conversations :items])]
    (cond-> []
      (contains? selected :assistants)
      (into (for [c conversations
                  :let [aid (:assistant-id c)]
                  :when (and (string? aid) (not (contains? assistant-ids aid)))]
              {:category :conversations
               :id (model/item-id c)
               :missing :assistant
               :assistant-id aid}))

      (contains? selected :workspaces)
      (into (for [c conversations
                  :let [wid (:workspace-id c)]
                  :when (and (string? wid) (not (contains? workspace-ids wid)))]
              {:category :conversations
               :id (model/item-id c)
               :missing :workspace
               :workspace-id wid})))))

(defn prepare
  "Turn one domain snapshot into a backup payload.

  `opts` must contain `:categories` (a selected-category collection),
  `:sampled-at`, and the selection flags from `model/default-policy`."
  [snapshot opts]
  (let [selected (set (map model/normalize-category (:categories opts)))
        ;; The port returns the instant its coherent read completed.  It is
        ;; authoritative; the request value is only the start hint.
        sampled-at (or (:sampled-at snapshot) (:sampled-at opts))]
    (when-not (and (string? sampled-at) (not (str/blank? sampled-at)))
      (err/fail! :BACKUP_SNAPSHOT_INCOHERENT
                 "Storage port returned no sampling instant"
                 {:requested (:sampled-at opts)
                  :returned (:sampled-at snapshot)}))
    (let [exclusions (atom [])
          secret-counts (atom {:secret 0 :non-exportable 0})
          category-stats (atom {})
          prepared (atom {})]
      (doseq [category model/category-order
              :when (contains? selected category)]
        (let [raw-items (model/items snapshot category)
              kept (reduce (fn [acc item]
                             (let [{:keys [keep? reason]} (keep-item? category item opts)]
                               (if keep?
                                 (let [{:keys [item removed]} (prepare-item category item opts)]
                                   (swap! secret-counts
                                          (fn [m] (merge-with + m removed)))
                                   (conj acc item))
                                 (do
                                   (swap! exclusions conj
                                          (exclusion reason category 1))
                                   acc))))
                           []
                           raw-items)
              _ (model/assert-unique-ids! category kept)
              bytes (reduce + 0 (map #(alength (codec/canonical-bytes %)) kept))]
          (swap! category-stats assoc category
                 {:count (count kept) :bytes bytes})
          (swap! prepared assoc category {:items kept})))
      (let [conversations (get-in @prepared [:conversations :items])
            attachments (when (contains? selected :conversations)
                          (prepare-attachments conversations
                                               (:attachments snapshot)
                                               opts))
            conversations' (:conversations attachments)
            prepared' (cond-> @prepared
                        (contains? selected :conversations)
                        (assoc-in [:conversations :items] conversations'))
            missing (missing-references prepared' selected)
            incomplete (:incomplete attachments)
            permissions-result (strip-secrets (or (:permissions snapshot) {})
                                              (:include-secrets? opts))
            _ (swap! secret-counts
                     #(merge-with + % (:removed permissions-result)))
            payload {:format package/payload-format
                     :format-version package/format-version
                     :sampled-at sampled-at
                     :categories prepared'
                     :attachments (or (:attachments attachments) {})
                     :exclusions (aggregate-exclusions
                                  (concat @exclusions
                                          (map (fn [x]
                                                 {:reason :incomplete-attachment
                                                  :category :conversations
                                                  :count 1})
                                               incomplete)))
                     :incomplete-attachments (vec incomplete)
                     :missing-references (vec missing)
                     :secrets {:included? (boolean (:include-secrets? opts))
                               :removed @secret-counts}
                     :permissions (:value permissions-result)}
            attachment-bytes (reduce + 0 (map :size (vals (:attachments payload))))]
        {:payload payload
         :stats {:category-stats @category-stats
                 :attachment-stats {:count (count (:attachments payload))
                                    :bytes attachment-bytes}
                 :exclusions (:exclusions payload)
                 :incomplete-attachments (:incomplete-attachments payload)
                 :missing-references (:missing-references payload)
                 :secrets (:secrets payload)}}))))
