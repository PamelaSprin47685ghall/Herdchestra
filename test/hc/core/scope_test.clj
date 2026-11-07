(ns hc.core.scope-test
  "Property and example tests for hc.core.scope (root AGENTS.md §6.2)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.scope :as scope]
            [hc.core.scope.admission :as admission]
            [hc.core.scope.annotation :as annotation]
            [hc.core.scope.errors :as errors]
            [hc.core.scope.ledger :as ledger]
            [hc.core.scope.projection :as projection]
            [hc.core.scope.records :as records]
            [hc.core.scope.runtime :as runtime]))

;; ---------------------------------------------------------------- annotations

(def gen-positive-budget
  (gen/fmap inc (gen/choose 0 1000000)))

(def gen-text
  (gen/fmap #(str "text-" %) (gen/choose 0 1000000)))

(def gen-valid-annotation
  (gen/one-of
   [(gen/fmap (fn [[goal budget]]
                {:kind :reset-scope
                 :new-scope-goal goal
                 :new-scope-retention-budget budget})
              (gen/tuple gen-text gen-positive-budget))
    (gen/return {:kind :keep-last})
    (gen/fmap (fn [replacement]
                {:kind :remove-last :replacement replacement})
              gen-text)
    (gen/return {:kind :me-too})]))

(def gen-raw-annotation
  (gen/one-of
   [gen/any-printable
    (gen/map (gen/one-of [gen/keyword gen/string gen/large-integer])
             gen/any-printable)]))

(deftest parses-the-four-kinds
  (is (= {:kind :reset-scope :goal "g" :budget 3}
         (annotation/parse-annotation
          {:kind :reset-scope :new-scope-goal "g" :new-scope-retention-budget 3})))
  (is (= {:kind :keep-last}
         (annotation/parse-annotation {:kind :keep-last})))
  (is (= {:kind :remove-last :replacement "lesson"}
         (annotation/parse-annotation
          {:kind :remove-last :replacement "lesson"})))
  (is (= {:kind :me-too}
         (annotation/parse-annotation {:kind :me-too}))))

(deftest rejects-missing-or-non-map
  (doseq [raw [nil "keep" [] 3]]
    (let [result (annotation/parse-annotation raw)]
      (is (false? (:ok result)))
      (is (= errors/annotation-missing (:code result)))
      (is (= :scope (get-in result [:details :field]))
          "the refusal names the missing field")
      (is (= ["reset-scope" "keep-last" "remove-last" "me-too"]
             (get-in result [:details :expected :kind]))
          "the refusal lists the accepted kinds for self-correction"))))

(deftest rejects-unknown-kinds
  (doseq [raw [{:kind :keep} {} {:kind "keep-last"} {:kind :resetScope}]]
    (let [result (annotation/parse-annotation raw)]
      (is (false? (:ok result)))
      (is (= errors/kind-invalid (:code result))))))

(deftest reset-needs-a-non-blank-goal-and-positive-integer-budget
  (doseq [raw [{:kind :reset-scope :new-scope-goal "" :new-scope-retention-budget 1}
               {:kind :reset-scope :new-scope-goal "  \n " :new-scope-retention-budget 1}
               {:kind :reset-scope :new-scope-goal 7 :new-scope-retention-budget 1}
               {:kind :reset-scope :new-scope-goal "g" :new-scope-retention-budget 0}
               {:kind :reset-scope :new-scope-goal "g" :new-scope-retention-budget -1}
               {:kind :reset-scope :new-scope-goal "g" :new-scope-retention-budget 1.5}
               {:kind :reset-scope :new-scope-goal "g" :new-scope-retention-budget "3"}
               {:kind :reset-scope :new-scope-goal "g"}]]
    (let [result (annotation/parse-annotation raw)]
      (is (false? (:ok result)))
      (is (= errors/field-invalid (:code result)))))
  (is (= 1000000000
         (:budget (annotation/parse-annotation
                   {:kind :reset-scope
                    :new-scope-goal "g"
                    :new-scope-retention-budget 1000000000})))))

(deftest remove-needs-a-non-blank-replacement
  (doseq [raw [{:kind :remove-last}
               {:kind :remove-last :replacement ""}
               {:kind :remove-last :replacement " \t"}
               {:kind :remove-last :replacement 7}]]
    (let [result (annotation/parse-annotation raw)]
      (is (false? (:ok result)))
      (is (= errors/field-invalid (:code result))))))

(deftest no-kind-accepts-extra-fields
  (doseq [raw [{:kind :reset-scope
                :new-scope-goal "g"
                :new-scope-retention-budget 1
                :extra true}
               {:kind :keep-last :new-scope-goal "g"}
               {:kind :me-too :replacement "x"}
               {:kind :remove-last :replacement "x" :new-scope-retention-budget 2}]]
    (let [result (annotation/parse-annotation raw)]
      (is (false? (:ok result)))
      (is (= errors/field-unexpected (:code result))))))

(defspec valid-annotations-always-parse 100
  (prop/for-all [raw gen-valid-annotation]
    (let [parsed (annotation/parse-annotation raw)]
      (and (map? parsed)
           (= (:kind raw) (:kind parsed))
           (not (contains? parsed :ok))
           (contains? errors/kinds (:kind parsed))
           (or (not= :reset-scope (:kind parsed))
               (and (= (:new-scope-goal raw) (:goal parsed))
                    (= (:new-scope-retention-budget raw) (:budget parsed))))))))

(defspec parse-is-total 200
  (prop/for-all [raw gen-raw-annotation]
    (let [result (annotation/parse-annotation raw)]
      (or (and (map? result)
               (contains? errors/kinds (:kind result))
               (not (contains? result :ok)))
          (and (map? result)
               (false? (:ok result))
               (contains? errors/admission-codes (:code result)))))))

;; ---------------------------------------------------------------- ledger

(defn parsed-reset [budget]
  {:kind :reset-scope :goal "goal" :budget budget})

(deftest reset-establishes-and-replaces-the-budget
  (let [book (ledger/new-book)
        {book :book result :result}
        (ledger/settle-turn book 1 (parsed-reset 3) {:call-id "r1"})]
    (is (true? (:ok result)))
    (is (= :settled (:outcome result)))
    (is (ledger/established? book))
    (is (= 3 (ledger/remaining book)))
    (is (= "r1" (ledger/last-reset-call-id book)))
    (is (= 1 (count (ledger/reset-beats book))))
    (is (= 1 (ledger/settlements book)))
    (is (= 1 (count (:records result))))))

(deftest keep-spends-and-remove-does-not
  (let [book (:book (ledger/settle-turn (ledger/new-book) 1 (parsed-reset 3)
                                        {:call-id "r1"}))
        {book :book keep-result :result}
        (ledger/settle-turn book 2 {:kind :keep-last} {:call-id "k1"})]
    (is (= 2 (ledger/remaining book)))
    (is (= :settled (:outcome keep-result)))
    (is (= 1 (count (:records keep-result))))
    (let [{book :book remove-result :result}
          (ledger/settle-turn book 3
                              {:kind :remove-last :replacement "lesson"}
                              {:call-id "e1"})]
      (is (= 2 (ledger/remaining book)))
      (is (= :settled (:outcome remove-result)))
      (is (= 1 (ledger/spin book)))
      (is (= [{:call-id "e1" :replacement "lesson" :spin 1}]
             (ledger/erases book)))
      (is (= 1 (count (:records remove-result)))))))

(deftest me-too-is-staged-then-the-true-annotation-settles-once
  (let [reset-book (:book (ledger/settle-turn (ledger/new-book) 1 (parsed-reset 2)
                                              {:call-id "r1"}))
        {staged-book :book staged :result}
        (ledger/settle-turn reset-book 2 {:kind :me-too} {:call-id "m1"})
        {kept-book :book kept :result}
        (ledger/settle-turn staged-book 2 {:kind :keep-last} {:call-id "k1"})
        {closed-book :book closed :result}
        (ledger/close-turn kept-book)]
    (is (= :staged (:outcome staged)))
    (is (= 2 (ledger/remaining staged-book)))
    (is (= :settled (:outcome kept)))
    (is (= 1 (ledger/remaining kept-book)))
    (is (= :closed (:outcome closed)))
    (is (= 1 (ledger/remaining closed-book)))))

(deftest a-second-true-annotation-is-only-confirmed
  (let [book (:book (ledger/settle-turn (ledger/new-book) 1 (parsed-reset 3)
                                        {:call-id "r1"}))
        {book :book first-result :result}
        (ledger/settle-turn book 2 {:kind :keep-last} {:call-id "k1"})
        {book :book second-result :result}
        (ledger/settle-turn book 2 {:kind :reset-scope :goal "other" :budget 9}
                            {:call-id "r2"})]
    (is (= :settled (:outcome first-result)))
    (is (= :confirmed (:outcome second-result)))
    (is (= 2 (ledger/remaining book)))
    (is (= "goal" (get-in book [:last-reset :goal])))
    (is (= 1 (count (ledger/reset-beats book))))))

(deftest a-turn-with-only-me-too-defaults-to-keep-last
  (let [reset-book (:book (ledger/settle-turn (ledger/new-book) 1 (parsed-reset 2)
                                              {:call-id "r1"}))
        {staged-book :book staged :result}
        (ledger/settle-turn reset-book 2 {:kind :me-too} {:call-id "m1"})
        {closed-book :book closed :result}
        (ledger/close-turn staged-book)]
    (is (= :staged (:outcome staged)))
    (is (= :settled (:outcome closed)))
    (is (= :keep-last (:kind closed)))
    (is (true? (:defaulted? closed)))
    (is (= 1 (ledger/remaining closed-book)))
    (is (= 2 (ledger/settlements closed-book)))
    (is (= [{:v 1 :type :keep :turn-id 2 :remaining 1 :defaulted? true}]
           (:records closed)))))

(deftest a-settle-needs-a-turn-id
  (let [book (ledger/new-book)
        {book :book result :result}
        (ledger/settle-turn book nil (parsed-reset 3) {:call-id "r1"})]
    (is (false? (:ok result)))
    (is (= errors/turn-missing (:code result)))
    (is (not (ledger/established? book)))))

(deftest remove-without-a-call-id-still-spins
  (let [book (:book (ledger/settle-turn (ledger/new-book) 1 (parsed-reset 2)
                                        {:call-id "r1"}))
        {book :book result :result}
        (ledger/settle-turn book 2
                            {:kind :remove-last :replacement "lesson"}
                            {})]
    (is (= :settled (:outcome result)))
    (is (= 1 (ledger/spin book)))
    (is (empty? (ledger/erases book)))
    (is (empty? (:records result)))))

(deftest a-new-turn-finalizes-a-staged-me-too
  (let [reset-book (:book (ledger/settle-turn (ledger/new-book) 1 (parsed-reset 2)
                                              {:call-id "r1"}))
        {staged-book :book staged :result}
        (ledger/settle-turn reset-book 2 {:kind :me-too} {:call-id "m1"})
        {next-book :book next-result :result}
        (ledger/settle-turn staged-book 3 {:kind :keep-last} {:call-id "k1"})]
    (is (= :staged (:outcome staged)))
    (is (= 2 (count (:records next-result))))
    (is (= 0 (ledger/remaining next-book)))
    (is (= 3 (ledger/settlements next-book)))))

(deftest a-refused-new-turn-still-books-the-staged-default
  (let [reset-book (:book (ledger/settle-turn (ledger/new-book) 1 (parsed-reset 1)
                                              {:call-id "r1"}))
        {staged-book :book _ :result}
        (ledger/settle-turn reset-book 2 {:kind :me-too} {:call-id "m1"})
        {next-book :book result :result}
        (ledger/settle-turn staged-book 3 {:kind :keep-last} {:call-id "k1"})]
    (is (false? (:ok result)))
    (is (= errors/budget-spent (:code result)))
    (is (= 0 (ledger/remaining next-book)))
    (is (= 2 (ledger/settlements next-book)))
    (is (= 1 (count (:prior-records result))))
    (is (= :keep (:type (first (:prior-records result)))))))

(deftest restore-keeps-history-but-not-the-budget
  (let [cold (records/read-cold-state
              [{:v 1 :type :reset :turn-id 1 :call-id "r1"
                :goal "old" :budget 4 :remaining 4}
               {:v 1 :type :keep :turn-id 2 :remaining 3}
               {:v 1 :type :remove :turn-id 3 :call-id "e1"
                :replacement "lesson" :spin 1}])
        book (ledger/restore-book cold)]
    (is (not (ledger/established? book)))
    (is (= 0 (ledger/remaining book)))
    (is (= "old" (get-in book [:last-reset :goal])))
    (is (= "r1" (ledger/last-reset-call-id book)))
    (is (= 1 (ledger/spin book)))
    (is (= 1 (count (ledger/erases book))))))

(def gen-ledger-budget
  (gen/fmap inc (gen/choose 0 5)))

(def gen-op
  (gen/one-of
   [(gen/fmap (fn [budget]
                {:annotation (parsed-reset budget)
                 :call-id (str "r" budget)})
              gen-ledger-budget)
    (gen/return {:annotation {:kind :keep-last} :call-id "k"})
    (gen/fmap (fn [id]
                {:annotation {:kind :remove-last :replacement "lesson"}
                 :call-id id})
              (gen/elements ["e1" "e2" "e3"]))
    (gen/return {:annotation {:kind :me-too} :call-id "m"})]))

(def gen-turn
  (gen/vector gen-op 0 5))

(defn- settle-step [book turn-id op]
  (let [before (ledger/remaining book)
        {next-book :book result :result}
        (ledger/settle-turn book turn-id (:annotation op)
                            {:call-id (:call-id op)})
        after (ledger/remaining next-book)
        outcome (:outcome result)
        kind (:kind result)
        settled? (= :settled outcome)]
    {:book next-book
     :ok? (cond
            (false? (:ok result)) (and (= book next-book) (= before after))
            settled? (and (not (neg? after))
                          (case kind
                            :reset-scope (= after (:budget result))
                            :keep-last (= after (dec before))
                            :remove-last (= after before)
                            true))
            :else (and (= before after) (not (neg? after))))
     :settled? settled?
     :staged? (= :staged outcome)
     :remove? (and settled? (= :remove-last kind))}))

(defn- simulate-turn [book turn-id ops]
  (loop [book book
         ops (seq ops)
         settled 0
         staged? false
         removes 0
         ok? true]
    (if-let [op (first ops)]
      (let [{:keys [book ok? settled? staged? remove?]}
            (settle-step book turn-id op)]
        (recur book
               (next ops)
               (+ settled (if settled? 1 0))
               (cond settled? false staged? true :else false)
               (+ removes (if remove? 1 0))
               (and ok? ok?)))
      (let [{next-book :book result :result} (ledger/close-turn book)
            default? (= :settled (:outcome result))
            final-settled (+ settled (if default? 1 0))]
        {:book next-book
         :settled final-settled
         :removes removes
         :ok? (and ok?
                   (<= final-settled 1)
                   (not (neg? (ledger/remaining next-book))))}))))

(defspec one-settlement-per-turn-and-monotone-budget 100
  (prop/for-all [turns (gen/vector gen-turn 0 6)]
    (loop [book (ledger/new-book)
           turns (seq turns)
           turn-id 0
           settled 0
           removes 0
           ok? true]
      (if-let [ops (first turns)]
        (let [{turn-book :book
               turn-settled :settled
               turn-ok? :ok?
               turn-removes :removes}
              (simulate-turn book turn-id ops)]
          (recur turn-book
                 (next turns)
                 (inc turn-id)
                 (+ settled turn-settled)
                 (+ removes turn-removes)
                 (and ok? turn-ok?)))
        (and ok?
             (= settled (ledger/settlements book))
             (= removes (ledger/spin book))
             (= removes (count (ledger/erases book)))
             (not (neg? (ledger/remaining book))))))))

;; ---------------------------------------------------------------- projection

(defn message [role text]
  {:kind :message :role role :text text})

(defn tool-call [member call-id & [scope-annotation]]
  (cond-> {:kind :tool-call :member member :call-id call-id}
    scope-annotation (assoc :scope scope-annotation)))

(defn tool-result [member call-id & [scope-annotation]]
  (cond-> {:kind :tool-result :member member :call-id call-id}
    scope-annotation (assoc :scope scope-annotation)))

(def reset-scope-annotation {:kind :reset-scope})

(deftest a-settled-boundary-keeps-the-old-conversation-shape
  (let [items [(message :operator "do it")
               (message :assistant "working")
               {:kind :thinking :text "old thought"}
               (tool-call "cat" "old")
               (tool-result "cat" "old")
               (tool-call "assume" "a1")
               (tool-result "assume" "a1")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")
               (tool-call "cat" "new")
               (tool-result "cat" "new")
               {:kind :thinking :text "new thought"}
               {:kind :provider :text "provider original"}]
        output (projection/project items {:settled? true :reset-call-id "r1"})]
    (is (vector? output))
    (is (= [(message :operator "do it")
            (message :assistant "working")
            (tool-call "assume" "a1")
            (tool-result "assume" "a1")
            (tool-call "resetScope" "r1" reset-scope-annotation)
            (tool-result "resetScope" "r1")
            (tool-call "cat" "new")
            (tool-result "cat" "new")
            {:kind :thinking :text "new thought"}
            {:kind :provider :text "provider original"}]
           output))))

(deftest no-settled-boundary-keeps-everything
  (let [items [(tool-call "cat" "old")
               (tool-result "cat" "old")
               {:kind :thinking :text "thought"}]]
    (is (= items (projection/project items {:settled? true :reset-call-id nil})))
    (is (= items (projection/project items {:settled? true
                                            :reset-call-id "missing"})))))

(deftest without-a-settled-fact-the-newest-annotation-is-the-fallback
  (let [items [(tool-call "cat" "old")
               (tool-result "cat" "old")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")]
        output (projection/project items)]
    (is (= [(tool-call "resetScope" "r1" reset-scope-annotation)
            (tool-result "resetScope" "r1")]
           output))))

(deftest an-explicit-call-id-wins-over-a-newer-annotation
  (let [items [(tool-call "cat" "old")
               (tool-result "cat" "old")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")
               (tool-call "cat" "middle")
               (tool-result "cat" "middle")
               (tool-call "resetScope" "r2" reset-scope-annotation)
               (tool-result "resetScope" "r2")]
        output (projection/project items {:settled? true :reset-call-id "r1"})]
    (is (some #(= "middle" (:call-id %)) output))
    (is (not-any? #(= "old" (:call-id %)) output))))

(deftest a-result-carried-reset-annotation-finds-its-call
  (let [items [(tool-call "cat" "old")
               (tool-result "cat" "old")
               (tool-call "resetScope" "r1")
               (tool-result "resetScope" "r1" reset-scope-annotation)]
        output (projection/project items)]
    (is (= [(tool-call "resetScope" "r1")
            (tool-result "resetScope" "r1" reset-scope-annotation)]
           output))))

(deftest a-later-call-with-the-reset-call-id-does-not-move-the-boundary
  (let [items [(tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")
               (tool-call "cat" "r1")
               (tool-result "cat" "r1")
               (message :assistant "after")]
        output (projection/project items {:settled? true :reset-call-id "r1"})]
    (is (= items output))))

(deftest a-reset-item-disambiguates-a-duplicate-call-id
  (let [items [(tool-call "cat" "r1")
               (tool-result "cat" "r1")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")]
        output (projection/project items {:settled? true :reset-call-id "r1"})]
    (is (= [(tool-call "resetScope" "r1" reset-scope-annotation)
            (tool-result "resetScope" "r1")]
           output))))

(deftest remove-last-rewrites-the-previous-beat
  (let [items [(message :assistant "the wrong turn")
               (tool-call "cat" "e1")
               (message :assistant "after")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson learned"
                                             :spin 2}]})]
    (is (= [(assoc (message :assistant "lesson learned\nSpin count: 2")
                   :erased-call-id "e1")
            (tool-call "cat" "e1")
            (message :assistant "after")]
           output))))

(deftest remove-last-rewrites-a-call-and-its-result-together
  (let [items [(tool-call "cat" "a")
               (tool-result "cat" "a")
               (tool-call "cat" "e1")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= [(assoc (message :assistant "lesson\nSpin count: 1")
                   :erased-call-id "e1")
            (tool-call "cat" "e1")]
           output))))

(deftest a-beat-before-the-boundary-is-not-rewritten
  (let [items [(message :assistant "old prose")
               (tool-call "cat" "e1")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id "r1"
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= [(message :assistant "old prose")
            (tool-call "resetScope" "r1" reset-scope-annotation)
            (tool-result "resetScope" "r1")]
           output))))

(deftest a-pinned-call-keeps-its-paired-result-by-call-id
  (let [items [(tool-call "assume" "a1")
               (tool-result "other" "a1")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")]
        output (projection/project items {:settled? true :reset-call-id "r1"})]
    (is (= [(tool-call "assume" "a1")
            (tool-result "other" "a1")
            (tool-call "resetScope" "r1" reset-scope-annotation)
            (tool-result "resetScope" "r1")]
           output))))

(deftest multiple-results-are-paired-by-call-id-not-adjacency
  (let [items [(tool-call "assume" "a1")
               (tool-call "cat" "b1")
               (tool-result "assume" "a1")
               (tool-result "cat" "b1")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")]
        output (projection/project items {:settled? true :reset-call-id "r1"})]
    (is (= [(tool-call "assume" "a1")
            (tool-result "assume" "a1")
            (tool-call "resetScope" "r1" reset-scope-annotation)
            (tool-result "resetScope" "r1")]
           output))))

(deftest an-adjacent-pair-with-incompatible-call-ids-must-agree
  (let [items [(tool-call "assume" nil)
               (tool-result "other" "x")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")]
        result (projection/project items {:settled? true :reset-call-id "r1"})]
    (is (false? (:ok result)))
    (is (= errors/field-invalid (:code result)))))

(deftest the-input-is-not-changed
  (let [items [(tool-call "cat" "old")
               (tool-result "cat" "old")
               (tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")]
        original (vec items)]
    (projection/project items {:settled? true :reset-call-id "r1"})
    (is (= original items))))

(def gen-item
  (gen/one-of
   [(gen/fmap (fn [[role text]] (message role text))
              (gen/tuple (gen/elements [:operator :assistant :user])
                         gen/string))
    (gen/fmap (fn [[member call-id]]
                (tool-call member call-id))
              (gen/tuple (gen/elements ["cat" "assume" "negotiate" "other"])
                         gen/string-alphanumeric))
    (gen/fmap (fn [[member call-id]]
                (tool-result member call-id))
              (gen/tuple (gen/elements ["cat" "assume" "negotiate" "other"])
                         gen/string-alphanumeric))
    (gen/return {:kind :thinking :text "thought"})
    (gen/return {:kind :provider :text "provider"})
    (gen/return {:kind :unknown :text "unknown"})]))

(defspec projection-is-total 150
  (prop/for-all [items (gen/vector gen-item 0 20)]
    (let [result (projection/project items {:settled? true
                                            :reset-call-id nil})]
      (or (vector? result)
          (and (map? result) (false? (:ok result)))))))

(defspec old-ordinary-tools-are-dropped 100
  (prop/for-all [call-id gen/string-alphanumeric]
    (let [items [(tool-call "cat" call-id)
                 (tool-result "cat" call-id)
                 (tool-call "resetScope" "r1" reset-scope-annotation)
                 (tool-result "resetScope" "r1")]
          output (projection/project items {:settled? true :reset-call-id "r1"})]
      (and (= 2 (count output))
           (not-any? #(and (= :tool-call (:kind %))
                           (= "cat" (:member %)))
                     output)))))

(defspec messages-after-the-boundary-are-kept 100
  (prop/for-all [tail (gen/vector gen-item 0 10)]
    (let [items (into [(tool-call "resetScope" "r1" reset-scope-annotation)
                       (tool-result "resetScope" "r1")]
                      tail)
          output (projection/project items {:settled? true :reset-call-id "r1"})]
      (= (count items) (count output)))))

;; ---------------------------------------------------------------- records

(def gen-record-text
  (gen/fmap #(str "goal-" %) (gen/choose 0 1000)))

(def gen-record-budget
  (gen/fmap inc (gen/choose 0 1000)))

(deftest record-constructors-carry-the-version
  (is (= 1 (:v (records/reset-record {:turn-id 1 :call-id "r"
                                      :goal "g" :budget 2 :remaining 2}))))
  (is (= 1 (:v (records/keep-record {:turn-id 1 :remaining 1}))))
  (is (= 1 (:v (records/remove-record {:turn-id 1 :call-id "e"
                                       :replacement "lesson" :spin 1})))))

(deftest read-cold-state-restores-history-spin-and-erase
  (let [cold (records/read-cold-state
              [(records/reset-record {:turn-id 1 :call-id "r1"
                                      :goal "old" :budget 4 :remaining 4})
               (records/keep-record {:turn-id 2 :remaining 3})
               (records/remove-record {:turn-id 3 :call-id "e1"
                                       :replacement "lesson" :spin 1})
               {:v 1 :type :budget :remaining 3}
               {:v 1 :type :me-too :turn-id 4}])]
    (is (= "old" (get-in cold [:last-reset :goal])))
    (is (= "r1" (get-in cold [:last-reset :call-id])))
    (is (= 1 (:spin cold)))
    (is (= [{:call-id "e1" :replacement "lesson" :spin 1}]
           (:erases cold)))))

(deftest malformed-records-throw
  (doseq [raw [{:v 2 :type :reset}
               {:v 1 :type :unknown}
               {:v 1 :type :reset :goal "" :budget 1}
               {:v 1 :type :reset :goal "g" :budget 0}
               {:v 1 :type :remove :replacement "lesson" :spin 1}
               {:v 1 :type :remove :call-id "e" :replacement "" :spin 1}
               {:v 1 :type :remove :call-id "e" :replacement "lesson" :spin 0}
               "not a map"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (records/read-cold-state [raw])))))

(defspec a-reset-round-trips 100
  (prop/for-all [goal gen-record-text
                 budget gen-record-budget
                 call-id gen/string-alphanumeric]
    (let [cold (records/read-cold-state
                [(records/reset-record {:turn-id 1
                                        :call-id call-id
                                        :goal goal
                                        :budget budget
                                        :remaining budget})])]
      (= {:turn-id 1
          :call-id call-id
          :goal goal
          :budget budget
          :remaining budget}
         (:last-reset cold)))))

(defspec a-remove-round-trips 100
  (prop/for-all [replacement gen-record-text
                 spin (gen/fmap inc (gen/choose 0 100))
                 call-id gen/string-alphanumeric]
    (let [cold (records/read-cold-state
                [(records/remove-record {:turn-id 1
                                         :call-id call-id
                                         :replacement replacement
                                         :spin spin})])]
      (and (= spin (:spin cold))
           (= [{:call-id call-id :replacement replacement :spin spin}]
              (:erases cold))))))

(defspec reading-is-total 100
  (prop/for-all [raw gen/any-printable]
    (try
      (map? (records/read-cold-state [raw]))
      (catch clojure.lang.ExceptionInfo error
        (= ::errors/argument-error (:type (ex-data error)))))))

;; ---------------------------------------------------------------- runtime adapter

(deftest runtime-extracts-annotations-from-owner-and-model-events
  (let [owner-event {:kind :tool-call
                     :payload {:executor :workspace
                               :name "write"
                               :scope {:kind :keep-last}}}
        model-event {:kind :tool-call
                     :tool-name "cat"
                     :call-id "t1"
                     :arguments {:scope {:kind :keep-last}}}
        request {:name "cat"
                 :arguments {:scope {:kind :reset-scope
                                     :new-scope-goal "g"
                                     :new-scope-retention-budget 2}}}]
    (is (= {:kind :keep-last} (runtime/scope-annotation owner-event)))
    (is (= "write" (runtime/tool-member owner-event)))
    (is (= {:kind :keep-last} (runtime/scope-annotation model-event)))
    (is (= "cat" (runtime/tool-member model-event)))
    (is (= "t1" (runtime/tool-call-id model-event)))
    (is (= {:kind :reset-scope
            :new-scope-goal "g"
            :new-scope-retention-budget 2}
           (runtime/scope-annotation request)))))

(deftest runtime-cold-start-requires-a-reset
  (let [book (runtime/cold-start-book
              [{:v 1 :type :reset :turn-id 1 :call-id "r1"
                :goal "old" :budget 4 :remaining 4}])
        keep-event {:kind :tool-call :tool-name "cat" :call-id "t1"
                    :arguments {:scope {:kind :keep-last}}}
        reset-event {:kind :tool-call :tool-name "cat" :call-id "t1"
                     :arguments {:scope {:kind :reset-scope
                                         :new-scope-goal "g"
                                         :new-scope-retention-budget 2}}}]
    (is (false? (scope/book-established? book)))
    (is (= errors/uninitialized
           (:code (runtime/admission-check book nil keep-event))))
    (let [{book :book result :result}
          (runtime/settle-tool book nil reset-event "turn-1" "t1")]
      (is (= :settled (:outcome result)))
      (is (true? (scope/book-established? book)))
      (is (= 2 (scope/book-remaining book))))))

(deftest runtime-latest-boundary-tracks-reset-and-remove
  (let [scope-state {:book {:last-reset {:call-id "r1"}
                            :erases [{:call-id "e1"}]}
                     :records [{:v 1 :type :reset :call-id "r1"}
                               {:v 1 :type :keep}
                               {:v 1 :type :remove :call-id "e1"}
                               {:v 1 :type :keep}]}
        boundary {:kind :remove-last :call-id "e1"}]
    (is (= boundary (runtime/latest-boundary scope-state)))
    (is (= boundary (runtime/pending-boundary scope-state nil)))
    (is (nil? (runtime/pending-boundary scope-state boundary)))
    (is (= boundary
           (runtime/pending-boundary
            scope-state
            {:kind :reset-scope :call-id "r1"})))))

(deftest runtime-latest-boundary-falls-back-to-the-book
  (let [scope-state {:book {:last-reset {:call-id "r1"}
                            :erases []}
                     :records []}]
    (is (= {:kind :reset-scope :call-id "r1"}
           (runtime/latest-boundary scope-state)))))

(deftest runtime-cold-start-state-clears-the-budget-and-keeps-records
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        records [{:v 1 :type :reset :turn-id "turn-1" :call-id "r1"
                  :goal "g" :budget 2 :remaining 2}]
        cold (runtime/cold-start-state {:book book :records records})]
    (is (false? (scope/book-established? (:book cold))))
    (is (= 0 (scope/book-remaining (:book cold))))
    (is (= records (:records cold)))
    (is (= "r1" (scope/last-reset-call-id (:book cold))))))

(deftest runtime-cold-start-state-uses-book-history-when-records-are-gone
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        cold (runtime/cold-start-state {:book book :records []})]
    (is (false? (scope/book-established? (:book cold))))
    (is (= "r1" (scope/last-reset-call-id (:book cold))))))

(deftest runtime-cold-start-state-keeps-book-history-beside-keep-records
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        records [{:v 1 :type :keep :turn-id "turn-2" :remaining 1}]
        cold (runtime/cold-start-state {:book book :records records})]
    (is (false? (scope/book-established? (:book cold))))
    (is (= "r1" (scope/last-reset-call-id (:book cold))))
    (is (= records (:records cold)))))

(deftest runtime-settles-a-tool-call-and-closes-me-too-only
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        keep-event {:kind :tool-call :tool-name "cat" :call-id "t1"
                    :arguments {:scope {:kind :keep-last}}}
        {book :book keep-result :result}
        (runtime/settle-tool book nil keep-event "turn-2" "t1")
        me-too-event {:kind :tool-call :tool-name "cat" :call-id "t2"
                      :arguments {:scope {:kind :me-too}}}]
    (is (= :settled (:outcome keep-result)))
    (is (= 1 (scope/book-remaining book)))
    (let [{book :book staged :result}
          (runtime/settle-tool book nil me-too-event "turn-3" "t2")]
      (is (= :staged (:outcome staged)))
      (is (= 1 (scope/book-remaining book)))
      (let [{book :book closed :result} (runtime/close-turn book)]
        (is (= :settled (:outcome closed)))
        (is (true? (:defaulted? closed)))
        (is (= 0 (scope/book-remaining book)))))))

(deftest runtime-normalizes-owner-tool-call-and-tool-messages
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        conversation [{:role :user :text "hi"}
                      {:role :tool-call
                       :member "cat"
                       :call-id "t1"
                       :scope {:kind :keep-last}}
                      {:role :tool
                       :member "cat"
                       :call-id "t1"
                       :text "ok"}]
        messages (runtime/project-conversation book conversation)]
    (is (vector? messages))
    (is (= [:user :assistant :tool] (mapv :role messages)))
    (is (= [{:id "t1" :name "cat"}] (:tool-calls (second messages))))
    (is (= "t1" (:tool-call-id (last messages))))
    (is (= "cat" (:name (last messages))))))

(deftest runtime-preserves-the-tool-result-error-flag
  (let [messages (runtime/items->messages
                  [{:kind :tool-result
                    :member "cat"
                    :call-id "t1"
                    :text "boom"
                    :error? true}])]
    (is (= true (:error? (first messages))))))

(deftest close-turn-without-an-annotation-keeps-the-turn-open-for-late-tools
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        {book :book closed :result} (scope/close-turn book)
        {book :book late :result}
        (scope/settle-turn book "turn-2" {:kind :keep-last}
                           {:call-id "late"})]
    (is (= :closed (:outcome closed)))
    (is (= :settled (:outcome late)))
    (is (= 1 (scope/book-remaining book)))))

(deftest close-turn-with-a-stale-id-does-not-close-a-newer-turn
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        book (:book (scope/settle-turn book "turn-2" {:kind :me-too} {}))
        {book :book result :result} (scope/close-turn book "turn-1")]
    (is (= :closed (:outcome result)))
    (is (= :turn-mismatch (:ignored result)))
    (is (= "turn-2" (get-in book [:turn :id])))
    (is (false? (get-in book [:turn :settled?])))
    (is (= 2 (scope/book-remaining book)))))

(deftest close-turn-with-a-turn-id-on-an-empty-book-is-a-no-op
  (let [{book :book result :result}
        (scope/close-turn (scope/new-book) "turn-1")]
    (is (= :closed (:outcome result)))
    (is (nil? (:ignored result)))
    (is (nil? (:turn book)))))

(deftest runtime-projects-actual-message-shapes
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        conversation [{:role :user :content "do it"}
                      {:role :assistant :content "working"}
                      {:role :tool :content "old result"
                       :tool-call-id "old" :name "cat"}
                      {:role :assistant :content "reset"
                       :tool-calls [{:id "r1" :name "resetScope"
                                     :arguments {:scope
                                                 {:kind :reset-scope
                                                  :new-scope-goal "g"
                                                  :new-scope-retention-budget 2}}}]}
                      {:role :tool :content "reset result"
                       :tool-call-id "r1" :name "resetScope"}
                      {:role :assistant :content "new"
                       :tool-calls [{:id "t2" :name "cat"
                                     :arguments {:scope {:kind :keep-last}}}]}
                      {:role :tool :content "new result"
                       :tool-call-id "t2" :name "cat"}]
        messages (runtime/project-conversation book conversation)]
    (is (vector? messages))
    (is (= [:user :assistant :assistant :tool :assistant :tool]
           (mapv :role messages)))
    (is (= "do it" (:text (first messages))))
    (is (not-any? #(= "old" (:tool-call-id %)) messages))
    (is (= "reset result" (:text (nth messages 3))))
    (is (= "r1" (:tool-call-id (nth messages 3))))
    (is (= "new result" (:text (last messages))))))

(deftest runtime-keeps-multiple-results-by-call-id
  (let [book (:book (scope/settle-turn
                     (scope/new-book) "turn-1"
                     {:kind :reset-scope :goal "g" :budget 2}
                     {:call-id "r1"}))
        conversation [{:role :assistant :content "calls"
                       :tool-calls [{:id "a1" :name "assume"
                                     :arguments {:scope {:kind :keep-last}}}
                                    {:id "b1" :name "cat"
                                     :arguments {:scope {:kind :keep-last}}}]}
                      {:role :tool :content "assume result"
                       :tool-call-id "a1" :name "assume"}
                      {:role :tool :content "cat result"
                       :tool-call-id "b1" :name "cat"}
                      {:role :assistant :content "reset"
                       :tool-calls [{:id "r1" :name "resetScope"
                                     :arguments {:scope
                                                 {:kind :reset-scope
                                                  :new-scope-goal "g"
                                                  :new-scope-retention-budget 2}}}]}
                      {:role :tool :content "reset result"
                       :tool-call-id "r1" :name "resetScope"}]
        messages (runtime/project-conversation book conversation)]
    (is (vector? messages))
    (is (= #{"a1" "r1"}
           (set (mapcat (fn [message]
                          (concat (map :id (:tool-calls message))
                                  (when-let [id (:tool-call-id message)] [id])))
                        messages))))
    (is (not-any? #(= "b1" (:tool-call-id %)) messages))
    (is (= ["a1"] (mapv :id (:tool-calls (first messages)))))))

;; ---------------------------------------------------------------- stateful scope

(defn raw-reset [budget]
  {:kind :reset-scope
   :new-scope-goal "goal"
   :new-scope-retention-budget budget})

(defn make-test-scope []
  (let [store (scope/memory-store)]
    {:scope (scope/make-scope {:session-id "s1" :store store})
     :store store}))

(deftest a-scope-needs-a-session-id
  (is (thrown? clojure.lang.ExceptionInfo
               (scope/make-scope {:session-id ""})))
  (is (thrown? clojure.lang.ExceptionInfo
               (scope/make-scope {})))
  (is (thrown? clojure.lang.ExceptionInfo
               (scope/cold-start {:session-id "s1" :records []}))))

(deftest uninitialized-admits-only-reset
  (let [{:keys [scope]} (make-test-scope)]
    (let [refused (scope/admit! scope {:member "cat"
                                       :annotation {:kind :keep-last}})]
      (is (false? (:ok refused)))
      (is (= errors/uninitialized (:code refused))))
    (let [refused (scope/admit! scope {:member "cat"
                                       :annotation {:kind :me-too}})]
      (is (= errors/uninitialized (:code refused))))
    (let [allowed (scope/admit! scope {:member "cat"
                                       :annotation (raw-reset 2)})]
      (is (true? (:ok allowed)))
      (is (true? (:must-reset allowed)))
      (is (some? (:notice allowed))))))

(deftest reset-settles-and-persists
  (let [{:keys [scope store]} (make-test-scope)
        result (scope/settle! scope {:member "cat"
                                     :annotation (raw-reset 2)
                                     :turn-id 1
                                     :call-id "r1"})]
    (is (true? (:ok result)))
    (is (= :settled (:outcome result)))
    (is (= 2 (scope/remaining scope)))
    (is (true? (scope/budget-known? scope)))
    (is (nil? (scope/notice scope)))
    (is (= 1 (count @(:written store))))
    (is (= :reset (:type (first @(:written store)))))))

(deftest keep-spends-and-persists
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id 1 :call-id "r1"})
    (let [result (scope/settle! scope {:member "cat"
                                       :annotation {:kind :keep-last}
                                       :turn-id 2
                                       :call-id "k1"})]
      (is (= :settled (:outcome result)))
      (is (= 1 (scope/remaining scope)))
      (is (= [:reset :keep] (map :type @(:written store)))))))

(deftest remove-does-not-spend-and-is-projected
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id 1 :call-id "r1"})
    (let [result (scope/settle! scope {:member "cat"
                                       :annotation {:kind :remove-last
                                                    :replacement "lesson"}
                                       :turn-id 2
                                       :call-id "e1"})]
      (is (= :settled (:outcome result)))
      (is (= 2 (scope/remaining scope)))
      (is (= 1 (scope/spin-count scope)))
      (is (= 1 (count (scope/erased scope))))
      (is (= :remove (:type (last @(:written store)))))
      (let [items [{:kind :message :role :assistant :text "wrong"}
                   {:kind :tool-call :member "cat" :call-id "e1"}]
            output (scope/project-request scope items)]
        (is (= {:kind :message
                :role :assistant
                :text "lesson\nSpin count: 1"
                :erased-call-id "e1"}
               (first output)))))))

(deftest a-me-too-only-turn-defaults-to-keep-last
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id 1 :call-id "r1"})
    (let [staged (scope/settle! scope {:member "cat"
                                       :annotation {:kind :me-too}
                                       :turn-id 2
                                       :call-id "m1"})]
      (is (= :staged (:outcome staged)))
      (is (= 2 (scope/remaining scope))))
    (let [closed (scope/close-turn! scope)]
      (is (= :settled (:outcome closed)))
      (is (= :keep-last (:kind closed)))
      (is (true? (:defaulted? closed)))
      (is (= 1 (scope/remaining scope)))
      (is (= :keep (:type (last @(:written store))))))))

(deftest a-spent-budget-admits-only-reset
  (let [{:keys [scope]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 1)
                          :turn-id 1 :call-id "r1"})
    (scope/settle! scope {:member "cat" :annotation {:kind :keep-last}
                          :turn-id 2 :call-id "k1"})
    (is (true? (scope/spent? scope)))
    (doseq [annotation [{:kind :keep-last}
                        {:kind :remove-last :replacement "lesson"}
                        {:kind :me-too}]]
      (let [refused (scope/admit! scope {:member "cat" :annotation annotation})]
        (is (false? (:ok refused)))
        (is (= errors/budget-spent (:code refused)))))
    (is (true? (:ok (scope/admit! scope {:member "cat"
                                         :annotation (raw-reset 3)}))))))

(deftest exempt-members-ignore-budget-and-annotation
  (let [{:keys [scope store]} (make-test-scope)]
    (doseq [member ["negotiate" "askPreviousScope"]]
      (let [admitted (scope/admit! scope {:member member})]
        (is (true? (:ok admitted)))
        (is (true? (:exempt admitted))))
      (let [settled (scope/settle! scope {:member member :turn-id 1})]
        (is (= :exempt (:outcome settled)))
        (is (empty? @(:written store)))))))

(deftest record-failure-leaves-the-book-unchanged
  (let [scope (scope/make-scope
               {:session-id "s1"
                :store (fn [_] (throw (ex-info "disk full" {})))})
        result (scope/settle! scope {:member "cat"
                                     :annotation (raw-reset 2)
                                     :turn-id 1
                                     :call-id "r1"})]
    (is (false? (:ok result)))
    (is (= errors/record-failed (:code result)))
    (is (false? (scope/budget-known? scope)))
    (is (= 0 (scope/remaining scope)))
    (is (nil? (scope/last-reset scope)))))

(deftest a-refused-new-turn-persists-the-staged-default
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 1)
                          :turn-id 1 :call-id "r1"})
    (scope/settle! scope {:member "cat" :annotation {:kind :me-too}
                          :turn-id 2 :call-id "m1"})
    (let [result (scope/settle! scope {:member "cat"
                                       :annotation {:kind :keep-last}
                                       :turn-id 3
                                       :call-id "k1"})]
      (is (false? (:ok result)))
      (is (= errors/budget-spent (:code result)))
      (is (= 0 (scope/remaining scope)))
      (is (= [:reset :keep] (map :type @(:written store))))
      (is (= 1 (count (:prior-records result)))))))

(deftest cold-start-needs-a-new-reset
  (let [records [{:v 1 :type :reset :turn-id 1 :call-id "r1"
                  :goal "old goal" :budget 4 :remaining 4}
                 {:v 1 :type :remove :turn-id 2 :call-id "e1"
                  :replacement "old lesson" :spin 1}]
        store (scope/memory-store)
        cold (scope/cold-start {:session-id "s1" :records records :store store})]
    (is (false? (scope/budget-known? cold)))
    (is (some? (scope/notice cold)))
    (is (= 1 (scope/spin-count cold)))
    (is (= 1 (count (scope/erased cold))))
    (is (= errors/uninitialized
           (:code (scope/admit! cold {:member "cat"
                                      :annotation {:kind :keep-last}}))))
    (is (true? (:ok (scope/admit! cold {:member "cat"
                                        :annotation (raw-reset 2)}))))
    (let [result (scope/settle! cold {:member "cat"
                                      :annotation (raw-reset 2)
                                      :turn-id 3
                                      :call-id "r2"})]
      (is (= :settled (:outcome result)))
      (is (true? (scope/budget-known? cold)))
      (is (= 2 (scope/remaining cold))))
    (let [items [{:kind :tool-call :member "cat" :call-id "old"}
                 {:kind :tool-result :member "cat" :call-id "old"}
                 {:kind :tool-call :member "resetScope" :call-id "r1"
                  :scope {:kind :reset-scope}}
                 {:kind :tool-result :member "resetScope" :call-id "r1"}
                 {:kind :tool-call :member "cat" :call-id "between"}
                 {:kind :tool-result :member "cat" :call-id "between"}
                 {:kind :tool-call :member "resetScope" :call-id "r2"
                  :scope {:kind :reset-scope}}
                 {:kind :tool-result :member "resetScope" :call-id "r2"}]
          output (scope/project-request cold items)]
      (is (not-any? #(= "old" (:call-id %)) output))
      (is (not-any? #(= "between" (:call-id %)) output))
      (is (some #(= "r2" (:call-id %)) output)))))

(deftest the-session-table-owns-one-scope-per-session
  (let [table (scope/table)
        first-scope (scope/create-scope! table
                                         {:session-id "s1"
                                          :store (scope/memory-store)})
        second (scope/create-scope! table
                                    {:session-id "s1"
                                     :store (scope/memory-store)})]
    (is (identical? first-scope (scope/find-scope table "s1")))
    (is (false? (:ok second)))
    (is (= errors/owner-taken (:code second)))
    (is (identical? first-scope (scope/require-scope table "s1")))
    (is (false? (:ok (scope/require-scope table "missing"))))
    (scope/drop-scope! table "s1")
    (is (nil? (scope/find-scope table "s1")))))

(deftest snapshot-reports-the-current-facts
  (let [{:keys [scope]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id 1 :call-id "r1"})
    (let [snapshot (scope/snapshot scope)]
      (is (= "goal" (:goal snapshot)))
      (is (= 2 (:budget snapshot)))
      (is (= 2 (:remaining snapshot)))
      (is (false? (:must-reset snapshot)))
      (is (= 1 (:resets snapshot))))))

(deftest default-exempt-members-cannot-be-removed
  (doseq [member ["negotiate" "askPreviousScope"]]
    (let [result (admission/check {:member member
                                   :annotation nil
                                   :retention {:established? true :remaining 0}
                                   :admission {:exempt #{}}})]
      (is (true? (:ok result)))
      (is (true? (:exempt result))))))

(def gen-member
  (gen/one-of [(gen/return "cat")
               (gen/return "assume")
               (gen/return "negotiate")
               (gen/return "askPreviousScope")
               (gen/return "")
               (gen/return nil)]))

(def gen-admission-annotation
  (gen/one-of [gen/any-printable
               (gen/return nil)
               (gen/return {:kind :keep-last})
               (gen/return {:kind :me-too})
               (gen/fmap (fn [budget]
                           {:kind :reset-scope
                            :new-scope-goal "goal"
                            :new-scope-retention-budget budget})
                         (gen/fmap inc (gen/choose 0 5)))]))

(def gen-retention
  (gen/one-of [(gen/return {:established? false :remaining 0})
               (gen/fmap (fn [remaining]
                           {:established? true :remaining remaining})
                         (gen/choose 0 3))]))

(defspec admission-is-total-and-exempt-members-always-pass 200
  (prop/for-all [member gen-member
                 annotation gen-admission-annotation
                 retention gen-retention]
    (let [result (admission/check {:member member
                                   :annotation annotation
                                   :retention retention})]
      (cond
        (contains? errors/default-exempt-members (errors/member-name member))
        (and (true? (:ok result)) (true? (:exempt result)))

        (nil? annotation)
        (and (false? (:ok result))
             (= errors/annotation-missing (:code result)))

        :else
        (or (and (false? (:ok result))
                 (contains? errors/admission-codes (:code result)))
            (and (true? (:ok result))
                 (false? (:exempt result))
                 (contains? errors/kinds (get-in result [:annotation :kind]))))))))

;; ------------------------------------------------ settlement without re-admission

(deftest a-same-turn-second-call-is-confirmed-after-the-budget-is-spent
  (let [{:keys [scope store]} (make-test-scope)]
    (is (= :settled
           (:outcome (scope/settle! scope {:member "cat"
                                           :annotation (raw-reset 1)
                                           :turn-id 1
                                           :call-id "r1"}))))
    (is (= :settled
           (:outcome (scope/settle! scope {:member "cat"
                                           :annotation {:kind :keep-last}
                                           :turn-id 2
                                           :call-id "k1"}))))
    (is (= 0 (scope/remaining scope)))
    (doseq [[annotation call-id] [[{:kind :me-too} "m1"]
                                  [{:kind :keep-last} "k2"]
                                  [{:kind :remove-last :replacement "lesson"} "e1"]]]
      (let [result (scope/settle! scope {:member "cat"
                                         :annotation annotation
                                         :turn-id 2
                                         :call-id call-id})]
        (is (true? (:ok result)))
        (is (= :confirmed (:outcome result)))
        (is (= 0 (scope/remaining scope)))
        (is (false? (:persisted result)))
        (is (empty? (:records result)))))
    (is (= [:reset :keep] (map :type @(:written store))))))

(deftest a-new-turn-still-refuses-a-spent-budget
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 1)
                          :turn-id 1 :call-id "r1"})
    (scope/settle! scope {:member "cat" :annotation {:kind :keep-last}
                          :turn-id 2 :call-id "k1"})
    (let [result (scope/settle! scope {:member "cat"
                                       :annotation {:kind :keep-last}
                                       :turn-id 3
                                       :call-id "k2"})]
      (is (false? (:ok result)))
      (is (= errors/budget-spent (:code result)))
      (is (= :settle (:phase result)))
      (is (false? (:persisted result)))
      (is (= [:reset :keep] (map :type @(:written store))))
      (is (= 0 (scope/remaining scope))))))

(deftest admission-refusals-carry-the-admission-phase
  (let [{:keys [scope]} (make-test-scope)
        result (scope/admit! scope {:member "cat"
                                    :annotation {:kind :keep-last}})]
    (is (= errors/uninitialized (:code result)))
    (is (= :admission (:phase result)))))

(deftest runtime-confirms-a-same-turn-second-call-after-the-budget-is-spent
  (let [reset-event {:kind :tool-call :tool-name "cat" :call-id "r1"
                     :arguments {:scope (raw-reset 1)}}
        keep-event {:kind :tool-call :tool-name "cat" :call-id "k1"
                    :arguments {:scope {:kind :keep-last}}}
        me-too-event {:kind :tool-call :tool-name "cat" :call-id "m1"
                      :arguments {:scope {:kind :me-too}}}
        {book :book reset-result :result}
        (runtime/settle-tool (scope/new-book) nil reset-event "turn-1" "r1")
        _ (is (= :settled (:outcome reset-result)))
        {book :book keep-result :result}
        (runtime/settle-tool book nil keep-event "turn-2" "k1")
        _ (is (= :settled (:outcome keep-result)))
        {book :book me-too-result :result}
        (runtime/settle-tool book nil me-too-event "turn-2" "m1")]
    (is (= 0 (scope/book-remaining book)))
    (is (= :confirmed (:outcome me-too-result)))
    (is (empty? (:records me-too-result)))))

;; ------------------------------------------------ prior versus current records

(deftest a-staged-turn-after-a-prior-default-does-not-claim-persistence
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id 1 :call-id "r1"})
    (is (= :staged
           (:outcome (scope/settle! scope {:member "cat"
                                           :annotation {:kind :me-too}
                                           :turn-id 2
                                           :call-id "m1"}))))
    (let [result (scope/settle! scope {:member "cat"
                                       :annotation {:kind :me-too}
                                       :turn-id 3
                                       :call-id "m2"})]
      (is (true? (:ok result)))
      (is (= :staged (:outcome result)))
      (is (false? (:persisted result)))
      (is (true? (:prior-persisted result)))
      (is (empty? (:records result)))
      (is (= 1 (count (:prior-records result))))
      (is (= 1 (scope/remaining scope)))
      (is (= [:reset :keep] (map :type @(:written store)))))))

(deftest a-settled-turn-after-a-prior-default-separates-records
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id 1 :call-id "r1"})
    (scope/settle! scope {:member "cat" :annotation {:kind :me-too}
                          :turn-id 2 :call-id "m1"})
    (let [result (scope/settle! scope {:member "cat"
                                       :annotation {:kind :keep-last}
                                       :turn-id 3
                                       :call-id "k1"})]
      (is (= :settled (:outcome result)))
      (is (true? (:persisted result)))
      (is (true? (:prior-persisted result)))
      (is (= 1 (count (:records result))))
      (is (= 1 (count (:prior-records result))))
      (is (= 0 (scope/remaining scope)))
      (is (= [:reset :keep :keep] (map :type @(:written store)))))))

;; ------------------------------------------------ removeLast beat selection

(deftest remove-last-erases-the-previous-round-not-the-current-prose
  (let [items [(tool-call "cat" "a")
               (tool-result "cat" "a")
               (message :assistant "current preamble")
               (tool-call "cat" "e1")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= [(assoc (message :assistant "lesson\nSpin count: 1")
                   :erased-call-id "e1")
            (message :assistant "current preamble")
            (tool-call "cat" "e1")]
           output))))

(deftest remove-last-erases-the-previous-round-not-a-sibling-call
  (let [items [(tool-call "cat" "a")
               (tool-result "cat" "a")
               (tool-call "cat" "s1")
               (tool-call "cat" "e1")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= [(assoc (message :assistant "lesson\nSpin count: 1")
                   :erased-call-id "e1")
            (tool-call "cat" "s1")
            (tool-call "cat" "e1")]
           output))))

(deftest remove-last-includes-the-previous-rounds-thinking
  (let [prev-prose (assoc (message :assistant "prev prose") :source-index 0)
        prev-thought {:kind :thinking :text "prev thought" :source-index 0}
        prev-call (assoc (tool-call "cat" "a") :source-index 0)
        prev-result (assoc (tool-result "cat" "a") :source-index 1)
        current-prose (assoc (message :assistant "current preamble") :source-index 2)
        current-thought {:kind :thinking :text "current thought" :source-index 2}
        eraser (assoc (tool-call "cat" "e1") :source-index 2)
        items [prev-prose prev-thought prev-call prev-result
               current-prose current-thought eraser]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= [prev-prose
            (assoc (message :assistant "lesson\nSpin count: 1")
                   :erased-call-id "e1")
            current-prose
            current-thought
            eraser]
           output))))

(deftest remove-last-erases-all-results-of-the-previous-round
  (let [items [(tool-call "cat" "a")
               (tool-result "cat" "a")
               (tool-result "cat" "a")
               (tool-call "cat" "e1")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= [(assoc (message :assistant "lesson\nSpin count: 1")
                   :erased-call-id "e1")
            (tool-call "cat" "e1")]
           output))))

(deftest remove-last-skips-a-pinned-previous-round
  (let [items [(tool-call "assume" "a1")
               (tool-result "assume" "a1")
               (tool-call "cat" "e1")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= items output))))

(deftest remove-last-skips-a-reset-previous-round
  (let [items [(tool-call "resetScope" "r1" reset-scope-annotation)
               (tool-result "resetScope" "r1")
               (tool-call "cat" "e1")]
        output (projection/project items
                                   {:settled? true
                                    :reset-call-id nil
                                    :erase [{:call-id "e1"
                                             :replacement "lesson"
                                             :spin 1}]})]
    (is (= items output))))

;; ------------------------------------------------ provider originals and thinking

(deftest runtime-preserves-provider-originals
  (is (= [{:role :assistant
           :text "raw provider"
           :meta {:provider true}}]
         (runtime/items->messages [{:kind :provider :text "raw provider"}]))))

(deftest runtime-preserves-standalone-thinking
  (is (= [{:role :assistant :text "" :thinking "thought"}]
         (runtime/items->messages [{:kind :thinking :text "thought"}]))))

(deftest runtime-project-conversation-keeps-provider-originals
  (let [book (scope/new-book)
        conversation [{:role :user :text "hi"}
                      {:role :provider :text "raw provider"}
                      {:role :assistant :text "answer"}]
        messages (runtime/project-conversation book conversation)
        provider (some #(when (= "raw provider" (:text %)) %) messages)]
    (is (vector? messages))
    (is (some? provider))
    (is (= {:provider true} (:meta provider)))))

;; ------------------------------------------------ cold start and session table

(deftest latest-boundary-prefers-the-book-reset-over-an-older-erase
  (let [scope-state {:book {:last-reset {:call-id "r1" :turn-id "t1"}
                            :erases [{:call-id "e1"
                                      :replacement "old"
                                      :spin 1}]}
                     :records [{:v 1 :type :keep :turn-id "t2"
                                :remaining 1}]}]
    (is (= {:kind :reset-scope :call-id "r1" :turn-id "t1"}
           (runtime/latest-boundary scope-state)))))

(deftest make-scope-clears-a-supplied-book-budget
  (let [book (-> (ledger/new-book)
                 (assoc :retention {:established? true :remaining 5})
                 (assoc :turn {:id "old" :settled? false :staged? false}))
        scope (scope/make-scope {:session-id "s1"
                                 :store (scope/memory-store)
                                 :book book})]
    (is (false? (scope/budget-known? scope)))
    (is (= 0 (scope/remaining scope)))
    (is (= errors/uninitialized
           (:code (scope/admit! scope {:member "cat"
                                       :annotation {:kind :keep-last}}))))
    (is (= :closed (:outcome (scope/close-turn! scope))))))

(deftest require-scope-reports-a-missing-session
  (let [table (scope/table)
        result (scope/require-scope table "missing")]
    (is (false? (:ok result)))
    (is (= errors/not-found (:code result)))
    (is (= "missing" (:session-id (:details result))))))

(deftest preceding-scope-returns-the-immediate-predecessor
  (let [{:keys [scope]} (make-test-scope)]
    (is (nil? (scope/preceding-scope scope)))
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id 1 :call-id "r1"})
    (is (nil? (scope/preceding-scope scope)))
    (scope/settle! scope {:member "cat" :annotation (raw-reset 3)
                          :turn-id 2 :call-id "r2"})
    (is (= "r1" (:call-id (scope/preceding-scope scope))))
    (scope/settle! scope {:member "cat" :annotation (raw-reset 4)
                          :turn-id 3 :call-id "r3"})
    (is (= "r2" (:call-id (scope/preceding-scope scope))))
    (is (= 3 (count (scope/previous-scopes scope))))))

(deftest close-turn-with-a-stale-id-in-the-stateful-scope
  (let [{:keys [scope store]} (make-test-scope)]
    (scope/settle! scope {:member "cat" :annotation (raw-reset 2)
                          :turn-id "turn-1" :call-id "r1"})
    (scope/settle! scope {:member "cat" :annotation {:kind :me-too}
                          :turn-id "turn-2" :call-id "m1"})
    (let [result (scope/close-turn! scope "turn-1")]
      (is (= :closed (:outcome result)))
      (is (= :turn-mismatch (:ignored result)))
      (is (= 2 (scope/remaining scope)))
      (is (false? (:persisted result))))
    (let [result (scope/close-turn! scope "turn-2")]
      (is (= :settled (:outcome result)))
      (is (true? (:defaulted? result)))
      (is (= 1 (scope/remaining scope)))
      (is (= [:reset :keep] (map :type @(:written store)))))))

(deftest read-cold-state-requires-remaining
  (doseq [raw [{:v 1 :type :reset :goal "g" :budget 2}
               {:v 1 :type :reset :goal "g" :budget 2 :remaining -1}
               {:v 1 :type :keep :turn-id 1}
               {:v 1 :type :keep :turn-id 1 :remaining -1}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (records/read-cold-state [raw])))))
