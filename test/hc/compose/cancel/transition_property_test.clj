(ns hc.compose.cancel.transition-property-test
  "Property tests for operator cancellation: a fixed :applying action is not
  rewritten, descendants carry :parent-terminated, and the root keeps the
  operator reason."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.cancel :as cancel]))

(defspec applying-actions-are-preserved-during-operator-cancel 50
  (prop/for-all [n (gen/choose 1 4)]
    (let [nodes (mapv (fn [i]
                        {:node-id (str "a" i)
                         :kind :action
                         :parent-id "r"
                         :state :applying
                         :side-effecting? true
                         :action-kind :edit})
                      (range n))
          state (-> (cancel/new-state {:session-id "s" :run-id "r"})
                    (cancel/add-nodes nodes))
          result (cancel/accept-operator-cancel
                  state {:session-id "s" :run-id "r"
                         :command-id "c" :origin :operator-esc :at 1})
          state' (:state result)]
      (and (:ok? result)
           (= :user-cancel (get-in state' [:nodes "r" :cancel-reason]))
           (every? (fn [i]
                     (let [node (get-in state' [:nodes (str "a" i)])]
                       (and (= :applying (:state node))
                            (= :parent-terminated (:cancel-reason node))
                            (true? (:cancel-after-apply? node)))))
                   (range n))))))
