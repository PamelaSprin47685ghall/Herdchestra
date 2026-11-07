(ns hc.core.text-location.scan
  "Exact UTF-16 code-unit search and line spans.

  Matching is literal.  It never trims, normalizes, or interprets regular
  expressions.  Offsets count UTF-16 code units, so a returned span works
  directly with `subs`.")

(set! *warn-on-reflection* true)

(def context-units
  "Code units shown on each side of an ambiguous match."
  80)

(defn- invalid!
  [field expected actual]
  (throw (ex-info (str "text-location: " field " must be " expected)
                  {:status :INVALID_ARGUMENT
                   :error :INVALID_ARGUMENT
                   :field field
                   :expected expected
                   :actual (some-> actual class .getName)})))

(defn request
  "Validate one single-map tool request.

  Return `{:content ... :needle ...}`.  Non-string input is a wiring error,
  not a location result."
  [request]
  (when-not (map? request)
    (invalid! :request "a map" request))
  (let [content (:content request)
        needle (:needle request)]
    (when-not (string? content)
      (invalid! :content "a string" content))
    (when-not (string? needle)
      (invalid! :needle "a string" needle))
    {:content content :needle needle}))

(defn- find-up-to*
  "Return at most LIMIT exact matches as `[begin end]` pairs.

  When OVERLAPPING? is true, the next search starts one code unit after the
  current match; otherwise it starts where the current match ended.  An empty
  needle has no match: a zero-width span is not a slice."
  [^String content ^String needle limit overlapping?]
  (let [n (.length needle)
        len (.length content)
        limit (long limit)]
    (if (or (zero? n) (<= limit 0) (> n len))
      []
      (loop [from 0
             found 0
             out (transient [])]
        (if (or (>= found limit) (> (+ from n) len))
          (persistent! out)
          (let [b (.indexOf content needle (int from))]
            (if (neg? b)
              (persistent! out)
              (recur (if overlapping? (inc b) (+ b n))
                     (inc found)
                     (conj! out [(int b) (int (+ b n))])))))))))

(defn find-up-to
  "Return at most LIMIT non-overlapping exact matches as `[begin end]` pairs.

  The next search starts where the previous match ended.  An empty needle has
  no match: a zero-width span is not a slice."
  [^String content ^String needle limit]
  (find-up-to* content needle limit false))

(defn find-up-to-overlapping
  "Return at most LIMIT exact matches, including overlapping occurrences.

  The next search starts one code unit after the current match.  `where` uses
  this to decide whether a needle is unique; `whereAll` keeps the
  non-overlapping replacement law."
  [^String content ^String needle limit]
  (find-up-to* content needle limit true))

(defn find-all
  "Return every non-overlapping exact match as a vector of `[begin end]`."
  [^String content ^String needle]
  (find-up-to content needle Long/MAX_VALUE))

(defn- blank-range?
  [^String content start end]
  (loop [i start]
    (if (>= i end)
      true
      (if (Character/isWhitespace (.charAt content (int i)))
        (recur (inc i))
        false))))

(defn line-spans
  "Return up to LIMIT non-blank line spans from CONTENT.

  A span is `{:start :end}` and excludes the line terminator.  Lines end at
  `\\n`, `\\r\\n`, or a lone `\\r`.  A whitespace-only line is blank and
  consumes no scan budget."
  [^String content limit]
  (let [len (.length content)
        limit (long limit)]
    (loop [start 0
           found 0
           out (transient [])]
      (if (or (>= start len) (>= found limit))
        (persistent! out)
        (let [i (loop [i start]
                  (if (>= i len)
                    len
                    (let [c (.charAt content (int i))]
                      (if (or (= c \newline) (= c \return))
                        i
                        (recur (inc i))))))
              terminator (if (>= i len)
                           0
                           (if (and (= \return (.charAt content (int i)))
                                    (< (inc i) len)
                                    (= \newline (.charAt content (int (inc i)))))
                             2
                             1))
              next-start (+ i terminator)]
          (if (and (> i start)
                   (not (blank-range? content start i)))
            (recur (long next-start)
                   (inc found)
                   (conj! out {:start (int start) :end (int i)}))
            (recur (long next-start) found out)))))))

(defn truncate-text
  "Return `{:text ... :truncated? ...}` with at most MAX-UNITS code units.

  A cut that would split a surrogate pair moves back by one unit."
  [^String text max-units]
  (let [len (.length text)
        max-units (int max-units)]
    (if (<= len max-units)
      {:text text :truncated? false}
      (let [cut (if (and (pos? max-units)
                         (Character/isHighSurrogate (.charAt text (dec max-units)))
                         (Character/isLowSurrogate (.charAt text max-units)))
                  (dec max-units)
                  max-units)]
        {:text (subs text 0 cut) :truncated? true}))))

(defn context
  "Return the match plus up to 80 UTF-16 code units on each side.

  A boundary that would split a surrogate pair moves inward by one code unit,
  so the context may hold 79 units on that side."
  [^String content b e]
  (let [len (.length content)
        raw-start (max 0 (- (long b) context-units))
        raw-end (min len (+ (long e) context-units))
        start (if (and (< raw-start len)
                       (Character/isLowSurrogate (.charAt content (int raw-start))))
                (inc raw-start)
                raw-start)
        end (if (and (pos? raw-end)
                     (Character/isHighSurrogate (.charAt content (int (dec raw-end)))))
              (dec raw-end)
              raw-end)]
    (subs content (int start) (int end))))
