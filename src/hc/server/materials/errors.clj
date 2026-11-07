(ns hc.server.materials.errors
  "Ordinary refusals and small validation helpers for the materials domain.

  Expected refusals are values, not exceptions:

    {:ok? false
     :state state
     :error {:code :MEMORY_BODY_INVALID
             :message '...'
             :details {...}}
     :effects []}

  A refusal never changes the state. Callers persist only successful results."
  (:require [clojure.string :as str]))

(defn error
  "Build one refusal error map. DETAILS is omitted when nil."
  [code message details]
  (cond-> {:code code :message message}
    (some? details) (assoc :details details)))

(defn refuse
  "A refusal that leaves STATE untouched."
  [state code message details]
  {:ok? false
   :state state
   :error (error code message details)
   :effects []})

(defn non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn unicode-length
  "Count Unicode code points rather than UTF-16 code units.

  The 2,000-character memory limit is a user-visible character count; counting
  UTF-16 units would let a string of astral characters exceed it silently."
  [^String value]
  (if (string? value)
    (.codePointCount value 0 (.length value))
    0))

(defn trimmed-body
  "Trim outer whitespace but preserve internal line breaks and spacing."
  [body]
  (when (string? body)
    (str/trim body)))

(defn invalid-body
  "Return a refusal error when BODY is not a non-blank string, else nil."
  [body max-chars]
  (cond
    (not (string? body))
    (error :MEMORY_BODY_INVALID
           "memory body must be a string"
           {:body body})

    (str/blank? body)
    (error :MEMORY_BODY_INVALID
           "memory body must not be blank"
           {:body body})

    (and max-chars (> (unicode-length body) max-chars))
    (error :MEMORY_BODY_TOO_LONG
           (str "memory body exceeds " max-chars " characters")
           {:length (unicode-length body)
            :limit max-chars})

    :else nil))
