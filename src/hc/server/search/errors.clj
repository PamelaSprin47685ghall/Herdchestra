(ns hc.server.search.errors
  "Ordinary refusals for the search adapter.

  Expected failures are values, never exceptions:

    {:ok? false
     :error {:code :SEARCH_CREDENTIAL_INVALID
             :message \"...\"
             :details {:service-id \"...\"}}}

  DETAILS never contain credentials, request headers, request bodies or
  response bodies.  A caller may show them in a diagnostics card."
  (:require [clojure.string :as str]))

(defn error
  "Build one refusal error map. DETAILS is omitted when nil."
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn refusal
  "A refusal with no domain state attached."
  [code message details]
  {:ok? false
   :error (error code message details)})

(defn non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn trim-to-nil
  "Trim a string, returning nil for a blank or non-string value."
  [value]
  (when (string? value)
    (let [trimmed (str/trim value)]
      (when-not (str/blank? trimmed)
        trimmed))))

(defn truncate
  "Bound a string to LIMIT characters, appending no marker.

  The caller records the truncation separately; a marker inside the text
  would become part of a quotation."
  [value limit]
  (when (string? value)
    (if (> (.length ^String value) limit)
      (let [limit (max 0 (long limit))
            end (if (and (pos? limit)
                         (Character/isHighSurrogate
                          (.charAt ^String value (dec limit))))
                  (dec limit)
                  limit)]
        (subs value 0 end))
      value)))

