(ns hc.server.app.search-commands
  "Command handlers for the rebuildable history full-text index.

  This namespace is a thin adapter between the app command router and
  `hc.server.history.index`.  It owns no store and starts no work: the caller
  injects `:store`, `:read-session-fn` and `:list-sessions-fn`.

  The browser must never be able to rebuild the index.  `reindex-handler`
  therefore captures trust at construction time; a payload field cannot turn
  it on.

  Return shapes are data, never thrown:

    {:status :ok    :payload {..}}
    {:status :error :error {:code .. :message .. :details ..?}}

  Search payload keys are allow-listed.  Unknown keys are ignored and are
  never forwarded to `index/search`."
  (:require [clojure.string :as str]
            [hc.server.history.index :as index]
            [hc.server.storage :as storage]))

(def ^:private search-payload-keys
  [:query :assistant-id :status :include-versions? :has-attachments?
   :from-ms :to-ms])

(def ^:private codes
  {:query-invalid :SEARCH_QUERY_INVALID
   :limit-invalid :SEARCH_LIMIT_INVALID
   :session-invalid :SEARCH_SESSION_INVALID
   :search-failed :HISTORY_SEARCH_FAILED
   :status-failed :HISTORY_SEARCH_STATUS_FAILED
   :reindex-failed :HISTORY_REINDEX_FAILED
   :reindex-untrusted :REINDEX_REQUIRES_TRUSTED_CALLER})

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- error-result [code message details]
  (cond-> {:status :error
           :error {:code code :message message}}
    (some? details) (assoc-in [:error :details] details)))

(defn- failure-from-throwable
  "Convert an index/runtime failure into the command error shape.

  We deliberately catch Throwable at the command boundary so a malformed
  index or a missing injected function cannot tear down the transport
  dispatcher.  A `:search/error` ex-info keeps its data as details."
  [throwable fallback-code]
  (let [data (ex-data throwable)
        details (when (map? data) (dissoc data :search/error))]
    (error-result (or (:code data) fallback-code)
                  (or (ex-message throwable)
                      "search index operation failed")
                  (when (seq details) details))))

(defn- resolve-limit
  "Resolve payload/default limit.  Positive integers are clamped to
  `index/max-limit`; zero, negatives and non-integers are refused."
  [default-limit payload]
  (let [raw (if (contains? payload :limit)
              (:limit payload)
              default-limit)]
    (cond
      (nil? raw)
      {:ok? true :limit nil}

      (and (integer? raw) (pos? raw))
      {:ok? true :limit (long (min raw index/max-limit))}

      :else
      {:ok? false})))

(defn- distinct-message-count [session-doc]
  (count (distinct (map (fn [key]
                          (if (vector? key) (first key) key))
                        (keys (:messages session-doc))))))

(defn- index-documents [store]
  (get-in (storage/export-state store {:partition index/partition})
          [:partitions index/partition :docs]
          []))

(defn search-handler
  "Build a `:history/search` handler.

  OPTS:
    :store           required open storage handle
    :read-session-fn required function which reads the authoritative session
    :limit           optional default limit (payload `:limit` wins)

  PAYLOAD:
    :query :assistant-id :status :include-versions? :has-attachments?
    :from-ms :to-ms :limit :session-id

  Returns `{:status :ok :payload {:mode :search :query .. :hits .. :count ..
  :truncated? .. :index-generation ..}}` or the command error shape.
  Unknown payload keys are ignored and never forwarded to the index.

  `:session-id` is applied as a post-filter here.  Because `index/search`
  does not take a session predicate, a scoped search asks for the maximum
  index window and reports `:truncated?` conservatively when that window may
  hide further scoped hits."
  [{:keys [store read-session-fn limit]}]
  (fn [payload]
    (let [payload (if (map? payload) payload {})
          query (:query payload)
          limit-result (resolve-limit limit payload)
          scoped-session? (and (contains? payload :session-id)
                               (some? (:session-id payload)))
          session-id (:session-id payload)]
      (cond
        (not (non-blank-string? query))
        (error-result (:query-invalid codes)
                      "query must be a non-blank string"
                      {:query query})

        (not (:ok? limit-result))
        (error-result (:limit-invalid codes)
                      "limit must be a positive integer"
                      {:limit (get payload :limit limit)})

        (and scoped-session? (not (non-blank-string? session-id)))
        (error-result (:session-invalid codes)
                      "session-id must be a non-blank string"
                      {:session-id session-id})

        :else
        (try
          (let [effective-limit (:limit limit-result)
                request-limit (if scoped-session? index/max-limit effective-limit)
                index-request (cond-> (select-keys payload search-payload-keys)
                                request-limit (assoc :limit request-limit))
                result (index/search store index-request read-session-fn)
                all-hits (vec (or (:hits result) []))
                scoped-hits (if scoped-session?
                              (filterv #(= session-id (:session-id %)) all-hits)
                              all-hits)
                hits (if scoped-session?
                       (vec (take (or effective-limit index/default-limit)
                                  scoped-hits))
                       scoped-hits)
                total (or (:total result) (count all-hits))
                truncated? (if scoped-session?
                             (or (> (count scoped-hits) (count hits))
                                 (and (>= (count all-hits) index/max-limit)
                                      (> total (count all-hits))))
                             (> total (count hits)))
                generation (or (:generation (index/index-meta store)) 0)]
            {:status :ok
             :payload {:mode :search
                       :query (str/trim query)
                       :hits hits
                       :count (count hits)
                       :truncated? (boolean truncated?)
                       :index-generation generation}})
          (catch Throwable throwable
            (failure-from-throwable throwable (:search-failed codes))))))))

(defn search-status-handler
  "Build a `:history/search-status` handler.

  OPTS: `:store` required.

  The state, generation and built-at values come from `index/index-meta`.
  Session and distinct-message counts come from a read-only scan of the
  `\"search\"` partition.  A missing control document is `:missing`, not an
  error; that is a fresh or restored store which has not rebuilt yet."
  [{:keys [store]}]
  (fn [_payload]
    (try
      (let [meta (index/index-meta store)
            documents (index-documents store)
            sessions (filter #(= index/session-kind (:kind %)) documents)
            state (cond
                    (nil? meta) :missing
                    (= :ready (:state meta)) :ready
                    :else :building)]
        {:status :ok
         :payload {:state state
                   :generation (or (:generation meta) 0)
                   :session-count (count sessions)
                   :message-count (reduce + 0
                                          (map (comp distinct-message-count :doc)
                                               sessions))
                   :built-at (:updated-at meta)
                   :last-error (:last-error meta)}})
      (catch Throwable throwable
        (failure-from-throwable throwable (:status-failed codes))))))

(defn reindex-handler
  "Build a `:history/reindex` handler.

  OPTS:
    :store            required open storage handle
    :list-sessions-fn required function returning authoritative session maps
    :allow-reindex?   trust switch.  Only an explicit `true` permits rebuild.
    :session-ids      accepted for future incremental callers; this slice
                      always performs the complete rebuild so narrowing the
                      source list cannot delete other sessions from the index.

  The trust switch is captured here, never read from the payload.  A browser
  cannot turn a refused handler into a rebuild by adding fields.

  Returns `{:status :ok :payload {:generation g :session-count n}}`."
  [{:keys [store list-sessions-fn allow-reindex?]}]
  (fn [_payload]
    (if-not (true? allow-reindex?)
      (error-result (:reindex-untrusted codes)
                    "reindex requires a trusted server-side caller"
                    nil)
      (try
        (let [result (index/rebuild! store list-sessions-fn)]
          {:status :ok
           :payload {:generation (or (:generation result) 0)
                     :session-count (or (:indexed result)
                                        (:sessions result)
                                        0)}})
        (catch Throwable throwable
          (failure-from-throwable throwable (:reindex-failed codes)))))))

(defn command-kinds
  "Command kinds served by this adapter."
  []
  #{:history/search :history/search-status :history/reindex})

(defn handlers
  "Build the command-kind -> handler map.

  OPTS is shared by all three handlers; each one reads only its own keys."
  [opts]
  {:history/search (search-handler opts)
   :history/search-status (search-status-handler opts)
   :history/reindex (reindex-handler opts)})
