(ns hc.server.program.receipt
  "Receipt shapes, EDN result sanitization, and UFSS rendering.

  Authority: root `AGENTS.md` §4.3 and §6.3.  Receipts are ordinary Clojure
  data; the gateway renders them with `hc.core.ufss/write-str`."
  (:require [clojure.string :as str]
            [hc.core.ufss :as ufss])
  (:import (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def default-max-result-nodes 10000)
(def default-max-result-depth 64)
(def default-max-text-bytes (* 2 1024 1024))

;; ---------------------------------------------------------------------------
;; Errors

(defn throwable->data
  "A small, EDN-safe description of a throwable.  One cause level only."
  [^Throwable t]
  (let [data (ex-data t)
        base {:class (.getName (class t))
              :message (.getMessage t)}]
    (cond-> base
      (some? data) (assoc :data data)
      (instance? clojure.lang.Compiler$CompilerException t)
      (merge (select-keys data [:line :column]))
      (some? (.getCause t))
      (assoc :cause {:class (.getName (class (.getCause t)))
                     :message (.getMessage (.getCause t))}))))

;; ---------------------------------------------------------------------------
;; Result sanitization

(defn- limit!
  [what limit actual]
  (throw (ex-info (str "Program result " what " exceeds the sandbox limit")
                  {:status :LIMIT_EXCEEDED
                   :error :LIMIT_EXCEEDED
                   :what what
                   :limit limit
                   :actual actual})))

(defn sanitize-value
  "Return a plain EDN copy of VALUE, or throw `:LIMIT_EXCEEDED` /
  `:UNSUPPORTED_OPERATION`.

  Lazy and infinite sequences are bounded by MAX-NODES before realization."
  ([value] (sanitize-value value {}))
  ([value {:keys [max-nodes max-depth max-text-bytes]
           :or {max-nodes default-max-result-nodes
                max-depth default-max-result-depth
                max-text-bytes default-max-text-bytes}}]
   (let [budget (volatile! (long max-nodes))]
     (letfn [(text! [^String text]
               (let [bytes (alength (.getBytes text StandardCharsets/UTF_8))]
                 (when (> bytes (long max-text-bytes))
                   (limit! "text bytes" max-text-bytes bytes))
                 text))
             (walk [x depth]
               (when (> depth (long max-depth))
                 (limit! "depth" max-depth depth))
               (when (neg? (vswap! budget dec))
                 (limit! "node count" max-nodes (inc max-nodes)))
               (cond
                 (nil? x) nil
                 (boolean? x) x
                 (number? x) x
                 (string? x) (text! x)
                 (keyword? x) (do (text! (name x)) x)
                 (symbol? x) (do (text! (name x)) x)
                 (or (char? x) (uuid? x) (inst? x)) x

                 (map? x)
                 (into (empty x)
                       (map (fn [[k v]]
                              [(walk k (inc depth)) (walk v (inc depth))]))
                       x)

                 (vector? x)
                 (mapv #(walk % (inc depth)) x)

                 (set? x)
                 (into #{} (map #(walk % (inc depth))) x)

                 (or (list? x) (seq? x))
                 (let [items (take (inc (long max-nodes)) x)]
                   (when (> (count items) (long max-nodes))
                     (limit! "sequence length" max-nodes (count items)))
                   (doall (map #(walk % (inc depth)) items)))

                 :else
                 (throw (ex-info "Program result is not EDN data"
                                 {:status :UNSUPPORTED_OPERATION
                                  :error :UNSUPPORTED_OPERATION
                                  :reason :non-edn-result
                                  :class (.getName (class x))}))))]
       (walk value 0)))))

;; ---------------------------------------------------------------------------
;; Receipts

(defn numbered-source
  [source]
  (->> (str/split source #"\n" -1)
       (map-indexed (fn [i line] (str (inc i) " | " line)))
       (str/join "\n")))

(defn base
  "Common fields for every program result."
  [program]
  (let [source (or (:numbered-source program) (:source program))
        ;; `base` is applied twice on the park path (once in `program-view`,
        ;; once in `park`), so the raw text must survive the second pass.
        raw (or (:raw-source program) (:source program))]
    (-> (select-keys program
                     [:call-id :attempt-id :name :role :source :numbered-source
                      :intent-bytes :deadline-ms :started-at :holder? :s1?])
        ;; §4.3.4 defines `:source` as the line-numbered program text.  Keep
        ;; the raw text under `:raw-source` for callers that need to re-read
        ;; the submitted form without line prefixes.
        (assoc :source source
               :raw-source raw))))

(defn park
  "Build the §4.3 parked receipt.  PROGRAM carries the live state; EXTRA
  supplies `:reason`, `:payload`, `:remaining-wait-ms`, `:delta`, and the
  command view."
  [program {:keys [reason payload remaining-wait-ms delta command]
            :as extra}]
  (cond-> (merge (base program)
                 {:status :PARKED
                  :state :parked
                  :reason reason
                  :payload payload
                  :pending-operations (vec (:pending-operations extra))
                  :command command
                  :delta (or delta "")
                  :control-results (vec (:control-results program))
                  :commits (vec (:commits program))
                  :remaining-wait-ms (long (or remaining-wait-ms 0))})
    (= :ask reason) (assoc :interrupt true)
    (:park-id extra) (assoc :park-id (:park-id extra))
    (:revision extra) (assoc :revision (:revision extra))))

(defn completed
  [program value commit facts]
  (merge (base program)
         {:status :COMPLETED
          :state :completed
          :value value
          :commit commit
          :control-results (vec (:control-results program))
          :commits (vec (:commits program))
          :read-set (or (:read-set facts) {})
          :checks (or (:checks facts) {})
          :operation-count (count (:ops facts))}))

(def ^:private non-failure-statuses
  #{:COMPLETED :PARKED :APPLIED :RUNNING :WAITING :SETTLING :DRAINING :CANCELLED})

(defn failed
  [program error commit]
  (let [status (or (:status error) :FAILED)
        status (if (contains? non-failure-statuses status) :FAILED status)]
    (merge (base program)
           {:status status
            :state :failed
            :error error
            :commit commit
            :command (:command program)
            :pending-operations (vec (:pending-operations program))
            :delta (:delta program)
            :control-results (vec (:control-results program))
            :commits (vec (:commits program))})))

(defn cancelled
  "The terminal receipt for a cancellation whose call (if any) returned.
  `:cancel-requested?` lets the application post this late terminal when
  `cancel!` deferred it while a shell was in flight."
  [program commit]
  (merge (base program)
         {:status :CANCELLED
          :state :cancelled
          :cancel-requested? true
          :command (:command program)
          :pending-operations (vec (:pending-operations program))
          :error {:status :CANCELLED
                  :error :CANCELLED
                  :message "Program was cancelled"}
          :commit commit
          :delta (:delta program)
          :control-results (vec (:control-results program))
          :commits (vec (:commits program))}))

(defn cancelled-unconfirmed
  "A terminal receipt for a cancellation whose in-flight call returned an
  unconfirmed result.

  The program thread is finished, but the external call may still own a
  process or a write.  The status is `:OUTCOME_UNKNOWN`, not `:CANCELLED`,
  and the pending operation plus its command view stay in the receipt.
  Consumers must keep the resource responsible until a real terminal."
  [program command pending]
  (merge (base program)
         {:status :OUTCOME_UNKNOWN
          :state :cancelled
          :cancel-requested? true
          :cancel-unconfirmed? true
          :error {:status :UNCERTAIN
                  :error :UNCERTAIN
                  :message "Program cancellation could not confirm that the in-flight call ended"}
          :pending-operations (vec pending)
          :command command
          :commit nil
          :control-results (vec (:control-results program))
          :commits (vec (:commits program))}))

(defn rejection
  "A call that never started a program: name conflict, replay mismatch,
  unknown program, stale control, capability denial, or invalid input."
  [request status message details]
  (let [request (if (map? request) request {})]
    (merge (select-keys request [:call-id :attempt-id :role :name :source])
         {:status status
          :state :rejected
          :error (cond-> {:status status :error status :message message}
                   (some? details) (assoc :details details))})))

(defn render
  "Render a receipt as EDN+UFSS for the model.  A receipt that somehow
  contains a non-printable value returns a small printable refusal instead of
  throwing into the gateway."
  [result]
  (try
    (ufss/write-str result)
    (catch Throwable t
      (ufss/write-str
       (cond-> {:status :UNSUPPORTED_OPERATION
                :error :UNSUPPORTED_OPERATION
                :message "Receipt could not be rendered as EDN+UFSS"
                :class (.getName (class t))}
         (map? result) (merge (select-keys result
                                            [:call-id :attempt-id :name :role :state])))))))
