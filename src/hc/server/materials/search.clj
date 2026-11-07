(ns hc.server.materials.search
  "Search references: stable source identity, date separation and citations.

  Authority: root AGENTS.md §5.5 and §7.4 旅程 6.1（搜索）.

  A reference set is a value, not a persisted global index. It belongs to the
  message or turn that used the sources. The caller supplies the real query
  ids and fetch times; this namespace reads no clock and performs no network
  request.

  Two dates never collapse:

    :published-at  what the site or service reported, or nil (unknown)
    :fetched-at    when this product obtained the result

  A nil :published-at is rendered as unknown. It is never replaced by the
  fetch time.

  The same canonical URL discovered by several queries is one source. Its
  citation number is assigned on first citation and reused afterwards."
  (:require [clojure.string :as str]
            [hc.server.materials.errors :as errors])
  (:import (java.net URI URISyntaxException)))

(def reference-version 1)

(def content-statuses
  #{:unknown :blocked :summary :partial :full})

(def content-status-rank
  {:unknown 0
   :blocked 1
   :summary 2
   :partial 3
   :full 4})

(defn new-set
  "An empty reference set."
  []
  {:v reference-version
   :sources (sorted-map)
   :order []
   :next-citation 1})

;; ---------------------------------------------------------------------------
;; URL identity

(defn- parse-uri
  [url]
  (when (and (string? url) (not (str/blank? url)))
    (try
      (URI. url)
      (catch URISyntaxException _ nil))))

(defn canonical-url
  "Canonical identity for an http(s) URL, or nil when the URL is not usable.

  Normalization is deliberately small: lowercase scheme and host, remove the
  default port, remove the fragment and normalize an empty path to `/`.
  Query parameters keep their order; reordering them could merge two
  different resources.

  User-info is refused: credentials do not belong in a visible source card."
  [url]
  (when-let [^URI uri (parse-uri url)]
    (let [scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) str/lower-case)
          port (.getPort uri)]
      (when (and (contains? #{"http" "https"} scheme)
                 (not (str/blank? host))
                 (nil? (.getUserInfo uri)))
        (let [default-port? (or (and (= scheme "http") (= port 80))
                                (and (= scheme "https") (= port 443)))
              authority (if (or (neg? port) default-port?)
                          host
                          (str host ":" port))
              path (let [raw (.getRawPath uri)]
                     (if (str/blank? raw) "/" raw))
              query (.getRawQuery uri)]
          (str scheme "://" authority path
               (when (some? query) (str "?" query))))))))

(defn valid-url?
  [url]
  (some? (canonical-url url)))

(defn site-of
  "The host shown on a source card."
  [url]
  (some-> (parse-uri url) .getHost str/lower-case))

(defn- derive-title
  "A neutral title when the service returned none."
  [canonical]
  (let [uri (parse-uri canonical)
        path (some-> uri .getRawPath)
        segment (->> (str/split (or path "") #"/")
                     (remove str/blank?)
                     last)]
    (if (and (string? segment) (not (str/blank? segment)))
      (-> segment
          (str/replace #"%[0-9A-Fa-f]{2}" " ")
          (str/replace #"[-_]+" " ")
          str/trim)
      (site-of canonical))))

;; ---------------------------------------------------------------------------
;; Result normalization

(defn- invalid [code message details]
  {:ok? false
   :error (errors/error code message details)})

(defn- optional-string
  [value]
  (cond
    (nil? value) nil
    (not (string? value)) ::invalid
    (str/blank? value) nil
    :else value))

(defn- normalize-result
  [result query-id]
  (cond
    (not (map? result))
    (invalid :SEARCH_RESULT_INVALID
             "search result must be a map"
             {:result result})

    :else
    (let [canonical (canonical-url (:url result))
          title (optional-string (:title result))
          site (optional-string (:site result))
          published (optional-string (:published-at result))
          snippet (optional-string (:snippet result))
          status (get result :content-status :summary)]
      (cond
        (nil? canonical)
        (invalid :SEARCH_URL_INVALID
                 "search result needs a usable http(s) URL"
                 {:url (:url result)})

        (= ::invalid title)
        (invalid :SEARCH_RESULT_INVALID
                 "search result title must be a string"
                 {:title (:title result)})

        (= ::invalid site)
        (invalid :SEARCH_RESULT_INVALID
                 "search result site must be a string"
                 {:site (:site result)})

        (= ::invalid published)
        (invalid :SEARCH_PUBLISHED_AT_INVALID
                 "published-at must be a string when present"
                 {:published-at (:published-at result)})

        (= ::invalid snippet)
        (invalid :SEARCH_RESULT_INVALID
                 "search result snippet must be a string"
                 {:snippet (:snippet result)})

        (not (contains? content-statuses status))
        (invalid :SEARCH_CONTENT_STATUS_INVALID
                 "content-status is unknown"
                 {:content-status status
                  :allowed (vec (keys content-status-rank))})

        :else
        {:ok? true
         :result {:canonical-url canonical
                  :url (:url result)
                  :title title
                  :site (or site (site-of canonical))
                  :published-at published
                  :snippet snippet
                  :content-status status
                  :query-id query-id}}))))

;; ---------------------------------------------------------------------------
;; Merging

(defn- better-status
  [a b]
  (if (>= (get content-status-rank a 0)
          (get content-status-rank b 0))
    a
    b))

(defn- add-alternative
  [alternatives value]
  (if (or (nil? value)
          (= value (last alternatives))
          (some #{value} alternatives))
    alternatives
    (conj (vec alternatives) value)))

(defn- new-source
  [result fetched-at]
  (let [title (:title result)]
    {:canonical-url (:canonical-url result)
     :url (:url result)
     :title (or title (derive-title (:canonical-url result)))
     :title-derived? (nil? title)
     :site (:site result)
     :published-at (:published-at result)
     :published-at-alternatives []
     :fetched-at fetched-at
     :last-fetched-at fetched-at
     :snippet (:snippet result)
     :content-status (:content-status result)
     :queries [(:query-id result)]
     :citation-number nil}))

(defn- merge-result
  [source result fetched-at]
  (let [published (:published-at result)
        existing-published (:published-at source)
        alternatives (if (and (some? existing-published)
                              (some? published)
                              (not= existing-published published))
                       (add-alternative (:published-at-alternatives source)
                                        published)
                       (:published-at-alternatives source))]
    (-> source
        (update :queries
                (fn [queries]
                  (if (some #{(:query-id result)} queries)
                    queries
                    (conj (vec queries) (:query-id result)))))
        (assoc :last-fetched-at fetched-at)
        (cond-> (nil? existing-published) (assoc :published-at published)
                (and (or (nil? (:title source))
                         (:title-derived? source))
                     (some? (:title result)))
                (assoc :title (:title result)
                       :title-derived? false)
                (nil? (:title source))
                (assoc :title (derive-title (:canonical-url source))
                       :title-derived? true)
                (nil? (:site source)) (assoc :site (:site result))
                (nil? (:snippet source)) (assoc :snippet (:snippet result))
                true (assoc :content-status
                            (better-status (:content-status source)
                                           (:content-status result))))
        (assoc :published-at-alternatives alternatives))))

(defn add-results
  "Add one query's results, merging duplicate URLs into existing sources.

  REQUEST:

    {:query-id \"q1\"
     :fetched-at \"2026-11-03T10:00:00Z\"
     :results [{:url \"https://example.com/a\"
                :title \"...\"
                :site \"example.com\"
                :published-at \"2026-10-01\"   ; optional
                :snippet \"...\"
                :content-status :summary}]}

  All results are validated before any is merged. The caller must supply the
  real fetch time; this namespace does not call a clock."
  [refs request]
  (let [request (or request {})
        query-id (:query-id request)
        fetched-at (:fetched-at request)
        results (:results request)]
    (cond
      (not (map? refs))
      (invalid :SEARCH_SET_INVALID "reference set must be a map" {:refs refs})

      (not (errors/non-blank-string? query-id))
      (invalid :SEARCH_QUERY_INVALID
               "query-id must be a non-blank string" {:query-id query-id})

      (not (errors/non-blank-string? fetched-at))
      (invalid :SEARCH_FETCHED_AT_INVALID
               "fetched-at must be a non-blank string" {:fetched-at fetched-at})

      (not (sequential? results))
      (invalid :SEARCH_RESULTS_INVALID
               "results must be a sequence" {:results results})

      :else
      (let [normalized (mapv #(normalize-result % query-id) results)
            failed (first (remove :ok? normalized))]
        (if failed
          (assoc failed :refs refs)
          (let [results (mapv :result normalized)
                [sources order added merged]
                (reduce (fn [[sources order added merged] result]
                          (let [canonical (:canonical-url result)
                                existing (get sources canonical)]
                            (if existing
                              [(assoc sources canonical
                                      (merge-result existing result fetched-at))
                               order
                               added
                               (conj merged canonical)]
                              [(assoc sources canonical
                                      (new-source result fetched-at))
                               (conj order canonical)
                               (conj added canonical)
                               merged])))
                        [(or (:sources refs) (sorted-map))
                         (vec (or (:order refs) []))
                         []
                         []]
                        results)]
            {:ok? true
             :refs (assoc refs
                          :sources sources
                          :order order)
             :added added
             :merged merged
             :count (count results)}))))))

;; ---------------------------------------------------------------------------
;; Citations

(defn- source-by-url
  [refs url]
  (when-let [canonical (canonical-url url)]
    (get-in refs [:sources canonical])))

(declare public-source)

(defn citation-number
  "The assigned citation number for URL, or nil when it was never cited."
  [refs url]
  (:citation-number (source-by-url refs url)))

(defn cite
  "Assign or reuse a citation number for one real source.

  An unknown URL is refused. The package never invents a source from model
  prose: only a URL that was returned by a search result can be cited."
  [refs url]
  (let [source (source-by-url refs url)]
    (cond
      (not (map? refs))
      (invalid :SEARCH_SET_INVALID "reference set must be a map" {:refs refs})

      (nil? source)
      (invalid :SEARCH_SOURCE_UNKNOWN
               "cannot cite a URL that no search result returned"
               {:url url})

      (some? (:citation-number source))
      {:ok? true
       :refs refs
       :number (:citation-number source)
       :source (public-source source)
       :replayed? true}

      :else
      (let [number (long (or (:next-citation refs) 1))
            canonical (:canonical-url source)]
        {:ok? true
         :refs (-> refs
                   (assoc-in [:sources canonical :citation-number] number)
                   (assoc :next-citation (inc number)))
         :number number
         :source (public-source (assoc source :citation-number number))
         :replayed? false}))))

(defn citation-marker
  "The visible marker for a source, or nil when it is not cited."
  [refs url]
  (when-let [number (citation-number refs url)]
    (str "[" number "]")))

(defn- public-source
  [source]
  (assoc source :published-at-unknown? (nil? (:published-at source))))

(defn source-list
  "Return cited sources in first-citation order and uncited results separately.

  Uncited results are not dropped; they belong in the \"other results\" area."
  [refs]
  (let [ordered (map #(get-in refs [:sources %]) (:order refs))
        cited (->> ordered
                   (filter :citation-number)
                   (sort-by :citation-number)
                   (mapv public-source))
        other (->> ordered
                   (remove :citation-number)
                   (mapv public-source))]
    {:cited cited
     :other other}))

;; ---------------------------------------------------------------------------
;; Read status

(defn record-read
  "Record that a source was opened and how much was obtained.

  STATUS is one of :full, :partial, :blocked or :summary. READ-AT is the
  caller's real timestamp; it is not used as a publication date."
  [refs url {:keys [status read-at]}]
  (let [source (source-by-url refs url)]
    (cond
      (not (map? refs))
      (invalid :SEARCH_SET_INVALID "reference set must be a map" {:refs refs})

      (nil? source)
      (invalid :SEARCH_SOURCE_UNKNOWN
               "cannot update a URL that no search result returned"
               {:url url})

      (not (contains? content-statuses status))
      (invalid :SEARCH_CONTENT_STATUS_INVALID
               "content-status is unknown"
               {:content-status status
                :allowed (vec (keys content-status-rank))})

      (not (errors/non-blank-string? read-at))
      (invalid :SEARCH_FETCHED_AT_INVALID
               "read-at must be a non-blank string" {:read-at read-at})

      :else
      (let [canonical (:canonical-url source)]
        {:ok? true
         :refs (-> refs
                   (assoc-in [:sources canonical :content-status]
                             (better-status (:content-status source) status))
                   (assoc-in [:sources canonical :read-at] read-at))
         :source (public-source
                  (assoc source
                         :content-status (better-status (:content-status source)
                                                        status)
                         :read-at read-at))}))))

;; ---------------------------------------------------------------------------
;; Plain-text export

(defn- date-line
  [source]
  (str "published: " (or (:published-at source) "unknown")
       "; fetched: " (:fetched-at source)))

(defn plain-text
  "Plain-text source appendix with stable numbers and complete URLs.

  A cited source always keeps its number. A source without a publication date
  says unknown; the fetch time is printed separately."
  [refs]
  (let [{:keys [cited other]} (source-list refs)]
    (str "Sources:\n"
         (if (seq cited)
           (str/join "\n"
                     (map (fn [source]
                            (str "[" (:citation-number source) "] "
                                 (or (:title source) (:url source)) " — "
                                 (:url source)
                                 " (" (date-line source) ")"))
                          cited))
           "none")
         (when (seq other)
           (str "\nOther results:\n"
                (str/join "\n"
                          (map (fn [source]
                                 (str "- " (or (:title source) (:url source))
                                      " — " (:url source)
                                      " (" (date-line source) ")"))
                               other)))))))

;; ---------------------------------------------------------------------------
;; Invariants

(defn check
  "Return reference-set invariant violations."
  [refs]
  (if-not (map? refs)
    [{:invariant :search-set-not-a-map :refs refs}]
    (let [sources (:sources refs)
          order (:order refs)
          next-citation (:next-citation refs)
          base (cond-> []
                 (not= reference-version (:v refs))
                 (conj {:invariant :search-set-version-invalid
                        :version (:v refs)})

                 (not (map? sources))
                 (conj {:invariant :search-sources-not-a-map :sources sources})

                 (not (vector? order))
                 (conj {:invariant :search-order-invalid :order order})

                 (and (vector? order)
                      (not= (count order) (count (set order))))
                 (conj {:invariant :search-order-duplicate :order order})

                 (not (and (integer? next-citation) (pos? next-citation)))
                 (conj {:invariant :search-next-citation-invalid
                        :next-citation next-citation})

                 (and (map? sources) (vector? order)
                      (not= (set order) (set (keys sources))))
                 (conj {:invariant :search-order-source-mismatch
                        :order order
                        :sources (vec (keys sources))}))
          citation-violations
          (if (and (map? sources) (integer? next-citation))
            (let [numbers (keep :citation-number (vals sources))
                  valid-numbers (filter integer? numbers)]
              (cond-> []
                (not= (count numbers) (count (set numbers)))
                (conj {:invariant :search-citation-duplicate
                       :numbers (vec numbers)})

                (not= (count numbers) (count valid-numbers))
                (conj {:invariant :search-citation-not-integer
                       :numbers (vec numbers)})

                (some #(or (not (pos? %)) (>= % next-citation)) valid-numbers)
                (conj {:invariant :search-citation-out-of-range
                       :numbers (vec valid-numbers)
                       :next-citation next-citation})))
            [])
          source-violations
          (if (map? sources)
            (into []
                  (keep (fn [[canonical source]]
                          (let [number (:citation-number source)]
                            (cond
                              (not (map? source))
                              {:invariant :search-source-not-a-map
                               :canonical-url canonical}

                              (not= canonical (:canonical-url source))
                              {:invariant :search-source-key-mismatch
                               :key canonical
                               :canonical-url (:canonical-url source)}

                              (not (valid-url? (:url source)))
                              {:invariant :search-source-url-invalid
                               :canonical-url canonical}

                              (not= canonical (canonical-url (:url source)))
                              {:invariant :search-source-canonical-mismatch
                               :canonical-url canonical
                               :url (:url source)}

                              (not (errors/non-blank-string? (:fetched-at source)))
                              {:invariant :search-source-fetched-at-invalid
                               :canonical-url canonical}

                              (not (errors/non-blank-string?
                                    (:last-fetched-at source)))
                              {:invariant :search-source-last-fetched-at-invalid
                               :canonical-url canonical}

                              (and (some? (:title source))
                                   (not (string? (:title source))))
                              {:invariant :search-source-title-invalid
                               :canonical-url canonical}

                              (and (some? (:site source))
                                   (not (string? (:site source))))
                              {:invariant :search-source-site-invalid
                               :canonical-url canonical}

                              (not (contains? content-statuses
                                              (:content-status source)))
                              {:invariant :search-source-status-invalid
                               :canonical-url canonical}

                              (not (and (vector? (:queries source))
                                        (seq (:queries source))
                                        (every? errors/non-blank-string?
                                                (:queries source))))
                              {:invariant :search-source-queries-invalid
                               :canonical-url canonical}

                              (and (some? (:published-at source))
                                   (not (string? (:published-at source))))
                              {:invariant :search-source-published-at-invalid
                               :canonical-url canonical}

                              (and (some? number) (not (integer? number)))
                              {:invariant :search-source-citation-invalid
                               :canonical-url canonical
                               :citation-number number}

                              (not (and (vector? (:published-at-alternatives source))
                                        (every? string?
                                                (:published-at-alternatives source))))
                              {:invariant :search-source-date-alternatives-invalid
                               :canonical-url canonical}

                              :else nil)))
                  sources))
            [])]
      (into [] (concat base citation-violations source-violations)))))
