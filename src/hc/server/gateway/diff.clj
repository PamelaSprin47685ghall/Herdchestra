(ns hc.server.gateway.diff
  "Compute RFC 6902 operations from one AG-UI state tree to the next.

  The application publisher uses this namespace to send only changed fields
  instead of republishing a complete `STATE_SNAPSHOT` on every tick.  The
  output uses `add`, `remove` and `replace` with the same JSON Pointer
  escaping as `hc.server.gateway.patch`; both inputs are left untouched.

  This is deliberately an application-side projection helper, not a second
  patch applier.  The authoritative tree stays in `hc.server.gateway.feed`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; JSON Pointer

(defn- pointer-segment
  [key]
  (let [text (cond
               (keyword? key) (subs (str key) 1)
               (symbol? key) (str key)
               :else (str key))]
    (-> text
        (str/replace "~" "~0")
        (str/replace "/" "~1"))))

(defn- pointer
  [segments]
  (if (empty? segments)
    ""
    (str "/" (str/join "/" (map pointer-segment segments)))))

(defn- operation
  [name segments value? value]
  (cond-> {:op name :path (pointer segments)}
    value? (assoc :value value)))

(defn- replace-op
  [segments value]
  (operation "replace" segments true value))

(defn- add-op
  [segments value]
  (operation "add" segments true value))

(defn- remove-op
  [segments]
  (operation "remove" segments false nil))

;; ---------------------------------------------------------------------------
;; Tree comparison

(defn- pointer-key?
  [key]
  ;; `patch/apply-patch` resolves map keys by exact string, then by keyword.
  ;; Other key types cannot be addressed by a JSON Pointer and replace the
  ;; containing map as a whole.
  (or (string? key) (keyword? key)))

(defn- map-key-style
  [m]
  (let [keys (keys m)]
    (cond
      (empty? m) :empty
      (every? keyword? keys) :keyword
      (every? string? keys) :string
      :else :mixed)))

(defn- add-preserves-key?
  "True when the patch applier will add KEY with the same key type.

  `new-map-key` keeps keyword style for a non-empty all-keyword map and
  defaults to a string otherwise.  An empty map therefore accepts only string
  keys; a keyword key would come back as a string and the patched tree would
  not equal the target."
  [style key]
  (case style
    :keyword (keyword? key)
    :string (string? key)
    :empty (string? key)
    :mixed (string? key)))

(declare diff-value)

(defn- diff-map
  [segments old new]
  (let [added-keys (remove #(contains? old %) (keys new))
        style (map-key-style old)]
    (if-not (and (every? pointer-key? (concat (keys old) (keys new)))
                 (every? #(add-preserves-key? style %) added-keys))
      ;; JSON Pointer cannot address an arbitrary Clojure key, or the patch
      ;; applier would change a keyword key into a string.  Replace the
      ;; map as one value; the client still receives the complete new map.
      [(replace-op segments new)]
      (let [removed (mapv (fn [key]
                            (remove-op (conj segments key)))
                          (remove #(contains? new %) (keys old)))
            added (mapv (fn [key]
                          (add-op (conj segments key) (get new key)))
                        added-keys)
            changed (mapcat (fn [key]
                              (diff-value (conj segments key)
                                          (get old key)
                                          (get new key)))
                            (filter #(contains? new %) (keys old)))]
        (vec (concat removed changed added))))))

(defn- diff-vector
  [segments old new]
  (let [old-count (count old)
        new-count (count new)
        common (min old-count new-count)
        changed (mapcat (fn [index]
                          (diff-value (conj segments index)
                                      (nth old index)
                                      (nth new index)))
                        (range common))
        ;; Remove from the end so earlier indices stay valid.
        removed (mapv (fn [index]
                        (remove-op (conj segments index)))
                      (range (dec old-count) (dec common) -1))
        ;; Appending at the old boundary keeps the earlier prefix stable.
        added (mapv (fn [index]
                      (add-op (conj segments index) (nth new index)))
                    (range common new-count))]
    (vec (concat changed removed added))))

(defn- diff-value
  [segments old new]
  (cond
    (= old new)
    []

    (and (map? old) (map? new))
    (diff-map segments old new)

    (and (vector? old) (vector? new))
    (diff-vector segments old new)

    :else
    [(replace-op segments new)]))

(defn diff
  "Return RFC 6902 operations that turn OLD into NEW.

  An equal pair returns an empty vector.  Maps are compared by key, vectors
  by index, and every other value is replaced as a whole.  The result is
  accepted by both `hc.server.gateway.patch/apply-patch` and
  `hc.client.sync.json-patch/apply-patch`."
  [old new]
  (if (= old new)
    []
    (diff-value [] old new)))
