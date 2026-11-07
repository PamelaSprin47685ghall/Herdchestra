(ns hc.server.materials.durable-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]
            [hc.server.materials.durable-store :as durable]
            [hc.server.materials.session :as session]
            [hc.server.storage :as storage])
  (:import (java.nio.file Files FileVisitOption Path)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  []
  (Files/createTempDirectory "hc-materials-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [stream (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path path (sort-by #(.getNameCount ^Path %)
                                  >
                                  (iterator-seq (.iterator stream)))]
        (Files/deleteIfExists path)))))

(defn- open-store!
  [path create?]
  (storage/open-store {:path (str path)
                       :create? create?
                       :durability :sync
                       :mapsize-mib 64}))

(deftest durable-state-survives-reopen-and-cas
  (let [dir (temp-dir)
        path (.resolve dir "store")]
    (try
      (let [storage-handle (open-store! path true)
            dstore (durable/durable-store storage-handle)
            initialized (materials/initialize! dstore)
            settings (materials/transact!
                      dstore
                      #(materials/update-settings
                        % {:memory-read? true :memory-suggest? true}))
            remembered (materials/transact!
                        dstore
                        #(materials/remember
                          %
                          {:id "m1"
                           :body "User prefers verifiable steps."
                           :category :working-style
                           :scope :global
                           :epistemic :preference}))
            book (materials/transact!
                  dstore
                  #(materials/create-worldbook % {:id "wb1" :name "Project"}))
            _ (materials/transact!
               dstore
               #(materials/bind-worldbook % "wb1" "a1"))
            entry (materials/transact!
                   dstore
                   #(materials/create-worldbook-entry
                     %
                     {:id "e1"
                      :book-id "wb1"
                      :title "Compiler"
                      :body "The project builds a compiler."
                      :trigger {:kind :keyword :keywords ["compiler"]}}))
            card (materials/transact!
                  dstore
                  #(materials/create-instruction-card
                    %
                    {:id "card1" :name "Explain" :body "Explain {{topic}}."}))
            turn (materials/transact!
                  dstore
                  #(materials/run-worldbook-turn
                    %
                    {:session-id "s1"
                     :turn-id "turn-1"
                     :assistant-id "a1"
                     :user-texts ["The compiler is slow."]
                     :budget 4000}))
            staged (materials/transact!
                    dstore
                    #(materials/stage-instruction-turn-cards
                      % {:session-id "s1" :card-ids ["card1"]}))
            loaded (materials/load-state dstore)]
        (is (true? (:ok? initialized)))
        (is (true? (:ok? settings)))
        (is (true? (:ok? remembered)))
        (is (true? (:ok? book)))
        (is (true? (:ok? entry)))
        (is (true? (:ok? card)))
        (is (true? (:ok? turn)))
        (is (true? (:ok? staged)))
        (is (= ["card1"]
               (get-in loaded [:state :instruction-turns "s1" :card-ids])))
        (is (= :active (get-in loaded [:state :memories "m1" :status])))
        (is (= "e1" (get-in loaded [:state :worldbook :entries "e1" :id])))
        (is (= "card1" (get-in loaded [:state :instruction-cards "card1" :id])))
        (is (= 1 (get-in loaded [:state :worldbook :sessions "s1" :turn])))
        (is (empty? (materials/check-invariants (:state loaded))))
        (storage/close-store! storage-handle)
        (let [reopened (open-store! path false)
              dstore2 (durable/durable-store reopened)
              loaded2 (materials/load-state dstore2)]
          (is (true? (:ok? loaded2)))
          (is (= 1 (get-in loaded2 [:state :worldbook :sessions "s1" :turn])))
          (is (= :active (get-in loaded2 [:state :memories "m1" :status])))
          (is (= ["e1"] (keys (get-in loaded2 [:state :worldbook :entries]))))
          (is (= ["card1"] (keys (get-in loaded2 [:state :instruction-cards]))))
          (is (= ["card1"]
                 (get-in loaded2 [:state :instruction-turns "s1" :card-ids])))
          (testing "a stale domain revision is refused before the write"
            (let [stale (materials/commit! dstore2 0
                                           (assoc (:state loaded2) :revision 1))]
              (is (false? (:ok? stale)))
              (is (= :revision-conflict (:reason stale)))
              (is (= (:revision (:state loaded2)) (:current-revision stale)))))
          (storage/close-store! reopened)))
      (finally
        (delete-tree! dir)))))

(deftest durable-session-service-round-trip
  (let [dir (temp-dir)
        path (.resolve dir "store")]
    (try
      (let [storage-handle (open-store! path true)
            service (session/service (durable/durable-store storage-handle))
            session-record {:session-id "s1" :assistant-id "a1"}
            _ (is (true? (:ok? (session/initialize! service))))
            _ (is (true? (:ok? (session/handle-command!
                                service
                                {:session session-record
                                 :payload {:kind :materials/settings/update
                                           :memory-read? true
                                           :memory-suggest? true}}))))
            _ (is (true? (:ok? (session/handle-command!
                                service
                                {:session session-record
                                 :payload {:kind :materials/memory/remember
                                           :id "m1"
                                           :body "User prefers verifiable steps."
                                           :category :working-style
                                           :scope :global
                                           :epistemic :preference}}))))
            _ (is (true? (:ok? (session/handle-command!
                                service
                                {:session session-record
                                 :payload {:kind :materials/worldbook/create
                                           :id "wb1" :name "Project"}}))))
            _ (is (true? (:ok? (session/handle-command!
                                service
                                {:session session-record
                                 :payload {:kind :materials/worldbook/bind
                                           :book-id "wb1"
                                           :assistant-id "a1"}}))))
            _ (is (true? (:ok? (session/handle-command!
                                service
                                {:session session-record
                                 :payload {:kind :materials/worldbook/entry/create
                                           :id "e1"
                                           :book-id "wb1"
                                           :title "Compiler"
                                           :body "The project builds a compiler."
                                           :trigger {:kind :keyword
                                                     :keywords ["compiler"]}}}))))
            turn (session/context-for-turn!
                  service
                  {:session session-record
                   :turn-id "turn-1"
                   :text "The compiler is slow."
                   :user-texts ["The compiler is slow."]
                   :memory-budget 2000
                   :worldbook-budget 4000})]
        (is (true? (:ok? turn)))
        (is (= ["e1"] (map :entry-id (get-in turn [:worldbook :selected]))))
        (is (re-find #"verifiable steps" (get-in turn [:memory :text])))
        (storage/close-store! storage-handle)
        (let [reopened (open-store! path false)
              service2 (session/service (durable/durable-store reopened))
              snap (session/snapshot service2 session-record)]
          (is (true? (:materials/ready? snap)))
          (is (= :active (get-in snap [:memory/by-id "m1" :status])))
          (is (= 1 (get-in snap [:worldbook-session :turn])))
          (is (= ["e1"] (keys (:worldbook-entry/by-id snap))))
          (storage/close-store! reopened)))
      (finally
        (delete-tree! dir)))))
