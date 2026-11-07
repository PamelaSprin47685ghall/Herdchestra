(ns hc.server.search.providers.common
  "Shared result normalization for search providers.

  A provider's response is untrusted.  A result with no usable http(s) URL is
  dropped and counted, not allowed to fail the whole query.  A publication
  date is accepted only when it looks machine-readable; a human phrase is
  treated as unknown rather than displayed as a date."
  (:require [clojure.string :as str]
            [hc.server.materials.search :as refs]
            [hc.server.search.errors :as errors])
  (:import (java.net URI URLEncoder)
           (java.time LocalDate LocalDateTime OffsetDateTime)
           (java.time.format DateTimeParseException)
           (java.nio.charset StandardCharsets)))

(def max-title-chars 500)
(def max-snippet-chars 2000)
(def max-published-chars 100)
(def max-document-chars 200000)
(def max-result-count 50)

(defn clamp-count
  [value maximum]
  (-> (long (or value 8))
      (max 1)
      (min (long maximum))))

(defn normalize-count
  [value]
  (clamp-count value max-result-count))

(defn effective-preferences
  "Per-query values override the service's saved preferences."
  [service request]
  (let [preferences (:preferences service)]
    {:count (normalize-count (or (:count request)
                                 (:result-count preferences)))
     :language (or (:language request) (:language preferences))
     :country (or (:country request) (:country preferences))
     :safe-search (or (:safe-search request) (:safe-search preferences))
     :date-range (or (:date-range request) (:date-range preferences))
     :search-depth (or (:search-depth request) (:search-depth preferences))
     :include-raw-content? (boolean
                            (if (contains? request :include-raw-content?)
                              (:include-raw-content? request)
                              (:include-raw-content? preferences)))
     :categories (:categories preferences)
     :engines (:engines preferences)}))

(defn- valid-published-date?
  "True only for a real ISO date, local datetime or offset datetime.

  Shape matching is not enough: `2026-13-45` has the right characters but is
  not a date.  Unknown text must stay unknown instead of being displayed as
  a publication date."
  [^String value]
  (let [value (str/replace value " " "T")]
    (or (try
          (some? (LocalDate/parse value))
          (catch DateTimeParseException _ false))
        (try
          (some? (LocalDateTime/parse value))
          (catch DateTimeParseException _ false))
        (try
          (some? (OffsetDateTime/parse value))
          (catch DateTimeParseException _ false)))))

(defn published-at
  "Accept a real ISO date or datetime.  Anything else is unknown."
  [value]
  (when (string? value)
    (let [trimmed (str/trim value)]
      (when (and (<= (count trimmed) max-published-chars)
                 (valid-published-date? trimmed))
        trimmed))))

(defn- document
  [service request canonical text truncated?]
  {:text text
   :source :search-service
   :retrieval :search-result
   :service-id (:id service)
   :fetched-at (:fetched-at request)
   :truncated? truncated?
   :status (if truncated? :partial :full)
   :length (.length ^String text)})

(defn normalize-result
  "Convert one provider result to the materials reference shape.

  Returns `{:ok? true :result ... :document ...}` or `{:ok? false}`.  A
  document is present only when the service supplied full text."
  [service request raw]
  (if-not (map? raw)
    {:ok? false}
    (let [url (:url raw)
          canonical (refs/canonical-url url)]
      (if (nil? canonical)
        {:ok? false}
        (let [title (errors/truncate (errors/trim-to-nil (:title raw))
                                     max-title-chars)
              snippet (errors/truncate (errors/trim-to-nil (:snippet raw))
                                       max-snippet-chars)
              published (published-at (:published-at raw))
              raw-text (errors/trim-to-nil (:raw-content raw))
              text (when raw-text
                     (errors/truncate raw-text max-document-chars))
              truncated? (and raw-text
                              (> (.length ^String raw-text)
                                 max-document-chars))
              status (cond
                       (nil? text) :summary
                       truncated? :partial
                       :else :full)]
          {:ok? true
           :result {:url url
                    :title title
                    :snippet snippet
                    :published-at published
                    :content-status status}
           :document (when text
                       [canonical
                        (document service request canonical text
                                  truncated?)])})))))

(defn normalize-results
  "Normalize a provider result sequence and merge duplicate documents.

  Duplicate canonical URLs keep the longer text.  The result vector still
  contains every normalized row; `materials/add-results` performs the
  authoritative URL merge."
  [service request raws]
  (let [normalized (mapv #(normalize-result service request %) raws)
        valid (filterv :ok? normalized)
        documents (reduce (fn [documents [canonical value]]
                            (let [existing (get documents canonical)]
                              (if (and existing
                                       (>= (:length existing)
                                           (:length value)))
                                documents
                                (assoc documents canonical value))))
                          {}
                          (keep :document valid))]
    {:results (mapv :result valid)
     :documents documents
     :invalid-count (- (count normalized) (count valid))}))

(defn encode
  [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn query-uri
  "Append parameters to an endpoint that validation proved has no query."
  [base-url params]
  (let [pairs (for [[key value] params
                    :when (some? value)
                    :let [text (str value)]
                    :when (not (str/blank? text))]
                (str (encode (name key)) "=" (encode text)))]
    (URI. (str base-url "?" (str/join "&" pairs)))))

(defn date-filter
  "Record what the user asked for and what the service actually received."
  [requested applied params notice]
  (cond-> {:requested requested
           :applied applied
           :params params}
    notice (assoc :notice notice)))

(defn response-error
  "A provider response with an explicit error member is a service failure."
  [body service-id]
  (when (some? (:error body))
    (errors/refusal :SEARCH_SERVICE_ERROR
                    "the search service reported an error"
                    {:service-id service-id})))
