(ns hc.core.scope.ledger
  "Pure retention and turn bookkeeping for one Scope.

  One model turn settles at most once. The first true annotation settles it.
  A me-too that arrives first is staged; a turn that closes with only me-too
  settles as keep-last and spends one point. A second true annotation in the
  same turn is only confirmed."
  (:require [clojure.string :as string]
            [hc.core.scope.errors :as errors]
            [hc.core.scope.records :as records]))

(defn new-book
  ([] (new-book {}))
  ([{:keys [retention last-reset reset-beats settlements spin erases]}]
   {:retention (merge {:established? false :remaining 0} retention)
    :turn nil
    :last-reset last-reset
    :reset-beats (vec reset-beats)
    :settlements (or settlements 0)
    :spin (or spin 0)
    :erases (vec erases)}))

(defn restore-book
  "Rebuild a book from cold-start facts. The budget stays unknown."
  [cold]
  (-> (new-book)
      (assoc :last-reset (:last-reset cold)
             :reset-beats (vec (:resets cold))
             :spin (or (:spin cold) 0)
             :erases (vec (:erases cold)))))

(defn established? [book]
  (get-in book [:retention :established?]))

(defn remaining [book]
  (get-in book [:retention :remaining]))

(defn spent? [book]
  (and (established? book) (zero? (remaining book))))

(defn retention [book]
  (:retention book))

(defn last-reset [book]
  (:last-reset book))

(defn last-reset-call-id [book]
  (get-in book [:last-reset :call-id]))

(defn reset-beats [book]
  (:reset-beats book))

(defn erases [book]
  (:erases book))

(defn spin [book]
  (:spin book))

(defn settlements [book]
  (:settlements book))

(defn- close-turn-state [book]
  (if-let [turn (:turn book)]
    (assoc book :turn (assoc turn :settled? true :staged? false))
    book))

(defn- open-turn [book turn-id]
  (assoc book :turn {:id turn-id :settled? false :staged? false}))

(defn- turn-settled? [book turn-id]
  (and (:turn book)
       (= turn-id (get-in book [:turn :id]))
       (get-in book [:turn :settled?])))

(defn- spend-one [book]
  (assoc-in book [:retention :remaining] (dec (remaining book))))

(defn- finalize-staged
  "Settle a previous turn that closed with only me-too. Returns a result with
  records, or nil when there is nothing to finalize."
  [book]
  (let [turn (:turn book)]
    (if (and turn
             (not (:settled? turn))
             (:staged? turn))
      (cond
        (not (established? book))
        {:book book
         :result (errors/refuse errors/uninitialized
                                "the budget is not established"
                                {:turn-id (:id turn)})}

        (zero? (remaining book))
        {:book book
         :result (errors/refuse errors/budget-spent
                                "the budget is spent; a staged :me-too cannot settle"
                                {:turn-id (:id turn)})}

        :else
        (let [book' (-> book
                        spend-one
                        (update :settlements inc)
                        close-turn-state)
              left (remaining book')
              record (records/keep-record {:turn-id (:id turn)
                                           :remaining left
                                           :defaulted? true})]
          {:book book'
           :result {:ok true
                    :outcome :settled
                    :kind :keep-last
                    :defaulted? true
                    :turn-id (:id turn)
                    :remaining left
                    :records [record]}}))
      {:book book :result nil})))

(defn- settle-me-too [book turn-id]
  (cond
    (not (established? book))
    {:book book
     :result (errors/refuse errors/uninitialized
                            "a :me-too cannot arrive before the budget is established"
                            {:kind :me-too :turn-id turn-id})}

    (zero? (remaining book))
    {:book book
     :result (errors/refuse errors/budget-spent
                            "the budget is spent; a :me-too cannot open the turn"
                            {:kind :me-too :turn-id turn-id})}

    :else
    {:book (assoc-in book [:turn :staged?] true)
     :result {:ok true
              :outcome :staged
              :kind :me-too
              :turn-id turn-id
              :remaining (remaining book)
              :records []}}))

(defn- settle-reset [book turn-id annotation call-id observed]
  (let [goal (:goal annotation)
        budget (:budget annotation)]
    (if-not (and (string? goal)
                 (not (string/blank? goal))
                 (integer? budget)
                 (pos? budget))
      {:book book
       :result (errors/refuse errors/field-invalid
                              "resetScope needs a goal and a positive budget"
                              {:turn-id turn-id})}
      (let [beat {:turn-id turn-id
                  :call-id call-id
                  :goal goal
                  :budget budget}
            book' (-> book
                      (assoc :retention {:established? true :remaining budget})
                      (assoc :last-reset beat)
                      (update :reset-beats conj beat)
                      (update :settlements inc)
                      close-turn-state)
            record (records/reset-record {:turn-id turn-id
                                          :call-id call-id
                                          :goal goal
                                          :budget budget
                                          :remaining budget})]
        {:book book'
         :result {:ok true
                  :outcome :settled
                  :kind :reset-scope
                  :turn-id turn-id
                  :goal goal
                  :budget budget
                  :remaining budget
                  :observed observed
                  :records [record]}}))))

(defn- settle-keep [book turn-id observed]
  (cond
    (not (established? book))
    {:book book
     :result (errors/refuse errors/uninitialized
                            "the budget is not established; resetScope is required"
                            {:kind :keep-last :turn-id turn-id})}

    (zero? (remaining book))
    {:book book
     :result (errors/refuse errors/budget-spent
                            "the budget is spent; resetScope is required"
                            {:kind :keep-last :turn-id turn-id})}

    :else
    (let [book' (-> book
                    spend-one
                    (update :settlements inc)
                    close-turn-state)
          left (remaining book')
          record (records/keep-record {:turn-id turn-id :remaining left})]
      {:book book'
       :result {:ok true
                :outcome :settled
                :kind :keep-last
                :turn-id turn-id
                :remaining left
                :observed observed
                :records [record]}})))

(defn- settle-remove [book turn-id annotation call-id observed]
  (let [replacement (:replacement annotation)]
    (cond
      (or (not (string? replacement))
          (string/blank? replacement))
      {:book book
       :result (errors/refuse errors/field-invalid
                              "removeLast needs a non-blank replacement"
                              {:turn-id turn-id})}

      (not (established? book))
      {:book book
       :result (errors/refuse errors/uninitialized
                              "the budget is not established; resetScope is required"
                              {:kind :remove-last :turn-id turn-id})}

      (zero? (remaining book))
      {:book book
       :result (errors/refuse errors/budget-spent
                              "the budget is spent; resetScope is required"
                              {:kind :remove-last :turn-id turn-id})}

      :else
      (let [spin' (inc (spin book))
            book' (-> book
                      (assoc :spin spin')
                      (update :settlements inc)
                      close-turn-state)
            book' (if (some? call-id)
                    (update book' :erases conj {:call-id call-id
                                                :replacement replacement
                                                :spin spin'})
                    book')
            record (when (some? call-id)
                     (records/remove-record {:turn-id turn-id
                                             :call-id call-id
                                             :replacement replacement
                                             :spin spin'}))]
        {:book book'
         :result {:ok true
                  :outcome :settled
                  :kind :remove-last
                  :turn-id turn-id
                  :replacement replacement
                  :spin spin'
                  :remaining (remaining book')
                  :observed observed
                  :records (cond-> [] record (conj record))}}))))

(defn settle-turn
  "Settle one parsed annotation for `turn-id`.

  Returns `{:book next-book :result result}`. A refusal leaves `:book` as it
  was. `call-id` names the physical call; it is needed to project a reset
  boundary or a removeLast erasure.

  A successful result carries `:records` (the full batch to append),
  `:current-records` (this turn) and, when a prior staged turn was
  finalized, `:prior-records`. The stateful wrapper persists the full batch
  and reports `:persisted` from the current records only. `observed` is
  diagnostic data echoed in the receipt; it is not persisted and is not the
  provider original."
  [book turn-id annotation {:keys [call-id observed]}]
  (cond
    (nil? turn-id)
    {:book book
     :result (errors/refuse errors/turn-missing
                            "a settle needs the model turn id")}

    (not (contains? errors/kinds (:kind annotation)))
    {:book book
     :result (errors/refuse errors/kind-invalid
                            (str "unknown scope kind: "
                                 (pr-str (:kind annotation)))
                            {:kind (:kind annotation)})}

    (turn-settled? book turn-id)
    {:book book
     :result {:ok true
              :outcome :confirmed
              :kind (:kind annotation)
              :turn-id turn-id
              :remaining (remaining book)
              :records []}}

    :else
    (let [same-turn? (and (:turn book)
                          (= turn-id (get-in book [:turn :id])))
          {final-book :book final-result :result}
          (if same-turn?
            {:book book :result nil}
            (finalize-staged book))]
      (if (and final-result (false? (:ok final-result)))
        {:book final-book :result final-result}
        (let [book (if same-turn? book (open-turn final-book turn-id))
              {next-book :book result :result}
              (case (:kind annotation)
                :me-too (settle-me-too book turn-id)
                :reset-scope (settle-reset book turn-id annotation call-id observed)
                :keep-last (settle-keep book turn-id observed)
                :remove-last (settle-remove book turn-id annotation call-id observed)
                {:book book
                 :result (errors/refuse errors/kind-invalid
                                        (str "unknown scope kind: "
                                             (pr-str (:kind annotation)))
                                        {:kind (:kind annotation)})})]
          (let [prior (vec (:records final-result))
                current (vec (:records result))]
            (if (false? (:ok result))
              {:book final-book
               :result (cond-> result
                         (seq prior)
                         (assoc :prior-records prior))}
              {:book next-book
               :result (cond-> (assoc result
                                      :records (into prior current)
                                      :current-records current)
                         (seq prior) (assoc :prior-records prior))})))))))

(defn close-turn
  "Close the current turn.

  A turn with only staged me-too settles as keep-last and spends one point.
  Every other open turn closes without booking.

  With `turn-id`, a close for a different turn is ignored.  A late model
  terminal must not default a newer turn's staged me-too."
  ([book] (close-turn book nil))
  ([book turn-id]
   (if (and (some? turn-id)
            (some? (get-in book [:turn :id]))
            (not= turn-id (get-in book [:turn :id])))
     {:book book
      :result {:ok true
               :outcome :closed
               :ignored :turn-mismatch
               :turn-id turn-id
               :current-turn-id (get-in book [:turn :id])
               :records []}}
     (let [turn (:turn book)]
       (cond
         (nil? turn)
         {:book book :result {:ok true :outcome :closed :records []}}

         (:settled? turn)
         {:book book :result {:ok true :outcome :closed :records []}}

         (:staged? turn)
         (cond
           (not (established? book))
           {:book book
            :result (errors/refuse errors/uninitialized
                                   "the budget is not established"
                                   {:turn-id (:id turn)})}

           (zero? (remaining book))
           {:book book
            :result (errors/refuse errors/budget-spent
                                   "the budget is spent; the staged :me-too cannot settle"
                                   {:turn-id (:id turn)})}

           :else
           (let [book' (-> book
                           spend-one
                           (update :settlements inc)
                           close-turn-state)
                 left (remaining book')
                 record (records/keep-record {:turn-id (:id turn)
                                              :remaining left
                                              :defaulted? true})]
             {:book book'
              :result {:ok true
                       :outcome :settled
                       :kind :keep-last
                       :defaulted? true
                       :turn-id (:id turn)
                       :remaining left
                       :records [record]}}))

         :else
         {:book book :result {:ok true :outcome :closed :records []}})))))
