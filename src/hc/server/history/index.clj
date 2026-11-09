(ns hc.server.history.index
  "Rebuildable full-text index for session history.

  Authority: root AGENTS.md §7.1 item 1 and §7.4 journey 5.1.

  The index is a *derived* layer, never current state.  It lives in the
  `\"search\"` partition of the same store and can be deleted and rebuilt from
  the authoritative session records at any time.  It stores token sets and
  posting identities, not message bodies:

    {:kind :search-index   :id \"meta\"}          control document
    {:kind :search-session :id <session-id>}      one per indexed session
    {:kind :search-posting :id <term>}            inverted term document

  A session document keeps the current revision, display metadata, and one
  term-set entry per indexed message version.  A posting document maps
  `[session-id message-id]` (or `[session-id message-id version-id]` for an
  explicitly indexed historical version) to the version identity that
  produced it.

  Every `search` first reads candidate postings, then calls the caller's
  `read-session-fn` for each candidate session and validates the hit against
  the authoritative session record: the session and message must still exist,
  the message must not be deleted or folded, the indexed version identity must
  still be selected (unless historical versions were explicitly requested),
  and the *current* message body must still contain every query term.  The
  snippet is cut from that main-store body, never from an indexed copy.

  The storage facade commits one scope per migration.  Postings and the meta
  document are partition-scoped (`:session-id nil`); a session document is
  session-scoped.  `index-session!` therefore writes postings first and the
  session document second.  A crash between the two commits can leave new
  postings with an older session document; the next indexing or removal pass
  reconciles from that older document, and search still validates every hit
  against the main store.  No stale body can be returned from the index."
  (:refer-clojure :exclude [partition])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hc.server.storage :as storage])
  (:import (java.util Locale UUID)))

(def partition "search")
(def schema-version 1)
(def tokenizer-version
  "Bump this whenever `tokenize` changes the term set or its encoding.
  `index-health` treats a mismatch as an incompatible generation which must
  be rebuilt; it never keeps postings produced by a different tokenizer."
  1)
(def meta-kind :search-index)
(def session-kind :search-session)
(def posting-kind :search-posting)
(def meta-id "meta")

(def default-limit 50)
(def max-limit 200)
(def snippet-radius 60)
(def max-term-chars 200)

(def ^:private envelope-keys
  [:storage/id :storage/kind :storage/partition :storage/session-id
   :storage/version :storage/updated-at])

;; ---------------------------------------------------------------------------
;; Index handle

(defn- store-of
  "Return the storage handle from INDEX.  A bare store and `{:store ..}` are
  both accepted so a caller can attach private options without wrapping."
  [index]
  (if (and (map? index) (contains? index :store))
    (:store index)
    index))

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :search/error true))))

(defn- session-id-of [session]
  (or (:session-id session) (:id session)))

(defn- unwrap [doc]
  (when (map? doc)
    (apply dissoc doc envelope-keys)))

;; ---------------------------------------------------------------------------
;; Tokenizer

(defn- code-point-strings [^String s]
  (let [n (.length s)]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [cp (.codePointAt s i)
              width (Character/charCount cp)]
          (recur (+ i width)
                 (conj out (subs s (int i) (int (+ i width))))))))))

(defn- han-tokens [^String run]
  (let [chars (code-point-strings run)
        n (count chars)]
    (concat chars
            (map (fn [i] (str (nth chars i) (nth chars (inc i))))
                 (range (max 0 (dec n)))))))

(defn tokenize
  "Return a deterministic sorted vector of search tokens for TEXT.

  Rules:

  - ASCII letters and digits form lower-cased word tokens of length 2..200;
  - every Han character is a unigram token, including one-character queries;
  - each adjacent pair in a Han run is a bigram token;
  - other characters separate tokens and are never indexed.

  The function is pure and the output is sorted, so indexing and querying use
  exactly the same term set on every platform and locale."
  [text]
  (if-not (string? text)
    []
    (let [words (for [match (re-seq #"[A-Za-z0-9]+" text)
                      :let [token (.toLowerCase ^String match Locale/ROOT)]
                      :when (<= 2 (count token) max-term-chars)]
                  token)
          han (for [run (re-seq #"\p{IsHan}+" text)
                    token (han-tokens run)
                    :when (<= (count token) max-term-chars)]
                token)]
      (vec (sort (distinct (concat words han)))))))

;; ---------------------------------------------------------------------------
;; Session and message projection

(defn- visible-message? [message]
  (and (map? message)
       (not (true? (:deleted? message)))
       (not (true? (:folded? message)))))

(defn- version-id-of
  [message index version]
  (or (:version-id version)
      (str (:message-id message) "-v" (inc (long index)))))

(defn- current-version-entry [message]
  (let [versions (:versions message)
        index (long (or (:current-version message) 0))]
    (or
     (when (and (sequential? versions)
                (<= 0 index)
                (< index (count versions)))
       (let [version (nth versions index)]
         (when (map? version)
           {:index index
            :version version
            :version-id (version-id-of message index version)})))
     ;; A legacy or minimal message may carry its one body at the message
     ;; level.  Index it under the same fallback version identity the main
     ;; store validation will derive, instead of silently skipping the text.
     (when (string? (:content message))
       {:index 0
        :version (select-keys message
                              [:content :role :created-at :attachments
                               :version-id])
        :version-id (or (:version-id message)
                        (str (:message-id message) "-v1"))}))))

(defn- version-entry-by-id [message id]
  (when (and (sequential? (:versions message)) id)
    (first
     (keep-indexed
      (fn [index version]
        (when (and (map? version)
                   (= id (version-id-of message index version)))
          {:index index
           :version version
           :version-id (version-id-of message index version)}))
      (:versions message)))))

(defn- entry-for-version [message version id historical?]
  (cond-> {:version-id id
           :role (or (:role version) (:role message))
           :created-at (or (:created-at version) (:created-at message) 0)
           :terms (set (tokenize (:content version)))}
    historical? (assoc :historical-version? true)))

(defn- message-entries
  "Index one entry per visible message.  The current selected version is keyed
  by message ID.  With INCLUDE-HISTORY? every other version is additionally
  keyed by `[message-id version-id]`."
  [session include-history?]
  (into {}
        (mapcat
         (fn [[map-key message]]
           (when-let [{:keys [index version] :as selected-entry}
                      (and (visible-message? message)
                           (current-version-entry message))]
             (let [message-id (or (:message-id message) map-key)
                   selected-version-id (:version-id selected-entry)
                   current (entry-for-version message version
                                              selected-version-id false)]
               (if-not include-history?
                 [[message-id current]]
                 (concat
                  [[message-id current]]
                  (for [i (range (count (:versions message)))
                        :let [candidate (nth (:versions message) i)]
                        :when (and (not= i index) (map? candidate))
                        :let [candidate-id (version-id-of message i candidate)]
                        :when (not= candidate-id selected-version-id)]
                    [[message-id candidate-id]
                     (entry-for-version message candidate candidate-id true)]))))))
         (:messages session))))

(defn- session-index-document [session entries]
  {:session-id (or (:session-id session) (:id session))
   :session-revision (long (or (:revision session) 0))
   :assistant-id (:assistant-id session)
   :title (:title session)
   :status (or (:status session) :active)
   :temporary? (boolean (:temporary? session))
   :messages entries})

;; ---------------------------------------------------------------------------
;; Postings

(defn- posting-key [session-id entry-key entry]
  (if (vector? entry-key)
    [session-id (first entry-key) (:version-id entry)]
    [session-id entry-key]))

(defn- posting-value [session-id entry-key entry revision]
  {:session-id session-id
   :message-id (if (vector? entry-key) (first entry-key) entry-key)
   :version-id (:version-id entry)
   :session-revision (long revision)})

(defn- entries-by-term [entries]
  (reduce (fn [acc [entry-key entry]]
            (reduce (fn [acc term]
                      (update acc term (fnil conj []) [entry-key entry]))
                    acc
                    (:terms entry)))
          {}
          entries))

(defn- changed-entry-pairs [old-entries new-entries]
  [(vec (for [k (sort-by pr-str (keys old-entries))
              :when (not= (get old-entries k) (get new-entries k))]
          [k (get old-entries k)]))
   (vec (for [k (sort-by pr-str (keys new-entries))
              :when (not= (get old-entries k) (get new-entries k))]
          [k (get new-entries k)]))])

(defn- read-posting [store term]
  (unwrap
   (storage/read-document
    store
    {:partition partition
     :session-id nil
     :kind posting-kind
     :id term})))

(defn- posting-document [term postings]
  {:term term :postings postings})

(defn- posting-ops
  "Return `{:puts .. :deletes .. :removed n}` for one session transition."
  [store session-id old-entries new-entries revision]
  (let [[removed-entries added-entries] (changed-entry-pairs old-entries new-entries)
        removals (entries-by-term removed-entries)
        additions (entries-by-term added-entries)
        terms (sort (set/union (set (keys removals)) (set (keys additions))))]
    (reduce
     (fn [acc term]
       (let [current (or (:postings (read-posting store term)) {})
             removal-keys (mapv (fn [[entry-key entry]]
                                  (posting-key session-id entry-key entry))
                                (get removals term []))
             added (into {}
                         (map (fn [[entry-key entry]]
                                [(posting-key session-id entry-key entry)
                                 (posting-value session-id entry-key entry revision)]))
                         (get additions term []))
             next-postings (merge (apply dissoc current removal-keys) added)
             removed (count (filter #(contains? current %) removal-keys))]
         (cond
           (empty? next-postings)
           (cond-> acc
             (seq current)
             (-> (update :deletes conj {:kind posting-kind :id term})
                 (update :removed + removed)))

           (= next-postings current)
           acc

           :else
           (-> acc
               (update :puts conj {:kind posting-kind
                                   :id term
                                   :doc (posting-document term next-postings)})
               (update :removed + removed)))))
     {:puts [] :deletes [] :removed 0}
     terms)))

(defn- commit-search!
  [store session-id reason puts deletes]
  (when (or (seq puts) (seq deletes))
    (storage/commit-migration!
     store
     {:migration-id (str "search-" (UUID/randomUUID))
      :partition partition
      :session-id session-id
      :reason reason
      :put (vec puts)
      :delete (vec deletes)})))

;; ---------------------------------------------------------------------------
;; Control document

(defn- meta-document
  ([generation state]
   (meta-document generation state {}))
  ([generation state statistics]
   {:schema-version schema-version
    :tokenizer-version tokenizer-version
    :state state
    :generation (long generation)
    :updated-at (System/currentTimeMillis)
    :session-count (long (or (:session-count statistics) 0))
    :message-count (long (or (:message-count statistics) 0))}))

(defn- read-meta* [store]
  (unwrap
   (storage/read-document
    store
    {:partition partition
     :session-id nil
     :kind meta-kind
     :id meta-id})))

(defn index-meta
  "Return the control document, or nil when the index has never been written."
  [index]
  (read-meta* (store-of index)))

(defn- write-meta!
  ([store generation state]
   (write-meta! store generation state {}))
  ([store generation state statistics]
   (commit-search! store nil :search-index
                   [{:kind meta-kind
                     :id meta-id
                     :doc (meta-document generation state statistics)}]
                   [])))

(defn- read-session-document [store session-id]
  (unwrap
   (storage/read-document
    store
    {:partition partition
     :session-id session-id
     :kind session-kind
     :id session-id})))

;; ---------------------------------------------------------------------------
;; Index and remove one session

(defn remove-session!
  "Remove every posting and the index document of SESSION-ID.

  The operation is idempotent.  It reads the session index document to learn
  which terms may refer to this session; postings whose session document was
  lost are harmless for correctness (search revalidates every hit) and are
  reclaimed by `rebuild!`."
  [index session-id]
  (let [store (store-of index)]
    (locking store
      (when-not (and (string? session-id) (not (str/blank? session-id)))
        (invalid! "search index needs a non-blank session ID"
                  {:session-id session-id}))
      (if-let [old (read-session-document store session-id)]
        (let [{:keys [puts deletes removed]}
              (posting-ops store session-id (:messages old) {} 0)]
          (commit-search! store nil :search-session-remove puts deletes)
          (commit-search! store session-id :search-session-remove
                          [] [{:kind session-kind :id session-id}])
          {:ok? true
           :session-id session-id
           :removed? true
           :postings-put (count puts)
           :postings-deleted (count deletes)
           :postings-removed removed})
        {:ok? true
         :session-id session-id
         :removed? false
         :postings-put 0
         :postings-deleted 0
         :postings-removed 0}))))

(defn index-session!
  "Index the current visible messages of SESSION-DOC.

  Temporary sessions and deleted sessions are never indexed: they are removed
  immediately, including terms from an older generation.  Active and archived
  sessions are indexed.  An unknown status is treated as non-indexable and is
  removed rather than exposed.

  Options:

    :include-history-versions?  also index non-selected versions, marked
                                `:historical-version? true`.  Default false.

  Returns a plain result map.  The index document and every posting are
  written through `storage/commit-migration!`; no message body is stored."
  ([index session-doc]
   (index-session! index session-doc {}))
  ([index session-doc {:keys [include-history-versions?] :or {include-history-versions? false}}]
   (when-not (map? session-doc)
     (invalid! "search index needs a session map" {:session session-doc}))
   (let [store (store-of index)
         session-id (session-id-of session-doc)]
     (locking store
       (when-not (and (string? session-id) (not (str/blank? session-id)))
         (invalid! "search index needs a session with :session-id"
                   {:session session-doc}))
       (let [old (read-session-document store session-id)
             indexed-revision (long (or (:session-revision old) 0))
             revision (long (or (:revision session-doc) 0))
             status (or (:status session-doc) :active)
             remove? (or (true? (:temporary? session-doc))
                         (true? (:deleted? session-doc))
                         (= :deleted status)
                         (not (contains? #{:active :archived} status)))]
         (if (> indexed-revision revision)
           ;; A rebuild snapshot can be older than an incremental write that
           ;; landed after the snapshot was taken.  The comparison happens
           ;; under the same store monitor as the write, so this is not a
           ;; check-then-act race.  Never let the old snapshot touch the
           ;; newer postings or session document.
           {:ok? true
            :indexed? false
            :skipped? true
            :reason :stale-snapshot
            :session-id session-id
            :session-revision revision
            :indexed-session-revision indexed-revision
            :messages 0
            :postings-put 0
            :postings-deleted 0
            :postings-removed 0
            :session-changed? false
            :committed? false}
           (if remove?
             (assoc (remove-session! index session-id)
                    :indexed? false
                    :skipped? true
                    :reason :temporary-or-deleted)
             (let [include-history? (true? include-history-versions?)
                   old-entries (or (:messages old) {})
                   new-entries (message-entries session-doc include-history?)
                   {:keys [puts deletes removed]}
                   (posting-ops store session-id old-entries new-entries revision)
                   meta (read-meta* store)
                   meta-puts (if meta
                               []
                               [{:kind meta-kind
                                 :id meta-id
                                 :doc (meta-document 1 :ready)}])
                   new-document (session-index-document session-doc new-entries)
                   session-changed? (not= old new-document)
                   postings-committed? (boolean
                                        (commit-search! store nil :search-index
                                                        (concat puts meta-puts)
                                                        deletes))
                   session-committed? (boolean
                                       (when session-changed?
                                         (commit-search! store session-id :search-index
                                                         [{:kind session-kind
                                                           :id session-id
                                                           :doc new-document}]
                                                         [])))]
               {:ok? true
                :indexed? true
                :skipped? false
                :session-id session-id
                :session-revision revision
                :messages (count new-entries)
                :postings-put (count puts)
                :postings-deleted (count deletes)
                :postings-removed removed
                :session-changed? session-changed?
                :committed? (or postings-committed? session-committed?)}))))))))

;; ---------------------------------------------------------------------------
;; Search with main-store validation

(defn- status-match? [wanted actual]
  (cond
    (nil? wanted) true
    (set? wanted) (contains? wanted actual)
    (sequential? wanted) (boolean (some #{actual} wanted))
    :else (= wanted actual)))

(defn- read-result-session [result]
  (cond
    (and (map? result)
         (map? (:session result)))
    (:session result)

    (and (map? result)
         (or (contains? result :session-id)
             (contains? result :id)))
    result

    :else nil))

(defn- searchable-session? [session opts]
  (let [status (or (:status session) :active)]
    (and (map? session)
         (not (true? (:temporary? session)))
         (not (true? (:deleted? session)))
         (not= :deleted status)
         (status-match? (:status opts) status)
         (or (nil? (:assistant-id opts))
             (= (:assistant-id opts) (:assistant-id session))))))

(defn- version-content [version]
  (:content version))

(defn- version-attachments [message version]
  (cond
    (contains? version :attachments) (:attachments version)
    :else (:attachments message)))

(defn- selected-created-at [message version]
  (or (:created-at version) (:created-at message) 0))

(defn- in-time-range? [created-at opts]
  (and (or (nil? (:from-ms opts))
           (and (number? (:from-ms opts))
                (number? created-at)
                (<= (:from-ms opts) created-at)))
       (or (nil? (:to-ms opts))
           (and (number? (:to-ms opts))
                (number? created-at)
                (<= created-at (:to-ms opts))))))

(defn- attachments-match? [message version opts]
  (if (some? (:has-attachments? opts))
    (= (boolean (:has-attachments? opts))
       (boolean (seq (version-attachments message version))))
    true))

(defn- snippet
  "Cut a snippet of `snippet-radius` characters before and after the first
  occurrence of a query term in the authoritative body."
  [content terms]
  (when (string? content)
    (let [lower (.toLowerCase ^String content Locale/ROOT)
          found (->> terms
                     (keep (fn [term]
                             (let [term-lower (.toLowerCase ^String term Locale/ROOT)]
                               (when-let [index (str/index-of lower term-lower)]
                                 [index (count term)]))))
                     (sort-by first)
                     first)]
      (if found
        (let [[index width] found
              start (max 0 (- (long index) snippet-radius))
              end (min (count content) (+ (long index) (long width) snippet-radius))]
          (subs content (int start) (int end)))
        (subs content 0 (int (min (count content) (* 2 snippet-radius))))))))

(defn- candidate-hit
  "Validate one posting candidate against the authoritative session."
  [session candidate opts terms]
  (let [message-id (:message-id candidate)
        message (get-in session [:messages message-id])
        include-history? (true? (:include-versions? opts))
        indexed-version-id (:version-id candidate)]
    (when (and (map? message)
               (not (true? (:deleted? message)))
               (not (true? (:folded? message))))
      (let [current (current-version-entry message)
            chosen (cond
                     (and current
                          (= indexed-version-id (:version-id current)))
                     current

                     include-history?
                     (version-entry-by-id message indexed-version-id)

                     :else nil)]
        (when chosen
          (let [version (:version chosen)
                content (version-content version)
                content-terms (set (tokenize content))
                created-at (selected-created-at message version)]
            (when (and (string? content)
                       (every? content-terms terms)
                       (in-time-range? created-at opts)
                       (attachments-match? message version opts))
              (let [current-version-id (:version-id current)
                    historical? (not= current-version-id (:version-id chosen))]
                {:session-id (or (:session-id session) (:id session))
                 :message-id message-id
                 :version-id (:version-id chosen)
                 :role (or (:role version) (:role message))
                 :created-at created-at
                 :snippet (snippet content terms)
                 :historical-version? historical?
                 :indexed-version-id indexed-version-id
                 :indexed-session-revision (:session-revision candidate)
                 :session-revision (long (or (:revision session) 0))
                 :session-title (:title session)
                 :session-status (or (:status session) :active)
                 :assistant-id (:assistant-id session)}))))))))

(defn- normalize-limit [limit]
  (if (and (integer? limit) (not (neg? limit)))
    (min max-limit (long limit))
    default-limit))

(defn search
  "Search indexed history and validate every candidate against the main store.

  OPTS:

    :query              required string
    :assistant-id       optional exact assistant filter
    :status             optional keyword, set or sequence of session statuses
    :include-versions?  include non-selected versions (default false)
    :has-attachments?   optional boolean; true requires attachments, false
                        requires none
    :from-ms / :to-ms   optional inclusive message-created-at range
    :limit              optional positive integer (default 50, max 200)

  READ-SESSION-FN receives one session ID and returns the authoritative
  session, `{:status :found :session ..}`, or a missing/error result.  It is
  called exactly once for every session that owns a candidate posting, before
  any hit from that session is accepted.

  A hit is returned only when:

    1. the session exists, is not temporary, is not deleted, and matches the
       requested assistant/status filters;
    2. the message exists and is neither deleted nor folded;
    3. the indexed version identity still matches the selected version, or a
       historical version was explicitly requested and still exists;
    4. the authoritative body still contains every query term; and
    5. the message passes the time and attachment filters.

  The snippet is extracted from that authoritative body, not from the index."
  [index {:keys [query] :as opts} read-session-fn]
  (let [store (store-of index)
        terms (tokenize query)]
    (when-not (ifn? read-session-fn)
      (invalid! "search needs a read-session-fn" {:read-session-fn read-session-fn}))
    (let [limit (normalize-limit (:limit opts))]
      (if (empty? terms)
        {:ok? true :query query :terms [] :hits [] :count 0 :total 0
         :limit limit :stale-dropped 0}
        (let [postings (mapv (fn [term] [term (or (:postings (read-posting store term)) {})])
                             terms)
              candidates
              (when (every? (comp seq second) postings)
                (let [key-sets (map (fn [[_ postings]] (set (keys postings))) postings)
                      keys (apply set/intersection key-sets)]
                  (into {}
                        (map (fn [key]
                               [key (first (keep (fn [[_ postings]]
                                                   (get postings key))
                                                 postings))]))
                        keys)))]
          (if (empty? candidates)
            {:ok? true :query query :terms terms :hits [] :count 0 :total 0
             :limit limit :stale-dropped 0}
            (let [grouped (->> candidates
                               (keys)
                               (sort-by pr-str)
                               (group-by first))
                  result
                  (reduce
                   (fn [{:keys [hits stale] :as acc} [session-id keys]]
                     (let [session (read-result-session (read-session-fn session-id))]
                       (if-not (searchable-session? session opts)
                         (-> acc
                             (assoc :stale (+ stale (count keys))))
                         (reduce
                          (fn [{:keys [hits stale] :as inner} key]
                            (if-let [hit (candidate-hit session
                                                        (get candidates key)
                                                        opts terms)]
                              (assoc inner :hits (conj hits hit))
                              (assoc inner :stale (inc stale))))
                          acc
                          keys))))
                   {:hits [] :stale 0}
                   grouped)
                  ordered (sort-by (fn [hit]
                                     [(- (long (or (:created-at hit) 0)))
                                      (str (:session-id hit))
                                      (str (:message-id hit))
                                      (str (:version-id hit))])
                                   (:hits result))
                  limited (vec (take limit ordered))]
              {:ok? true
               :query query
               :terms terms
               :hits limited
               :count (count limited)
               :total (count ordered)
               :limit limit
               :stale-dropped (:stale result)})))))))

;; ---------------------------------------------------------------------------
;; Rebuild

(defn- listed-sessions [value]
  (cond
    (and (map? value) (sequential? (:sessions value))) (vec (:sessions value))
    (and (map? value) (sequential? (:items value))) (vec (:items value))
    (and (map? value)
         (every? #(and (map? %)
                       (or (:session-id %) (:id %)))
                 (vals value)))
    (vec (vals value))
    (sequential? value) (vec value)
    :else []))

(defn- search-documents [store]
  (get-in (storage/export-state store {:partition partition})
          [:partitions partition :docs]
          []))

(defn- session-document-message-count
  "Distinct message IDs in one stored session document.  Historical versions
  are keyed by `[message-id version-id]`, so they collapse to the message."
  [session-doc]
  (count (distinct (map (fn [key]
                          (if (vector? key) (first key) key))
                        (keys (:messages session-doc))))))

(defn- index-statistics [store]
  (let [documents (search-documents store)
        sessions (filter #(= session-kind (:kind %)) documents)]
    {:session-count (count sessions)
     :message-count (reduce + 0
                            (map (comp session-document-message-count :doc)
                                 sessions))}))

(defn- clear-derivable! [store]
  (let [docs (remove #(= meta-kind (:kind %)) (search-documents store))
        partition-docs (filter #(nil? (:session-id %)) docs)
        scoped (group-by :session-id (filter #(some? (:session-id %)) docs))]
    (when (seq partition-docs)
      (commit-search! store nil :search-rebuild
                      []
                      (mapv #(select-keys % [:kind :id]) partition-docs)))
    (doseq [[session-id documents] scoped]
      (commit-search! store session-id :search-rebuild
                      []
                      (mapv #(select-keys % [:kind :id]) documents)))
    (count docs)))

(defonce ^:private rebuild-locks
  ;; Serialize rebuilds per store.  Incremental index-session! calls keep
  ;; their own short store lock and may interleave with the per-session loop.
  ;; Weak keys avoid retaining a closed store; the lock value does not
  ;; reference the key.
  (java.util.WeakHashMap.))

(defn- rebuild-lock [store]
  (locking rebuild-locks
    (or (.get rebuild-locks store)
        (let [lock (Object.)]
          (.put rebuild-locks store lock)
          lock))))

(defn- predicate-true?
  "Read a cancellation/open predicate.  A boolean, an IDeref or a function is
  accepted; a throwing predicate is treated as true by the caller."
  [value]
  (cond
    (nil? value) false
    (instance? clojure.lang.IDeref value) (boolean @value)
    (ifn? value) (boolean (value))
    :else (boolean value)))

(defn- cancellation-requested?
  "True when the caller's cancellation flag is set or the store is no longer
  open.  An absent predicate is not a cancellation.  A throwing predicate is
  fail-safe: stop writing."
  [store opts]
  (try
    (or (predicate-true? (:cancelled? opts))
        (not (predicate-true?
              (if (contains? opts :store-open?)
                (:store-open? opts)
                #(storage/store-open? store)))))
    (catch Throwable _
      true)))

(defn- cancelled-rebuild
  [indexed generation session-count skipped results]
  (cond-> {:ok? false
           :cancelled? true
           :indexed (long indexed)
           :generation generation
           :sessions (long session-count)
           :skipped (long skipped)
           :reason :cancelled
           :postings-put (reduce + 0 (map :postings-put results))
           :postings-deleted (reduce + 0 (map :postings-deleted results))}
    generation (assoc :state :building)))

(defn rebuild!
  "Clear the derived index and rebuild it from authoritative sessions.

  LIST-SESSIONS-FN is called with no arguments and returns a collection of
  session maps (or `{:sessions [..]}`).  It is called once before the marker
  to validate the source, and again after the clear to include sessions
  committed during the rebuild window.  If the refresh throws, the pre-clear
  list is used and the result reports `:list-refreshed? false`; the first
  list's failure still happens before any write.  The generation is
  incremented before clearing and marked `:ready` after the last session, so
  a crash leaves `:state :building` instead of a false claim of completeness.

  The optional OPTS map is passed to `index-session!`; in particular
  `:include-history-versions?` can rebuild historical-version postings too.
  When `:current-session-fn` is supplied, each snapshot session is checked
  against the authoritative store first: a newer current revision is indexed
  instead of the snapshot, and a session that has disappeared is left
  deleted.  This keeps a background rebuild from overwriting an incremental
  write that landed after the snapshot was taken.  Independently,
  `index-session!` compares the snapshot revision with the indexed session
  revision under the store lock and returns `:stale-snapshot` instead of
  overwriting a newer incremental write.

  Rebuilds are serialized by a private per-store rebuild lock, so two
  generations cannot be allocated or committed concurrently.  The store
  monitor is held only for the `:building` marker, clear and final `:ready`
  phases; each per-session index call takes its own short lock.

  Temporary and deleted sessions are passed through `index-session!` and are
  removed rather than indexed.  Repeating a rebuild over the same sessions
  produces the same search hits."
  ([index list-sessions-fn]
   (rebuild! index list-sessions-fn {}))
  ([index list-sessions-fn opts]
   (let [store (store-of index)
         current-session-fn (:current-session-fn opts)]
     (when-not (ifn? list-sessions-fn)
       (invalid! "rebuild! needs a list-sessions-fn"
                 {:list-sessions-fn list-sessions-fn}))
     (locking (rebuild-lock store)
       (if (cancellation-requested? store opts)
         ;; Cancellation before the marker is a zero-write cancellation.  The
         ;; caller closes the store after setting the flag; no index document
         ;; or partition metadata may be touched first.
         (cancelled-rebuild 0 nil 0 0 [])
         (let [pre-sessions (listed-sessions (list-sessions-fn))
             ;; Generation allocation, marker and clear are one store-monitor
             ;; phase.  The rebuild lock above ensures another rebuild cannot
             ;; allocate the same generation or advance it concurrently.
             [generation cleared]
             (locking store
               (let [old-meta (read-meta* store)
                     generation (inc (long (or (:generation old-meta) 0)))]
                 (write-meta! store generation :building)
                 [generation (clear-derivable! store)]))
             ;; Re-list after the clear.  A session committed before the
             ;; clear (whose incremental hook was wiped by it) is visible
             ;; here, and a session committed after this point writes after
             ;; the clear so its hook survives.
             refreshed (try
                         {:ok? true
                          :sessions (listed-sessions (list-sessions-fn))}
                         (catch Throwable throwable
                           {:ok? false :error throwable}))
             sessions (if (:ok? refreshed)
                        (:sessions refreshed)
                        pre-sessions)
             cancelled-now? (fn []
                              (cancellation-requested? store opts))
             loop-result
             (loop [remaining sessions
                    results []
                    indexed 0
                    skipped 0]
               (if (empty? remaining)
                 {:cancelled? false
                  :results results
                  :indexed indexed
                  :skipped skipped}
                 (if (cancelled-now?)
                   {:cancelled? true
                    :results results
                    :indexed indexed
                    :skipped skipped}
                   (let [session (first remaining)
                         sid (session-id-of session)
                         snapshot-rev
                         (long (or (:revision session) 0))
                         current-result
                         (when current-session-fn
                           (try
                             {:ok (current-session-fn sid)}
                             (catch Throwable t {:error t})))
                         use
                         (cond
                           (nil? current-result) session
                           (:error current-result) session
                           (nil? (:ok current-result)) nil
                           (>= (long (or (:revision (:ok current-result)) 0))
                               snapshot-rev)
                           (:ok current-result)
                           :else session)]
                     (cond
                       (cancelled-now?)
                       {:cancelled? true
                        :results results
                        :indexed indexed
                        :skipped skipped}

                       (nil? use)
                       (recur (rest remaining)
                              (conj results
                                    {:indexed? false
                                     :skipped? true
                                     :reason :deleted
                                     :postings-put 0
                                     :postings-deleted 0})
                              indexed
                              (inc skipped))

                       :else
                       (let [result (index-session! index use opts)]
                         (recur (rest remaining)
                                (conj results result)
                                (if (:indexed? result)
                                  (inc indexed)
                                  indexed)
                                (if (:skipped? result)
                                  (inc skipped)
                                  skipped))))))))]
        (if (:cancelled? loop-result)
          (assoc (cancelled-rebuild
                  (:indexed loop-result)
                  generation
                  (count sessions)
                  (:skipped loop-result)
                  (:results loop-result))
                 :list-refreshed? (:ok? refreshed)
                 :cleared cleared)
          (let [ready?
                (locking store
                  (if (cancelled-now?)
                    false
                    (do
                      (write-meta! store generation :ready
                                   (index-statistics store))
                      true)))]
            (if (not ready?)
              (assoc (cancelled-rebuild
                      (:indexed loop-result)
                      generation
                      (count sessions)
                      (:skipped loop-result)
                      (:results loop-result))
                     :list-refreshed? (:ok? refreshed)
                     :cleared cleared)
              {:ok? true
               :generation generation
               :state :ready
               :sessions (count sessions)
               :indexed (:indexed loop-result)
               :skipped (:skipped loop-result)
               :list-refreshed? (:ok? refreshed)
               :cleared cleared
               :postings-put
               (reduce + 0 (map :postings-put (:results loop-result)))
               :postings-deleted
               (reduce + 0
                       (map :postings-deleted (:results loop-result)))})))))))))

;; ---------------------------------------------------------------------------
;; Health and startup self-healing

(defn- full-session-map?
  "True for a session record that carries the body map `index-session!` reads.
  A summary row without `:messages` is not a rebuild source."
  [session]
  (and (map? session)
       (or (:session-id session) (:id session))
       (map? (:messages session))))

(defn- checked-listed-sessions
  "Normalize a list-sessions-fn result, but refuse values that cannot be
  distinguished from a failed or truncated read.  A thrown function and an
  unusable return are treated as list failures by `ensure-ready!`; neither may
  be interpreted as an empty store because rebuilding would erase a valid
  index.

  A session must carry the full `:messages` map.  Summary rows from a list
  projection are rejected: rebuilding from summaries would silently index
  empty conversations."
  [value]
  (cond
    (and (sequential? value) (every? full-session-map? value))
    {:ok? true :sessions (vec value)}

    (and (map? value)
         (sequential? (:sessions value))
         (every? full-session-map? (:sessions value)))
    {:ok? true :sessions (vec (:sessions value))}

    (and (map? value)
         (sequential? (:items value))
         (every? full-session-map? (:items value)))
    {:ok? true :sessions (vec (:items value))}

    (and (map? value)
         (every? full-session-map? (vals value)))
    {:ok? true :sessions (vec (vals value))}

    :else
    {:ok? false}))

(defn- valid-generation [meta]
  (let [generation (:generation meta)]
    (when (and (integer? generation) (pos? generation))
      generation)))

(defn index-health
  "Return one conservative health record for the derived history index.

  The record is:

    {:state :ready|:building|:missing|:incompatible|:corrupt
     :generation g|nil
     :session-count n :message-count n
     :built-at ms|nil
     :needs-rebuild? bool
     :reason kw|nil}

  Counts are read from the actual `\"search\"` partition, not trusted from
  stale metadata.  The meta `:session-count` is used only for the
  `:index-empty` inconsistency check.  Any read failure is reported as
  `:corrupt`/`:corrupt-meta` rather than thrown; it is not evidence that the
  index is empty.

  A ready index is not rebuilt merely because the main store has newer
  commits.  Incremental freshness belongs to `index-session!`; startup health
  only detects missing, interrupted, incompatible or internally inconsistent
  derived state."
  [store]
  (try
    (let [store (store-of store)
          statistics (index-statistics store)
          meta (read-meta* store)
          generation (when (map? meta) (valid-generation meta))
          base (merge statistics
                      {:generation generation
                       :built-at (when (map? meta) (:updated-at meta))})]
      (cond
        (nil? meta)
        (assoc base
               :state :missing
               :needs-rebuild? true
               :reason :no-meta)

        (not (map? meta))
        (assoc base
               :state :corrupt
               :needs-rebuild? true
               :reason :corrupt-meta)

        (contains? #{:building :rebuilding} (:state meta))
        (assoc base
               :state :building
               :needs-rebuild? true
               :reason :building-leftover)

        (not= schema-version (:schema-version meta))
        (assoc base
               :state :incompatible
               :needs-rebuild? true
               :reason :schema-version-mismatch)

        (not= tokenizer-version (:tokenizer-version meta))
        (assoc base
               :state :incompatible
               :needs-rebuild? true
               :reason :tokenizer-version-mismatch)

        (nil? generation)
        (assoc base
               :state :corrupt
               :needs-rebuild? true
               :reason :generation-missing)

        (not= :ready (:state meta))
        (assoc base
               :state :corrupt
               :needs-rebuild? true
               :reason :corrupt-meta)

        (and (pos? (or (:session-count meta) 0))
             (zero? (:session-count statistics 0)))
        (assoc base
               :state :corrupt
               :needs-rebuild? true
               :reason :index-empty)

        :else
        (assoc base
               :state :ready
               :needs-rebuild? false
               :reason nil)))
    (catch Throwable _
      {:state :corrupt
       :generation nil
       :session-count 0
       :message-count 0
       :built-at nil
       :needs-rebuild? true
       :reason :corrupt-meta})))

(defn- throwable-cause-details
  "Preserve the lower structured cause without changing the top-level code or
  message.  An empty ex-data still identifies the throwable class."
  [throwable]
  (let [data (ex-data throwable)
        cause (or (:code data) (:error data) :unknown)]
    {:cause cause
     :cause-details (if (seq data)
                      (not-empty
                       (dissoc data :error :code :storage/expected?))
                      {:class (.getName (class throwable))})}))

(defn ensure-ready!
  "Make the derived index usable at startup, with conservative rebuilding.

  OPTS:
    :list-sessions-fn required only when a rebuild is needed or FORCE? is
                      true; returns authoritative full session maps.
    :force?           true rebuilds even when health says ready.

  A healthy index returns `{:ok? true :action :none :generation g :health h}`
  without calling the list function or writing any document.

  A rebuild returns `{:ok? true :action :rebuilt :generation g' :health h'}`
  where HEALTH is read after the rebuild.

  LIST-SESSIONS-FN is called and normalized BEFORE any rebuild write.  If it
  throws or returns an unusable value, the result is
  `:INDEX_REBUILD_LIST_FAILED` and the existing index is untouched.

  If the rebuild itself fails after the `:building` marker was written, the
  meta document is deliberately left as `:building`; the next
  `ensure-ready!` sees `:building-leftover` and retries.  This is preferable
  to claiming a partial generation is ready.  The error is returned, never
  thrown."
  [store {:keys [list-sessions-fn force? current-session-fn] :as opts}]
  (let [store (store-of store)
        health (index-health store)]
    (if (cancellation-requested? store opts)
      {:ok? false
       :cancelled? true
       :error {:code :INDEX_REBUILD_CANCELLED
               :message "the index rebuild was cancelled before it started"}
       :health health}
      (if (and (not (true? force?))
               (not (:needs-rebuild? health)))
        {:ok? true
         :action :none
         :generation (:generation health)
         :health health}
        (if-not (ifn? list-sessions-fn)
          {:ok? false
           :error {:code :INDEX_REBUILD_LIST_FAILED
                   :message "list-sessions-fn is required for an index rebuild"
                   :details {:cause :unknown
                             :cause-details
                             {:reason :list-sessions-fn-missing}}}
           :health health}
          (let [listed (try
                         (checked-listed-sessions (list-sessions-fn))
                         (catch Throwable throwable
                           {:ok? false :throwable throwable}))]
            (if-not (:ok? listed)
              (let [throwable (:throwable listed)
                    details (if throwable
                              (throwable-cause-details throwable)
                              {:cause :unknown
                               :cause-details
                               {:reason :list-sessions-fn-unusable}})]
                {:ok? false
                 :error {:code :INDEX_REBUILD_LIST_FAILED
                         :message (or (some-> throwable ex-message)
                                      "list-sessions-fn returned an unusable value")
                         :details details}
                 :health health})
              (try
                (let [result
                      (rebuild! store
                                (fn [] (:sessions listed))
                                (cond-> {}
                                  current-session-fn
                                  (assoc :current-session-fn current-session-fn)
                                  (some? (:cancelled? opts))
                                  (assoc :cancelled? (:cancelled? opts))
                                  (some? (:store-open? opts))
                                  (assoc :store-open? (:store-open? opts))))]
                  (if (false? (:ok? result))
                    {:ok? false
                     :action :cancelled
                     :cancelled? (boolean (:cancelled? result))
                     :error (or (:error result)
                                {:code :INDEX_REBUILD_CANCELLED
                                 :message "the index rebuild was cancelled"})
                     :health (index-health store)}
                    {:ok? true
                     :action :rebuilt
                     :generation (:generation result)
                     :health (index-health store)}))
                (catch Throwable throwable
                  {:ok? false
                   :error {:code :INDEX_REBUILD_FAILED
                           :message (or (ex-message throwable)
                                        "index rebuild failed")
                           :details (throwable-cause-details throwable)}
                   :health (index-health store)})))))))))
