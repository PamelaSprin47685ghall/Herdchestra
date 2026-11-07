(ns hc.server.gateway.patch
  "Server-side RFC 6902 JSON Patch applier for the AG-UI state tree.

  The gateway keeps the authoritative tree so a new subscription can receive
  one atomic `STATE_SNAPSHOT` without replaying the delta log.  This applier
  is the server twin of `hc.client.sync.json-patch`: it accepts string or
  keyword operation keys because Sente/Transit may keywordize the wire
  payload, and a failed patch never exposes a partially applied document.

  Keep the two implementations semantically aligned.  They are deliberately
  separate because `hc.server` must not depend on the browser client package."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Results and operation field access

(defn- ok [value]
  {:ok? true :value value})

(defn- fail [code message details]
  {:ok? false :error (merge {:code code :message message} details)})

(defn keyword-string
  "Return the wire name of a keyword, or the string itself."
  [k]
  (if (keyword? k)
    (subs (str k) 1)
    (str k)))

(defn- op-contains? [op k]
  (or (contains? op k)
      (contains? op (keyword-string k))))

(defn- op-value [op k]
  (if (contains? op k)
    (get op k)
    (get op (keyword-string k))))

(defn- op-name [op]
  (let [value (or (get op :op) (get op "op"))]
    (some-> value name str/lower-case)))

;; ---------------------------------------------------------------------------
;; JSON Pointer

(defn- decode-segment
  [segment]
  (loop [chars (seq segment)
         out []]
    (if-let [c (first chars)]
      (if (= c \~)
        (let [n (second chars)]
          (case n
            \0 (recur (nnext chars) (conj out \~))
            \1 (recur (nnext chars) (conj out \/))
            (throw (ex-info "invalid JSON pointer escape"
                            {:code :invalid-pointer
                             :message (str "invalid escape in segment "
                                           (pr-str segment))}))))
        (recur (next chars) (conj out c)))
      (apply str out))))

(defn parse-pointer
  "Parse a JSON Pointer into path segments.

  Returns `{:ok? true :value [segment ...]}` or an error.  The empty pointer
  addresses the document root."
  [pointer]
  (cond
    (not (string? pointer))
    (fail :invalid-pointer "JSON pointer must be a string" {:pointer pointer})

    (= pointer "")
    (ok [])

    (not (str/starts-with? pointer "/"))
    (fail :invalid-pointer "JSON pointer must be empty or start with '/'"
          {:pointer pointer})

    :else
    (try
      (ok (mapv decode-segment (str/split (subs pointer 1) #"/" -1)))
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
        (fail :invalid-pointer
              (or (:message (ex-data e)) "invalid JSON pointer")
              {:pointer pointer})))))

(defn- parse-index
  "Parse a non-negative array index without accepting leading zeroes."
  [s]
  (when (and (string? s)
             (re-matches #"(?:0|[1-9][0-9]*)" s))
    (try
      (parse-long s)
      (catch #?(:clj Exception :cljs :default) _
        nil))))

;; ---------------------------------------------------------------------------
;; Map keys
;;
;; The protocol tree can be built from JSON (string keys) or Clojure (keyword
;; keys).  A lookup prefers the exact string, then the keyword with the same
;; wire name.  A new key follows the style of the existing map; an empty map
;; defaults to a string because the protocol tree comes from the JSON wire
;; format, and normalized table keys must stay string entity IDs.

(defn- map-key
  [m segment]
  (cond
    (contains? m segment) [true segment]
    (and (not (str/blank? segment))
         (contains? m (keyword segment))) [true (keyword segment)]
    :else [false nil]))

(defn- new-map-key
  [m segment]
  (if (and (not (str/blank? segment))
           (seq m)
           (every? keyword? (keys m)))
    (keyword segment)
    segment))

;; ---------------------------------------------------------------------------
;; Navigation and mutation

(defn- get-at
  [document segments pointer]
  (loop [node document
         remaining (seq segments)]
    (if-let [segment (first remaining)]
      (cond
        (map? node)
        (let [[found? k] (map-key node segment)]
          (if found?
            (recur (get node k) (next remaining))
            (fail :missing-path
                  (str "path segment " (pr-str segment) " is missing")
                  {:path pointer :segment segment})))

        (vector? node)
        (let [index (parse-index segment)]
          (cond
            (nil? index)
            (fail :invalid-index
                  (str "array index " (pr-str segment) " is invalid")
                  {:path pointer :segment segment})

            (>= index (count node))
            (fail :missing-path
                  (str "array index " index " is out of bounds")
                  {:path pointer :segment segment :index index})

            :else
            (recur (nth node index) (next remaining))))

        :else
        (fail :missing-path
              (str "cannot descend into " (pr-str (type node)))
              {:path pointer :segment segment}))
      (ok node))))

(defn- add-at
  [document segments value pointer]
  (if (empty? segments)
    (ok value)
    (let [segment (first segments)
          remaining (next segments)]
      (cond
        (map? document)
        (let [[found? k] (map-key document segment)]
          (if (and (not found?) (seq remaining))
            (fail :missing-path
                  (str "parent path segment " (pr-str segment) " is missing")
                  {:path pointer :segment segment})
            (let [child (when found? (get document k))
                  result (add-at child remaining value pointer)]
              (if (:ok? result)
                (ok (assoc document
                           (if found? k (new-map-key document segment))
                           (:value result)))
                result))))

        (vector? document)
        (let [index (parse-index segment)]
          (cond
            (and (nil? index) (not= segment "-"))
            (fail :invalid-index
                  (str "array index " (pr-str segment) " is invalid")
                  {:path pointer :segment segment})

            (empty? remaining)
            (let [at (if (= segment "-") (count document) index)]
              (if (<= at (count document))
                (ok (vec (concat (subvec document 0 at)
                                 [value]
                                 (subvec document at))))
                (fail :invalid-index
                      (str "array index " at " is out of bounds")
                      {:path pointer :segment segment :index at})))

            (or (= segment "-") (>= index (count document)))
            (fail :missing-path
                  (str "array index " (pr-str segment) " is out of bounds")
                  {:path pointer :segment segment})

            :else
            (let [result (add-at (nth document index) remaining value pointer)]
              (if (:ok? result)
                (ok (assoc document index (:value result)))
                result))))

        :else
        (fail :missing-path
              (str "cannot descend into " (pr-str (type document)))
              {:path pointer :segment segment})))))

(defn- replace-at
  [document segments value pointer]
  (if (empty? segments)
    (ok value)
    (let [segment (first segments)
          remaining (next segments)]
      (cond
        (map? document)
        (let [[found? k] (map-key document segment)]
          (if-not found?
            (fail :missing-path
                  (str "path segment " (pr-str segment) " is missing")
                  {:path pointer :segment segment})
            (let [result (replace-at (get document k) remaining value pointer)]
              (if (:ok? result)
                (ok (assoc document k (:value result)))
                result))))

        (vector? document)
        (let [index (parse-index segment)]
          (cond
            (nil? index)
            (fail :invalid-index
                  (str "array index " (pr-str segment) " is invalid")
                  {:path pointer :segment segment})

            (>= index (count document))
            (fail :missing-path
                  (str "array index " index " is out of bounds")
                  {:path pointer :segment segment :index index})

            :else
            (let [result (replace-at (nth document index) remaining value pointer)]
              (if (:ok? result)
                (ok (assoc document index (:value result)))
                result))))

        :else
        (fail :missing-path
              (str "cannot descend into " (pr-str (type document)))
              {:path pointer :segment segment})))))

(defn- remove-at
  [document segments pointer]
  (if (empty? segments)
    (ok nil)
    (let [segment (first segments)
          remaining (next segments)]
      (cond
        (map? document)
        (let [[found? k] (map-key document segment)]
          (if-not found?
            (fail :missing-path
                  (str "path segment " (pr-str segment) " is missing")
                  {:path pointer :segment segment})
            (if (empty? remaining)
              (ok (dissoc document k))
              (let [result (remove-at (get document k) remaining pointer)]
                (if (:ok? result)
                  (ok (assoc document k (:value result)))
                  result)))))

        (vector? document)
        (let [index (parse-index segment)]
          (cond
            (nil? index)
            (fail :invalid-index
                  (str "array index " (pr-str segment) " is invalid")
                  {:path pointer :segment segment})

            (>= index (count document))
            (fail :missing-path
                  (str "array index " index " is out of bounds")
                  {:path pointer :segment segment :index index})

            (empty? remaining)
            (ok (vec (concat (subvec document 0 index)
                             (subvec document (inc index)))))

            :else
            (let [result (remove-at (nth document index) remaining pointer)]
              (if (:ok? result)
                (ok (assoc document index (:value result)))
                result))))

        :else
        (fail :missing-path
              (str "cannot descend into " (pr-str (type document)))
              {:path pointer :segment segment})))))

(defn- descendant?
  [parent child]
  (and (> (count child) (count parent))
       (= parent (subvec child 0 (count parent)))))

;; ---------------------------------------------------------------------------
;; Operations

(defn- apply-operation
  [document op]
  (let [name (op-name op)
        path (op-value op :path)]
    (if-not (string? path)
      (fail :invalid-patch "operation path must be a string"
            {:op name :path path})
      (let [parsed (parse-pointer path)]
        (if-not (:ok? parsed)
          parsed
          (let [segments (:value parsed)]
            (case name
              "add"
              (if (op-contains? op :value)
                (add-at document segments (op-value op :value) path)
                (fail :invalid-patch "add requires a value"
                      {:op name :path path}))

              "remove"
              (remove-at document segments path)

              "replace"
              (if (op-contains? op :value)
                (replace-at document segments (op-value op :value) path)
                (fail :invalid-patch "replace requires a value"
                      {:op name :path path}))

              "move"
              (let [from (op-value op :from)]
                (if-not (string? from)
                  (fail :invalid-patch "move requires a string from path"
                        {:op name :path path :from from})
                  (let [from-parsed (parse-pointer from)]
                    (if-not (:ok? from-parsed)
                      from-parsed
                      (let [from-segments (:value from-parsed)
                            value-result (get-at document from-segments from)]
                        (if-not (:ok? value-result)
                          value-result
                          (cond
                            (= from-segments segments)
                            (ok document)

                            (descendant? from-segments segments)
                            (fail :invalid-move
                                  "cannot move a value into one of its children"
                                  {:op name :path path :from from})

                            :else
                            (let [removed (remove-at document from-segments from)]
                              (if-not (:ok? removed)
                                removed
                                (add-at (:value removed)
                                        segments
                                        (:value value-result)
                                        path))))))))))

              "copy"
              (let [from (op-value op :from)]
                (if-not (string? from)
                  (fail :invalid-patch "copy requires a string from path"
                        {:op name :path path :from from})
                  (let [from-parsed (parse-pointer from)]
                    (if-not (:ok? from-parsed)
                      from-parsed
                      (let [value-result (get-at document (:value from-parsed) from)]
                        (if-not (:ok? value-result)
                          value-result
                          (add-at document segments (:value value-result) path)))))))

              "test"
              (if (op-contains? op :value)
                (let [expected (op-value op :value)
                      actual-result (get-at document segments path)]
                  (if-not (:ok? actual-result)
                    actual-result
                    (if (= expected (:value actual-result))
                      (ok document)
                      (fail :test-failed "JSON Patch test operation failed"
                            {:op name :path path
                             :expected expected
                             :actual (:value actual-result)}))))
                (fail :invalid-patch "test requires a value"
                      {:op name :path path}))

              (fail :invalid-op
                    (str "unsupported JSON Patch operation " (pr-str name))
                    {:op name :path path}))))))))

(defn apply-patch
  "Apply a sequence of RFC 6902 operations to `document`.

  Returns `{:ok? true :value document'}` or
  `{:ok? false :error {:code ... :message ... :index ...}}`.  The original
  document is never mutated; callers keep it when the patch fails."
  [document ops]
  (if-not (sequential? ops)
    (fail :invalid-patch "patch must be a sequence of operations"
          {:patch ops})
    (loop [current document
           remaining (seq ops)
           index 0]
      (if (seq remaining)
        (let [op (first remaining)]
          (if-not (map? op)
            (fail :invalid-patch "operation must be a map"
                  {:op-index index :op op})
            (let [result (apply-operation current op)]
              (if (:ok? result)
                (recur (:value result) (next remaining) (inc index))
                (update result :error assoc :op-index index)))))
        (ok current)))))
