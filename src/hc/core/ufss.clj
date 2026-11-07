(ns hc.core.ufss
  "Public facade for the EDN + UFSS reader and printer (AGENTS.md §6.3).

  System-facing data is printed with `write-str`, which never emits JSON
  backslash noise: strings use plain quotes, single-line raw fences, or
  indentation-stripped raw blocks. Model submissions are parsed with
  `read-input`, which accepts EDN+UFSS and JSON conveniences; `read-str` is
  the strict EDN+UFSS reader used for the system's own output."
  (:require [hc.core.ufss.printer :as printer]
            [hc.core.ufss.reader :as reader]))

(defn read-str
  "Read exactly one EDN+UFSS value from s. Throws ExceptionInfo with
  :type :ufss/read-error on failure; ex-data has :error, :offset, :line,
  :column, :expected, and :found."
  ([s] (reader/read-str s))
  ([s opts] (reader/read-str s opts)))

(defn read-input
  "Read one model-submitted value. Accepts EDN+UFSS plus JSON conveniences:
  null, \"key\": value with an adjacent colon, and the \\/ escape."
  ([s] (reader/read-input s))
  ([s opts] (reader/read-input s opts)))

(defn read-all-str
  "Read every EDN+UFSS form in s and return a vector of values."
  ([s] (reader/read-all-str s))
  ([s opts] (reader/read-all-str s opts)))

(defn read-str-safe
  "Like read-str, but return {:ok value} or {:error ex-data} for expected
  read errors."
  ([s] (reader/read-str-safe s))
  ([s opts] (reader/read-str-safe s opts)))

(defn write-str
  "Render v as EDN+UFSS text. A string is rendered as a string literal, so
  (= s (read-str (write-str s))) holds for every Unicode string s."
  [v]
  (printer/write-str v))
