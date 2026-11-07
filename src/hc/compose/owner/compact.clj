(ns hc.compose.owner.compact
  "Bounded current-state compaction for large program sources and receipts.

  The Owner state is the current-state authority, not a content store.  A
  program source or tool receipt that repeats the same long text in a call
  request/result is replaced by a small reference:

    {:source-ref \"blob-...\"
     :source-hash \"...\"
     :source-bytes 12345
     :source-preview \"first 1 KiB\"}

  The blob store is injected.  A missing store leaves the value unchanged.
  Compaction is idempotent: an existing reference map is not rewritten.

  `restore-state` expands references through the same store.  Recovery does
  not need it: a terminal call never runs again, and its preview is enough to
  identify the original.  Backup/export and forensic readers use it when they
  need the full text.  A restored state must be compacted again before it is
  committed, or the document grows back to its uncompacted size."
  (:require [clojure.edn :as edn])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def max-inline-chars
  "Longest string kept inline in a call/cancel record.

  A terminal call keeps its preview only; the full source or receipt lives in
  the blob store.  One KiB identifies the program or receipt without turning
  every call into a second copy of the content."
  1024)

(def large-keys
  "Fields that carry program sources or tool receipts."
  #{:raw-source :source :numbered-source :program-source :receipts
    :source-preview :message :hint :stack :stdout :stderr :output :body
    :details :late-results :receipt :terminal :node})

(def recursive-keys
  "Container fields that may hold large keys.  Ordinary conversation text is
  never in this set."
  #{:receipts :late-results :evidence :result :request :value :payload :error
    :retry :last-error :last-failure :output :body :details :receipt :terminal
    :node :nodes})

(defn- sha256
  [^String text]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and (long %) 0xFF))
                    (.digest digest (.getBytes text StandardCharsets/UTF_8))))))

(defn memory-blob-store
  "Reference blob store for tests.  `:get` returns the original text."
  []
  (let [blobs (atom {})]
    {:put! (fn [text]
             (let [hash (sha256 text)
                   ref (str "blob-" hash)]
               (swap! blobs assoc ref text)
               {:ref ref :hash hash :bytes (count text)}))
     :get (fn [ref] (get @blobs ref))
     :count (fn [] (count @blobs))}))

(defn- preview
  [text]
  (subs text 0 (min max-inline-chars (count text))))

(defn- reference
  [store text]
  (if-let [put! (:put! store)]
    (let [{:keys [ref hash bytes]} (put! text)]
      {:source-ref ref
       :source-hash hash
       :source-bytes bytes
       :source-preview (preview text)})
    text))

(defn- compact-value
  [store value]
  (cond
    (and (string? value)
         (> (count value) max-inline-chars))
    (reference store value)

    (map? value)
    (into {}
          (map (fn [[k v]]
                 [k (cond
                      (contains? large-keys k)
                      (if (and (string? v)
                               (> (count v) max-inline-chars))
                        (reference store v)
                        (compact-value store v))

                      ;; Recurse through receipts/late results but never
                      ;; rewrite ordinary conversation text.
                      (contains? recursive-keys k)
                      (compact-value store v)

                      :else v)]))
          value)

    (vector? value)
    (mapv #(compact-value store %) value)

    (list? value)
    (apply list (map #(compact-value store %) value))

    :else value))

(def terminal-call-states
  #{:succeeded :failed :cancelled :outcome-unknown})

(defn- compact-error
  [value]
  (when (map? value)
    (select-keys (assoc value :message (preview (str (:message value))))
                 [:code :message :phase])))

(defn- compact-retry
  [retry]
  (when (map? retry)
    (select-keys retry [:attempts :max-attempts :retries :max-retries
                        :deadline :retry-at :next-at :last-error-code])))

(defn- request-referenced?
  [request]
  (and (map? request)
       (string? (:request-ref request))
       (contains? request :request-preview)))

(defn- compact-request
  [store request]
  (let [text (pr-str request)]
    (cond
      (request-referenced? request)
      request

      (and store (:put! store) (> (count text) max-inline-chars))
      (let [{:keys [ref hash bytes]} ((:put! store) text)]
        {:request-ref ref
         :request-hash hash
         :request-bytes bytes
         :request-preview (preview text)
         :request-encoding :edn
         :messages-count (count (get-in request [:snapshot :messages]))})

      :else request)))

(defn- compact-call
  [store call]
  (let [call (compact-value store call)]
    (if (contains? terminal-call-states (:state call))
      (-> call
          (update :retry compact-retry)
          (update :last-error compact-error)
          (update :last-failure compact-error)
          (update :error compact-error)
          (update :request #(compact-request store %)))
      call)))

(defn compact-state
  "Return `[state' stats]` with large call/cancel content referenced.

  `stats` is `{:before :after :saved :refs}`.  The state is otherwise
  untouched: call ids, attempt ids, states, and scheduler claims stay
  byte-identical.

  The three-argument arity accepts the already measured BEFORE size.  The
  commit path measures the state once to decide whether compaction is
  needed; passing that size avoids a second full `pr-str` of a multi-megabyte
  current-state document."
  ([state store] (compact-state state store nil))
  ([state store measured-before]
  (if-not (and store (:put! store))
    [state {:before (or measured-before (count (pr-str state)))
            :after (count (pr-str state))
            :saved 0
            :refs 0
            :skipped? true}]
    (let [before (or measured-before (count (pr-str state)))
          state' (-> state
                     (update :calls
                             (fn [calls]
                               (into {} (map (fn [[k v]] [k (compact-call store v)])
                                             calls))))
                     (update :cancel
                             (fn [cancel]
                               (if (map? cancel)
                                 (update cancel :nodes
                                         (fn [nodes]
                                           (into {} (map (fn [[k v]]
                                                           [k (compact-value store v)])
                                                         nodes))))
                                 cancel)))
                     (update :runs
                             (fn [runs]
                               (into {} (map (fn [[k v]]
                                               [k (compact-value store v)])
                                             runs)))))
          after (count (pr-str state'))]
      [state' {:before before
               :after after
               :saved (max 0 (- before after))
               :refs (if-let [c (:count store)] (c) 0)}]))))

(defn- source-reference?
  [value]
  (and (map? value)
       (string? (:source-ref value))
       (contains? value :source-preview)))

(defn- restore-reference
  [store value]
  (if-let [text (when-let [get-fn (:get store)]
                  (get-fn (:source-ref value)))]
    text
    value))

(defn- restore-request
  [store value]
  (if-let [text (when-let [get-fn (:get store)]
                  (get-fn (:request-ref value)))]
    (if (= :edn (:request-encoding value))
      (try (edn/read-string text)
           (catch Throwable _ text))
      text)
    value))

(defn restore-value
  "Expand every blob reference in VALUE through STORE.

  A missing store, missing blob, or unreadable EDN leaves the reference map
  intact so a caller can still see the hash and preview.  This is an explicit
  recovery/export operation; the Owner commit path never calls it."
  [store value]
  (cond
    (source-reference? value)
    (restore-reference store value)

    (and (map? value) (contains? value :request-ref))
    (restore-request store value)

    (map? value)
    (into {} (map (fn [[k v]] [k (restore-value store v)])) value)

    (vector? value)
    (mapv #(restore-value store %) value)

    (list? value)
    (apply list (map #(restore-value store %) value))

    :else value))

(defn restore-state
  "Expand the compacted call/cancel/run records in STATE through STORE.

  The result is a read-only forensic view: commit it only after compacting it
  again, because the full text is what made the document large in the first
  place."
  [state store]
  (if-not (and store (:get store))
    state
    (-> state
        (update :calls (fn [calls] (into {} (map (fn [[k v]]
                                                   [k (restore-value store v)])
                                                 calls))))
        (update :cancel (fn [cancel]
                          (if (map? cancel)
                            (update cancel :nodes
                                    (fn [nodes]
                                      (into {} (map (fn [[k v]]
                                                      [k (restore-value store v)])
                                                    nodes))))
                            cancel)))
        (update :runs (fn [runs] (into {} (map (fn [[k v]]
                                                 [k (restore-value store v)])
                                               runs)))))))
