(ns hc.core.scope.scope
  "One stateful Scope per session.

  The Scope owns the newest reset, the retention budget, the turn ledger, the
  records to persist and the projection input. It performs no I/O: a store is
  injected. A store is a function of records; it throws to refuse the append.

  Settlement is serialized on one lock. Records are appended before the live
  book changes, so a failed append cannot leave memory ahead of the record
  stream."
  (:require [clojure.string :as string]
            [hc.core.scope.admission :as admission]
            [hc.core.scope.annotation :as annotation]
            [hc.core.scope.errors :as errors]
            [hc.core.scope.ledger :as ledger]
            [hc.core.scope.projection :as projection]
            [hc.core.scope.records :as records]))

(defn memory-store
  "A store that keeps records in an atom. Useful for tests and dry runs."
  []
  (let [written (atom [])]
    {:written written
     :append! (fn [entries]
                (swap! written into entries)
                true)}))

(defn- store-fn [store]
  (cond
    (fn? store) store
    (and (map? store) (fn? (:append! store))) (:append! store)
    :else (throw (errors/argument-error
                  "scope needs a store function or a map with :append!"))))

(defn- check-session-id [session-id]
  (when-not (and (string? session-id)
                 (not (string/blank? session-id)))
    (throw (errors/argument-error "scope needs a non-blank :session-id")))
  session-id)

(defn- normalize-admission [value]
  (cond
    (nil? value) (admission/admission)
    (set? value) (admission/admission {:exempt value})
    (map? value) (admission/admission value)
    :else (throw (errors/argument-error
                  "admission must be a map or a set of exempt members"))))

(defrecord Scope [session-id store admission book lock])

(defn- history-book
  "Keep a supplied book's history, but not its process-local budget or turn."
  [book]
  (if (nil? book)
    (ledger/new-book)
    (-> book
        (assoc :retention {:established? false :remaining 0})
        (assoc :turn nil))))

(defn make-scope
  "Create a Scope for a session that starts now.

  Options: `:session-id` (required), `:store`, `:admission`, `:book`.

  `:book` restores history only. The retention budget and any in-memory turn
  are cleared, so a persisted book cannot bypass the cold-start reset. Use
  `cold-start` for persisted records."
  ([] (make-scope {}))
  ([{:keys [session-id store admission book]}]
   (when (and (some? book) (not (map? book)))
     (throw (errors/argument-error "scope :book must be a map")))
   (let [session-id (check-session-id session-id)
         store (store-fn (if (nil? store) (memory-store) store))]
     (->Scope session-id
              store
              (normalize-admission admission)
              (atom (history-book book))
              (Object.)))))

(defn cold-start
  "Create a Scope from persisted records.

  The budget stays unknown. Only a reset that settles in this process
  establishes it."
  [{:keys [session-id records store admission] :as options}]
  (when-not store
    (throw (errors/argument-error "cold-start needs a :store")))
  (let [cold (records/read-cold-state (if (nil? records) [] records))]
    (make-scope (assoc options
                       :session-id session-id
                       :store store
                       :admission admission
                       :book (ledger/restore-book cold)))))

(defn session-id [scope]
  (:session-id scope))

(defn budget-known? [scope]
  (ledger/established? @(:book scope)))

(defn retention [scope]
  (ledger/retention @(:book scope)))

(defn remaining [scope]
  (ledger/remaining @(:book scope)))

(defn spent? [scope]
  (ledger/spent? @(:book scope)))

(defn spin-count [scope]
  (ledger/spin @(:book scope)))

(defn erased [scope]
  (ledger/erases @(:book scope)))

(defn reset-beats [scope]
  (ledger/reset-beats @(:book scope)))

(defn reset-count [scope]
  (count (ledger/reset-beats @(:book scope))))

(defn last-reset [scope]
  (ledger/last-reset @(:book scope)))

(defn must-reset? [scope]
  (let [book @(:book scope)]
    (or (not (ledger/established? book))
        (ledger/spent? book))))

(defn- notice-for [book]
  (when-not (ledger/established? book)
    (records/cold-start-notice
     {:resets (ledger/reset-beats book)})))

(defn notice
  "The cold-start notice while the budget is unknown, otherwise nil."
  [scope]
  (notice-for @(:book scope)))

(defn admit!
  "Check one tool call before it executes. Returns a value; nothing changes."
  [scope {:keys [member annotation]}]
  (let [book @(:book scope)
        result (admission/check {:member member
                                 :annotation annotation
                                 :retention (ledger/retention book)
                                 :admission (:admission scope)})]
    (cond-> (assoc result :session-id (:session-id scope))
      (:ok result) (assoc :must-reset (or (not (ledger/established? book))
                                          (ledger/spent? book))
                          :notice (notice-for book)))))

(defn- append-failure [scope turn-id error]
  (assoc (errors/refuse errors/record-failed
                        "scope records could not be appended; no fact changed"
                        {:session-id (:session-id scope)
                         :reason (ex-message error)
                         :turn-id turn-id
                         :retryable true})
         :phase :settle))

(defn settle!
  "Settle one tool call after it executed.

  Admission happens before the tool runs. Settlement only decides exemption,
  parses the annotation, and lets the ledger enforce turn and budget rules; a
  turn that already settled returns :confirmed before any budget check.
  Returns a receipt, a refusal, or `:outcome :exempt` for negotiate and
  askPreviousScope. A record failure leaves the book unchanged.

  `:observed` is diagnostic data echoed in the receipt. It is not persisted
  and is not the provider original; provider originals travel as :provider
  items."
  [scope {:keys [member annotation turn-id call-id observed]}]
  (locking (:lock scope)
    (let [book @(:book scope)
          exempt? (admission/exempt? member (:admission scope))
          parsed (when-not exempt?
                   (annotation/parse-annotation annotation))]
      (cond
        exempt?
        {:ok true
         :outcome :exempt
         :kind nil
         :call-id call-id
         :session-id (:session-id scope)
         :persisted false
         :prior-persisted false
         :records []
         :remaining (ledger/remaining book)
         :retention (ledger/retention book)
         :notice (notice-for book)}

        (string/blank? (str (errors/member-name member)))
        (assoc (errors/refuse errors/annotation-missing
                              "the tool call has no member name")
               :session-id (:session-id scope)
               :phase :settle)

        (false? (:ok parsed))
        (assoc parsed
               :session-id (:session-id scope)
               :phase :settle)

        (nil? turn-id)
        (assoc (errors/refuse errors/turn-missing
                              "a settle needs the model turn id"
                              {:session-id (:session-id scope)})
               :phase :settle)

        :else
        (let [{next-book :book result :result}
              (ledger/settle-turn book turn-id parsed
                                  {:call-id call-id :observed observed})
              prior (vec (:prior-records result))
              current (vec (or (:current-records result) (:records result)))]
          (if (false? (:ok result))
            (let [append (when (seq prior)
                           (try
                             ((:store scope) prior)
                             nil
                             (catch Exception error
                               (append-failure scope turn-id error))))]
              (if (and append (false? (:ok append)))
                append
                (do
                  (when (seq prior)
                    (reset! (:book scope) next-book))
                  (assoc result
                         :session-id (:session-id scope)
                         :phase :settle
                         :persisted false
                         :prior-persisted (boolean (seq prior))
                         :remaining (ledger/remaining next-book)
                         :retention (ledger/retention next-book)
                         :notice (notice-for next-book)))))
            (let [entries (into prior current)
                  append (when (seq entries)
                           (try
                             ((:store scope) entries)
                             nil
                             (catch Exception error
                               (append-failure scope turn-id error))))]
              (if (and append (false? (:ok append)))
                append
                (do
                  (reset! (:book scope) next-book)
                  (assoc result
                         :session-id (:session-id scope)
                         :call-id call-id
                         :phase :settle
                         :records current
                         :prior-records (when (seq prior) prior)
                         :persisted (boolean (seq current))
                         :prior-persisted (boolean (seq prior))
                         :retention (ledger/retention next-book)
                         :notice (notice-for next-book)))))))))))

(defn close-turn!
  "Close the current model turn.

  A turn with only me-too settles as keep-last and spends one point. A record
  failure leaves the book unchanged.

  With `turn-id`, a close for a different turn is ignored. A late model
  terminal must not default a newer turn's staged me-too."
  ([scope] (close-turn! scope nil))
  ([scope turn-id]
   (locking (:lock scope)
     (let [{next-book :book result :result}
           (ledger/close-turn @(:book scope) turn-id)
           entries (:records result)]
       (if (false? (:ok result))
         (assoc result
                :session-id (:session-id scope)
                :phase :settle
                :remaining (ledger/remaining @(:book scope))
                :retention (ledger/retention @(:book scope))
                :notice (notice scope))
         (let [append (when (seq entries)
                        (try
                          ((:store scope) entries)
                          nil
                          (catch Exception error
                            (append-failure scope (:turn-id result) error))))]
           (if (and append (false? (:ok append)))
             append
             (do
               (reset! (:book scope) next-book)
               (assoc result
                      :session-id (:session-id scope)
                      :phase :settle
                      :persisted (boolean (seq entries))
                      :prior-persisted false
                      :remaining (ledger/remaining next-book)
                      :retention (ledger/retention next-book)
                      :notice (notice-for next-book))))))))))

(defn project-request
  "Return the outbound request copy, or a refusal value."
  ([scope items] (project-request scope items {}))
  ([scope items options]
   (let [book @(:book scope)]
     (projection/project
      items
      (merge {:erase (ledger/erases book)
              :reset-call-id (ledger/last-reset-call-id book)
              :settled? true}
             options)))))

(def project project-request)

(defn snapshot
  "The synchronous state a negotiate call may read."
  [scope]
  (let [book @(:book scope)
        reset (ledger/last-reset book)]
    {:ok true
     :kind :negotiate
     :session-id (:session-id scope)
     :goal (:goal reset)
     :budget (:budget reset)
     :remaining (ledger/remaining book)
     :established? (ledger/established? book)
     :must-reset (or (not (ledger/established? book))
                     (ledger/spent? book))
     :spin (ledger/spin book)
     :resets (count (ledger/reset-beats book))
     :notice (notice-for book)}))

(defn previous-scopes
  "The reset history, in order, including the newest reset.

  `askPreviousScope` wants the reset immediately before the current one; use
  `preceding-scope` for that."
  [scope]
  (ledger/reset-beats @(:book scope)))

(defn preceding-scope
  "The reset beat immediately before the newest one, or nil."
  [scope]
  (let [beats (ledger/reset-beats @(:book scope))]
    (when (>= (count beats) 2)
      (nth beats (- (count beats) 2)))))

(defn table
  "Create an empty per-process session table."
  []
  (atom {}))

(defn- create-in-table! [table options factory]
  (let [session-id (:session-id options)]
    (check-session-id session-id)
    (locking table
      (if (contains? @table session-id)
        (errors/refuse errors/owner-taken
                       (str "session " session-id " already has a scope")
                       {:session-id session-id})
        (let [scope (factory options)]
          (swap! table assoc session-id scope)
          scope)))))

(defn create-scope! [table options]
  (create-in-table! table options make-scope))

(defn cold-start-scope! [table options]
  (create-in-table! table options cold-start))

(defn find-scope [table session-id]
  (get @table session-id))

(defn require-scope [table session-id]
  (or (find-scope table session-id)
      (errors/refuse errors/not-found
                     (str "no scope for session " session-id)
                     {:session-id session-id})))

(defn drop-scope! [table session-id]
  (swap! table dissoc session-id)
  session-id)
