(ns hc.client.workbench.materials-bridge-test
  "The materials bridge projects a session switch through the public
  `hc.client.materials` facade.  A missing facade var is a release-build
  failure, so the facade arity the bridge calls is pinned here.

  The test stops at the pure model because the facade itself requires the
  React view (`hc.client.materials.view`), and the JVM test classpath has no
  React runtime.  This file fails loudly if that boundary moves: the facade
  load error names the missing var or namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.materials.model :as model]))

(deftest begin-session-drops-server-derived-tables
  (testing "a session switch clears confirmed tables but keeps local commands"
    (let [snapshot {:materials/revision 7
                    :materials/ready? true
                    :materials/session {:session-id "s-1"}
                    :memory/by-id {"m-1" {:memory-id "m-1" :body "remember me"}}
                    :memory/order ["m-1"]
                    :memory/counts {:candidate 0 :active 1 :archived 0
                                    :rejected 0 :deleted 0}
                    :worldbook/by-id {"b-1" {:book-id "b-1" :name "world"}}
                    :instruction-card/by-id {"c-1" {:card-id "c-1"
                                                    :name "card"}}
                    :instruction-session {:card-ids ["c-1"]}}
          applied (model/apply-snapshot (model/initial-state) snapshot)
          state (assoc applied
                       :commands {"cmd-1" {:command-id "cmd-1"}}
                       :turn-card-ids ["c-1"]
                       :instruction-values {"count" "3"})
          switched (model/begin-session state "s-2")]
      (is (= 7 (:revision state)))
      (is (= {"m-1" {:memory-id "m-1" :body "remember me"}} (:memories state)))
      (is (= 1 (get-in state [:memory-counts :active])))
      (is (nil? (:revision switched)))
      (is (= {} (:memories switched)))
      (is (empty? (:memory-order switched)))
      (is (= 0 (get-in switched [:memory-counts :active])))
      (is (= {} (:books switched)))
      (is (= {} (:cards switched)))
      ;; Per-turn staging is local "next submission" state: a session switch
      ;; must not discard cards picked on the materials page.  The next
      ;; snapshot still filters them against the new session's card table.
      (is (= ["c-1"] (:turn-card-ids switched)))
      (is (= {"count" "3"} (:instruction-values switched)))
      (is (= {"cmd-1" {:command-id "cmd-1"}} (:commands switched)))
      (is (< (long (:turn-selection-version state))
             (long (:turn-selection-version switched))))
      (is (= "s-2" (:selected-session-id switched)))
      (is (= {:session-id "s-2"} (:session switched)))
      (is (false? (:ready? switched))))))

(deftest merge-local-state-keeps-receipt-delta-on-stale-write
  (testing "a receipt state older than the committed revision still lands"
    (let [current (-> (model/apply-snapshot (model/initial-state)
                                            {:materials/revision 8
                                             :materials/ready? true
                                             :materials/session
                                             {:session-id "s-1"}
                                             :worldbook/by-id
                                             {"wb-1" {:book-id "wb-1"}}})
                     (assoc :commands {"move-1" {:command-id "move-1"
                                                 :status :submitting}}
                            :turn-card-ids ["c-1"]
                            :turn-selection-version 4))
          stale-next (-> current
                          (assoc :revision 7
                                 :last-move {:entry-ids ["wbe-1"]
                                             :book-ids {"wbe-1" "wb-1"}}
                                 :commands {"move-1" {:command-id "move-1"
                                                      :status :accepted}})
                          (assoc-in [:commands "snap-9" :status] :queued))
          merged (model/merge-local-state current stale-next)]
      ;; Server-derived fields stay with CURRENT's newer revision.
      (is (= 8 (:revision merged)))
      (is (= {"wb-1" {:book-id "wb-1"}} (:books merged)))
      ;; The receipt's local delta survives the stale guard.
      (is (= {:entry-ids ["wbe-1"] :book-ids {"wbe-1" "wb-1"}}
             (:last-move merged)))
      (is (= :accepted (get-in merged [:commands "move-1" :status])))
      (is (= :queued (get-in merged [:commands "snap-9" :status])))
      (is (= ["c-1"] (:turn-card-ids merged)))
      (is (= 4 (:turn-selection-version merged)))
      (testing "an undo receipt that clears last-move is not resurrected"
        (let [cleared (model/merge-local-state
                       merged
                       (assoc merged :last-move nil))]
          (is (contains? cleared :last-move))
          (is (nil? (:last-move cleared))))))))
