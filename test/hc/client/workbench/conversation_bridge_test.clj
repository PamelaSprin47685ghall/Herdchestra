(ns hc.client.workbench.conversation-bridge-test
  "Workbench integration for the conversation reducer.

  These tests exercise the same path the browser uses: an operator action
  updates `:ui/conversation`, the adapter mirrors the flat keys, a server
  receipt returns through `events` as `:composer/ack`, and the mirror is
  refreshed.  They deliberately do not test the transport itself."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.conversation :as conversation]
            [hc.client.conversation.draft :as draft]
            [hc.client.workbench.conversation-state :as conv-state]
            [hc.client.workbench.events :as events]))

(defn- base-db
  []
  {:ui/conversation conversation/initial-state
   :ui/selected-session-id "s1"
   :ui/draft {"s1" (assoc draft/empty-draft :text "old" :version 2)}
   :ui/stream {}
   :ui/ask {}
   :ui/command {}
   :ui/connection {:phase :synced :command-allowed? true}
   :ui/platform :desktop
   :ui/viewport-width 1280
   :session/by-id {"s1" {:session-id "s1"
                         :command-epoch 11
                         :model-binding {:model-entry-id "m1"
                                         :display-name "Model"}}}
   :message/by-id {}
   :message/order []})

(defn- apply!
  [db actions]
  (conv-state/apply-actions db {:session-id "s1" :epoch 11} actions))

(defn- last-command
  [db kind]
  (->> (vals (get-in db [:ui/conversation :commands]))
       (filter #(= kind (:kind %)))
       (sort-by :sequence)
       last))

(defn- receipt
  [command-id status]
  {:type "CUSTOM"
   :name "hc/command-receipt"
   :value {:ok? (contains? #{:accepted :applied} status)
           :command-id command-id
           :receipt {:status status :command-id command-id}}})

(defn- handle-event
  "The browser path: `events` reduces messages/notices, then the conversation
  listener mirrors the reducer state."
  [db event]
  (let [db (:db (events/apply-event db event))]
    (conv-state/apply-event-db db event)))

(defn- send-state
  "Reducer state for a direct composer/send action."
  [version text]
  (-> conversation/initial-state
      (assoc :session-id "s1"
             :session {:model {:id "m1"} :run-status :finished}
             :connection {:phase :synced :command-allowed? true}
             :model {:id "m1"}
             :drafts {"s1" (assoc draft/empty-draft
                                   :text text
                                   :cursor (count text)
                                   :selection {:start (count text)
                                               :end (count text)}
                                   :version version
                                   :saved-version version
                                   :save-state :saved)})))

(deftest stale-snapshot-version-with-same-content-clears-the-live-draft
  (let [state (send-state 4 "same text")
        snapshot {:text "same text" :version 3
                  :attachments [] :quotes []}
        next (conversation/apply-action
              state
              {:type :composer/send :session-id "s1"
               :snapshot snapshot :at "t1"})
        command (last (:outbox next))]
    (is (= 4 (:submitted-version command))
        "the live version wins when the content is identical")
    (is (= 4 (get-in command [:payload :version])))
    (is (= "" (get-in next [:drafts "s1" :text]))
        "the composer is cleared even though the snapshot version lagged")
    (is (= 5 (get-in next [:drafts "s1" :version])))))

(deftest captured-send-keeps-newer-draft-when-content-differs
  (let [state (send-state 5 "new text")
        snapshot {:text "old text" :version 3
                  :attachments [] :quotes []}
        next (conversation/apply-action
              state
              {:type :composer/send :session-id "s1"
               :snapshot snapshot :at "t1"})
        command (last (:outbox next))]
    (is (= 3 (:submitted-version command))
        "a genuine newer edit keeps the captured version guard")
    (is (= "new text" (get-in next [:drafts "s1" :text])))
    (is (= 5 (get-in next [:drafts "s1" :version])))
    (is (= 3 (get-in next [:drafts "s1" :submitted-version])))
    (is (= "old text" (get-in command [:payload :text])))))

(deftest send-receipt-keeps-the-cleared-draft
  (let [db (apply! (base-db)
                   [{:type :draft/edit :session-id "s1"
                     :text "hello" :cursor 5 :at "t1"}
                    {:type :composer/send :session-id "s1" :at "t2"}])
        command (last-command db :send)]
    (is (some? command) "the send action queues one command")
    (is (= "hello" (get-in command [:payload :text])))
    (is (empty? (get-in db [:ui/draft "s1" :text]))
        "the admitted version is cleared at queue time")
    (is (= :submitting (get-in db [:ui/command (:command-id command) :status])))
    (let [db (handle-event db (receipt (:command-id command) :accepted))]
      (is (= :accepted (get-in db [:ui/command (:command-id command) :status])))
      (is (= :accepted (get-in db [:ui/conversation :commands
                                   (:command-id command) :status])))
      (is (empty? (get-in db [:ui/draft "s1" :text]))
          "an accepted receipt never resurrects the sent text"))))

(deftest bare-command-failure-restores-the-cleared-draft
  (let [db (apply! (base-db)
                   [{:type :draft/edit :session-id "s1"
                     :text "hello" :cursor 5 :at "t1"}
                    {:type :composer/send :session-id "s1" :at "t2"}])
        command (last-command db :send)]
    (is (empty? (get-in db [:ui/draft "s1" :text])))
    (let [db (handle-event
              db
              {:type "CUSTOM"
               :name "hc/command-receipt"
               :value {:ok? false
                       :command-id (:command-id command)
                       :error {:code :COMMAND_STORE_FAILED
                               :message "command state could not be committed"}}})]
      (is (= :rejected (get-in db [:ui/command (:command-id command) :status]))
          "a bare gateway failure is a rejected command")
      (is (= "hello" (get-in db [:ui/draft "s1" :text]))
          "the optimistically cleared draft is restored from the command snapshot")
      (is (= :unsaved (get-in db [:ui/draft "s1" :save-state]))))))

(deftest stop-and-send-sends-the-captured-snapshot
  (let [db (-> (base-db)
               (assoc-in [:ui/conversation :streams "s1"]
                         {:streaming? true :run-id "run-1"})
               (assoc-in [:ui/draft "s1"]
                         (assoc draft/empty-draft :text "first" :version 2)))
        db (apply! db
                   [{:type :composer/stop-and-send :session-id "s1" :at "t1"}
                    {:type :draft/edit :session-id "s1"
                     :text "second" :cursor 6 :at "t2"}])
        stop (last-command db :stop)]
    (is (some? stop))
    (is (= "first" (get-in db [:ui/conversation :streams "s1"
                               :pending-send :snapshot :text])))
    (let [db (handle-event db (receipt (:command-id stop) :accepted))
          send (last-command db :send)]
      (is (some? send) "the accepted stop releases the captured send")
      (is (= "first" (get-in send [:payload :text]))
          "the server receives the snapshot captured at decision time")
      (is (= "second" (get-in db [:ui/draft "s1" :text]))
          "text typed while the stop was in flight survives"))))

(deftest late-tool-result-carries-its-message-id
  (let [db (-> (base-db)
               (assoc-in [:ui/conversation :streams "s1"]
                         {:streaming? false
                          :stop-state :stopped
                          :active-message-id "m1"
                          :run-id "run-1"}))
        db (handle-event
            db
            {:type "TOOL_CALL_RESULT"
             :session-id "s1"
             :message-id "m2"
             :tool-call-id "tool-1"
             :status "succeeded"
             :completed-before-stop? true
             :at "t3"})
        late (last (get-in db [:ui/conversation :streams "s1"
                               :late-tool-results]))]
    (is (= "m2" (:message-id late))
        "the late result belongs to the message that produced it")
    (is (= "tool-1" (:tool-call-id late)))
    (is (= "停止前已执行，结果后来返回" (:label late)))
    (is (= :stopped (get-in db [:ui/conversation :streams "s1" :stop-state])))))

(deftest rejected-stop-resumes-the-composer
  (let [db (-> (base-db)
               (assoc-in [:ui/draft "s1"]
                         (assoc draft/empty-draft :text "hello" :version 2)))
        db (apply! db [{:type :composer/stop :session-id "s1" :at "t1"}])
        stop (last-command db :stop)]
    (is (some? stop))
    (is (= :stopping (get-in db [:ui/conversation :streams "s1" :stop-state])))
    (let [db (handle-event db (receipt (:command-id stop) :rejected))]
      (is (false? (get-in db [:ui/conversation :streams "s1" :streaming?]))
          "a rejected stop on an idle stream restores idle, not generating")
      (is (not= :stopping (get-in db [:ui/conversation :streams "s1" :stop-state])))
      (let [db (apply! db [{:type :composer/send :session-id "s1" :at "t2"}])]
        (is (some? (last-command db :send))
            "the operator can send again after a refused stop")))))

(deftest skip-ask-records-a-visible-skip
  (let [db (apply! (base-db)
                   [{:type :ask/show
                     :session-id "s1"
                     :ask {:ask-id "a1"
                           :session-id "s1"
                           :name "plan"
                           :question "继续吗？"
                           :options [{:value "yes" :label "继续"}]}}])
        db (apply! db [{:type :ask/skip :session-id "s1"
                        :ask-id "a1" :at "t2"}])
        command (last-command db :unpark)
        card (get-in db [:ui/conversation :asks "s1" "a1"])]
    (is (some? command))
    (is (= {:skipped? true} (get-in command [:payload :value])))
    (is (= :answer-ask
           (get-in db [:ui/command (:command-id command) :kind])))
    (is (= "s1" (get-in db [:ui/ask "a1" :session-id]))
        "the session view filters ask cards by this id")
    (is (= :submitting (:status card)))
    (is (true? (get-in card [:last-answer :skipped?])))
    (is (= :skipped (get-in card [:last-outcome :result])))))

(deftest send-then-stop-use-distinct-stable-command-ids
  (testing "each operator action gets its own command id and the stop payload
            is frozen at enqueue time, so a replay cannot change it"
    (let [db (base-db)
          db (apply! db
                     [{:type :draft/edit :session-id "s1"
                       :text "hello" :cursor 5 :at "t1"}
                      {:type :composer/send :session-id "s1" :at "t2"}])
          send (last-command db :send)
          ;; A RUN_STARTED event from the server supplies the run identity.
          ;; The operator's stop must freeze that identity at queue time.
          db (assoc-in db [:ui/conversation :streams "s1" :run-id] "run-1")
          db (apply! db
                     [{:type :composer/stop :session-id "s1"
                       :source :operator-stop :at "t3"}])
          stop (last-command db :stop)]
      (is (some? send))
      (is (some? stop))
      (is (not= (:command-id send) (:command-id stop))
          "the stop must not reuse the send command id")
      (is (= "run-1" (get-in stop [:payload :run-id]))
          "the run id is captured when the stop is queued")
      (is (= :operator-stop (get-in stop [:payload :origin])))
      ;; Reading the same command after the live stream moved on must return
      ;; the same payload.  A replayed envelope therefore carries identical
      ;; bytes and cannot trip the Owner's payload-conflict check.
      (let [later (assoc-in db [:ui/conversation :streams "s1" :run-id]
                            "run-2")
            replayed (last-command later :stop)]
        (is (= (:command-id stop) (:command-id replayed)))
        (is (= (:payload stop) (:payload replayed)))))))

(deftest operator-button-stop-uses-the-standard-origin
  (let [db (apply! (base-db)
                   [{:type :composer/stop :session-id "s1"
                     :source :operator-button :at "t1"}])
        stop (last-command db :stop)]
    (is (some? stop) "the operator button queues a stop instead of crashing")
    (is (= :operator-stop (get-in stop [:payload :origin])))))

(deftest captured-send-snapshot-carries-turn-selection-keys
  (testing "D-27: the send payload must carry the turn selection the
  materials bridge later compares against, or the per-turn cards are never
  cleared and leak into every later submission."
    (let [db (apply! (base-db)
                     [{:type :composer/send :session-id "s1" :at "t1"
                       :snapshot {:text "hello" :cursor 5 :version 3
                                  :turn-card-ids ["c-1"]
                                  :instruction-values {"tone" "dark"}
                                  :turn-selection-version 7
                                  :temporary-read? true}}])
          send (last-command db :send)]
      (is (some? send))
      (is (= ["c-1"] (get-in send [:payload :turn-card-ids])))
      (is (= {"tone" "dark"} (get-in send [:payload :instruction-values])))
      (is (= 7 (get-in send [:payload :turn-selection-version])))
      (is (true? (get-in send [:payload :temporary-read?]))))))
