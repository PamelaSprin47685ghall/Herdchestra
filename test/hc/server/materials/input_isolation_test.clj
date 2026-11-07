(ns hc.server.materials.input-isolation-test
  "Actual materials input and isolation path without the app assembly.

  These tests exercise the same `hc.server.materials.session/context-for-turn!`
  call that the model-request hook uses, with real session maps.  They cover
  memory scope, temporary-session isolation, assistant-bound worldbook
  isolation and per-session turn counters."
  (:require [clojure.test :refer [deftest is]]
            [hc.server.materials :as materials]
            [hc.server.materials.session :as session]))

(defn- command!
  [service session payload]
  (session/handle-command! service
                           {:session session
                            :command-id "cmd-1"
                            :command-epoch 1
                            :payload payload}))

(deftest memory-and-worldbook-input-isolation
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        s1 {:session-id "s1" :assistant-id "a1"}
        s2 {:session-id "s2" :assistant-id "a2"}
        temp {:session-id "s3" :assistant-id "a1" :temporary? true}
        _ (command! service s1
                    {:kind :materials/settings/update
                     :memory-read? true :memory-suggest? true})
        _ (command! service s1
                    {:kind :materials/memory/remember
                     :id "global" :body "GLOBAL-MEMORY"
                     :category :working-style :scope :global})
        _ (command! service s1
                    {:kind :materials/memory/remember
                     :id "a1-memory" :body "A1-MEMORY"
                     :category :working-style
                     :scope {:kind :assistant :assistant-id "a1"}})
        _ (command! service s1
                    {:kind :materials/memory/remember
                     :id "a2-memory" :body "A2-MEMORY"
                     :category :working-style
                     :scope {:kind :assistant :assistant-id "a2"}})
        _ (command! service s1
                    {:kind :materials/worldbook/create :id "wb1" :name "Project"})
        _ (command! service s1
                    {:kind :materials/worldbook/bind
                     :book-id "wb1" :assistant-id "a1"})
        _ (command! service s1
                    {:kind :materials/worldbook/entry/create
                     :id "e1" :book-id "wb1" :title "Compiler"
                     :body "COMPILER-LORE"
                     :trigger {:kind :keyword :keywords ["compiler"]}})
        context (fn [session turn-id]
                  (session/context-for-turn!
                   service
                   {:session session
                    :turn-id turn-id
                    :text "compiler question"
                    :user-texts ["compiler question"]
                    :memory-budget 4000
                    :worldbook-budget 4000}))
        a1 (context s1 "s1-turn")
        a2 (context s2 "s2-turn")
        temporary (context temp "s3-turn")
        a1-text (get-in a1 [:memory :text])
        a2-text (get-in a2 [:memory :text])
        temp-text (get-in temporary [:memory :text])]
    (is (re-find #"A1-MEMORY" a1-text))
    (is (re-find #"GLOBAL-MEMORY" a1-text))
    (is (not (re-find #"A2-MEMORY" a1-text)))
    (is (= ["e1"] (map :entry-id (get-in a1 [:worldbook :selected]))))
    (is (re-find #"GLOBAL-MEMORY" a2-text))
    (is (not (re-find #"A1-MEMORY" a2-text)))
    (is (re-find #"A2-MEMORY" a2-text))
    (is (= [] (get-in a2 [:worldbook :selected])))
    (is (= "" temp-text))
    (is (= :temporary-session (get-in temporary [:memory :reason])))
    (is (= ["e1"] (map :entry-id (get-in temporary [:worldbook :selected]))))
    (is (= 1 (:turn a1)))
    (is (= 1 (:turn a2)))
    (is (= 1 (:turn temporary)))))

(deftest worldbook-turn-counters-are-per-session
  (let [store (materials/memory-store)
        service (session/service store)
        _ (is (true? (:ok? (session/initialize! service))))
        s1 {:session-id "s1" :assistant-id "a1"}
        s2 {:session-id "s2" :assistant-id "a1"}
        _ (command! service s1
                    {:kind :materials/worldbook/create :id "wb1" :name "Project"})
        _ (command! service s1
                    {:kind :materials/worldbook/bind
                     :book-id "wb1" :assistant-id "a1"})
        _ (command! service s1
                    {:kind :materials/worldbook/entry/create
                     :id "e1" :book-id "wb1" :title "Compiler"
                     :body "COMPILER-LORE"
                     :trigger {:kind :keyword :keywords ["compiler"]}
                     :sticky 1})
        context (fn [session turn-id text]
                  (session/context-for-turn!
                   service
                   {:session session
                    :turn-id turn-id
                    :text text
                    :user-texts [text]
                    :memory-budget 1000
                    :worldbook-budget 4000}))
        first-s1 (context s1 "s1-t1" "compiler")
        first-s2 (context s2 "s2-t1" "nothing")
        second-s1 (context s1 "s1-t2" "nothing")]
    (is (= ["e1"] (map :entry-id (get-in first-s1 [:worldbook :selected]))))
    (is (= [] (get-in first-s2 [:worldbook :selected])))
    (is (= ["e1"] (map :entry-id (get-in second-s1 [:worldbook :selected]))))
    (is (= 2 (:turn second-s1)))))
