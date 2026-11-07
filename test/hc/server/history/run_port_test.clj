(ns hc.server.history.run-port-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.core.pair-hint :as pair-hint]
            [hc.server.history :as history]
            [hc.server.history.message :as message]
            [hc.server.history.run-port :as run-port]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]))

(defn- command
  [session kind payload now]
  (history/apply-command session (assoc payload :kind kind)
                          {:now now}))

(defn- session-with-messages
  []
  (let [created (sessions/new-session
                 {:session-id "s1"
                  :owner-id "o1"
                  :now 1000
                  :id-source (ids/counter-source {:prefix "t"})})]
    (is (:ok? created))
    (-> (:session created)
        (assoc :model-binding {:connection-id "conn-1"
                               :model-entry-id "model-1"})
        (assoc-in [:messages "m1"]
                  (:message (message/new-message "m1" :user "question"
                                                 {:now 1100})))
        (assoc-in [:messages "m2"]
                  (:message (message/new-message "m2" :assistant "old answer"
                                                 {:now 1200})))
        (assoc :message-order ["m1" "m2"]))))

(deftest frozen-run-is-invalidated-by-compression-and-undo
  (let [session0 {:session-id "s1"
                  :message-order ["m1" "m2" "m3" "m4"]
                  :messages {"m1" {:message-id "m1" :role :user
                                    :content "fact one"}
                             "m2" {:message-id "m2" :role :assistant
                                   :content "answer one"}
                             "m3" {:message-id "m3" :role :user
                                   :content "fact two"}
                             "m4" {:message-id "m4" :role :assistant
                                   :content "answer two"}}}
        session1 (:session (command session0 :history/prepare-compression
                                     {:compression-id "c1"
                                      :message-ids ["m1" "m2"]
                                      :summary "summary of one"}
                                     3000))
        session (:session (command session1 :history/apply-compression
                                   {:compression-id "c1"}
                                   4000))
        stale-run {:input-snapshot
                   {:context {:messages [{:message-id "m1" :role :user
                                          :content "fact one"}
                                         {:message-id "m2" :role :assistant
                                          :content "answer one"}
                                         {:message-id "m3" :role :user
                                          :content "fact two"}
                                         {:message-id "m4" :role :assistant
                                          :content "answer two"}]
                              :message-ids ["m1" "m2" "m3" "m4"]
                              :summary-ids []}}}
        entries (#'run-port/frozen-entries session stale-run)]
    (is (= ["m3" "m4"]
           (mapv :message-id (remove #(= :context-summary (:kind %)) entries))))
    (is (some #(and (= :context-summary (:kind %))
                    (= "summary of one" (:content %)))
              entries))
    (let [undone (:session (command session :history/undo-compression
                                    {:compression-id "c1"} 5000))
          restored (#'run-port/frozen-entries undone stale-run)]
      (is (= ["m1" "m2" "m3" "m4"]
             (mapv :message-id (remove #(= :context-summary (:kind %)) restored))))
      (is (not (some #(= :context-summary (:kind %)) restored))))))

(deftest frozen-run-taken-after-apply-is-invalidated-by-undo
  ;; D-24: a send frozen while a compression is applied keeps the summary
  ;; entry in its snapshot.  Undoing that compression changes which real
  ;; messages the range covers but not the summary-id set, so a staleness
  ;; check that only compares summary ids keeps serving the summary entry
  ;; and drops the restored originals.
  (let [session0 {:session-id "s1"
                  :message-order ["m1" "m2" "m3" "m4" "m5"]
                  :messages {"m1" {:message-id "m1" :role :user
                                    :content "fact one"}
                             "m2" {:message-id "m2" :role :assistant
                                   :content "answer one"}
                             "m3" {:message-id "m3" :role :user
                                   :content "fact two"}
                             "m4" {:message-id "m4" :role :assistant
                                   :content "answer two"}
                             "m5" {:message-id "m5" :role :user
                                   :content "later question"}}}
        session1 (:session (command session0 :history/prepare-compression
                                     {:compression-id "c1"
                                      :message-ids ["m1" "m2" "m3"]
                                      :summary "summary of one"}
                                     3000))
        applied (:session (command session1 :history/apply-compression
                                    {:compression-id "c1"}
                                    4000))
        ;; The frozen snapshot of the send that ran while c1 was applied:
        ;; summary entry first, then the uncovered tail.
        frozen-context {:messages [{:kind :context-summary
                                   :role :system
                                   :compression-id "c1"
                                   :content "summary of one"}
                                  {:message-id "m4" :role :assistant
                                   :content "answer two"}
                                  {:message-id "m5" :role :user
                                   :content "later question"}]
                        :message-ids ["m4" "m5"]
                        :summary-ids ["c1"]}
        run {:input-snapshot {:context frozen-context
                              :target-message-id nil}}
        undone (:session (command applied :history/undo-compression
                                  {:compression-id "c1"} 5000))
        entries (#'run-port/frozen-entries undone run)]
    (is (not (some #(= :context-summary (:kind %)) entries))
        "an undone compression must not emit its summary entry")
    (is (= ["m1" "m2" "m3" "m4" "m5"]
           (mapv :message-id (remove #(= :context-summary (:kind %)) entries)))
        "the restored originals must re-enter the next request")))

(deftest regenerate-model-messages-keep-message-id-for-pair-hint-anchor
  (let [session (session-with-messages)
        regenerated (command session :history/regenerate
                             {:message-id "m2"} 2000)
        effect (first (:effects regenerated))
        started (run-port/start (:session regenerated) effect
                                {:now 3000
                                 :id-source (ids/counter-source {:prefix "rp"})})]
    (is (:ok? started) (pr-str (:error started)))
    (let [messages (get-in started [:request :snapshot :messages])]
      (is (= [{:role :user :text "question" :message-id "m1"}]
             messages))
      ;; The pair-hint replay anchor must find `:message-id` in the frozen
      ;; request messages; without it `replay-placements` throws
      ;; PAIR_HINT_ANCHOR_MISSING and the model call never starts.
      (let [pair-session (pair-hint/create-session {:explicit-language :en})
            placed (pair-hint/append-hint pair-session
                                          {:messages messages
                                           :placement {:turn 1}
                                           :anchor {:after-message-id "m1"}
                                           :render pr-str})]
        (is (:ok? placed) (pr-str (:error placed)))
        (let [stripped (mapv #(dissoc % :message-id) messages)
              missing (pair-hint/project-request
                       (:state placed)
                       {:call-id "call-2"
                        :snapshot {:role :main :messages stripped}}
                       {:placement {:call-id "call-2"}
                        :render pr-str})]
          (is (false? (:ok? missing)))
          (is (= :PAIR_HINT_ANCHOR_MISSING
                 (get-in missing [:error :code]))))
        (let [replayed (pair-hint/project-request
                        (:state placed)
                        {:call-id "call-2"
                         :snapshot {:role :main :messages messages}}
                        {:placement {:call-id "call-2"}
                         :render pr-str})]
          (is (:ok? replayed) (pr-str (:error replayed))))))))
