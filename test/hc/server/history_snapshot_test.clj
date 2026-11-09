(ns hc.server.history-snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.history.context :as context]
            [hc.client.sync.normalize :as normalize]
            [hc.client.workbench.model :as workbench-model]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.history :as history]
            [hc.server.history.message :as message]
            [hc.server.history.snapshot :as history-snapshot]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]))

(def ^:private a1 {:attachment-id "a1" :name "first.png" :size 10})
(def ^:private a2 {:attachment-id "a2" :name "second.png" :size 20})

(defn- base-session
  []
  (:session (sessions/new-session
             {:session-id "s1"
              :owner-id "o1"
              :now 1000
              :id-source (ids/counter-source {:prefix "snap"})})))

(defn- add-message
  [session message-id role text opts]
  (let [result (message/new-message message-id role text
                                    (assoc opts :now (or (:now opts) 1000)))]
    (is (:ok? result))
    (-> session
        (assoc-in [:messages message-id] (:message result))
        (update :message-order (fnil conj []) message-id))))

(defn- command
  [session kind payload now]
  (let [result (history/apply-command session (assoc payload :kind kind)
                                      {:now now})]
    (is (:ok? result) (pr-str (:error result)))
    (:session result)))

(deftest history-snapshot-is-a-valid-client-baseline
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {:attachments [a1 a2]})
                    (add-message "m2" :assistant "one answer" {})
                    (add-message "m3" :user "two" {})
                    (command :history/edit-message
                             {:message-id "m1"
                              :content "one edited"
                              :attachments [a1]
                              :citations [{:url "https://edited.test"}]}
                             2000)
                    (command :history/prepare-compression
                             {:compression-id "c1"
                              :message-ids ["m1" "m2"]
                              :summary "summary"}
                             3000)
                    (command :history/apply-compression
                             {:compression-id "c1"}
                             4000)
                    (assoc-in [:messages "m3" :deleted?] true))
        history (history-snapshot/baseline [session] "s1")
        by-id (:message/by-id history)
        order (:message/order history)
        view (get-in history [:session/history "s1"])
        db-result (normalize/to-db history)]
    (testing "only visible messages enter the display tables"
      (is (= #{"m1" "m2"} (set (keys by-id))))
      (is (= ["m1" "m2"] order)))
    (testing "the current version and all versions cross the wire"
      (is (= "one edited" (get-in by-id ["m1" :content])))
      (is (= 2 (count (get-in by-id ["m1" :versions]))))
      (is (= 1 (get-in by-id ["m1" :current-version])))
      (is (= "one" (get-in by-id ["m1" :versions 0 :content])))
      (is (= "one edited" (get-in by-id ["m1" :versions 1 :content])))
      (is (= ["a1"] (map :attachment-id
                         (get-in by-id ["m1" :versions 1 :attachments]))))
      (is (= [] (get-in by-id ["m1" :versions 0 :citations])))
      (is (= [{:url "https://edited.test"}]
             (get-in by-id ["m1" :versions 1 :citations])))
      (is (= [{:url "https://edited.test"}]
             (get-in by-id ["m1" :sources])))
      (is (= "s1" (get-in by-id ["m1" :session-id]))))
    (testing "context and outbound preview are present"
      (is (= [] (get-in view [:context :outbound :message-ids])))
      (is (= ["c1"] (get-in view [:context :outbound :summary-ids])))
      (is (= :applied (get-in view [:context :compressions 0 :state]))))
    (testing "the client snapshot validator accepts the projection"
      (is (:ok? db-result) (pr-str (:error db-result)))
      (is (= #{"m1" "m2"}
             (set (keys (get-in db-result [:value :message/by-id])))))
      (is (= ["m1" "m2"] (get-in db-result [:value :message/order]))))))

(deftest app-baseline-includes-session-history-and-branch-origin
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {})
                    (assoc :origin-session-id "parent-session"))
        baseline (snapshot/baseline {:sessions [session]
                                     :session-records [session]
                                     :owner-snapshots
                                     {"s1" {:state {:commands {:command-epoch 7}}}}
                                     :active-session "s1"})
        summary (get-in baseline [:session/by-id "s1"])
        history (get-in baseline [:session/history "s1"])]
    (is (= ["m1"] (:message-ids summary)))
    (is (= "parent-session" (:branch-of summary)))
    (is (= 7 (:command-epoch summary)))
    (is (= ["m1"] (:message-ids history)))
    (is (contains? (:context history) :outbound))
    (is (contains? (:message/by-id baseline) "m1"))
    (is (= (set (keys (:message/by-id baseline)))
           (set (:message/order baseline))))
    (testing "the workbench model resolves the session's messages"
      (let [db {:message/by-id (:message/by-id baseline)
                :message/order (:message/order baseline)
                :session/by-id (:session/by-id baseline)}]
        (is (= ["m1"] (mapv :id (workbench-model/messages-for-session
                                 db "s1"))))))
    (testing "owner conversation extras merge without breaking the order"
      (let [merged (snapshot/baseline
                    {:sessions [session]
                     :session-records [session]
                     :active-session "s1"
                     :owner-snapshots
                     {"s1" {:state {:conversation
                                    [{:id "extra-report"
                                      :role :assistant
                                      :content "report"}]}}}})
            result (normalize/to-db merged)]
        (is (:ok? result) (pr-str (:error result)))
        (is (= #{"m1" "extra-report"}
               (set (keys (:message/by-id merged)))))
        (is (= "s1" (get-in merged [:message/by-id "extra-report"
                                    :session-id]))
            "active conversation messages carry their explicit session id")
        (is (= (set (keys (:message/by-id merged)))
               (set (:message/order merged))))))))

(deftest baseline-publishes-live-model-availability
  (let [session (assoc (base-session)
                       :model-binding
                       {:connection-id "conn-a"
                        :model-entry-id "m1"
                        :display-name "Model"})
        baseline-for
        (fn [connections]
          (snapshot/baseline
           {:sessions [session]
            :session-records [session]
            :active-session "s1"
            :config {:models {:connections connections}}}))
        availability
        (fn [connections]
          (get-in (baseline-for connections)
                  [:session/by-id "s1" :model-availability]))]
    (is (= :available
           (availability
            [{:id "conn-a" :name "A" :enabled? true
              :models [{:id "m1" :name "Model"
                        :enabled? true :availability :available}]}])))
    (is (= :connection-disabled
           (availability
            [{:id "conn-a" :name "A" :enabled? false
              :models [{:id "m1" :name "Model" :enabled? true}]}])))
    (is (= :model-removed
           (availability
            [{:id "conn-a" :name "A" :enabled? true :models []}])))
    (is (= :connection-removed
           (availability [])))
    (testing "a session without a binding publishes no availability"
      (let [plain (snapshot/baseline
                   {:sessions [(base-session)]
                    :session-records []
                    :active-session "s1"
                    :config {:models {:connections []}}})]
        (is (not (contains? (get-in plain [:session/by-id "s1"])
                            :model-availability)))))))

(deftest baseline-clears-streaming-when-the-run-finished
  (let [message-id "assistant-call-2-:attempt-225161a9--6"
        session (-> (base-session)
                    (add-message "m1" :user "question" {}))
        baseline (snapshot/baseline
                  {:sessions [session]
                   :session-records [session]
                   :active-session "s1"
                   :owner-snapshots
                   {"s1" {:state
                          {:conversation
                           [{:id message-id
                             :role :assistant
                             :content "answer"
                             :streaming? true}]
                           :runs
                           {"call-2#attempt-225161a9--6"
                            {:run-id "call-2#attempt-225161a9--6"
                             :message-id message-id
                             :status :finished}}}}}})]
    (is (false? (get-in baseline [:message/by-id message-id
                                  :streaming?])))
    (is (= "answer" (get-in baseline [:message/by-id message-id
                                      :content])))))

(deftest baseline-keeps-owner-extras-from-multiple-sessions-separate
  (let [s1 (-> (base-session)
               (add-message "s1-m1" :user "one" {}))
        s2 (-> (:session (sessions/new-session
                          {:session-id "s2"
                           :owner-id "o2"
                           :now 2000
                           :id-source (ids/counter-source {:prefix "s2"})}))
               (add-message "s2-m1" :user "two" {}))
        baseline (snapshot/baseline
                  {:sessions [s1 s2]
                   :session-records [s1 s2]
                   :active-session "s1"
                   ;; Explicit full history (nil), distinct from the
                   ;; production default 0 (0 = no inactive entities).
                   :inactive-history-window-size nil
                   :owner-snapshots
                   {"s1" {:state {:conversation
                                  [{:id "extra-s1"
                                    :role :assistant
                                    :content "one report"}]}}
                    "s2" {:state {:conversation
                                  [{:id "extra-s2"
                                    :role :assistant
                                    :content "two report"}]}}}})]
    (is (= "s1" (get-in baseline [:message/by-id "extra-s1" :session-id])))
    (is (= "s2" (get-in baseline [:message/by-id "extra-s2" :session-id])))
    (is (= #{"s1-m1" "s2-m1" "extra-s1" "extra-s2"}
           (set (keys (:message/by-id baseline)))))
    (is (= (set (keys (:message/by-id baseline)))
           (set (:message/order baseline))))
    (is (= ["s1-m1"] (get-in baseline [:session/history "s1"
                                       :message-ids])))
    (is (= ["s2-m1"] (get-in baseline [:session/history "s2"
                                       :message-ids])))))

(deftest outbound-range-survives-regenerate-and-excludes-hidden-messages
  (let [session (-> (base-session)
                    (assoc :model-binding {:connection-id "c1"
                                           :model-entry-id "m1"})
                    (add-message "m1" :user "one" {})
                    (add-message "m2" :assistant "one answer" {})
                    (add-message "m3" :user "two" {})
                    (add-message "m4" :assistant "two answer" {})
                    (command :history/prepare-compression
                             {:compression-id "c1"
                              :message-ids ["m1" "m2"]
                              :summary "summary"}
                             3000)
                    (command :history/apply-compression
                             {:compression-id "c1"}
                             4000)
                    (command :history/regenerate
                             {:message-id "m4"}
                             5000))
        outbound (get-in (history-snapshot/session-history session)
                         [:context :outbound])]
    (is (= ["m3" "m4"] (:message-ids outbound))
        "a regenerate freezes only the prefix; the next send still carries its target")
    (is (= ["c1"] (:summary-ids outbound)))
    (testing "a folded message is excluded from the next send range"
      (let [folded (assoc-in session [:messages "m4" :folded?] true)
            next-outbound (get-in (history-snapshot/session-history folded)
                                  [:context :outbound])]
        (is (= ["m3"] (:message-ids next-outbound)))))
    (testing "a branch carries only its copied prefix"
      (let [branch (:session (sessions/branch-session
                              session
                              {:session-id "branch-1"
                               :owner-id "branch-owner"
                               :before-message-id "m3"
                               :now 6000}))
            branch-ids (get-in (history-snapshot/session-history branch)
                               [:context :outbound :message-ids])]
        (is (= 2 (count branch-ids)))
        (is (not (some #{"m3" "m4"} branch-ids)))))))

(deftest baseline-projects-live-materials-only-for-the-active-session
  (let [calls (atom [])
        s1 (assoc (base-session) :session-id "s1")
        s2 (assoc (base-session) :session-id "s2")
        _ (history-snapshot/baseline
           [s1 s2] "s2"
           {:outbound-opts-fn (fn [session]
                                (swap! calls conj (:session-id session))
                                {})})]
    (is (= ["s2"] @calls)
        "one materials projection per publish, not one per session")))


(deftest compression-outbound-replaces-messages-and-undo-restores
  (let [session (-> (base-session)
                    (add-message "m1" :user "fact one" {})
                    (add-message "m2" :assistant "answer one" {})
                    (add-message "m3" :user "fact two" {})
                    (add-message "m4" :assistant "answer two" {})
                    (command :history/prepare-compression
                             {:compression-id "c1"
                              :message-ids ["m1" "m2"]
                              :summary "summary of one"}
                             3000)
                    (command :history/apply-compression
                             {:compression-id "c1"}
                             4000))
        out (context/outbound-context session)
        messages (context/model-messages out)]
    (is (= ["m3" "m4"] (:message-ids out)))
    (is (= ["c1"] (:summary-ids out)))
    (is (some #(and (= :system (:role %))
                    (= "summary of one" (:text %)))
              messages)
        "the reviewed summary reaches the model request")
    (is (not (some #(re-find #"fact one|answer one" (:text %)) messages))
        "messages replaced by the summary are absent")
    (let [undone (command session :history/undo-compression
                          {:compression-id "c1"} 5000)
          restored (context/outbound-context undone)]
      (is (= ["m1" "m2" "m3" "m4"] (:message-ids restored)))
      (is (= [] (:summary-ids restored))))))
