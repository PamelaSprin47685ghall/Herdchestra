(ns hc.server.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.commands :as owner-commands]
            [hc.compose.owner.handlers :as owner-handlers]
            [hc.compose.owner.state :as owner-state]
            [hc.server.history :as history]
            [hc.server.history.attachments :as attachments]
            [hc.server.history.context :as context]
            [hc.server.history.message :as message]
            [hc.server.history.run-port :as run-port]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.runs :as runs]))

(defn- base-session
  []
  (let [result (sessions/new-session
                {:session-id "s1"
                 :owner-id "o1"
                 :now 1000
                 :id-source (ids/counter-source {:prefix "t"})})]
    (is (:ok? result))
    (:session result)))

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
  (history/apply-command session (assoc payload :kind kind) {:now now}))

(defn- view-of
  [session message-id]
  (message/message-view (get-in session [:messages message-id])))

(def ^:private a1 {:attachment-id "a1" :name "first.png" :size 10})
(def ^:private a2 {:attachment-id "a2" :name "second.png" :size 20})
(def ^:private a3 {:attachment-id "a3" :name "third.png" :size 30})
(def ^:private usage {:prompt-tokens 4 :completion-tokens 6 :source :provider})

(deftest editing-appends-a-version-and-preserves-identity
  (let [session (add-message (base-session) "m1" :user "hello"
                             {:attachments [a1 a2]
                              :citations [{:url "https://example.test/a"}]
                              :tool-records [{:tool-call-id "t1"}]
                              :usage usage})
        edited (command session :history/edit-message
                        {:message-id "m1"
                         :content "hello edited"
                         :attachments [a1]}
                        2000)
        session' (:session edited)
        versions (message/message-history session' "m1")]
    (is (:ok? edited))
    (is (= 2 (count versions)))
    (is (= 1 (:current-version (get-in session' [:messages "m1"]))))
    (is (= 1 (get-in edited [:result :version-index])))
    (is (= 2000 (:activity-at session')))
    (testing "the original version keeps its exact attachment and citation snapshot"
      (is (= [a1 a2] (get-in versions [0 :attachments])))
      (is (= [{:url "https://example.test/a"}] (get-in versions [0 :citations])))
      (is (= [{:tool-call-id "t1"}] (get-in versions [0 :tool-records])))
      (is (= usage (get-in versions [0 :usage]))))
    (testing "the edited version removes only its own reference to a2"
      (is (= [a1] (get-in versions [1 :attachments])))
      (is (= [{:url "https://example.test/a"}]
             (get-in versions [1 :citations])))
      (is (= [{:tool-call-id "t1"}] (get-in versions [1 :tool-records])))
      (is (= usage (get-in versions [1 :usage])))
      (is (true? (get-in versions [1 :usage-inherited?]))))
    (testing "the current view follows the selected version"
      (is (= "hello edited" (:content (view-of session' "m1"))))
      (is (= [a1] (:attachments (view-of session' "m1")))))
    (testing "the untouched original remains readable"
      (is (= "hello" (get-in versions [0 :content]))))
    (is (empty? (history/check-invariants session')))))

(deftest editing-can-remove-every-attachment-without-touching-the-original
  (let [session (add-message (base-session) "m1" :user "three images"
                             {:attachments [a1 a2 a3]})
        edited (command session :history/edit-message
                        {:message-id "m1"
                         :content "three images edited"
                         :attachments []}
                        2000)
        session' (:session edited)
        versions (message/message-history session' "m1")
        view (view-of session' "m1")]
    (is (:ok? edited))
    (is (= [] (:attachments view))
        "the new version keeps no attachment references")
    (is (= [a1 a2 a3] (get-in versions [0 :attachments]))
        "the original version still references all three images")
    (is (= "three images" (get-in versions [0 :content])))
    (is (= [] (get-in versions [1 :attachments])))
    (is (empty? (history/check-invariants session')))))

(deftest owner-conversation-message-mirror-is-idempotent
  (let [client-attachment {:id "client-a1" :name "client.png" :size 7}
        session (base-session)
        first-result (command session :history/append-message
                              {:message-id "user-c1"
                               :role :user
                               :content "hello"
                               :attachments [client-attachment]
                               :citations [{:url "https://source.test"}]}
                              2000)
        second-result (command (:session first-result)
                               :history/append-message
                               {:message-id "user-c1"
                                :role :user
                                :content "different"}
                               3000)
        view (view-of (:session second-result) "user-c1")]
    (is (:ok? first-result))
    (is (:ok? second-result))
    (is (= "hello" (:content view)))
    (is (= ["client-a1"] (map :attachment-id (:attachments view))))
    (is (= "client-a1" (get-in view [:attachments 0 :id])))
    (is (= [{:url "https://source.test"}] (:citations view)))
    (is (= 1 (count (:message-order (:session second-result)))))
    (is (empty? (history/check-invariants (:session second-result))))
    (testing "a blank assistant placeholder is upgraded in place"
      (let [placeholder (command (:session second-result)
                                 :history/append-message
                                 {:message-id "assistant-c1"
                                  :role :assistant
                                  :content ""}
                                 4000)
            final (command (:session placeholder)
                           :history/append-message
                           {:message-id "assistant-c1"
                            :role :assistant
                            :content "final answer"
                            :usage {:prompt-tokens 1 :completion-tokens 2}}
                           5000)
            message (get-in (:session final) [:messages "assistant-c1"])]
        (is (= "final answer" (get-in message [:versions 0 :content])))
        (is (= 1 (count (:versions message))))
        (is (= 1 (count (filter #{"assistant-c1"}
                                (:message-order (:session final))))))))
    (testing "a partial original answer is replaced, not duplicated"
      (let [partial (command (:session second-result)
                             :history/append-message
                             {:message-id "assistant-c2"
                              :role :assistant
                              :content "partial"}
                             6000)
            final (command (:session partial)
                           :history/append-message
                           {:message-id "assistant-c2"
                            :role :assistant
                            :content "final answer"
                            :partial? true}
                           7000)
            message (get-in (:session final) [:messages "assistant-c2"])]
        (is (= "final answer" (get-in message [:versions 0 :content])))
        (is (= 1 (count (:versions message))))))
    (testing "legacy owner :text becomes a version on first mirror"
      (let [legacy (assoc-in (:session second-result)
                             [:messages "legacy-1"]
                             {:role :assistant
                              :message-id "legacy-1"
                              :text "legacy answer"})
            result (command legacy :history/append-message
                            {:message-id "legacy-1"
                             :role :assistant
                             :content "legacy answer"}
                            8000)
            view (view-of (:session result) "legacy-1")]
        (is (= "legacy answer" (:content view)))
        (is (= 1 (count (:versions (get-in (:session result)
                                           [:messages "legacy-1"])))))))))

(deftest streaming-assistant-chunks-grow-the-single-version
  ;; The Owner mirrors the assistant text on every chunk.  The durable copy
  ;; must follow that append-only growth instead of stopping at the first
  ;; chunk; otherwise a reload or a restart loses the rest of the answer.
  (let [append (fn [session content at]
                 (command session :history/append-message
                          {:message-id "assistant-stream"
                           :role :assistant
                           :content content}
                          at))
        first-chunk (append (base-session) "第一段" 1000)
        second-chunk (append (:session first-chunk) "第一段，第二段" 2000)
        repeated (append (:session second-chunk) "第一段，第二段" 3000)
        final (append (:session repeated) "第一段，第二段，完" 4000)
        message (get-in (:session final) [:messages "assistant-stream"])]
    (is (:ok? final))
    (is (= "第一段，第二段，完" (get-in message [:versions 0 :content]))
        "the durable version carries the full streamed answer")
    (is (= 1 (count (:versions message)))
        "streaming growth never appends a second version")
    (is (= 1 (count (filter #{"assistant-stream"}
                            (:message-order (:session final)))))
        "the message id and order do not change")
    (is (= "第一段，第二段，完" (:content (view-of (:session final)
                                                   "assistant-stream")))
        "a fresh read of the session sees the full answer")
    (is (empty? (history/check-invariants (:session final))))
    (testing "a user message is never overwritten by a chunk"
      (let [user (command (:session final) :history/append-message
                          {:message-id "user-fixed"
                           :role :user
                           :content "原始问题"}
                          5000)
            user-again (command (:session user) :history/append-message
                                {:message-id "user-fixed"
                                 :role :user
                                 :content "原始问题，追加"}
                                6000)]
        (is (= "原始问题"
               (get-in (:session user-again)
                       [:messages "user-fixed" :versions 0 :content])))))
    (testing "an edited assistant answer keeps its new version"
      (let [edited (command (:session final) :history/edit-message
                            {:message-id "assistant-stream"
                             :content "手工改写"}
                            7000)
            late (append (:session edited) "第一段，第二段，完" 8000)
            versions (get-in (:session late)
                             [:messages "assistant-stream" :versions])]
        (is (= 2 (count versions)))
        (is (= "手工改写" (get-in versions [1 :content]))
            "a late chunk cannot rewrite an edited answer")))))

(deftest selecting-a-version-switches-snapshots-without-activity
  (let [session (-> (base-session)
                    (add-message "m1" :assistant "first answer"
                                 {:attachments [a1]
                                  :citations [{:url "https://one.test"}]
                                  :usage usage})
                    (add-message "m2" :user "next" {}))
        edited (command session :history/edit-message
                        {:message-id "m1"
                         :content "manual answer"
                         :attachments [a2]
                         :citations [{:url "https://two.test"}]
                         :usage nil}
                        2000)
        session' (:session edited)
        activity (:activity-at session')
        selected (command session' :history/select-version
                          {:message-id "m1" :version-id "m1-v1"}
                          3000)
        selected-session (:session selected)
        view (view-of selected-session "m1")]
    (is (:ok? selected))
    (is (= 0 (get-in selected-session [:messages "m1" :current-version])))
    (is (= activity (:activity-at selected-session)))
    (is (= "first answer" (:content view)))
    (is (= [a1] (:attachments view)))
    (is (= [{:url "https://one.test"}] (:citations view)))
    (is (= usage (:usage view)))
    (testing "the manual version is still readable and marked"
      (let [versions (message/message-history selected-session "m1")]
        (is (= "manual answer" (get-in versions [1 :content])))
        (is (true? (get-in versions [1 :manual-edit?])))))
    (is (empty? (history/check-invariants selected-session)))))

(deftest regeneration-adds-an-explicit-version-and-never-starts-a-run
  (let [session (-> (base-session)
                    (add-message "m1" :user "question" {})
                    (add-message "m2" :assistant "answer one"
                                 {:citations [{:url "https://one.test"}]
                                  :usage {:prompt-tokens 1 :completion-tokens 2}}))
        before-runs (:run-order session)
        result (command session :history/append-version
                        {:message-id "m2"
                         :content "answer two"
                         :citations [{:url "https://two.test"}]
                         :usage {:prompt-tokens 3 :completion-tokens 4}
                         :source :regenerated
                         :regenerated-from-version-id "m2-v1"}
                        2000)
        session' (:session result)
        versions (message/message-history session' "m2")]
    (is (:ok? result))
    (is (= 2 (count versions)))
    (is (= 1 (get-in session' [:messages "m2" :current-version])))
    (is (= "answer two" (:content (view-of session' "m2"))))
    (is (= [{:url "https://two.test"}] (:citations (view-of session' "m2"))))
    (is (= {:prompt-tokens 3 :completion-tokens 4} (:usage (view-of session' "m2"))))
    (is (= :regenerated (get-in versions [1 :source])))
    (is (= before-runs (:run-order session')))
    (is (= {} (:runs session')))
    (is (empty? (history/check-invariants session')))))

(deftest regeneration-appends-a-version-to-the-target-only
  (let [session (-> (base-session)
                    (assoc :model-binding {:connection-id "conn-1"
                                           :model-entry-id "model-1"})
                    (add-message "m1" :user "question" {})
                    (add-message "m2" :assistant "old answer"
                                 {:usage {:prompt-tokens 1 :completion-tokens 2}}))
        regenerated (command session :history/regenerate
                             {:message-id "m2"} 2000)
        run-id (get-in regenerated [:result :run-id])
        call-id (get-in regenerated [:result :call-id])
        claimed (runs/claim-call (:session regenerated)
                                 {:call-id call-id
                                  :attempt-id "attempt-1"
                                  :now 3000})
        running (runs/mark-attempt-running (:session claimed) "attempt-1" 3100)
        terminal (sessions/accept-model-event
                  (:session running)
                  {:kind :completed
                   :attempt-id "attempt-1"
                   :content "new answer"
                   :usage {:prompt-tokens 3 :completion-tokens 4}}
                  {:now 4000})
        final (:session terminal)
        versions (message/message-history final "m2")]
    (is (:ok? regenerated))
    (is (= :append-version (get-in final [:runs run-id :input-snapshot
                                         :target-mode])))
    (is (= 2 (count versions)))
    (is (= "old answer" (get-in versions [0 :content])))
    (is (= "new answer" (get-in versions [1 :content])))
    (is (= :regenerated (get-in versions [1 :source])))
    (is (= {:prompt-tokens 3 :completion-tokens 4}
           (get-in versions [1 :usage])))
    (is (= 1 (get-in final [:messages "m2" :current-version])))
    (is (= 2 (count (:message-order final))))
    (is (= 4000 (:activity-at final)))
    (is (empty? (history/check-invariants final)))))

(deftest session-run-port-claims-and-accepts-a-regeneration
  (let [session (-> (base-session)
                    (assoc :model-binding {:connection-id "conn-1"
                                           :model-entry-id "model-1"})
                    (add-message "m1" :user "question" {})
                    (add-message "m2" :assistant "old answer" {}))
        regenerated (command session :history/regenerate
                             {:message-id "m2"} 2000)
        effect (first (:effects regenerated))
        started (run-port/start (:session regenerated) effect
                                {:now 3000
                                 :id-source (ids/counter-source {:prefix "rp"})})]
    (is (= :run-started (:kind effect)))
    (is (:ok? started) (pr-str (:error started)))
    (is (= [{:role :user :text "question" :message-id "m1"}]
           (get-in started [:request :snapshot :messages])))
    (is (= :main (get-in started [:request :snapshot :role])))
    (is (= "conn-1" (get-in started [:request :snapshot :connection])))
    (is (= "model-1" (get-in started [:request :snapshot :model])))
    (is (false? (get-in started [:request :snapshot :user-turn?])))
    (is (true? (get-in started [:request :snapshot :regenerate?])))
    (is (= "m2-v1"
           (get-in (:session regenerated)
                   [:runs (:run-id effect)
                    :input-snapshot :context :target-version-id])))
    (is (= :dispatching (get-in (:session started)
                                [:attempts
                                 (get-in started [:session-run :attempt-id])
                                 :state])))
    (let [accepted (run-port/accept
                    (:session started)
                    {:event {:event :completed
                             :text "regenerated answer"
                             :usage {:prompt-tokens 2 :completion-tokens 3}}
                     :session-run (:session-run started)
                     :now 4000})
          versions (message/message-history (:session accepted) "m2")]
      (is (:ok? accepted) (pr-str (:error accepted)))
      (is (= 2 (count versions)))
      (is (= "regenerated answer" (get-in versions [1 :content])))
      (is (= :regenerated (get-in versions [1 :source])))
      (is (= {:prompt-tokens 2 :completion-tokens 3}
             (get-in versions [1 :usage])))
      (is (= 2 (count (:message-order (:session accepted)))))
      (is (empty? (history/check-invariants (:session accepted)))))))

(deftest session-run-port-keeps-compression-and-user-fields
  (let [base (-> (base-session)
                 (assoc :model-binding {:connection-id "conn-1"
                                        :model-entry-id "model-1"})
                 (add-message "m1" :user "question" {})
                 (add-message "m2" :assistant "answer" {}))
        prepared (command base :history/prepare-compression
                          {:compression-id "cmp-1"
                           :message-ids ["m1" "m2"]
                           :summary "summary of the first turn"}
                          2000)
        applied (command (:session prepared) :history/apply-compression
                         {:compression-id "cmp-1"}
                         3000)
        session (add-message (:session applied) "m3" :user "next"
                             {:attachments [{:attachment-id "a1"
                                             :name "a.png"}]
                              :citations [{:url "https://source.test"}]
                              :quotes [{:text "quoted text"}]})
        out (context/outbound-context session)
        started (runs/start-run
                 session
                 {:run-id "r1"
                  :call-id "c1"
                  :model-binding {:connection-id "conn-1"
                                  :model-entry-id "model-1"}
                  :message-ids (:message-ids out)
                  :context (select-keys out [:messages :message-ids
                                             :summary-ids :version-ids
                                             :excluded :pinned-attachments
                                             :warnings])
                  :now 4000})
        effect (first (:effects started))
        claimed (run-port/start (:session started) effect
                                {:now 5000
                                 :id-source (ids/counter-source {:prefix "cp"})})
        messages (get-in claimed [:request :snapshot :messages])]
    (is (:ok? claimed) (pr-str (:error claimed)))
    (is (true? (get-in claimed [:request :snapshot :user-turn?])))
    (is (false? (get-in claimed [:request :snapshot :regenerate?])))
    (is (= [{:role :system :text "summary of the first turn"}]
           (subvec messages 0 1)))
    (is (= "next" (:text (second messages))))
    (is (= ["a1"] (mapv :attachment-id (:attachments (second messages)))))
    (is (= [{:url "https://source.test"}]
           (:citations (second messages))))
    (is (= [{:text "quoted text"}] (:quotes (second messages))))))

(deftest compression-id-collision-and-completed-answer-are-refused
  (let [session (-> (base-session)
                    (add-message "m1" :user "question" {})
                    (add-message "m2" :assistant "final answer" {}))
        prepared (command session :history/prepare-compression
                          {:compression-id "cmp-1"
                           :message-ids ["m1" "m2"]
                           :summary "summary"}
                          2000)
        duplicate (command (:session prepared) :history/prepare-compression
                           {:compression-id "cmp-1"
                            :message-ids ["m1" "m2"]
                            :summary "another"}
                           3000)
        late (command (:session prepared) :history/append-message
                      {:message-id "m2"
                       :role :assistant
                       :content "different completed answer"}
                      4000)]
    (is (= :COMPRESSION_ID_TAKEN (get-in duplicate [:error :code])))
    (is (= "final answer" (get-in (view-of (:session late) "m2") [:content])))
    (is (= 1 (count (get-in (:session late) [:messages "m2" :versions]))))))

(deftest folded-message-is-not-editable
  (let [session (-> (base-session)
                    (add-message "m1" :user "question" {})
                    (assoc-in [:messages "m1" :folded?] true))
        result (command session :history/edit-message
                        {:message-id "m1" :content "changed"}
                        2000)]
    (is (false? (:ok? result)))
    (is (= :MESSAGE_FOLDED (get-in result [:error :code])))))

(deftest reverted-compression-does-not-freeze-message-visibility
  (let [session (-> (base-session)
                    (add-message "m1" :user "question" {})
                    (add-message "m2" :assistant "answer" {}))
        prepared (command session :history/prepare-compression
                          {:compression-id "cmp-1"
                           :message-ids ["m1" "m2"]
                           :summary "summary"}
                          2000)
        applied (command (:session prepared) :history/apply-compression
                         {:compression-id "cmp-1"}
                         3000)
        undone (command (:session applied) :history/undo-compression
                        {:compression-id "cmp-1"}
                        4000)
        deleted (sessions/apply-command (:session undone)
                                        {:kind :session/delete-message
                                         :message-id "m1"}
                                        {:now 5000})
        violations (history/check-invariants (:session deleted))]
    (is (:ok? deleted))
    (is (not-any? #(= :compression-message-not-visible (:invariant %))
                  violations))))

(deftest editing-a-message-cancels-an-affected-run-without-replay
  (let [session (-> (base-session)
                    (add-message "m1" :user "hello" {}))
        started (runs/start-run session
                                {:run-id "r1"
                                 :call-id "c1"
                                 :model-binding {:connection-id "conn-1"
                                                 :model-entry-id "model-1"}
                                 :message-ids ["m1"]
                                 :policy {}
                                 :language :en
                                 :now 2000})
        running (:session started)
        edited (command running :history/edit-message
                        {:message-id "m1" :content "changed"} 3000)]
    (is (:ok? started))
    (is (:ok? edited))
    (is (= :cancelled (get-in (:session edited) [:runs "r1" :state])))
    (is (= :input-changed (get-in (:session edited) [:runs "r1"
                                                     :cancel-reason])))
    (is (= :cancelled (get-in (:session edited) [:calls "c1" :state])))
    (is (not-any? #(= :run-started (:kind %)) (:effects edited)))
    (is (= 2 (count (message/message-history (:session edited) "m1"))))
    (is (= "hello" (get-in (message/message-history (:session edited) "m1")
                           [0 :content])))))

(deftest context-boundary-and-recent-rounds-change-only-the-outbound-projection
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {:attachments [a1]})
                    (add-message "m2" :assistant "one answer" {})
                    (add-message "m3" :user "two" {})
                    (add-message "m4" :assistant "two answer" {}))
        all-reading (context/reading-context session)
        bounded (command session :history/set-context-boundary
                         {:message-id "m2"} 2000)
        bounded-session (:session bounded)
        bounded-out (context/outbound-context bounded-session)
        recent (command bounded-session :history/set-recent-rounds
                        {:recent-rounds 1} 3000)
        recent-out (context/outbound-context (:session recent))
        cleared (command (:session recent) :history/clear-context-boundary
                         {} 4000)
        cleared-out (context/outbound-context (:session cleared))]
    (is (= ["m1" "m2" "m3" "m4"] (:message-ids all-reading)))
    (is (= ["m2" "m3" "m4"] (:message-ids bounded-out)))
    (is (= [{:message-id "m1" :reason :before-context-boundary}]
           (:excluded bounded-out)))
    (is (= ["m3" "m4"] (:message-ids recent-out)))
    (is (= ["m3" "m4"] (:message-ids cleared-out)))
    (is (= 4 (count (:message-ids (context/reading-context (:session cleared))))))
    (is (= (:activity-at session) (:activity-at (:session cleared))))
    (testing "a stale boundary never silently expands the send range"
      (let [stale (assoc-in (:session cleared) [:context :boundary]
                            {:message-id "gone"})
            out (context/outbound-context stale)]
        (is (= [] (:message-ids out)))
        (is (= :CONTEXT_BOUNDARY_MISSING (get-in out [:warnings 0 :code])))))
    (is (empty? (history/check-invariants (:session cleared))))))

(deftest pinned-attachments-survive-boundary-and-compression
  (let [session (-> (base-session)
                    (add-message "m1" :user "old" {:attachments [a1]})
                    (add-message "m2" :assistant "old answer" {})
                    (add-message "m3" :user "new" {:attachments [a2]}))
        pinned (command session :history/pin-attachment
                        {:attachment-id "a1"} 2000)
        bounded (command (:session pinned) :history/set-context-boundary
                         {:message-id "m3"} 3000)
        out (context/outbound-context (:session bounded))]
    (is (:ok? pinned))
    (is (= ["m3"] (:message-ids out)))
    (is (= ["a1"] (map :attachment-id (:pinned-attachments out))))
    (is (= "m1" (get-in out [:pinned-attachments 0 :message-id])))
    (testing "a pinned attachment that disappeared is reported, not hidden"
      (let [missing (assoc-in (:session bounded)
                              [:messages "m1" :versions 0 :attachments] [])]
        (is (= ["a1"]
               (get-in (context/outbound-context missing)
                       [:missing-pinned-attachments])))))
    (testing "an over-limit pin is an explicit warning, not a silent unpin"
      (let [out (context/outbound-context (:session bounded)
                                         {:attachment-limit 0})]
        (is (= :PINNED_ATTACHMENT_LIMIT_EXCEEDED
               (get-in out [:warnings 0 :code])))
        (is (= ["a1"] (map :attachment-id (:pinned-attachments out))))))))

(deftest compression-is-reviewed-reversible-and-never-deletes-messages
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {})
                    (add-message "m2" :assistant "one answer" {})
                    (add-message "m3" :user "two" {})
                    (add-message "m4" :assistant "two answer" {}))
        prepared (command session :history/prepare-compression
                          {:compression-id "c1"
                           :message-ids ["m1" "m2"]
                           :summary "draft summary"} 2000)
        drafted (:session prepared)
        draft-out (context/outbound-context drafted)
        edited (command drafted :history/update-compression-draft
                        {:compression-id "c1"
                         :summary "reviewed summary"} 3000)
        applied (command (:session edited) :history/apply-compression
                         {:compression-id "c1"} 4000)
        applied-session (:session applied)
        applied-out (context/outbound-context applied-session)
        later (add-message applied-session "m5" :user "after" {})
        later-out (context/outbound-context later)
        undone (command later :history/undo-compression
                        {:compression-id "c1"} 5000)
        undone-session (:session undone)
        undone-out (context/outbound-context undone-session)]
    (is (:ok? prepared))
    (is (= (:activity-at session) (:activity-at applied-session)))
    (is (= ["m1" "m2" "m3" "m4"] (:message-ids draft-out)))
    (is (= "reviewed summary"
           (get-in (:session edited) [:context :compressions "c1" :summary])))
    (is (= ["m3" "m4"] (:message-ids applied-out)))
    (is (= [:context-summary :user :assistant]
           (map #(or (:kind %) (:role %)) (:messages applied-out))))
    (is (= "reviewed summary" (get-in applied-out [:messages 0 :content])))
    (is (= ["m1" "m2"] (get-in applied-out [:messages 0 :source-message-ids])))
    (is (= ["c1"] (:summary-ids applied-out)))
    (testing "all original messages remain readable and persisted"
      (is (= ["m1" "m2" "m3" "m4" "m5"]
             (mapv :message-id (:messages (context/reading-context later)))))
      (is (= 5 (count (:messages later)))))
    (testing "messages appended after compression stay in the outbound range"
      (is (= ["m3" "m4" "m5"] (:message-ids later-out)))
      (is (= "reviewed summary" (get-in later-out [:messages 0 :content]))))
    (testing "undo restores the original range and keeps later messages"
      (is (= ["m1" "m2" "m3" "m4" "m5"] (:message-ids undone-out)))
      (is (= [] (:summary-ids undone-out)))
      (is (empty? (filter #(= :context-summary (:kind %))
                          (:messages undone-out)))
          "an undone compression must not emit a summary entry")
      (is (= :reverted (get-in undone-session
                               [:context :compressions "c1" :state]))))
    (is (empty? (history/check-invariants undone-session)))))

(deftest outbound-ignores-non-applied-compressions-listed-in-order
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {})
                    (add-message "m2" :assistant "one answer" {})
                    (add-message "m3" :user "two" {}))
        prepared (command session :history/prepare-compression
                          {:compression-id "c1"
                           :message-ids ["m1" "m2"]
                           :summary "draft summary"} 2000)
        applied (command (:session prepared) :history/apply-compression
                         {:compression-id "c1"} 3000)
        ;; A stale snapshot merge can leave a reverted id in
        ;; :compression-order; the outbound projection must still only
        ;; substitute records whose state is :applied.
        reverted (assoc-in (:session applied)
                           [:context :compressions "c1" :state] :reverted)
        out (context/outbound-context reverted)]
    (is (:ok? prepared))
    (is (:ok? applied))
    (is (= ["c1"] (context/compression-order reverted)))
    (is (= ["m1" "m2" "m3"] (:message-ids out)))
    (is (= [] (:summary-ids out)))
    (is (empty? (filter #(= :context-summary (:kind %)) (:messages out))))))

(deftest compression-rejects-overlap-and-non-contiguous-ranges
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {})
                    (add-message "m2" :assistant "one answer" {})
                    (add-message "m3" :user "two" {}))
        first (command session :history/prepare-compression
                       {:compression-id "c1"
                        :message-ids ["m1" "m2"]
                        :summary "summary one"} 2000)
        overlap (command (:session first) :history/prepare-compression
                         {:compression-id "c2"
                          :message-ids ["m2" "m3"]
                          :summary "summary two"} 3000)
        gap (command session :history/prepare-compression
                     {:compression-id "c3"
                      :message-ids ["m1" "m3"]
                      :summary "summary three"} 3000)]
    (is (= :COMPRESSION_RANGE_OVERLAP (get-in overlap [:error :code])))
    (is (= :COMPRESSION_RANGE_NOT_CONTIGUOUS (get-in gap [:error :code])))))

(deftest compression-summary-marks-changed-source-versions
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {})
                    (add-message "m2" :assistant "one answer" {}))
        prepared (command session :history/prepare-compression
                          {:compression-id "c1"
                           :message-ids ["m1" "m2"]
                           :summary "summary"} 2000)
        applied (command (:session prepared) :history/apply-compression
                         {:compression-id "c1"} 3000)
        edited (command (:session applied) :history/edit-message
                        {:message-id "m1" :content "one changed"} 4000)
        out (context/outbound-context (:session edited))]
    (is (true? (get-in out [:messages 0 :stale?])))))

(deftest branch-copies-the-path-and-preserves-attachment-identity
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {:attachments [a1]})
                    (add-message "m2" :assistant "one answer"
                                 {:attachments [a2] :usage usage})
                    (add-message "m3" :user "branch point" {}))
        branch-result (sessions/branch-session
                       session
                       {:session-id "s2"
                        :owner-id "o2"
                        :before-message-id "m3"
                        :id-source (ids/counter-source {:prefix "b"})
                        :now 5000})
        branch (:session branch-result)
        copied (mapv #(message/message-view (get-in branch [:messages %]))
                     (:message-order branch))]
    (is (:ok? branch-result))
    (is (= 2 (count copied)))
    (is (= ["one" "one answer"] (map :content copied)))
    (is (= ["a1"] (map :attachment-id (:attachments (first copied)))))
    (is (= ["a2"] (map :attachment-id (:attachments (second copied)))))
    (is (= "s1" (:origin-session-id branch)))
    (is (= "m3" (:origin-message-id branch)))
    (is (= {} (:runs branch)))
    (is (= [] (:run-order branch)))
    (is (= {} (:context branch)))
    (testing "fresh message and version identities, same attachment identities"
      (is (not (contains? (set (:message-order branch)) "m1")))
      (is (not= "m1-v1" (:version-id (first copied))))
      (is (= ["a1"] (attachments/ids (:attachments (first copied))))))))

(deftest branch-version-references-and-writes-are-isolated
  (let [session (-> (base-session)
                    (add-message "m1" :user "one" {:attachments [a1]})
                    (add-message "m2" :assistant "answer one"
                                 {:attachments [a2]
                                  :usage {:prompt-tokens 1 :completion-tokens 2}}))
        session (assoc-in session [:messages "m2" :run-id] "source-run-1")
        edited (command session :history/edit-message
                        {:message-id "m2"
                         :content "manual answer"}
                        2000)
        source (:session edited)
        branch-result (sessions/branch-session
                       source
                       {:session-id "branch-s1"
                        :owner-id "branch-o1"
                        :id-source (ids/counter-source {:prefix "branch"})
                        :now 3000})
        branch (:session branch-result)
        branch-m2 (get-in branch [:messages (second (:message-order branch))])
        source-m2 (get-in source [:messages "m2"])
        branch-versions (:versions branch-m2)
        source-versions (:versions source-m2)]
    (is (:ok? branch-result))
    (testing "version parent references are remapped to branch versions"
      (is (= (:version-id (first branch-versions))
             (get-in branch-versions [1 :parent-version-id])))
      (is (not= (get-in source-versions [0 :version-id])
                (get-in branch-versions [1 :parent-version-id]))))
    (testing "source run provenance is moved off the branch run namespace"
      (is (nil? (:run-id branch-m2)))
      (is (= "source-run-1" (:origin-run-id branch-m2))))
    (testing "editing the branch does not mutate the source versions"
      (let [branch-edited (command branch :history/edit-message
                                   {:message-id (:message-id branch-m2)
                                    :content "branch only"
                                    :attachments []}
                                   4000)]
        (is (= "manual answer"
               (get-in (:session branch-edited)
                       [:messages (:message-id branch-m2) :versions 1 :content])))
        (is (= [] (get-in (:session branch-edited)
                          [:messages (:message-id branch-m2)
                           :versions 2 :attachments])))
        (is (= ["a2"] (map :attachment-id
                           (get-in (:session branch-edited)
                                   [:messages (:message-id branch-m2)
                                    :versions 1 :attachments]))))
        (is (= "manual answer"
               (get-in source [:messages "m2" :versions 1 :content])))))
    (testing "source and branch attachment identities remain independent sets"
      (is (= ["a2"] (map :attachment-id (:attachments branch-m2))))
      (is (= ["a2"] (map :attachment-id (:attachments source-m2)))))))

(deftest delete-and-restore-preserve-versions-context-and-branch
  (let [session (-> (base-session)
                    (add-message "m1" :user "question" {})
                    (add-message "m2" :assistant "answer" {}))
        edited (command session :history/edit-message
                        {:message-id "m2" :content "edited answer"}
                        2000)
        bounded (command (:session edited) :history/set-context-boundary
                         {:message-id "m1"} 3000)
        prepared (command (:session bounded) :history/prepare-compression
                          {:compression-id "cmp-delete"
                           :message-ids ["m1" "m2"]
                           :summary "summary before delete"}
                          4000)
        applied (command (:session prepared) :history/apply-compression
                         {:compression-id "cmp-delete"} 5000)
        source (:session applied)
        branch (:session (sessions/branch-session
                          source
                          {:session-id "branch-delete"
                           :owner-id "branch-delete-owner"
                           :id-source (ids/counter-source {:prefix "bd"})
                           :now 6000}))
        deleted (sessions/apply-command source {:kind :session/delete}
                                        {:now 7000})
        refused (command (:session deleted) :history/edit-message
                         {:message-id "m2" :content "must not write"}
                         8000)
        restored (sessions/apply-command (:session deleted)
                                         {:kind :session/restore}
                                         {:now 9000})
        restored-session (:session restored)
        out (context/outbound-context restored-session)]
    (is (:ok? deleted))
    (is (= :deleted (get-in deleted [:session :status])))
    (is (= :SESSION_DELETED (get-in refused [:error :code])))
    (is (:ok? restored))
    (is (= :active (get-in restored [:session :status])))
    (is (nil? (get-in restored [:session :deleted-at])))
    (testing "versions, boundary and compression survive restore"
      (is (= 2 (count (message/message-history restored-session "m2"))))
      (is (= "edited answer"
             (get-in restored-session [:messages "m2" :versions 1 :content])))
      (is (= "m1" (get-in restored-session [:context :boundary :message-id])))
      (is (= :applied (get-in restored-session
                              [:context :compressions "cmp-delete" :state])))
      (is (= ["cmp-delete"] (:summary-ids out))))
    (testing "the branch stays readable after the source is deleted"
      (is (= 2 (count (:message-order branch))))
      (is (= ["question" "edited answer"]
             (mapv #(:content (message/message-view
                               (get-in branch [:messages %])))
                   (:message-order branch))))
      (is (= "s1" (:origin-session-id branch)))
      (is (= {} (:runs branch)))
      (is (= {} (:context branch))))))



(defn- get-view-attachments
  [state]
  (let [session (if (contains? state :session) (:session state) state)]
    (:attachments (view-of session "m1"))))

(deftest owner-routes-history-and-edit-commands
  (let [session (-> (base-session)
                    (add-message "m1" :user "hello" {:attachments [a1]}))
        state (owner-state/initial-state
               {:session-id "s1" :initial-session session :now 1000})
        port (sessions/session-port {:delegate history/apply-command})
        ctx {:now 2000
             :session-port port
             :registry (owner-commands/local-registry)}
        edited (owner-handlers/apply-message
                state
                {:type :command
                 :command-id "cmd-edit"
                 :command-epoch 1
                 :payload {:kind :session/edit-message
                           :session-id "s1"
                           :message-id "m1"
                           :content "hello edited"
                           :attachments []}}
                ctx)
        edited-session (get-in edited [:state :session])
        selected (owner-handlers/apply-message
                  (:state edited)
                  {:type :command
                   :command-id "cmd-select"
                   :command-epoch 1
                   :payload {:kind :history/select-version
                             :session-id "s1"
                             :message-id "m1"
                             :version-id "m1-v1"}}
                  ctx)]
    (is (= :accepted (get-in edited [:receipt :status])))
    (is (= 2 (count (message/message-history edited-session "m1"))))
    (is (= [] (get-view-attachments edited-session)))
    (is (= :accepted (get-in selected [:receipt :status])))
    (is (= 0 (get-in selected [:state :session :messages "m1"
                               :current-version])))
    (is (= [a1] (get-view-attachments (:state selected))))))
