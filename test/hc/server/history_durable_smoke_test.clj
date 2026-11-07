(ns hc.server.history-durable-smoke-test
  "Real-store smoke for versioned history.

  The test writes a session through the same `SessionPersistence` adapter the
  application uses, closes Datalevin, reopens the directory, and verifies the
  versions, attachment identities, context boundary, and applied compression
  after the round trip.  It uses a temporary directory only."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.conversation.timeline :as timeline]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.history :as history]
            [hc.server.history.context :as context]
            [hc.server.history.message :as message]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(def ^:private a1 {:attachment-id "a1" :name "first.png" :size 10})
(def ^:private a2 {:attachment-id "a2" :name "second.png" :size 20})

(defn- add-message
  [session message-id role text opts]
  (let [result (message/new-message message-id role text
                                    (assoc opts :now (or (:now opts) 1000)))]
    (is (:ok? result))
    (-> session
        (assoc-in [:messages message-id] (:message result))
        (update :message-order (fnil conj []) message-id))))

(defn- build-session
  []
  (let [created (sessions/new-session
                 {:session-id "durable-session"
                  :owner-id "durable-owner"
                  :now 1000
                  :id-source (ids/counter-source {:prefix "durable"})})
        session (:session created)]
    (is (:ok? created))
    (-> session
        (add-message "m1" :user "first question"
                     {:attachments [a1 a2]
                      :citations [{:url "https://example.test/source"}]
                      :tool-records [{:tool-call-id "tool-1"}]
                      :usage {:prompt-tokens 3 :completion-tokens 5}})
        (add-message "m2" :assistant "first answer"
                     {:attachments []
                      :usage {:prompt-tokens 7 :completion-tokens 11}})
        (add-message "m3" :user "second question" {})
        (add-message "m4" :assistant "second answer" {})
        (assoc :activity-at 2000))))

(defn- command
  [session kind payload now]
  (let [result (history/apply-command session (assoc payload :kind kind)
                                      {:now now})]
    (is (:ok? result) (pr-str (:error result)))
    (:session result)))

(deftest history-survives-a-real-store-restart
  (let [dir (support/temp-dir "hc-history-durable-")]
    (try
      (let [store (storage/open-store {:path dir :create? true
                                       :durability :sync})
            persistence (session-persistence/persistence store)
            session (-> (build-session)
                        (command :history/edit-message
                                 {:message-id "m1"
                                  :content "first question edited"
                                  :attachments [a1]}
                                 3000)
                        (command :history/pin-attachment
                                 {:attachment-id "a2"}
                                 3100)
                        (command :history/prepare-compression
                                 {:compression-id "cmp-durable"
                                  :message-ids ["m1" "m2"]
                                  :summary "reviewed durable summary"}
                                 3200)
                        (command :history/apply-compression
                                 {:compression-id "cmp-durable"}
                                 3300))
            commit (sessions/commit-session! persistence session)]
        (is (= :committed (:status commit)))
        (is (empty? (history/check-invariants (:session commit))))
        (is (empty? (sessions/check-invariants (:session commit))))
        (storage/close-store! store))

      (let [store (storage/open-store {:path dir})
            persistence (session-persistence/persistence store)
            loaded (ports/read-session persistence "durable-session")
            session (:session loaded)
            versions (message/message-history session "m1")
            out (context/outbound-context session)
            pinned (:pinned-attachments out)]
        (is (= :found (:status loaded)))
        (testing "version snapshots and attachment identities survive"
          (is (= 2 (count versions)))
          (is (= "first question" (get-in versions [0 :content])))
          (is (= "first question edited" (get-in versions [1 :content])))
          (is (= [a1 a2] (get-in versions [0 :attachments])))
          (is (= [a1] (get-in versions [1 :attachments])))
          (is (= [{:tool-call-id "tool-1"}] (get-in versions [1 :tool-records])))
          (is (= {:prompt-tokens 3 :completion-tokens 5}
                 (get-in versions [1 :usage]))))
        (testing "the applied summary and pinned attachment survive"
          (is (= :applied (get-in session
                                   [:context :compressions "cmp-durable" :state])))
          (is (= "reviewed durable summary"
                 (get-in out [:messages 0 :content])))
          (is (= ["m1" "m2"] (get-in out [:messages 0 :source-message-ids])))
          (is (= ["a2"] (map :attachment-id pinned)))
          (is (= "m1" (get-in pinned [0 :message-id])))
          (is (= ["m3" "m4"] (:message-ids out))))
        (testing "no history command started a run or replayed an effect"
          (is (= {} (:runs session)))
          (is (= [] (:run-order session)))
          (is (= {} (:calls session)))
          (is (= {} (:attempts session))))
        (is (empty? (history/check-invariants session)))
        (is (empty? (sessions/check-invariants session)))
        (testing "a branch is committed with copied versions and attachment identity"
          (let [branch-result (sessions/branch-session
                               session
                               {:session-id "durable-branch"
                                :owner-id "durable-branch-owner"
                                :before-message-id "m3"
                                :id-source (ids/counter-source {:prefix "branch"})
                                :now 5000})
                branch (:session branch-result)]
            (is (:ok? branch-result))
            (is (= :committed
                   (:status (sessions/commit-session! persistence branch))))
            (is (= 2 (count (:message-order branch))))
            (is (= ["a1"]
                   (mapcat #(map :attachment-id (:attachments %))
                           (mapv (fn [id] (message/message-view
                                           (get-in branch [:messages id])))
                                 (:message-order branch)))))
            (is (= "durable-session" (:origin-session-id branch)))
            (is (= {} (:runs branch)))))
        (storage/close-store! store))

      (let [store (storage/open-store {:path dir})
            persistence (session-persistence/persistence store)
            branch (ports/read-session persistence "durable-branch")
            branch-session (:session branch)]
        (is (= :found (:status branch)))
        (testing "the branch survives without the source being open"
          (is (= 2 (count (:message-order branch-session))))
          (is (= ["first question edited" "first answer"]
                 (mapv #(:content (message/message-view
                                   (get-in branch-session [:messages %])))
                       (:message-order branch-session))))
          (is (= ["a1"] (map :attachment-id
                             (:attachments (message/message-view
                                            (get-in branch-session
                                                    [:messages
                                                     (first (:message-order
                                                             branch-session))]))))))
          (is (= {} (:runs branch-session)))
          (is (= {} (:calls branch-session)))
          (is (= {} (:attempts branch-session)))
          (is (= "durable-session" (:origin-session-id branch-session)))
          (is (empty? (history/check-invariants branch-session))))
        (storage/close-store! store))
      (finally
        (support/delete-tree! dir)))))

(deftest selected-version-survives-restart-in-switch-order
  (let [dir (support/temp-dir "hc-history-selection-")
        session-id "selection-session"
        created (:session (sessions/new-session
                           {:session-id session-id
                            :owner-id "selection-owner"
                            :now 1000
                            :id-source (ids/counter-source
                                        {:prefix "selection"})}))
        session (-> created
                    (add-message "m1" :user "with image"
                                 {:attachments [a1]})
                    (command :history/edit-message
                             {:message-id "m1"
                              :content "without image"
                              :attachments []}
                             2000))]
    (try
      (let [store (storage/open-store {:path dir :create? true
                                       :durability :sync})
            persistence (session-persistence/persistence store)
            commit (sessions/commit-session! persistence session)]
        (is (= :committed (:status commit)))
        (is (= 2 (count (get-in commit [:session :messages "m1" :versions]))))
        (storage/close-store! store))

      ;; First restart: select the original version and persist the choice.
      (let [store (storage/open-store {:path dir})
            persistence (session-persistence/persistence store)
            loaded (:session (ports/read-session persistence session-id))
            selected (command loaded :history/select-version
                              {:message-id "m1" :version-id "m1-v1"}
                              3000)
            commit (sessions/commit-owner-session! persistence loaded
                                                   selected 3000)]
        (is (:ok? commit))
        (is (= 0 (get-in commit [:session :messages "m1" :current-version])))
        (storage/close-store! store))

      ;; Second restart: v1 is still selected, with its own attachments.
      (let [store (storage/open-store {:path dir})
            persistence (session-persistence/persistence store)
            loaded (:session (ports/read-session persistence session-id))
            baseline (snapshot/baseline {:sessions [loaded]
                                         :session-records [loaded]
                                         :active-session session-id})
            message (get-in baseline [:message/by-id "m1"])]
        (is (= 0 (get-in loaded [:messages "m1" :current-version])))
        (is (= [a1] (get-in loaded [:messages "m1" :attachments])))
        (is (= 2 (count (get-in loaded [:messages "m1" :versions]))))
        (is (= [a1] (:attachments message)))
        (is (= 0 (:current-version message)))
        (testing "the client view receives the selected version's image"
          (is (= ["first.png"]
                 (mapv :name (:attachments
                              (timeline/message-view message))))))
        (testing "selecting the empty version persists the empty snapshot"
          (let [selected (command loaded :history/select-version
                                  {:message-id "m1" :version-id "m1-v2"}
                                  4000)
                commit (sessions/commit-owner-session! persistence loaded
                                                       selected 4000)]
            (is (:ok? commit))
            (is (= 1 (get-in commit [:session :messages "m1"
                                     :current-version])))
            (is (= [] (get-in commit [:session :messages "m1"
                                      :attachments])))))
        (storage/close-store! store))

      ;; Third restart: the second selection survived and stays empty.
      (let [store (storage/open-store {:path dir})
            persistence (session-persistence/persistence store)
            loaded (:session (ports/read-session persistence session-id))
            baseline (snapshot/baseline {:sessions [loaded]
                                         :session-records [loaded]
                                         :active-session session-id})
            message (get-in baseline [:message/by-id "m1"])]
        (is (= 1 (get-in loaded [:messages "m1" :current-version])))
        (is (= [] (get-in loaded [:messages "m1" :attachments])))
        (is (= [] (:attachments message)))
        (is (= 1 (:current-version message)))
        (is (empty? (:attachments
                     (timeline/message-view message))))
        (storage/close-store! store))
      (finally
        (support/delete-tree! dir)))))
