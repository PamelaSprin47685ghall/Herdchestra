(ns hc.server.materials.instruction-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]))

(defn- create!
  [state id & [extra]]
  (:state (materials/create-instruction-card
           state
           (merge {:id id
                   :name id
                   :description "test card"
                   :body "Body of {{topic}}."}
                  extra))))

(deftest card-crud-and-selection
  (let [state (create! (materials/new-state) "card-1"
                       {:body "Explain {{topic}} for {{audience}}."
                        :placeholders [{:key "topic" :required? true}
                                       {:key "audience" :required? true}]})
        selected (materials/select-instruction-cards
                  state
                  {:card-ids ["card-1"]
                   :values {:topic "closures" :audience "beginners"}})
        missing (materials/select-instruction-cards
                 state
                 {:card-ids ["card-1"]
                  :values {:topic "closures"}})]
    (is (true? (:ok? selected)))
    (is (= "Explain closures for beginners." (:text selected)))
    (is (false? (:ok? missing)))
    (is (= :INSTRUCTION_PLACEHOLDER_MISSING (get-in missing [:error :code])))
    (is (empty? (materials/check-invariants state)))))

(deftest output-format-conflict-is-refused
  (let [state (-> (materials/new-state)
                  (create! "table" {:output-format :markdown-table
                                    :body "Use a table."})
                  (create! "json" {:output-format :json
                                   :body "Use JSON."}))
        result (materials/select-instruction-cards
                state
                {:card-ids ["table" "json"]})]
    (is (false? (:ok? result)))
    (is (= :INSTRUCTION_FORMAT_CONFLICT (get-in result [:error :code])))
    (is (= [:markdown-table :json] (get-in result [:error :details :formats])))))

(deftest session-selection-is-explicit-and-removed-with-card
  (let [state (-> (materials/new-state)
                  (create! "card-1" {})
                  (create! "card-2" {}))
        selected (:state (materials/set-instruction-session-cards
                          state {:session-id "s1" :card-ids ["card-2" "card-1"]}))
        cleared (:state (materials/clear-instruction-session-cards selected "s1"))
        reselected (:state (materials/set-instruction-session-cards
                            selected {:session-id "s1" :card-ids ["card-2"]}))
        staged (:state (materials/stage-instruction-turn-cards
                        reselected {:session-id "s1" :card-ids ["card-2"]}))
        deleted (:state (materials/delete-instruction-card staged "card-2"))]
    (is (= ["card-2" "card-1"]
           (materials/instruction-session-card-ids selected "s1")))
    (is (= [] (materials/instruction-session-card-ids cleared "s1")))
    (is (= [] (materials/instruction-session-card-ids deleted "s1")))
    (is (nil? (materials/instruction-turn-card-selection deleted "s1")))
    (is (nil? (get-in deleted [:instruction-cards "card-2"])))
    (is (empty? (materials/check-invariants deleted)))))

(deftest disabling-a-card-removes-it-from-selections
  (let [state (-> (materials/new-state)
                  (create! "card-1" {})
                  (create! "card-2" {}))
        selected (:state (materials/set-instruction-session-cards
                          state {:session-id "s1"
                                 :card-ids ["card-1" "card-2"]}))
        staged (:state (materials/stage-instruction-turn-cards
                        selected {:session-id "s1" :card-ids ["card-1"]}))
        disabled (:state (materials/update-instruction-card
                          staged "card-1" {:enabled? false}))]
    (is (= ["card-2"]
           (materials/instruction-session-card-ids disabled "s1")))
    (is (nil? (materials/instruction-turn-card-selection disabled "s1")))
    (is (empty? (materials/check-invariants disabled)))))

(deftest optional-placeholder-is-reported-unresolved
  (let [state (create! (materials/new-state) "card-1"
                       {:body "Explain {{topic}} for {{audience}}."
                        :placeholders [{:key "topic" :required? true}
                                       {:key "audience"}]})
        selected (materials/select-instruction-cards
                  state
                  {:card-ids ["card-1"]
                   :values {:topic "closures"}})]
    (is (true? (:ok? selected)))
    (is (= "Explain closures for {{audience}}." (:text selected)))
    (is (= [{:card-id "card-1" :key "audience"}]
           (:unresolved selected)))))

(deftest invalid-card-fields-are-refused
  (let [state (materials/new-state)
        blank (materials/create-instruction-card
               state {:id "c" :name " " :body "x"})
        dup-placeholders (materials/create-instruction-card
                          state
                          {:id "c2"
                           :name "C2"
                           :body "x {{a}}"
                           :placeholders [{:key "a"} {:key "a"}]})
        bad-format (materials/create-instruction-card
                    state
                    {:id "c3" :name "C3" :body "x" :output-format "json"})]
    (is (false? (:ok? blank)))
    (is (false? (:ok? dup-placeholders)))
    (is (false? (:ok? bad-format)))
    (is (= :INSTRUCTION_FORMAT_INVALID (get-in bad-format [:error :code])))))
