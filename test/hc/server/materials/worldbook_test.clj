(ns hc.server.materials.worldbook-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]))

(defn- book-state
  []
  (let [created (:state (materials/create-worldbook
                         (materials/new-state)
                         {:id "wb1" :name "Project lore" :description "Terms"}))]
    (:state (materials/bind-worldbook created "wb1" "a1"))))

(defn- add-entry!
  [state id request]
  (:state (materials/create-worldbook-entry
           state
           (merge {:id id
                   :book-id "wb1"
                   :title id
                   :body (str "Body of " id)
                   :trigger {:kind :keyword :keywords [id]}}
                  request))))

(defn- run-turn!
  [state turn-id text & [extra]]
  (materials/run-worldbook-turn
   state
   (merge {:turn-id turn-id
           :session-id "s1"
           :assistant-id "a1"
           :user-texts [text]
           :budget 10000}
          extra)))

(deftest book-binding-and-entry-validation
  (let [state (book-state)
        unbound (materials/create-worldbook state {:id "wb2" :name "Unbound"})
        entry (materials/create-worldbook-entry
               (:state unbound)
               {:id "e1"
                :book-id "wb2"
                :title "Compiler"
                :body "The project is a compiler."
                :trigger {:kind :keyword :keywords ["compiler"]}})]
    (is (true? (:ok? unbound)))
    (is (true? (:ok? entry)))
    (is (= [] (:selected (run-turn! (:state entry) "t1" "compiler"))))
    (testing "empty keyword lists cannot become match-all triggers"
      (let [bad (materials/create-worldbook-entry
                 (:state entry)
                 {:id "e2"
                  :book-id "wb2"
                  :title "Bad"
                  :body "Bad."
                  :trigger {:kind :keyword :keywords []}})]
        (is (false? (:ok? bad)))
        (is (= :WORLDBOOK_TRIGGER_INVALID (get-in bad [:error :code])))))
    (testing "a bound disabled book contributes nothing"
      (let [disabled (:state (materials/update-worldbook state "wb1"
                                                         {:enabled? false}))]
        (is (= [] (:selected (run-turn! disabled "t2" "wb1"))))))))

(deftest sticky-cooldown-and-delay-use-user-turns
  (let [state (-> (book-state)
                  (add-entry! "sticky" {:body "Sticky compiler fact."
                                        :trigger {:kind :keyword :keywords ["compiler"]}
                                        :sticky 1
                                        :cooldown 2})
                  (add-entry! "late" {:body "Late compiler fact."
                                      :trigger {:kind :keyword :keywords ["late"]}
                                      :delay 2}))
        t1 (run-turn! state "t1" "compiler")
        s1 (:state t1)
        t2 (run-turn! s1 "t2" "nothing")
        s2 (:state t2)
        t3 (run-turn! s2 "t3" "compiler late")
        s3 (:state t3)
        t4 (run-turn! s3 "t4" "compiler")]
    (is (= ["sticky"] (map :entry-id (:selected t1))))
    (is (= ["sticky"] (map :entry-id (:selected t2))))
    (is (= 1 (get-in s1 [:worldbook :sessions "s1" :turn])))
    (is (= 2 (get-in s2 [:worldbook :sessions "s1" :turn])))
    (testing "turn 3: sticky expired, cooldown blocks a new independent trigger"
      (is (= ["late"] (map :entry-id (:selected t3))))
      (is (= :cooldown (get-in t3 [:omitted 0 :reason]))))
    (testing "turn 3: delay 2 first becomes eligible on the third user turn"
      (is (contains? (set (map :entry-id (:selected t3))) "late")))
    (is (= ["sticky"] (map :entry-id (:selected t4))))
    (is (empty? (materials/check-invariants (:state t4))))))

(deftest turn-id-replay-and-conflict
  (let [state (-> (book-state)
                  (add-entry! "e1" {:trigger {:kind :keyword :keywords ["compiler"]}}))
        first (run-turn! state "same" "compiler")
        replay (run-turn! (:state first) "same" "compiler")
        conflict (run-turn! (:state first) "same" "different")]
    (is (true? (:ok? first)))
    (is (false? (:replayed? first)))
    (is (true? (:ok? replay)))
    (is (true? (:replayed? replay)))
    (is (= (:selected first) (:selected replay)))
    (is (= (:state first) (:state replay)))
    (is (false? (:ok? conflict)))
    (is (= :WORLDBOOK_TURN_CONFLICT (get-in conflict [:error :code])))))

(deftest preview-does-not-advance
  (let [state (-> (book-state)
                  (add-entry! "e1" {:trigger {:kind :keyword :keywords ["compiler"]}}))
        preview (materials/preview-worldbook-turn
                 state
                 {:session-id "s1"
                  :assistant-id "a1"
                  :user-texts ["compiler"]
                  :budget 1000})]
    (is (true? (:ok? preview)))
    (is (true? (:preview? preview)))
    (is (= state (:state preview)))
    (is (= ["e1"] (map :entry-id (:selected preview))))))


(deftest counters-are-per-session
  (let [state (-> (book-state)
                  (add-entry! "sticky" {:body "Sticky compiler fact."
                                        :trigger {:kind :keyword :keywords ["compiler"]}
                                        :sticky 1}))
        s1 (:state (run-turn! state "t1" "compiler" {:session-id "s1"}))
        s2 (:state (run-turn! s1 "t1" "nothing" {:session-id "s2"}))
        s3 (run-turn! s2 "t1" "nothing" {:session-id "s3"})]
    (is (= 1 (get-in s2 [:worldbook :sessions "s1" :turn])))
    (is (= 1 (get-in s2 [:worldbook :sessions "s2" :turn])))
    (is (= 2 (get-in s2 [:worldbook :sessions "s1" :runtime
                         "sticky" :sticky-until])))
    (is (empty? (get-in s2 [:worldbook :sessions "s2" :runtime])))
    (testing "the same turn-id is valid in another session"
      (is (true? (:ok? s3)))
      (is (false? (:replayed? s3)))
      (is (= 1 (get-in s3 [:state :worldbook :sessions "s3" :turn])))
      (is (= [] (:selected s3))))))

(deftest budget-reports-hit-but-not-provided
  (let [state (-> (book-state)
                  (add-entry! "key" {:body "0123456789"
                                     :key? true
                                     :trigger {:kind :constant}})
                  (add-entry! "other" {:body "abcdefghij"
                                       :trigger {:kind :constant}}))
        result (run-turn! state "t1" "" {:budget 10})]
    (is (= ["key"] (map :entry-id (:selected result))))
    (is (= [{:entry-id "other"
             :book-id "wb1"
             :title "other"
             :reason :budget
             :chars 10}]
           (:omitted result)))
    (is (= 10 (:used-chars result)))))

(deftest expression-trigger-and-disabled-draft
  (let [state (book-state)
        good (materials/create-worldbook-entry
              state
              {:id "expr"
               :book-id "wb1"
               :title "Rust"
               :body "Rust is used."
               :trigger {:kind :expression
                         :expression [:contains "rust"]}})
        draft (materials/create-worldbook-entry
               state
               {:id "draft"
                :book-id "wb1"
                :title "Draft"
                :body "Draft body."
                :enabled? false
                :trigger {:kind :expression
                          :expression [:regex "unsafe"]}})]
    (is (true? (:ok? good)))
    (is (true? (:ok? draft)))
    (is (false? (get-in draft [:entry :trigger :valid?])))
    (is (= ["expr"] (map :entry-id
                         (:selected (run-turn! (:state good) "t1" "I use Rust")))))
    (testing "an invalid expression cannot be enabled"
      (let [enable (materials/update-worldbook-entry
                    (:state draft) "draft" {:enabled? true})]
        (is (false? (:ok? enable)))
        (is (= :WORLDBOOK_EXPRESSION_INVALID (get-in enable [:error :code])))))
    (testing "unknown expression operators are refused"
      (is (= :WORLDBOOK_EXPRESSION_INVALID
             (get-in (materials/validate-worldbook-expression
                      [:regex "x"])
                     [:error :code]))))))

(deftest move-is-atomic-and-delete-book-is-guarded
  (let [base (-> (book-state)
                 (add-entry! "e1" {}))
        state (:state (materials/create-worldbook base
                                                  {:id "wb2" :name "Other"}))
        bound-delete (materials/delete-worldbook state "wb1" {})
        missing-target (materials/move-worldbook-entries
                        state {:entry-ids ["e1"] :book-id "missing"})
        moved (materials/move-worldbook-entries
               state {:entry-ids ["e1"] :book-id "wb2"})
        forced (materials/delete-worldbook (:state moved) "wb1" {:force? true})]
    (is (false? (:ok? bound-delete)))
    (is (= :WORLDBOOK_BOUND (get-in bound-delete [:error :code])))
    (is (false? (:ok? missing-target)))
    (is (= "wb1" (get-in missing-target [:state :worldbook :entries "e1" :book-id])))
    (is (true? (:ok? moved)))
    (is (= "wb2" (get-in moved [:state :worldbook :entries "e1" :book-id])))
    (is (true? (:ok? forced)))
    (is (nil? (get-in forced [:state :worldbook :books "wb1"])))))
