(ns hc.server.sessions.sessions-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.server.app.session-config :as session-config]
            [hc.server.commands :as commands]
            [hc.server.history :as history]
            [hc.server.history.snapshot :as snapshot]
            [hc.server.sessions :as sessions]
            [hc.server.blob-store.refs :as blob-refs]
            [hc.server.sessions.config :as config]
            [hc.server.sessions.connections :as connections]
            [hc.server.sessions.drafts :as drafts]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.runs :as runs]
            [hc.server.sessions.test-support :as support]))

(defn- session-with-model
  ([] (session-with-model {}))
  ([opts]
   (let [context (support/build-context)]
     {:context context
      :session (support/new-test-session context opts)})))

(deftest a-session-copies-its-model-binding-at-creation
  (let [{:keys [context session]} (session-with-model)
        binding (:model-binding session)
        changed (config/set-assistant-default-model
                 (:config context)
                 (:assistant-id context)
                 (:connection-id context)
                 (:model-entry-id context)
                 2000)]
    (is (= (:model-entry-id context) (:model-entry-id binding)))
    (is (:ok? changed))
    (is (= binding (:model-binding session)))))

(deftest a-session-can-exist-without-a-model
  (let [source (ids/counter-source)
        created (sessions/new-session {:config nil
                                       :id-source source
                                       :now 1000
                                       :client-id "w1"})]
    (is (:ok? created))
    (is (nil? (get-in created [:session :model-binding])))
    (let [session (support/attach-gate (:session created))
          sent (support/execute session :session/send-message
                                {:text "hello"}
                                {:now 2000 :config nil :id-source source})]
      (is (not (:ok? sent)))
      (is (= :MODEL_NOT_SELECTED (get-in sent [:error :code])))
      (is (= "hello" (get-in sent [:session :drafts "w1" :text]))))))

(deftest commands-are-deduplicated-before-business
  (let [{:keys [session]} (session-with-model)
        request {:command-id "cmd-1"
                 :command-epoch 1
                 :client-id "w1"
                 :type :session/rename
                 :payload {:title "First"}}
        first (support/execute-request session request {:now 2000})
        replay (support/execute-request (:session first) request {:now 3000})
        conflict (support/execute-request (:session replay)
                                   (assoc request :payload {:title "Second"})
                                   {:now 4000})]
    (is (:ok? first))
    (is (false? (:replayed? first)))
    (is (:ok? replay))
    (is (true? (:replayed? replay)))
    (is (= (:receipt first) (:receipt replay)))
    (is (= "First" (get-in replay [:session :title])))
    (is (= (:revision (:session first)) (:revision (:session replay))))
    (is (not (:ok? conflict)))
    (is (= :COMMAND_ID_PAYLOAD_CONFLICT (get-in conflict [:error :code])))))

(deftest a-retired-epoch-requires-resync
  (let [{:keys [session]} (session-with-model)
        first (support/execute session "cmd-1" 1 :session/rename
                               {:title "First"} {:now 2000})
        issued (support/issue-epoch (:session first) 3000)
        old (support/execute-request (:session issued)
                                     {:command-id "cmd-2"
                                      :command-epoch 1
                                      :client-id "w1"
                                      :type :session/rename
                                      :payload {:title "Old"}}
                                     {:now 4000})]
    (is (= 2 (:epoch issued)))
    (is (= 1 (:retired-epoch issued)))
    (is (not (:ok? old)))
    (is (= :COMMAND_EPOCH_RETIRED (get-in old [:error :code])))
    (is (= :resync-state (get-in old [:error :details :action])))))

(deftest drafts-are-per-client-and-only-saved-after-ack
  (let [{:keys [session]} (session-with-model)
        update-a (support/execute session :session/update-draft
                                  {:client-id "w1" :text "from one"}
                                  {:now 2000})
        update-b (support/execute (:session update-a) :session/update-draft
                                  {:client-id "w2" :text "from two"}
                                  {:now 2100 :client-id "w2"})
        revision (get-in update-b [:session :drafts "w1" :revision])
        confirmed (sessions/confirm-draft-saved (:session update-b)
                                                "w1" revision 3000)]
    (is (:ok? update-a))
    (is (:ok? update-b))
    (is (= "from one" (get-in update-b [:session :drafts "w1" :text])))
    (is (= "from two" (get-in update-b [:session :drafts "w2" :text])))
    (is (not (drafts/saved? (get-in update-b [:session :drafts "w1"]))))
    (is (:ok? confirmed))
    (is (drafts/saved? (get-in confirmed [:session :drafts "w1"])))
    (is (= revision (get-in confirmed [:session :drafts "w1"
                                       :persisted-revision])))
    (let [failed (sessions/fail-draft-save (:session confirmed)
                                           "w2"
                                           (get-in confirmed
                                                   [:session :drafts "w2" :revision])
                                           {:code :STORAGE_FULL
                                            :message "disk full"}
                                           4000)]
      (is (:ok? failed))
      (is (= :error (get-in failed [:session :drafts "w2" :save-status])))
      (is (not (drafts/saved? (get-in failed [:session :drafts "w2"]))))
      (is (= "from two" (get-in failed [:session :drafts "w2" :text]))))))

(deftest send-message-creates-a-message-run-and-ready-call
  (let [{:keys [context session]} (session-with-model)
        sent (support/execute session :session/send-message
                              {:text "hello"}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)})
        result (:receipt sent)
        message-id (get-in result [:result :message-id])
        run-id (get-in result [:result :run-id])
        call-id (get-in result [:result :call-id])]
    (is (:ok? sent))
    (is (= "hello" (get-in sent [:session :messages message-id
                                 :versions 0 :content])))
    (is (= :running (get-in sent [:session :runs run-id :state])))
    (is (= :ready (get-in sent [:session :calls call-id :state])))
    (is (= [message-id] (get-in sent [:session :runs run-id
                                      :input-snapshot :message-ids])))
    (is (drafts/blank? (get-in sent [:session :drafts "w1"])))
    (is (= :pending (get-in sent [:session :drafts "w1" :save-status])))
    (is (empty? (sessions/check-invariants (:session sent))))))

(deftest editing-a-message-cancels-the-affected-run
  (let [{:keys [context session]} (session-with-model)
        sent (support/execute session :session/send-message
                              {:text "question"}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)})
        message-id (get-in sent [:receipt :result :message-id])
        run-id (get-in sent [:receipt :result :run-id])
        call-id (get-in sent [:receipt :result :call-id])
        claimed (runs/claim-call (:session sent)
                                 {:call-id call-id
                                  :attempt-id "attempt-1"
                                  :now 3000})
        edited (support/execute (:session claimed) :session/edit-message
                                {:message-id message-id
                                 :content "question, revised"}
                                {:now 4000
                                 :config (:config context)
                                 :id-source (:source context)})]
    (is (:ok? edited))
    (is (= run-id (get-in edited [:receipt :result :cancelled-run-id])))
    (is (= :cancelled (get-in edited [:session :runs run-id :state])))
    (is (true? (get-in edited [:session :calls call-id :cancel-requested?])))
    (is (some #(= :cancel-attempt (:kind %)) (:effects edited)))
    (is (= 2 (count (get-in edited [:session :messages message-id :versions]))))
    (is (= "question" (get-in edited [:session :messages message-id
                                      :versions 0 :content])))
    (let [late (sessions/accept-model-event
                (:session edited)
                {:kind :completed
                 :attempt-id "attempt-1"
                 :content "late answer"}
                {:now 5000 :id-source (:source context)})]
      (is (:ok? late))
      (is (:late? late))
      (is (nil? (:message-id late)))
      (is (= 1 (count (get-in late [:session :messages]))))
      (is (= :cancelled (get-in late [:session :runs run-id :state]))))))

(deftest deleting-a-message-does-not-resurrect-it
  (let [{:keys [context session]} (session-with-model)
        sent (support/execute session :session/send-message
                              {:text "delete me"}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)})
        message-id (get-in sent [:receipt :result :message-id])
        call-id (get-in sent [:receipt :result :call-id])
        claimed (runs/claim-call (:session sent)
                                 {:call-id call-id
                                  :attempt-id "attempt-1"
                                  :now 3000})
        deleted (support/execute (:session claimed) :session/delete-message
                                 {:message-id message-id}
                                 {:now 4000
                                  :config (:config context)
                                  :id-source (:source context)})]
    (is (:ok? deleted))
    (is (empty? (sessions/visible-messages (:session deleted))))
    (is (= 1 (count (sessions/deleted-messages (:session deleted)))))
    (let [chunk (sessions/accept-model-event
                 (:session deleted)
                 {:kind :chunk
                  :attempt-id "attempt-1"
                  :text "late chunk"}
                 {:now 5000 :id-source (:source context)})
          terminal (sessions/accept-model-event
                    (:session chunk)
                    {:kind :completed
                     :attempt-id "attempt-1"
                     :content "late answer"}
                    {:now 6000 :id-source (:source context)})]
      (is (false? (:visible? chunk)))
      (is (nil? (:message-id terminal)))
      (is (empty? (sessions/visible-messages (:session terminal))))
      (is (= 1 (count (get-in terminal [:session :messages]))))
      (is (= :cancelled (get-in terminal [:session :runs
                                          (get-in sent [:receipt :result :run-id])
                                          :state]))))))

(deftest model-switch-during-a-run-never-mutates-the-run-snapshot
  (let [{:keys [context session]} (session-with-model)
        source (:source context)
        second-conn (get-in (connections/new-connection
                             {:connection-id "conn-2"
                              :name "Work"
                              :kind :preset
                              :default-base-url "https://work.example"
                              :now 1000})
                            [:connection])
        with-second (:config (config/add-connection (:config context)
                                                    second-conn 1500))
        refreshed (connections/refresh-models
                   (get-in with-second [:connections "conn-2"])
                   [{:provider-model-id "work-model"
                     :display-name "Work Model"
                     :capabilities {:text true}}]
                   source 1600)
        config' (assoc-in with-second [:connections "conn-2"]
                          (:connection refreshed))
        second-model (first (get-in refreshed [:connection :model-order]))
        sent (support/execute session :session/send-message
                              {:text "question"}
                              {:now 2000 :config config' :id-source source})
        run-id (get-in sent [:receipt :result :run-id])
        call-id (get-in sent [:receipt :result :call-id])
        original-binding (get-in sent [:session :runs run-id :model-binding])
        next-round (support/execute (:session sent) :session/select-model
                                    {:connection-id "conn-2"
                                     :model-entry-id second-model
                                     :mode :next-round}
                                    {:now 3000 :config config'
                                     :id-source source})]
    (is (:ok? next-round))
    (is (= :next-round (get-in next-round [:receipt :result :effective])))
    (is (= original-binding (get-in next-round [:session :runs run-id
                                                :model-binding])))
    (is (= "conn-2" (get-in next-round [:session :pending-model-binding
                                        :binding :connection-id])))
    (let [stopped (support/execute (:session next-round) :session/select-model
                                   {:connection-id "conn-2"
                                    :model-entry-id second-model
                                    :mode :stop-and-switch}
                                   {:now 4000 :config config'
                                    :id-source source})]
      (is (:ok? stopped))
      (is (= run-id (get-in stopped [:receipt :result :stopped-run-id])))
      (is (= :cancelled (get-in stopped [:session :runs run-id :state])))
      (is (= original-binding (get-in stopped [:session :runs run-id
                                               :model-binding])))
      (is (= "conn-2" (get-in stopped [:session :pending-model-binding
                                        :binding :connection-id]))))))

(deftest next-round-model-binding-is-adopted-by-the-next-message
  (let [{:keys [context session]} (session-with-model)
        source (:source context)
        second-conn (get-in (connections/new-connection
                             {:connection-id "conn-2"
                              :name "Work"
                              :kind :preset
                              :default-base-url "https://work.example"
                              :now 1000})
                            [:connection])
        with-second (:config (config/add-connection (:config context)
                                                    second-conn 1500))
        refreshed (connections/refresh-models
                   (get-in with-second [:connections "conn-2"])
                   [{:provider-model-id "work-model"
                     :display-name "Work Model"}]
                   source 1600)
        config' (assoc-in with-second [:connections "conn-2"]
                          (:connection refreshed))
        second-model (first (get-in refreshed [:connection :model-order]))
        selected (support/execute session :session/select-model
                                  {:connection-id "conn-2"
                                   :model-entry-id second-model
                                   :mode :next-round}
                                  {:now 2000 :config config' :id-source source})
        sent (support/execute (:session selected) :session/send-message
                              {:text "hello"}
                              {:now 3000 :config config' :id-source source})]
    (is (:ok? selected))
    (is (= :now (get-in selected [:receipt :result :effective])))
    (is (= "conn-2" (get-in sent [:session :model-binding :connection-id])))
    (is (nil? (:pending-model-binding (:session sent))))
    (is (= "conn-2" (get-in sent [:session :runs
                                  (get-in sent [:receipt :result :run-id])
                                  :model-binding :connection-id])))))

(deftest select-skills-writes-the-session-extension-scope
  (let [{:keys [session]} (session-with-model)
        custom (support/execute session :session/select-skills
                                {:mode :custom
                                 :skills ["skill-b" "skill-a" "skill-a"]}
                                {:now 2000})
        scope (get-in custom [:session :mcp-session-scope])]
    (is (:ok? custom))
    (is (= :now (get-in custom [:receipt :result :effective])))
    (is (= :custom (:mode scope)))
    (is (= #{"skill-a" "skill-b"} (:skills scope))
        "ids are de-duplicated and sorted")
    (let [follow (support/execute (:session custom) :session/select-skills
                                  {:mode :follow} {:now 3000})]
      (is (:ok? follow))
      (is (= :follow (get-in follow [:session :mcp-session-scope :mode])))
      (is (not (contains? (get-in follow [:session :mcp-session-scope])
                          :skills))
          "follow mode drops the custom skill set"))
    (let [bad-mode (support/execute session :session/select-skills
                                    {:mode :sometimes} {:now 4000})
          bad-skills (support/execute session :session/select-skills
                                      {:mode :custom :skills "not-a-list"}
                                      {:now 5000})]
      (is (= :SKILL_SELECTION_MODE_INVALID (get-in bad-mode [:error :code])))
      (is (= :SKILL_SELECTION_INVALID (get-in bad-skills [:error :code]))))))

(deftest manual-title-wins-over-a-late-auto-title
  (let [{:keys [session]} (session-with-model)
        renamed (support/execute session :session/rename
                                 {:title "Manual"}
                                 {:now 2000})
        suggested (sessions/suggest-title (:session renamed)
                                          "Automatic"
                                          3000)]
    (is (:ok? renamed))
    (is (= "Manual" (get-in renamed [:session :title])))
    (is (= :manual (get-in renamed [:session :title-source])))
    (is (= :manual-title (:ignored suggested)))
    (is (= "Manual" (get-in suggested [:session :title])))
    (is (= (:activity-at (:session renamed))
           (get-in suggested [:session :activity-at])))))

(deftest listing-orders-pinned-and-content-activity
  (let [{:keys [session]} (session-with-model)
        other (assoc session
                     :session-id "session-2"
                     :owner-id "owner-2"
                     :title "Other"
                     :created-at 1000
                     :activity-at 5000
                     :pinned? false)
        first (assoc session
                     :session-id "session-1"
                     :owner-id "owner-1"
                     :title "First"
                     :created-at 2000
                     :activity-at 3000
                     :pinned? true)
        ordered (sessions/list-sessions [other first] {})]
    (is (= ["session-1" "session-2"] (mapv :session-id ordered)))))

(deftest blank-session-reuse
  (let [{:keys [session]} (session-with-model)
        blank (assoc session :session-id "blank" :owner-id "owner-blank")
        reused (sessions/find-reusable-blank-session [blank])
        with-draft (assoc-in blank [:drafts "w1" :text] "typing")]
    (is (= "blank" (:session-id reused)))
    (is (nil? (sessions/find-reusable-blank-session [with-draft])))))

(deftest soft-delete-restore-and-purge
  (let [{:keys [session]} (session-with-model)
        deleted (support/execute session :session/delete {} {:now 2000})
        restored (support/execute (:session deleted) :session/restore {} {:now 3000})
        deleted-again (support/execute (:session restored) :session/delete {} {:now 4000})
        expired (sessions/expired-deletion? (:session deleted-again)
                                            (+ 4000 sessions/deletion-retention-ms))
        purge-refused (sessions/purge (:session deleted-again) {})
        purged (sessions/purge (:session deleted-again) {:confirm? true})]
    (is (= :deleted (get-in deleted [:session :status])))
    (is (= :active (get-in restored [:session :status])))
    (is (nil? (get-in restored [:session :deleted-at])))
    (is (= 4000 (:deleted-at (sessions/session-summary
                              (:session deleted-again))))
        "the list projection must expose the retention timestamp")
    (is (true? expired))
    (is (not (:ok? purge-refused)))
    (is (= :CONFIRMATION_REQUIRED (get-in purge-refused [:error :code])))
    (is (:purge? purged))
    (is (= :delete-session (get-in purged [:effects 0 :kind])))))

(deftest session-invariants-hold-across-commands
  (let [{:keys [context session]} (session-with-model)
        renamed (support/execute session :session/rename
                                 {:title "Named"}
                                 {:now 2000})
        drafted (support/execute (:session renamed) :session/update-draft
                                 {:text "draft"}
                                 {:now 3000})
        sent (support/execute (:session drafted) :session/send-message
                              {:text "hello"}
                              {:now 4000
                               :config (:config context)
                               :id-source (:source context)})
        claimed (runs/claim-call (:session sent)
                                 {:call-id (get-in sent [:receipt :result :call-id])
                                  :attempt-id "attempt-1"
                                  :now 5000})
        terminal (sessions/accept-model-event
                  (:session claimed)
                  {:kind :completed
                   :attempt-id "attempt-1"
                   :content "answer"}
                  {:now 6000 :id-source (:source context)})]
    (is (empty? (sessions/check-invariants (:session renamed))))
    (is (empty? (sessions/check-invariants (:session drafted))))
    (is (empty? (sessions/check-invariants (:session sent))))
    (is (empty? (sessions/check-invariants (:session claimed))))
    (is (empty? (sessions/check-invariants (:session terminal))))))

(deftest a-refused-command-consumes-its-id-and-replays-the-refusal
  (let [{:keys [session]} (session-with-model)
        request {:command-id "cmd-refused"
                 :command-epoch 1
                 :client-id "w1"
                 :type :session/update-draft
                 :payload {:text "one" :expected-revision 99}}
        first (support/execute-request session request {:now 2000})
        replay (support/execute-request (:session first) request {:now 3000})]
    (is (not (:ok? first)))
    (is (= :DRAFT_REVISION_CONFLICT (get-in first [:error :code])))
    (is (some? (:receipt first)))
    (is (false? (:ok? replay)))
    (is (true? (:replayed? replay)))
    (is (= (:receipt first) (:receipt replay)))
    (is (= (:revision (:session first)) (:revision (:session replay))))))

(deftest delegate-commands-are-deduplicated-once
  (let [{:keys [session]} (session-with-model)
        calls (atom 0)
        delegate (fn [session request _opts]
                   (swap! calls inc)
                   {:ok? true
                    :session (assoc session :title "delegated")
                    :result {:type (:type request)}
                    :effects [{:kind :delegated-effect}]})
        request {:command-id "cmd-delegate"
                 :command-epoch 1
                 :client-id "w1"
                 :type :mission/answer
                 :payload {:value 1}}
        first (support/execute-request session request {:now 2000 :delegate delegate})
        replay (support/execute-request (:session first) request {:now 3000
                                                               :delegate delegate})]
    (is (:ok? first))
    (is (= 1 @calls))
    (is (= "delegated" (get-in first [:session :title])))
    (is (= :delegated-effect (get-in first [:effects 0 :kind])))
    (is (some #(= :persist-session (:kind %)) (:effects first)))
    (is (:ok? replay))
    (is (true? (:replayed? replay)))
    (is (= 1 @calls))
    (is (= (:receipt first) (:receipt replay)))))

(deftest a-retired-epoch-never-runs-the-delegate
  (let [{:keys [session]} (session-with-model)
        calls (atom 0)
        delegate (fn [session _request _opts]
                   (swap! calls inc)
                   {:ok? true
                    :session session
                    :result {}
                    :effects []})
        issued (support/issue-epoch session 2000)
        result (sessions/execute
                (:session issued)
                {:command-id "cmd-retired-delegate"
                 :command-epoch 1
                 :client-id "w1"
                 :type :mission/answer
                 :payload {:value 1}}
                {:gate (support/gate (:session issued))
                 :now 3000
                 :delegate delegate})]
    (is (not (:ok? result)))
    (is (= :COMMAND_EPOCH_RETIRED (get-in result [:error :code])))
    (is (zero? @calls))))

(deftest execute-uses-the-scoped-command-key
  (let [{:keys [session]} (session-with-model)
        gate (:state (commands/issue-epoch (commands/new-state)))
        request {:command-id "cmd-scoped"
                 :command-epoch 1
                 :client-id "w1"
                 :type :session/rename
                 :payload {:title "Scoped"}}
        first (sessions/execute session request
                                {:gate gate :scope "s1" :now 2000})
        replay (sessions/execute (:session first) request
                                 {:gate (:gate-state first)
                                  :scope "s1"
                                  :now 3000})]
    (is (:ok? first))
    (is (some? (commands/receipt-for (:gate-state first)
                                     ["s1" "cmd-scoped"])))
    (is (= 1 (count (get-in (:gate-state first) [:receipts]))))
    (is (:ok? replay))
    (is (true? (:replayed? replay)))
    (is (= 1 (count (get-in (:gate-state replay) [:receipts]))))))

(deftest deleted-sessions-refuse-other-metadata-commands
  (let [{:keys [session]} (session-with-model)
        deleted (support/execute session :session/delete {} {:now 2000})
        renamed (support/execute (:session deleted) :session/rename
                                 {:title "Nope"}
                                 {:now 3000})
        restored (support/execute (:session deleted) :session/restore
                                  {}
                                  {:now 4000})]
    (is (:ok? deleted))
    (is (not (:ok? renamed)))
    (is (= :SESSION_DELETED (get-in renamed [:error :code])))
    (is (= :deleted (get-in renamed [:session :status])))
    (is (:ok? restored))
    (is (= :active (get-in restored [:session :status])))))

(deftest metadata-epoch-is-exposed-separately
  (let [{:keys [session]} (session-with-model)]
    (is (= 1 (support/metadata-epoch session)))))

(defspec command-replay-is-idempotent
  50
  (prop/for-all [title (gen/such-that #(not (str/blank? %))
                                      gen/string-alphanumeric
                                      20)]
    (let [{:keys [session]} (session-with-model)
          request {:command-id "property-cmd"
                   :command-epoch 1
                   :client-id "w1"
                   :type :session/rename
                   :payload {:title title}}
          first (support/execute-request session request {:now 2000})
          replay (support/execute-request (:session first) request {:now 3000})]
      (and (:ok? first)
           (:ok? replay)
           (true? (:replayed? replay))
           (= (:receipt first) (:receipt replay))
           (= (:revision (:session first))
              (:revision (:session replay)))
           (= title (get-in replay [:session :title]))))))

(deftest branching-copies-the-path-with-fresh-ids
  (let [{:keys [context session]} (session-with-model)
        message-1 (support/execute session :session/append-message
                                   {:role :user :content "one"}
                                   {:now 2000})
        message-2 (support/execute (:session message-1)
                                   :session/append-message
                                   {:role :assistant :content "two"}
                                   {:now 2100})
        source (:session message-2)
        branch-result (sessions/branch-session
                       source
                       {:before-message-id (get-in message-2
                                                   [:receipt :result
                                                    :message-id])
                        :id-source (:source context)
                        :now 3000})
        branch (:session branch-result)
        source-ids (vec (take 1 (:message-order source)))
        branch-ids (:message-order branch)]
    (is (:ok? branch-result))
    (is (= 1 (count branch-ids)))
    (is (not= (first source-ids) (first branch-ids)))
    (is (= "one" (get-in branch [:messages (first branch-ids)
                                 :versions 0 :content])))
    (is (= (:session-id source) (:origin-session-id branch)))
    (is (= (:model-binding source) (:model-binding branch)))
    (is (empty? (:runs branch)))
    (is (empty? (:drafts branch)))
    (is (empty? (sessions/check-invariants branch)))
    (let [deleted-source (support/execute source :session/delete {} {:now 4000})]
      (is (= 1 (count (sessions/visible-messages branch))))
      (is (= :deleted (get-in deleted-source [:session :status]))))))

(deftest pending-model-switch-is-used-by-the-next-run
  (let [{:keys [context session]} (session-with-model)
        source (:source context)
        second-conn (get-in (connections/new-connection
                             {:connection-id "conn-2"
                              :name "Work"
                              :kind :preset
                              :default-base-url "https://work.example"
                              :now 1000})
                            [:connection])
        with-second (:config (config/add-connection (:config context)
                                                    second-conn 1500))
        refreshed (connections/refresh-models
                   (get-in with-second [:connections "conn-2"])
                   [{:provider-model-id "work-model"
                     :display-name "Work Model"}]
                   source 1600)
        config' (assoc-in with-second [:connections "conn-2"]
                          (:connection refreshed))
        second-model (first (get-in refreshed [:connection :model-order]))
        first-send (support/execute session :session/send-message
                                    {:text "first"}
                                    {:now 2000 :config config' :id-source source})
        first-run (get-in first-send [:receipt :result :run-id])
        first-call (get-in first-send [:receipt :result :call-id])
        claimed (runs/claim-call (:session first-send)
                                 {:call-id first-call
                                  :attempt-id "attempt-1"
                                  :now 3000})
        switched (support/execute (:session claimed) :session/select-model
                                  {:connection-id "conn-2"
                                   :model-entry-id second-model
                                   :mode :next-round}
                                  {:now 4000 :config config' :id-source source})
        terminal (sessions/accept-model-event
                  (:session switched)
                  {:kind :completed
                   :attempt-id "attempt-1"
                   :content "first answer"}
                  {:now 5000 :id-source source})
        second-send (support/execute (:session terminal) :session/send-message
                                     {:text "second"}
                                     {:now 6000 :config config' :id-source source})]
    (is (:ok? first-send))
    (is (= :completed (get-in terminal [:session :runs first-run :state])))
    (is (= "conn-1" (get-in terminal [:session :runs first-run
                                      :model-binding :connection-id])))
    (is (= "conn-2" (get-in switched [:session :pending-model-binding
                                      :binding :connection-id])))
    (is (:ok? second-send))
    (is (= "conn-2" (get-in second-send [:session :model-binding
                                         :connection-id])))
    (is (nil? (get-in second-send [:session :pending-model-binding])))
    (is (= "conn-2" (get-in second-send [:session :runs
                                         (get-in second-send
                                                 [:receipt :result :run-id])
                                         :model-binding :connection-id])))))

(deftest owner-session-port-matches-the-owner-hook
  (let [{:keys [session]} (session-with-model)
        port (sessions/session-port)
        renamed ((:apply-command port) session
                 {:kind :session/rename :title "Port"})
        typed ((:apply-command port) session
               {:type :session/rename :title "Typed"})
        drafted (support/execute (:session renamed) :session/update-draft
                                 {:text "port draft"}
                                 {:now 2000})
        confirmed ((:confirm-draft-saved port)
                   (:session drafted)
                   {:draft-id "w1" :now 3000})
        failed ((:fail-draft-save port)
                (:session confirmed)
                {:draft-id "w1"
                 :revision (get-in confirmed [:session :drafts "w1" :revision])
                 :error {:code :STORAGE_FULL}
                 :now 4000})
        deleted ((:apply-command port)
                 (:session failed)
                 {:kind :session/delete})]
    (is (:ok? renamed))
    (is (= "Port" (get-in renamed [:session :title])))
    (is (:ok? typed))
    (is (= "Typed" (get-in typed [:session :title])))
    (is (= :saved (get-in confirmed [:session :drafts "w1" :save-status])))
    (is (= :error (get-in failed [:session :drafts "w1" :save-status])))
    (is (not (drafts/saved? (get-in failed [:session :drafts "w1"]))))
    (is (:ok? deleted))
    (is (integer? (get-in deleted [:session :deleted-at])))
    (is (empty? (sessions/check-invariants (:session deleted))))))

(deftest message-view-prefers-a-version-snapshot
  (let [message {:message-id "m-1"
                 :role :user
                 :versions [{:version-id "m-1-v1"
                             :content "old"
                             :attachments [{:attachment-id "old"}]}
                            {:version-id "m-1-v2"
                             :content "new"
                             :attachments [{:attachment-id "new"}]
                             :citations [{:url "https://example.com"}]}]
                 :current-version 1
                 :attachments [{:attachment-id "message-level"}]
                 :citations []}]
    (is (= "new" (:content (sessions/message-view message))))
    (is (= [{:attachment-id "new"}]
           (:attachments (sessions/message-view message))))
    (is (= [{:url "https://example.com"}]
           (:citations (sessions/message-view message))))))

(deftest initial-user-input-uses-the-first-visible-user-message
  (let [{:keys [context session]} (session-with-model)
        blob-id (str "sha256-" (apply str (repeat 64 "d")))
        attachment {:attachment-id "att-initial"
                    :blob-id blob-id
                    :name "initial.txt"
                    :mime "text/plain"
                    :size 7}
        first-append (support/execute
                      session :session/append-message
                      {:role :user :content "first question"}
                      {:now 2000})
        first-id (get-in first-append [:receipt :result :message-id])
        first-message (support/execute
                       (:session first-append) :session/edit-message
                       {:message-id first-id
                        :content "first question"
                        :attachments [attachment]}
                       {:now 2100})
        state (:session first-message)
        second-message (support/execute
                        state :session/append-message
                        {:role :user :content "second question"}
                        {:now 3000})
        state (:session second-message)
        initial (sessions/initial-user-input state)]
    (is (:ok? first-append))
    (is (:ok? first-message))
    (is (= {:user-input "first question"
            :user-attachments [attachment]}
           initial))
    (is (contains? (blob-refs/session-refs state) blob-id)
        "the session reference table keeps the initial attachment blob alive")
    (testing "deleting the first message falls through to the next visible user message"
      (let [deleted (support/execute
                     state :session/delete-message
                     {:message-id first-id}
                     {:now 4000})]
        (is (= {:user-input "second question"
                :user-attachments []}
               (sessions/initial-user-input (:session deleted))))
        (is (contains? (blob-refs/session-refs (:session deleted)) blob-id)
            "soft-deleting the message does not release the referenced blob")))
    (testing "attachment-only input keeps a stable placeholder"
      (let [{:keys [session]} (session-with-model)
            appended (support/execute
                      session :session/append-message
                      {:role :user :content ""}
                      {:now 5000})
            only (support/execute
                  (:session appended) :session/edit-message
                  {:message-id (get-in appended [:receipt :result :message-id])
                   :content ""
                   :attachments [attachment]}
                  {:now 5100})]
        (is (= {:user-input "[attachments]"
                :user-attachments [attachment]}
               (sessions/initial-user-input (:session only))))))
    (testing "no user message yields nil"
      (is (nil? (sessions/initial-user-input session))))))

(deftest session-port-delegates-unknown-history-commands
  (let [{:keys [session]} (session-with-model)
        delegate (fn [session _request _opts]
                   {:ok? true
                    :session (assoc session :title "history")
                    :result {:delegated true}
                    :effects []})
        port (sessions/session-port {:delegate delegate})
        result ((:apply-command port) session
                {:kind :history/select-version :message-id "m-1"})]
    (is (:ok? result))
    (is (= "history" (get-in result [:session :title])))
    (is (= {:delegated true} (:result result)))))

(deftest new-and-branch-sessions-start-with-empty-context
  (let [{:keys [context session]} (session-with-model)
        branch (:session (sessions/branch-session
                          session
                          {:id-source (:source context) :now 2000}))]
    (is (= {} (:context session)))
    (is (= {} (:context branch)))))

(deftest edit-message-stores-version-snapshots
  (let [{:keys [session]} (session-with-model)
        appended (support/execute session :session/append-message
                                  {:role :user
                                   :content "original"
                                   :attachments [{:attachment-id "old"}]}
                                  {:now 2000})
        message-id (get-in appended [:receipt :result :message-id])
        edited (support/execute (:session appended) :session/edit-message
                                {:message-id message-id
                                 :content "edited"
                                 :attachments [{:attachment-id "new"}]
                                 :citations [{:url "https://example.com"}]
                                 :tool-records [{:tool-call-id "t1"}]
                                 :usage {:input 10 :output 2}}
                                {:now 3000})
        message (get-in edited [:session :messages message-id])
        version (get-in message [:versions 1])
        view (first (sessions/visible-messages (:session edited)))]
    (is (:ok? edited))
    (is (= 2 (count (:versions message))))
    (is (= "original" (get-in message [:versions 0 :content])))
    (is (= [{:attachment-id "new"}] (:attachments version)))
    (is (= [{:url "https://example.com"}] (:citations version)))
    (is (= [{:tool-call-id "t1"}] (:tool-records version)))
    (is (= {:input 10 :output 2} (:usage version)))
    (is (= [{:attachment-id "new"}] (:attachments view)))
    (is (= [{:url "https://example.com"}] (:citations view)))
    (let [invalid (support/execute (:session appended) :session/edit-message
                                   {:message-id message-id
                                    :content "bad"
                                    :attachments :not-a-vector}
                                   {:now 4000})]
      (is (not (:ok? invalid)))
      (is (= :MESSAGE_VERSION_INVALID (get-in invalid [:error :code]))))))

(deftest send-message-freezes-the-outbound-projection
  (let [{:keys [context session]} (session-with-model)
        first-message (support/execute session :session/append-message
                                       {:role :user :content "old"}
                                       {:now 2000})
        second-message (support/execute (:session first-message)
                                        :session/append-message
                                        {:role :assistant :content "recent"}
                                        {:now 2100})
        seen (atom nil)
        projection (fn [session]
                     (let [ids (mapv :message-id
                                     (sessions/visible-messages session))]
                       (reset! seen ids)
                       {:message-ids (vec (rest ids))
                        :summary-ids ["summary-1"]
                        :version-ids ["v1"]
                        :excluded [{:message-id (first ids)
                                    :reason :before-context-boundary}]
                        :pinned-attachments ["a1"]
                        :warnings [{:code :CONTEXT_TRIMMED}]
                        :messages [{:message-id (second ids)
                                    :content "recent"}]}))
        sent (support/execute (:session second-message) :session/send-message
                              {:text "next"}
                              {:now 3000
                               :config (:config context)
                               :id-source (:source context)
                               :outbound-context projection})
        run-id (get-in sent [:receipt :result :run-id])
        snapshot (get-in sent [:session :runs run-id :input-snapshot])]
    (is (:ok? sent))
    (is (= (vec (rest @seen)) (:message-ids snapshot)))
    (is (not (contains? (set (:message-ids snapshot)) (first @seen))))
    (is (= ["summary-1"] (get-in snapshot [:context :summary-ids])))
    (is (= [{:code :CONTEXT_TRIMMED}] (get-in snapshot [:context :warnings])))
    (is (= ["a1"] (get-in snapshot [:context :pinned-attachments])))
    (is (= ["summary-1"] (get-in sent [:receipt :result
                                       :context-summary-ids])))
    (is (= [{:code :CONTEXT_TRIMMED}]
           (get-in sent [:receipt :result :context-warnings])))))

(deftest an-invalid-outbound-projection-preserves-the-draft
  (let [{:keys [context session]} (session-with-model)
        sent (support/execute session :session/send-message
                              {:text "keep me"}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)
                               :outbound-context (fn [_] {:message-ids "bad"})})]
    (is (not (:ok? sent)))
    (is (= :OUTBOUND_CONTEXT_INVALID (get-in sent [:error :code])))
    (is (= "keep me" (get-in sent [:session :drafts "w1" :text])))
    (is (nil? (get-in sent [:session :current-run-id])))))

(deftest send-message-prepares-compatible-attachments
  (let [{:keys [context session]} (session-with-model)
        blob-id (str "sha256-" (apply str (repeat 64 "a")))
        attachment {:attachment-id "att-1"
                    :name "doc.pdf"
                    :mime "application/pdf"
                    :size 10
                    :status :ready
                    :local-copy? true
                    :blob-id blob-id
                    :extraction {:status :ready :text "hello"}}
        sent (support/execute session :session/send-message
                              {:text "see attached"
                               :attachments [attachment]}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)})
        message-id (get-in sent [:receipt :result :message-id])
        stored (get-in sent [:session :messages message-id :attachments])]
    (is (:ok? sent))
    (is (= 1 (get-in sent [:receipt :result :attachments-sent])))
    (is (= 1 (count stored)))
    (is (= :text (:send-route (first stored))))
    (is (= "hello" (:send-text (first stored))))
    (is (= "text/plain; charset=utf-8" (:send-mime (first stored))))))

(deftest send-message-refuses-incompatible-attachments
  (let [{:keys [context session]} (session-with-model)
        blob-id (str "sha256-" (apply str (repeat 64 "b")))
        image {:attachment-id "att-img"
               :name "photo.png"
               :mime "image/png"
               :size 10
               :status :ready
               :local-copy? true
               :blob-id blob-id}
        sent (support/execute session :session/send-message
                              {:text "look"
                               :attachments [image]}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)})]
    (is (not (:ok? sent)))
    (is (= :ATTACHMENT_NOT_SENDABLE (get-in sent [:error :code])))
    (is (seq (get-in sent [:error :details :issues])))
    (is (= "look" (get-in sent [:session :drafts "w1" :text])))
    (is (nil? (get-in sent [:session :current-run-id])))))

(deftest all-excluded-attachments-need-text-to-send
  (let [{:keys [context session]} (session-with-model)
        blob-id (str "sha256-" (apply str (repeat 64 "c")))
        excluded {:attachment-id "att-excluded"
                  :name "broken.pdf"
                  :mime "application/pdf"
                  :size 10
                  :status :failed
                  :excluded? true
                  :local-copy? true
                  :blob-id blob-id}
        empty-send (support/execute session :session/send-message
                                    {:text ""
                                     :attachments [excluded]}
                                    {:now 2000
                                     :config (:config context)
                                     :id-source (:source context)})
        text-send (support/execute session :session/send-message
                                   {:text "only text"
                                    :attachments [excluded]}
                                   {:now 3000
                                    :config (:config context)
                                    :id-source (:source context)})]
    (is (not (:ok? empty-send)))
    (is (= :MESSAGE_EMPTY (get-in empty-send [:error :code])))
    (is (nil? (get-in empty-send [:session :current-run-id])))
    (is (:ok? text-send))
    (is (= 0 (get-in text-send [:receipt :result :attachments-sent])))
    (is (empty? (get-in text-send [:session :messages
                                   (get-in text-send [:receipt :result :message-id])
                                   :attachments])))))

(deftest temporary-session-metadata
  (let [{:keys [context session]} (session-with-model)
        temp (support/new-test-session context {:temporary? true})
        branch (:session (sessions/branch-session
                          temp {:id-source (:source context) :now 2000}))
        deleted (support/execute temp :session/delete {} {:now 3000})
        listed (sessions/list-sessions [(:session deleted)]
                                       {:include-deleted? true})]
    (is (false? (:temporary? session)))
    (is (true? (:temporary? temp)))
    (is (true? (:temporary? branch)))
    (is (empty? listed))
    (is (sessions/expired-deletion? (:session deleted) 3001))))

(deftest transferring-a-temporary-session-makes-it-permanent
  (let [{:keys [session]} (session-with-model)
        temp (support/new-test-session (support/build-context)
                                       {:temporary? true})
        transferred (support/execute temp :session/transfer {} {:now 2000})]
    (is (true? (:temporary? temp)))
    (is (:ok? transferred))
    (is (false? (get-in transferred [:session :temporary?])))
    (is (false? (:temporary? session)))
    (is (= (inc (:revision temp))
           (get-in transferred [:session :revision])))
    (is (= 1 (count (filter #(= :persist-session (:kind %))
                            (:effects transferred)))))))

(deftest deleted-sessions-refuse-every-command-except-restore-and-delete
  (let [{:keys [session]} (session-with-model)
        appended (support/execute session :session/append-message
                                  {:message-id "m1"
                                   :role :user
                                   :content "hello"}
                                  {:now 2000})
        deleted (support/execute (:session appended) :session/delete
                                 {} {:now 3000})]
    (is (:ok? deleted))
    (doseq [[label command]
            [["rename" [:session/rename {:title "Nope"}]]
             ["pin" [:session/pin {:pinned? true}]]
             ["archive" [:session/archive {}]]
             ["select-model" [:session/select-model
                              {:connection-id "conn-1"
                               :model-entry-id "missing-model"
                               :mode :next-round}]]
             ["edit-message" [:session/edit-message
                              {:message-id "m1" :content "changed"}]]
             ["fold-message" [:session/fold-message {:message-id "m1"}]]
             ["delete-message" [:session/delete-message {:message-id "m1"}]]]]
      (let [[type payload] command
            result (support/execute (:session deleted) type payload
                                    {:now 4000})]
        (is (not (:ok? result)) label)
        (is (= :SESSION_DELETED (get-in result [:error :code])) label)
        (is (= "hello"
               (get-in result [:session :messages "m1" :versions 0 :content]))
            label)))
    (let [restored (support/execute (:session deleted) :session/restore
                                    {} {:now 5000})]
      (is (:ok? restored))
      (is (= :active (get-in restored [:session :status]))))))

(defn- regeneration-run-session
  "One session with a running regeneration whose target is m2."
  []
  (let [{:keys [session]} (session-with-model)
        first-message (support/execute session :session/append-message
                                       {:message-id "m1"
                                        :role :user
                                        :content "question"}
                                       {:now 2000})
        target (support/execute (:session first-message)
                                :session/append-message
                                {:message-id "m2"
                                 :role :assistant
                                 :content "old answer"}
                                {:now 2100})
        started (runs/start-run
                 (:session target)
                 {:run-id "r1"
                  :call-id "c1"
                  :model-binding (:model-binding session)
                  :message-ids ["m1"]
                  :context {}
                  :target-message-id "m2"
                  :target-mode :append-version
                  :now 2200})]
    (is (:ok? started))
    (:session started)))

(deftest editing-or-hiding-the-regeneration-target-cancels-the-run
  (doseq [[label command]
          [["edit" {:type :session/edit-message
                    :payload {:message-id "m2" :content "edited"}}]
           ["fold" {:type :session/fold-message
                    :payload {:message-id "m2"}}]
           ["delete" {:type :session/delete-message
                      :payload {:message-id "m2"}}]]]
    (let [session (regeneration-run-session)
          result (sessions/apply-command session command {:now 3000})]
      (is (:ok? result) label)
      (is (= "r1" (:cancelled-run-id (:result result))) label)
      (is (= :cancelled (get-in result [:session :runs "r1" :state])) label))))

(deftest a-refused-command-without-a-business-change-does-not-bump
  (let [{:keys [session]} (session-with-model)
        result (support/execute session :session/rename
                                {:title ""} {:now 2000})]
    (is (not (:ok? result)))
    (is (= :TITLE_REQUIRED (get-in result [:error :code])))
    (is (= (:revision session) (get-in result [:session :revision])))
    (is (empty? (:effects result)))))

(deftest a-committed-session-acknowledges-its-pending-drafts
  (let [{:keys [session]} (session-with-model)
        persistence (ports/memory-persistence)
        initial (sessions/commit-session! persistence session)
        _ (is (= :committed (:status initial)))
        committed (:session initial)
        live (-> committed
                 (update :revision inc)
                 (assoc :updated-at 2000)
                 (assoc-in [:drafts "w1"]
                           {:client-id "w1"
                            :text "hello"
                            :attachments []
                            :citations []
                            :cursor 0
                            :revision 1
                            :persisted-revision 0
                            :save-status :pending
                            :updated-at 2000
                            :error nil}))
        result (sessions/commit-owner-session! persistence committed live 2000)
        draft (get-in result [:session :drafts "w1"])]
    (is (:ok? result))
    (is (= :saved (:save-status draft)))
    (is (true? (drafts/saved? draft)))))

(deftest send-message-stores-quotes-on-the-message-version
  (let [{:keys [context session]} (session-with-model)
        quotes [{:url "https://example.com/a" :text "quoted"}]
        sent (support/execute session :session/send-message
                              {:text "hello"
                               :quotes quotes}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)})
        message-id (get-in sent [:receipt :result :message-id])
        message (get-in sent [:session :messages message-id])]
    (is (:ok? sent))
    (is (= quotes (get-in message [:versions 0 :quotes])))
    (is (= quotes (:quotes (sessions/message-view message))))))

(deftest capability-scope-is-saved-and-copied-to-a-branch
  (let [{:keys [context]} (session-with-model)
        scope {:mode :restricted :allowed-tools ["read"]}
        session (support/new-test-session context {:capability-scope scope})
        branch (:session (sessions/branch-session
                          session {:id-source (:source context)
                                   :now 2000}))]
    (is (= scope (:capability-scope session)))
    (is (= scope (:capability-scope branch)))))

(deftest regeneration-uses-the-parent-version-frozen-in-the-run
  (let [{:keys [session]} (session-with-model)
        first-message (support/execute session :session/append-message
                                       {:message-id "m1"
                                        :role :user
                                        :content "question"}
                                       {:now 2000})
        target (support/execute (:session first-message)
                                :session/append-message
                                {:message-id "m2"
                                 :role :assistant
                                 :content "answer v1"}
                                {:now 2100})
        edited (support/execute (:session target) :session/edit-message
                                {:message-id "m2"
                                 :content "answer v2"}
                                {:now 2200})
        started (runs/start-run
                 (:session edited)
                 {:run-id "r1"
                  :call-id "c1"
                  :model-binding (:model-binding session)
                  :message-ids ["m1"]
                  :context {:target-version-id "m2-v1"}
                  :target-message-id "m2"
                  :target-mode :append-version
                  :now 2300})
        claimed (runs/claim-call (:session started)
                                 {:call-id "c1"
                                  :attempt-id "a1"
                                  :now 2400})
        current-v2 (assoc-in (:session claimed)
                             [:messages "m2" :current-version] 1)
        accepted (sessions/accept-model-event
                  current-v2
                  {:attempt-id "a1"
                   :call-id "c1"
                   :run-id "r1"
                   :kind :completed
                   :content "answer regenerated"}
                  {:now 2500})]
    (is (:ok? accepted))
    (is (= "m2-v1"
           (get-in accepted [:session :messages "m2"
                             :versions 2 :parent-version-id])))
    (is (= "answer regenerated"
           (get-in accepted [:session :messages "m2"
                             :versions 2 :content])))))

(deftest outbound-projection-keeps-every-turn-context-section
  (let [session {:session-id "s1"
                 :messages {"m1" {:message-id "m1"
                                  :role :user
                                  :versions [{:version-id "v1"
                                              :content "hello"}]}}
                 :message-order ["m1"]}
        context {:system {:prompt-bytes :unknown :modules []}
                 :assistant {:name "Helper" :revision 3}
                 :instructions {:session-override? false :text-preview nil}
                 :memory {:enabled? true :items [] :archived-excluded 2}
                 :worldbook {:enabled-books ["b1"] :provided []
                             :hit-not-provided ["b2"]}
                 :instruction-cards []
                 :tools [{:name "cat" :source :program :read-write :read
                          :enabled? true}]
                 :history {:range [0 1] :compressed? false
                           :visible-count 1 :excluded-count 0}
                 :attachments []
                 :estimates {:tokens :unknown :kind :unknown}}
        projection (#'sessions/outbound-projection
                    session
                    (fn [_] (assoc context :message-ids ["m1"])))
        frozen (:context projection)]
    (is (true? (:ok? projection)))
    (doseq [k [:system :assistant :instructions :memory :worldbook
               :instruction-cards :tools :history :attachments :estimates]]
      (is (contains? frozen k) (str "missing context section " k)))
    (is (= 2 (get-in frozen [:memory :archived-excluded])))
    (is (= :unknown (get-in frozen [:estimates :tokens]))
        "an unknown estimate is never rewritten as zero")))

(deftest outbound-projection-passes-the-turn-card-selection
  (let [seen (atom nil)
        session {:session-id "s1"
                 :messages {"m1" {:message-id "m1"
                                  :role :user
                                  :versions [{:version-id "v1"
                                              :content "hello"}]}}
                 :message-order ["m1"]}
        projection (#'sessions/outbound-projection
                    session
                    (fn [_session opts]
                      (reset! seen opts)
                      {:message-ids ["m1"]})
                    {:turn-card-ids ["c1"]
                     :instruction-values {:topic "compiler"}})]
    (is (true? (:ok? projection)))
    (is (= {:turn-card-ids ["c1"]
            :instruction-values {:topic "compiler"}}
           @seen)
        "the accepted send's per-turn selection reaches the projection")))

(deftest new-session-binds-a-default-assistant
  (let [config (session-config/session-config {:connections []})
        session (:session (sessions/new-session {:session-id "s1"
                                                 :config config}))]
    (is (= "default" (:assistant-id session))
        "an empty settings document still exposes the built-in assistant")
    (is (= "通用助手"
           (get-in config [:assistants "default" :name]))))
  (let [config (session-config/session-config
                {:connections []}
                {"a1" {:name "Helper"}})
        session (:session (sessions/new-session {:session-id "s1"
                                                 :config config}))]
    (is (= "a1" (:assistant-id session))
        "an existing assistant is used, never replaced by the built-in one")))

(deftest ui-sends-record-the-last-outbound-snapshot
  (let [{:keys [session]} (session-with-model)
        outbound-context (fn [session _opts]
                           {:message-ids (vec (keys (:messages session)))
                            :context {:system "sys" :messages []}})
        delegate (fn [s request opts] (history/apply-command s request opts))
        appended (support/execute session :history/append-message
                                  {:message-id "msg-ui-1"
                                   :role :user
                                   :content "hello"}
                                  {:now 2000
                                   :delegate delegate
                                   :outbound-context outbound-context})
        session (support/ok-session appended)
        recorded (support/execute session :session/record-outbound
                                  {:message-id "msg-ui-1"
                                   :run-id "run-ui-1"}
                                  {:now 3000
                                   :outbound-context outbound-context})
        session' (support/ok-session recorded)]
    (testing "an accepted UI send freezes its outbound context in the session"
      (is (= ["run-ui-1"] (:run-order session')))
      (is (seq (get-in session' [:runs "run-ui-1" :input-snapshot :context])))
      (is (= :ui-send (get-in session' [:runs "run-ui-1" :origin])))
      (is (= :last-run (:source (:outbound (snapshot/context-view session'))))))
    (testing "re-recording the same run-id is idempotent"
      (let [again (support/execute session' :session/record-outbound
                                   {:message-id "msg-ui-1"
                                    :run-id "run-ui-1"}
                                   {:now 4000
                                    :outbound-context outbound-context})]
        (is (= ["run-ui-1"] (:run-order (support/ok-session again))))))
    (testing "a missing message is refused"
      (let [missing (support/execute session' :session/record-outbound
                                     {:message-id "msg-missing"}
                                     {:now 5000
                                      :outbound-context outbound-context})]
        (is (not (:ok? missing)))
        (is (= :MESSAGE_NOT_FOUND (get-in missing [:error :code])))))))

(deftest send-message-freezes-temporary-read-into-run-snapshot
  (let [{:keys [context session]} (session-with-model)
        sent (support/execute session :session/send-message
                              {:text "hello"
                               :temporary-read? true}
                              {:now 2000
                               :config (:config context)
                               :id-source (:source context)})
        run-id (get-in sent [:receipt :result :run-id])
        snapshot (get-in sent [:session :runs run-id :input-snapshot])]
    (is (:ok? sent))
    (is (true? (:temporary-read? snapshot))
        "the accepted send's one-shot temporary read reaches the frozen run")
    (is (true? (get-in sent [:session :runs run-id :input-snapshot
                            :temporary-read?])))))

(deftest record-outbound-freezes-temporary-read-into-run-snapshot
  (let [{:keys [session]} (session-with-model)
        outbound-context (fn [session _opts]
                           {:message-ids (vec (keys (:messages session)))
                            :context {:system "sys" :messages []}})
        delegate (fn [s request opts] (history/apply-command s request opts))
        appended (support/execute session :history/append-message
                                  {:message-id "msg-temp-1"
                                   :role :user
                                   :content "hello"}
                                  {:now 2000
                                   :delegate delegate
                                   :outbound-context outbound-context})
        session (support/ok-session appended)
        recorded (support/execute session :session/record-outbound
                                  {:message-id "msg-temp-1"
                                   :run-id "run-temp-1"
                                   :temporary-read? true}
                                  {:now 3000
                                   :outbound-context outbound-context})
        session' (support/ok-session recorded)]
    (is (true? (get-in session' [:runs "run-temp-1"
                                :input-snapshot :temporary-read?])))))
