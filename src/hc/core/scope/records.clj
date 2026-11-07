(ns hc.core.scope.records
  "Persistent Scope records and cold-start reading.

  A record is an EDN map with a schema version and an explicit type. The store
  is injected; this namespace owns only the shapes and their reading.

  A persisted reset proves an old event. It does not establish a budget in this
  process. Cold start therefore restores history, spin and erasures, but the
  retention budget stays unknown until a reset settles in this process."
  (:require [clojure.string :as string]
            [hc.core.scope.errors :as errors]))

(def record-version 1)

(def record-types
  "Known record types. `:budget` and `:me-too` are accepted from older logs
  but never establish a budget or book a beat."
  #{:reset :keep :remove :budget :me-too})

(defn- non-blank-string? [value]
  (and (string? value) (not (string/blank? value))))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn reset-record
  "A settled reset. `:call-id` names the call that opened the Scope."
  [{:keys [turn-id call-id goal budget remaining]}]
  {:v record-version
   :type :reset
   :turn-id turn-id
   :call-id call-id
   :goal goal
   :budget budget
   :remaining remaining})

(defn keep-record
  "A keep-last spend. `:defaulted?` marks a turn that only had me-too."
  [{:keys [turn-id remaining defaulted?]}]
  (cond-> {:v record-version
           :type :keep
           :turn-id turn-id
           :remaining remaining}
    defaulted? (assoc :defaulted? true)))

(defn remove-record
  "A remove-last settlement. `:call-id` names the erasing call."
  [{:keys [turn-id call-id replacement spin]}]
  {:v record-version
   :type :remove
   :turn-id turn-id
   :call-id call-id
   :replacement replacement
   :spin spin})

(defn- invalid-record [index message]
  (throw (errors/argument-error
          (str "record " index ": " message))))

(defn- expect-record [raw index]
  (when-not (map? raw)
    (invalid-record index "not a map"))
  (when-not (= record-version (:v raw))
    (invalid-record index (str "version " (pr-str (:v raw))
                               ", expected " record-version)))
  (when-not (contains? record-types (:type raw))
    (invalid-record index (str "unknown type " (pr-str (:type raw)))))
  raw)

(defn read-cold-state
  "Read records into the facts a cold start may restore.

  Malformed reset, keep or remove records throw: a lost record would silently
  change the request copy. `:budget` and `:me-too` records are accepted as
  no-ops because they cannot establish anything in a new process."
  [records]
  (when-not (sequential? records)
    (throw (errors/argument-error "records must be a sequence")))
  (loop [remaining (seq records)
         index 0
         resets []
         erase []
         spin 0]
    (if-let [raw (first remaining)]
      (let [entry (expect-record raw index)]
        (case (:type entry)
          :reset
          (let [goal (:goal entry)
                budget (:budget entry)
                remaining-budget (:remaining entry)]
            (when-not (non-blank-string? goal)
              (invalid-record index "reset goal must be a non-blank string"))
            (when-not (positive-integer? budget)
              (invalid-record index "reset budget must be a positive integer"))
            (when-not (non-negative-integer? remaining-budget)
              (invalid-record index "reset remaining must be a non-negative integer"))
            (recur (next remaining)
                   (inc index)
                   (conj resets {:turn-id (:turn-id entry)
                                 :call-id (:call-id entry)
                                 :goal goal
                                 :budget budget
                                 :remaining remaining-budget})
                   erase
                   spin))

          :keep
          (do
            (when-not (non-negative-integer? (:remaining entry))
              (invalid-record index "keep remaining must be a non-negative integer"))
            (recur (next remaining) (inc index) resets erase spin))

          :remove
          (let [call-id (:call-id entry)
                replacement (:replacement entry)
                record-spin (:spin entry)]
            (when-not (non-blank-string? replacement)
              (invalid-record index "remove replacement must be a non-blank string"))
            (when (nil? call-id)
              (invalid-record index "remove needs the erased call-id"))
            (when-not (positive-integer? record-spin)
              (invalid-record index "remove spin must be a positive integer"))
            (recur (next remaining)
                   (inc index)
                   resets
                   (conj erase {:call-id call-id
                                :replacement replacement
                                :spin record-spin})
                   (max spin record-spin)))

          (:budget :me-too)
          (recur (next remaining) (inc index) resets erase spin)))
      {:resets resets
       :spin spin
       :erases erase
       :last-reset (peek resets)})))

(defn cold-start-notice
  "The notice a request carries while no budget is established."
  [cold]
  {:kind :scope-cold-start
   :reason errors/uninitialized
   :previous-resets (count (:resets cold))
   :message (str "The retention budget is not established; the next "
                 "non-exempt tool call must carry :reset-scope.")})
