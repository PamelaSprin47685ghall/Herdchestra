(ns hc.server.materials-smoke-test
  "One end-to-end pass over the materials domain.

  This is a smoke test, not a substitute for the focused tests. It exercises
  the public facade the way a server Owner would: load, transition, commit,
  worldbook turn, message-scoped search references, then reload."
  (:require [clojure.test :refer [deftest is]]
            [hc.server.materials :as materials]))

(deftest materials-domain-smoke
  (let [store (materials/memory-store)
        initialized (materials/initialize! store)
        settings (materials/transact!
                  store
                  #(materials/update-settings
                    % {:memory-read? true :memory-suggest? true}))
        manual (materials/transact!
                store
                #(materials/remember
                  %
                  {:id "m-global"
                   :body "User prefers verifiable steps."
                   :category :working-style
                   :scope :global
                   :epistemic :preference}))
        candidate (materials/transact!
                   store
                   #(materials/propose-memory
                     %
                     {:id "m-candidate"
                      :body "User works on compiler tooling."
                      :category :identity-background
                      :scope {:kind :assistant :assistant-id "assistant-1"}
                      :epistemic :fact
                      :source {:session-id "session-1"
                               :message-ids ["message-1"]
                               :excerpt "I work on compiler tooling."}}))
        approved (materials/transact!
                  store
                  #(materials/approve-memory % "m-candidate" {}))
        book (materials/transact!
              store
              #(materials/create-worldbook % {:id "wb" :name "Project"}))
        bound (materials/transact!
               store
               #(materials/bind-worldbook % "wb" "assistant-1"))
        entry (materials/transact!
               store
               #(materials/create-worldbook-entry
                 %
                 {:id "entry"
                  :book-id "wb"
                  :title "Compiler context"
                  :body "This project builds a compiler."
                  :trigger {:kind :keyword :keywords ["compiler"]}
                  :sticky 1
                  :cooldown 1}))
        turn (materials/transact!
              store
              #(materials/run-worldbook-turn
                %
                {:turn-id "turn-1"
                 :session-id "session-1"
                 :assistant-id "assistant-1"
                 :user-texts ["The compiler is slow."]
                 :budget 4000}))
        loaded (materials/load-state store)
        effective (materials/effective-memories
                   (:state loaded)
                   {:assistant-id "assistant-1"})
        selected (materials/select-memories (:memories effective) {:budget 4000})
        refs (:refs (materials/add-search-results
                     (materials/new-reference-set)
                     {:query-id "q1"
                      :fetched-at "2026-11-03T10:00:00Z"
                      :results [{:url "https://example.com/compiler"
                                 :title "Compiler notes"
                                 :published-at "2026-10-01"}]}))
        cited (materials/cite-search-source refs "https://example.com/compiler")]
    (is (true? (:ok? initialized)))
    (is (true? (:ok? settings)))
    (is (true? (:ok? manual)))
    (is (true? (:ok? candidate)))
    (is (true? (:ok? approved)))
    (is (true? (:ok? book)))
    (is (true? (:ok? bound)))
    (is (true? (:ok? entry)))
    (is (true? (:ok? turn)))
    (is (= ["m-candidate" "m-global"] (map :id (:memories effective))))
    (is (= ["m-candidate" "m-global"] (map :id (:provided selected))))
    (is (= ["entry"] (map :entry-id (:selected turn))))
    (is (true? (:ok? loaded)))
    (is (= 1 (get-in loaded [:state :worldbook :sessions
                             "session-1" :turn])))
    (is (= :active (get-in loaded [:state :memories "m-candidate" :status])))
    (is (true? (:ok? cited)))
    (is (= 1 (:number cited)))
    (is (= "[1]" (materials/search-citation-marker (:refs cited)
                                                   "https://example.com/compiler")))
    (is (empty? (materials/check-invariants (:state loaded))))
    (is (empty? (materials/check-search-references (:refs cited))))))
