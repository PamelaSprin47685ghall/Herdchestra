(ns hc.compose.owner.compact-property-test
  "Property tests for current-state compaction.

  The generator stays inside the reversible domain: non-terminal calls and a
  terminal call whose retry/error slots are already nil.  Terminal error and
  retry compaction is deliberately lossy; a round-trip test that included a
  populated error map would be testing an operation the namespace never
  promises to reverse."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner.compact :as compact]))

(def ^:private gen-long-text
  (gen/fmap (fn [text]
              (str text (apply str (repeat (inc compact/max-inline-chars)
                                           "x"))))
            gen/string-alphanumeric))

(def ^:private gen-state
  (gen/fmap
   (fn [[source stdout request-a request-b stderr output]]
     {:calls {"call-1" {:state :running
                        :raw-source source
                        :stdout stdout}
              "call-2" {:state :succeeded
                        :retry nil
                        :last-error nil
                        :last-failure nil
                        :error nil
                        :request {:arbitrary-a request-a
                                  :arbitrary-b request-b}}}
      :cancel {:nodes {"node-1" {:stderr stderr}}}
      :runs {"run-1" {:output output}}})
   (gen/tuple gen-long-text
              gen-long-text
              gen-long-text
              gen-long-text
              gen-long-text
              gen-long-text)))

(defn- expected-source-strings
  [state]
  {:raw-source (get-in state [:calls "call-1" :raw-source])
   :stdout (get-in state [:calls "call-1" :stdout])
   :stderr (get-in state [:cancel :nodes "node-1" :stderr])
   :output (get-in state [:runs "run-1" :output])})

(defn- all-strings
  [value]
  (cond
    (string? value) [value]
    (map? value) (mapcat (fn [[key child]]
                           (concat (when (string? key) [key])
                                   (all-strings child)))
                         value)
    (vector? value) (mapcat all-strings value)
    (list? value) (mapcat all-strings value)
    :else []))

(defn- source-ref-entries
  [value]
  (when (map? value)
    (mapcat (fn [[key child]]
              (cond
                (and (map? child) (contains? child :source-ref))
                [[key child]]

                (map? child)
                (source-ref-entries child)

                :else nil))
            value)))

(defspec compact-then-restore-is-identity 60
  (prop/for-all [state gen-state]
    (let [store (compact/memory-blob-store)
          [compacted stats] (compact/compact-state state store)]
      (and (= state (compact/restore-state compacted store))
           (pos? (:refs stats))))))

(defspec compacted-documents-have-no-oversized-inline-strings 60
  (prop/for-all [state gen-state]
    (let [store (compact/memory-blob-store)
          [compacted _stats] (compact/compact-state state store)
          expected (expected-source-strings state)
          refs (source-ref-entries compacted)]
      (and
       (every? (fn [text]
                 (<= (count text) compact/max-inline-chars))
               (all-strings compacted))
       (= (set (keys expected))
          (set (map first refs)))
       (every? (fn [[key reference]]
                 (and (contains? compact/large-keys key)
                      (string? (:source-ref reference))
                      (= (get expected key)
                         ((:get store) (:source-ref reference)))))
               refs)))))

(defspec compacting-an-already-compacted-state-adds-no-refs 60
  (prop/for-all [state gen-state]
    (let [store (compact/memory-blob-store)
          [once stats-once] (compact/compact-state state store)
          blobs-once ((:count store))
          [twice stats-twice] (compact/compact-state once store)]
      (and (= once twice)
           (= blobs-once ((:count store)))
           (= (:refs stats-once) (:refs stats-twice))))))
