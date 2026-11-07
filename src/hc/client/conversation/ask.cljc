(ns hc.client.conversation.ask
  "S1 ask cards.

  An ask is the only conversational interruption allowed by the unattended
  contract.  The card keeps the question, optional context, selectable options,
  and an answer draft.  Submitting creates an `:unpark` command; the card is
  frozen as submitted while the command is pending.  Editing an answered card
  starts a new draft and appends a new answer record instead of mutating the
  previous record.  Skipping is an explicit answer record, not a hidden
  dismissal."
  (:require [clojure.string :as str]
            [hc.client.conversation.draft :as draft]))

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn- meaningful?
  [text]
  (draft/meaningful-text? text))

(defn new-ask
  "Build a pending ask card from a server `(ask {:question ...})` event."
  [{:keys [ask-id name question context options multiple? input? created-at]
    :as ask}]
  (let [ask-id (or ask-id (str "ask-" (hash (or question name "ask"))))]
    {:ask-id (str ask-id)
     :name name
     :question (or question "")
     :context context
     :options (vec (or options []))
     :multiple? (boolean multiple?)
     :input? (if (contains? ask :input?) (boolean input?) true)
     :status :pending
     :draft {:text ""
             :selected #{}
             :version 0}
     :answers []
     ;; The answer must name the parked tenure.  The server sends it on the
     ;; ask event; dropping it here made every answer unresolvable.
     :lane (:lane ask)
     :tenure-id (:tenure-id ask)
     :created-at created-at
     :last-outcome {:kind :ask :result :shown}}))

(defn merge-ask
  "Merge a server re-send without losing locally submitted answers."
  [existing ask]
  (if existing
    (-> existing
        (merge (dissoc ask :answers :status :draft :last-outcome))
        (assoc :last-outcome {:kind :ask :result :updated}))
    ask))

(defn answered?
  [ask]
  (= :answered (keywordize (:status ask))))

(defn pending?
  [ask]
  (= :pending (keywordize (:status ask))))

(defn submitting?
  [ask]
  (= :submitting (keywordize (:status ask))))

(defn rejected?
  [ask]
  (= :rejected (keywordize (:status ask))))

(defn skipped?
  [ask]
  (= :skipped (keywordize (:status ask))))

(defn skippable?
  "A question can be skipped before it is answered and again after a rejected
  receipt.  An answered or already skipped card keeps its frozen record."
  [ask]
  (contains? #{:pending :rejected} (keywordize (:status ask))))

(defn can-submit?
  [ask]
  (and (not (submitting? ask))
       (or (meaningful? (get-in ask [:draft :text]))
           (seq (get-in ask [:draft :selected])))))

(defn- normalize-selected
  [ask selected]
  (let [selected (cond
                   (set? selected) selected
                   (sequential? selected) (set selected)
                   (nil? selected) #{}
                   :else #{selected})
        allowed (set (map :value (:options ask)))]
    (if (seq allowed)
      (set (filter allowed selected))
      selected)))

(defn edit
  "Edit the answer draft.  An answered card keeps its frozen answer records;
  the new draft is simply the beginning of another visible answer."
  [ask {:keys [text selected at] :as change}]
  (let [draft (:draft ask)
        text (if (string? text) text (:text draft))
        selected (if (contains? change :selected)
                   (normalize-selected ask selected)
                   (:selected draft))
        changed? (or (not= text (:text draft))
                     (not= selected (:selected draft)))]
    (-> ask
        (assoc :draft (cond-> (assoc draft
                                     :text text
                                     :selected selected)
                        changed? (update :version inc)))
        (cond-> (and changed? (rejected? ask))
          (assoc :status :pending))
        (assoc :last-outcome {:kind :ask-edit :result (if changed? :changed :noop)
                              :at at}))))

(defn answer-value
  "The value passed to `(unpark {:name ... :value ...})`.

  - text only        -> the trimmed string
  - one option       -> that option value
  - several options  -> a vector of values
  - text and options -> `{:text ... :selected [...]}`
  "
  [ask]
  (let [text (str/trim (or (get-in ask [:draft :text]) ""))
        selected (vec (sort (map str (get-in ask [:draft :selected]))))
        input? (:input? ask)
        multiple? (:multiple? ask)]
    (cond
      (and input? (seq selected))
      {:text text :selected selected}

      (and input? (not (seq selected)))
      text

      multiple?
      selected

      (= 1 (count selected))
      (first selected)

      (seq selected)
      selected

      :else
      text)))

(defn- option-label
  [ask value]
  (or (some (fn [option]
              (when (= (str value) (str (:value option)))
                (let [label (:label option)]
                  (if (and label (not (str/blank? (str label))))
                    (str label)
                    (str (:value option))))))
            (:options ask))
      (when (string? value) value)
      (str value)))

(defn answer-label
  "Human-readable label for an answer value.

  Option answers display the option's label, never its internal value.  Text
  and option answers keep both parts; multiple selections are joined with `、`."
  [ask value]
  (cond
    (and (map? value) (:skipped? value))
    "跳过"

    (and (map? value) (contains? value :selected))
    (let [text (str/trim (str (or (:text value) "")))
          labels (mapv #(option-label ask %) (or (:selected value) []))
          parts (cond-> []
                  (not (str/blank? text)) (conj text)
                  (seq labels) (conj (str/join "、" labels)))]
      (if (seq parts)
        (str/join "；" parts)
        "（空回答）"))

    (vector? value)
    (if (seq value)
      (str/join "、" (map #(option-label ask %) value))
      "（空回答）")

    (nil? value)
    "（空回答）"

    (map? value)
    (pr-str value)

    :else
    (option-label ask value)))

(defn submit
  "Freeze the current answer as a new record.

  `command-id` is supplied by the reducer so the pending command and the
  visible record share one identity."
  [ask command-id at]
  (if-not (can-submit? ask)
    {:ask ask :result :blocked :reason :empty-answer}
    (let [value (answer-value ask)
          answer {:record-id (str (:ask-id ask) "-a" (inc (count (:answers ask))))
                  :command-id command-id
                  :value value
                  :label (answer-label ask value)
                  :text (get-in ask [:draft :text])
                  :selected (vec (sort (get-in ask [:draft :selected])))
                  :status :submitting
                  :at at}]
      {:ask (-> ask
                (assoc :status :submitting
                       :last-answer answer)
                (update :answers conj answer)
                (assoc :last-outcome {:kind :ask-submit :result :submitted
                                      :record-id (:record-id answer)}))
       :answer answer
       :result :submitted})))

(defn skip
  "Record an explicit skip as a visible answer and freeze the card.

  The unpark value is `{:skipped? true}` so the parked program receives a real
  answer instead of remaining parked forever."
  [ask command-id at]
  (if-not (skippable? ask)
    {:ask ask :result :blocked :reason :not-skippable}
    (let [answer {:record-id (str (:ask-id ask) "-a" (inc (count (:answers ask))))
                  :command-id command-id
                  :value {:skipped? true}
                  :label "跳过"
                  :skipped? true
                  :text ""
                  :selected []
                  :status :submitting
                  :at at}]
      {:ask (-> ask
                (assoc :status :submitting
                       :last-answer answer)
                (update :answers conj answer)
                (assoc :last-outcome {:kind :ask-skip :result :skipped
                                      :record-id (:record-id answer)}))
       :answer answer
       :result :skipped})))

(defn ack
  "Apply a server receipt to the answer record carrying `command-id`.

  A late receipt for an older answer must not mark a newer answer as answered
  or rejected.  The card status follows the newest answer that is still
  submitting."
  [ask command-id accepted? at]
  (let [found? (some #(= command-id (:command-id %)) (:answers ask))
        skipped-answer? (some #(and (= command-id (:command-id %))
                                    (:skipped? %))
                              (:answers ask))
        answers (mapv (fn [answer]
                        (if (= command-id (:command-id answer))
                          (assoc answer
                                 :status (if accepted? :confirmed :rejected)
                                 :acked-at at)
                          answer))
                      (:answers ask))
        still-submitting? (some #(= :submitting (:status %)) answers)
        status (cond
                 (not found?) (:status ask)
                 accepted? (cond
                             still-submitting? :submitting
                             skipped-answer? :skipped
                             :else :answered)
                 (= :submitting (:status ask)) (if still-submitting?
                                                 :submitting
                                                 :rejected)
                 :else (:status ask))]
    (-> ask
        (assoc :answers answers
               :status status
               :last-outcome {:kind :ask-ack
                              :result (if accepted? :confirmed :rejected)
                              :command-id command-id}))))

(defn answer-records
  "Return the visible answer history in submission order."
  [ask]
  (:answers ask))
