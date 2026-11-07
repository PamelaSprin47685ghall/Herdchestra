(ns hc.server.app.outbound-context-test
  "The 8-section outbound context provider used by the Owner snapshot."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.system :as system]
            [hc.server.history.context :as context]
            [hc.server.materials.session :as materials-session]))

(deftest outbound-opts-produce-every-context-section
  (with-redefs [materials-session/effective-memories-read
                (fn [_ _]
                  {:ok? true
                   :memories [{:id "m1" :summary "note" :scope :global
                               :version 2}]
                   :archived-excluded 3})
                materials-session/worldbook-selection-read
                (fn [_ _]
                  {:enabled-books ["b1"]
                   :provided [{:book "b1" :entry "e1" :reason :keyword}]
                   :hit-not-provided [{:entry "e2" :reason :budget}]})
                materials-session/instruction-cards-read
                (fn [_ _] {:ok? true
                           :cards [{:name "card" :scope :turn :version 1}]})]
    (let [opts (#'system/outbound-context-opts
                {:session {:session-id "s1" :assistant-id "a1"}
                 :materials ::materials
                 :session-config
                 (fn []
                   {:assistants {"a1" {:name "Helper" :revision 4}}})
                 :skills [{:id "sk1" :name "Skill" :version "1"
                           :files {"SKILL.md" {:text "large body"}}}]})
          snap (context/context-snapshot opts)]
      (doseq [k [:system :assistant :instructions :memory :worldbook
                 :instruction-cards :tools :skills :history :attachments
                 :estimates]]
        (is (contains? snap k) (str "missing " k)))
      (testing "system prompt is real bytes, not zero"
        (is (pos? (get-in snap [:system :prompt-bytes])))
        (is (= ["program-role"] (get-in snap [:system :modules]))))
      (testing "skills are compact names, not file maps"
        (is (= [{:id "sk1" :name "Skill" :version "1"}]
               (get-in snap [:skills])))
        (is (not (contains? (first (get-in snap [:skills])) :files))))
      (testing "assistant entity"
        (is (= "Helper" (get-in snap [:assistant :name])))
        (is (= 4 (get-in snap [:assistant :revision]))))
      (testing "memory items and archived count"
        (is (= 3 (get-in snap [:memory :archived-excluded])))
        (is (= "m1" (get-in snap [:memory :items 0 :id])))
        (is (= "note" (get-in snap [:memory :items 0 :summary]))))
      (testing "worldbook provided and not-provided are separate"
        (is (= ["b1"] (get-in snap [:worldbook :enabled-books])))
        (is (= "e1" (get-in snap [:worldbook :provided 0 :entry])))
        (is (= "e2" (get-in snap [:worldbook :hit-not-provided 0 :entry]))))
      (testing "instruction cards use the real read shape"
        (is (= "card" (get-in snap [:instruction-cards 0 :name])))
        (is (true? (get-in snap [:instructions :session-override?]))))
      (testing "tool read/write nature"
        (let [tool (first (get-in snap [:tools]))]
          (is (= "devops" (:name tool)))
          (is (= :read (:read-write tool)))
          (is (true? (:enabled? tool)))))
      (testing "unknown estimate is never zero"
        (is (= :unknown (get-in snap [:estimates :tokens])))
        (is (= :unknown (get-in snap [:estimates :kind])))))))
