(ns hc.core.relay.tenure-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.relay.tenure :as tenure]))

(defn- active-execution
  []
  (tenure/create-tenure
   {:tenure-id "fleet-swarm-1"
    :epoch 2
    :lane :execution
    :milestone :execution
    :initial? false}))

(deftest abandoned-is-a-terminal-status
  (let [active (active-execution)
        abandoned (tenure/abandon-tenure active "master moved" 4242)
        from-map (tenure/abandon-tenure
                  active {:code :REBASE_CONFLICT :at 99})]
    (is (contains? tenure/statuses :abandoned))
    (testing "an active tenure is neither completed nor terminal"
      (is (tenure/active? active))
      (is (not (tenure/completed? active)))
      (is (not (tenure/abandoned? active)))
      (is (not (tenure/terminal? active))))
    (testing "abandon sets the terminal record"
      (is (= :abandoned (:status abandoned)))
      (is (= "master moved" (:abandoned-reason abandoned)))
      (is (= 4242 (:retired-at abandoned)))
      (is (false? (tenure/active? abandoned)))
      (is (false? (tenure/completed? abandoned)))
      (is (tenure/abandoned? abandoned))
      (is (tenure/terminal? abandoned))
      (is (= :closed (:review-window abandoned)))
      (is (= :none (:swarm-phase abandoned)))
      (is (empty? (:ledger abandoned)))
      (is (empty? (:root-tasks abandoned))))
    (testing "a map reason supplies the retirement time"
      (is (= 99 (:retired-at from-map))))
    (testing "a completed tenure stays completed, not abandoned"
      (let [closing (:tenure (tenure/promote-tenure active))
            completed (:tenure (tenure/promote-tenure closing))]
        (is (= :completed (:status completed)))
        (is (tenure/completed? completed))
        (is (not (tenure/abandoned? completed)))
        (is (tenure/terminal? completed))))))
