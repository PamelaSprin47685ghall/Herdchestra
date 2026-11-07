(ns hc.client.workbench.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.timeline :as timeline]
            [hc.client.sessions.controls :as session-controls]
            [hc.client.workbench.events :as events]
            [hc.client.workbench.model :as model]))

(defn- base-db []
  {:ui/selected-session-id "s1"
   :ui/draft {"s1" (assoc draft/empty-draft :text "hello" :version 0)}
   :ui/stream {"s1" (stream/initial)}
   :ui/command {}
   :ui/ask {}
   :ui/notices []
   :ui/run {}
   :session/by-id {"s1" {:id "s1" :title "Session"}}
   :message/by-id {}
   :message/order []})

(deftest text-chunks-append-and-stopped-chunks-are-late
  (let [db (base-db)
        r1 (events/apply-event db {:type "TEXT_MESSAGE_CONTENT"
                                   :message-id "m1"
                                   :session-id "s1"
                                   :delta "Hel"
                                   :seq 1})
        db1 (:db r1)]
    (is (:handled? r1))
    (is (= "Hel" (get-in db1 [:message/by-id "m1" :message/content])))
    (let [stopped (update-in db1 [:ui/stream "s1"]
                             stream/stop-requested "now")
          r2 (events/apply-event stopped {:type "TEXT_MESSAGE_CONTENT"
                                          :message-id "m1"
                                          :session-id "s1"
                                          :delta "lo"
                                          :seq 2})
          db2 (:db r2)]
      (is (= "Hel" (get-in db2 [:message/by-id "m1" :message/content])))
      (is (= 1 (get-in db2 [:ui/stream "s1" :late-text-count]))))))

(deftest live-message-appends-to-server-session-order
  (let [db (assoc-in (base-db) [:session/by-id "s1" :message-ids] [])
        result (:db (events/apply-event
                     db
                     {:type "TEXT_MESSAGE_CONTENT"
                      :message-id "m1"
                      :session-id "s1"
                      :delta "hello"}))]
    (is (= ["m1"] (get-in result [:session/by-id "s1" :message-ids])))
    (is (= "hello" (get-in result [:message/by-id "m1" :message/content])))))

(deftest tool-arguments-complete-is-not-terminal
  (let [db (base-db)
        db (-> db
               (events/apply-event {:type "TOOL_CALL_START"
                                    :message-id "m1"
                                    :session-id "s1"
                                    :tool-call-id "t1"
                                    :tool-call-name "read-file"})
               :db
               (events/apply-event {:type "TOOL_CALL_ARGS"
                                    :message-id "m1"
                                    :session-id "s1"
                                    :tool-call-id "t1"
                                    :delta "{}"})
               :db
               (events/apply-event {:type "TOOL_CALL_END"
                                    :message-id "m1"
                                    :session-id "s1"
                                    :tool-call-id "t1"})
               :db)
        tool (first (get-in db [:message/by-id "m1" :tool-calls]))]
    (is (true? (:arguments-complete? tool)))
    (is (= :running (:status tool)))
    (is (stream/tool-waiting? tool))
    (let [db (-> db
                 (events/apply-event {:type "TOOL_CALL_RESULT"
                                      :message-id "m1"
                                      :session-id "s1"
                                      :tool-call-id "t1"
                                      :status "succeeded"
                                      :content "ok"})
                 :db)
          tool (first (get-in db [:message/by-id "m1" :tool-calls]))]
      (is (= :succeeded (:status tool)))
      (is (false? (stream/tool-waiting? tool))))))

(deftest receipt-clears-only-the-submitted-version
  (let [db (assoc-in (base-db) [:ui/command "c1"]
                     {:command-id "c1"
                      :kind :send-message
                      :command-epoch 3
                      :session-id "s1"
                      :draft-version 0
                      :snapshot (draft/snapshot (get-in (base-db) [:ui/draft "s1"]))
                      :status :submitting})
        accepted (events/apply-event db {:type "CUSTOM"
                                         :name "hc/receipt"
                                         :value {:command-id "c1"
                                                 :status "accepted"}})
        db' (:db accepted)]
    (is (= "" (get-in db' [:ui/draft "s1" :text])))
    (is (= 1 (get-in db' [:ui/draft-reset "s1"])))
    (is (= :accepted (get-in db' [:ui/command "c1" :status])))
    (let [newer (-> db
                    (assoc-in [:ui/draft "s1" :version] 4)
                    (assoc-in [:ui/draft "s1" :text] "newer edit"))
          accepted (events/apply-event newer {:type "CUSTOM"
                                              :name "hc/receipt"
                                              :value {:command-id "c1"
                                                      :status "accepted"}})
          db'' (:db accepted)]
      (is (= "newer edit" (get-in db'' [:ui/draft "s1" :text])))
      (is (= 4 (get-in db'' [:ui/draft "s1" :version])))
      (is (nil? (get-in db'' [:ui/draft-reset "s1"]))
          "a preserved newer draft must not remount/clear the textarea"))))

(deftest rejected-receipt-restores-a-blank-draft
  (let [snapshot (draft/snapshot {:text "recover me" :version 2})
        db (-> (base-db)
               (assoc-in [:ui/draft "s1"] (assoc draft/empty-draft :version 3))
               (assoc-in [:ui/command "c1"]
                         {:command-id "c1"
                          :kind :send-message
                          :session-id "s1"
                          :draft-version 2
                          :snapshot snapshot
                          :status :submitting}))
        result (events/apply-event db {:type "CUSTOM"
                                       :name "hc/receipt"
                                       :value {:command-id "c1"
                                               :status "rejected"
                                               :message "no"}})
        db' (:db result)]
    (is (= "recover me" (get-in db' [:ui/draft "s1" :text])))
    (is (= :rejected (get-in db' [:ui/command "c1" :status])))))

(deftest ask-event-creates-a-pending-card
  (let [db (base-db)
        result (events/apply-event db {:type "CUSTOM"
                                       :name "hc/ask"
                                       :session-id "s1"
                                       :value {:ask-id "a1"
                                               :question "Which branch?"
                                               :options ["main" "release"]}})
        ask (get-in (:db result) [:ui/ask "a1"])]
    (is (= "Which branch?" (:question ask)))
    (is (= "s1" (:session-id ask)))
    (is (= :pending (:status ask)))))

(deftest nested-gateway-receipt-clears-submitted-draft
  (let [db (assoc-in (base-db) [:ui/command "c1"]
                     {:command-id "c1"
                      :kind :send-message
                      :command-epoch 3
                      :session-id "s1"
                      :draft-version 0
                      :snapshot (draft/snapshot (get-in (base-db) [:ui/draft "s1"]))
                      :status :submitting})
        result (events/apply-event db {:type "CUSTOM"
                                       :name "hc/command-receipt"
                                       :value {:ok? true
                                               :command-id "c1"
                                               :receipt {:status :accepted
                                                         :command-id "c1"}}})
        db' (:db result)]
    (is (= :accepted (get-in db' [:ui/command "c1" :status])))
    (is (= "" (get-in db' [:ui/draft "s1" :text])))
    (is (= 1 (get-in db' [:ui/draft-reset "s1"])))))

(deftest search-error-restores-draft-before-assistant-text
  (let [db (-> (base-db)
               (assoc-in [:ui/draft "s1"] (assoc draft/empty-draft :version 1))
               (assoc-in [:ui/command "c1"]
                         {:command-id "c1" :kind :send-message :session-id "s1"
                          :snapshot {:text "keep me" :version 0}
                          :status :accepted}))
        result (events/apply-event db {:type "RUN_ERROR" :session-id "s1"
                                       :error {:code :SEARCH_TIMEOUT
                                               :message "搜索服务超时"}})
        db' (:db result)]
    (is (= "keep me" (get-in db' [:ui/draft "s1" :text])))
    (is (= :failed (get-in db' [:ui/command "c1" :status])))
    (is (string? (get-in db' [:ui/command "c1" :message])))))

(deftest failed-run-finished-restores-draft
  (let [db (-> (base-db)
               (assoc-in [:ui/draft "s1"] (assoc draft/empty-draft :version 1))
               (assoc-in [:ui/command "c1"]
                         {:command-id "c1" :kind :send-message :session-id "s1"
                          :snapshot {:text "restore me" :version 0}
                          :status :accepted}))
        result (events/apply-event db {:type "RUN_FINISHED" :session-id "s1"
                                       :status :failed
                                       :error {:code :SEARCH_SERVICE_UNREACHABLE
                                               :message "搜索服务不可达"}})
        db' (:db result)]
    (is (= "restore me" (get-in db' [:ui/draft "s1" :text])))
    (is (= :failed (get-in db' [:ui/command "c1" :status])))))

(deftest snapshot-error-recovery-restores-once
  (let [db (-> (base-db)
               (assoc-in [:ui/draft "s1"] (assoc draft/empty-draft :version 1))
               (assoc-in [:ui/command "c1"]
                         {:command-id "c1" :kind :send-message :session-id "s1"
                          :snapshot {:text "snapshot draft" :version 0}
                          :status :accepted}))
        recovered (events/recover-snapshot-error
                   db "s1" {:code :SEARCH_TIMEOUT :message "搜索超时"})
        repeated (events/recover-snapshot-error
                  recovered "s1" {:code :SEARCH_TIMEOUT :message "搜索超时"})]
    (is (= "snapshot draft" (get-in recovered [:ui/draft "s1" :text])))
    (is (= :failed (get-in recovered [:ui/command "c1" :status])))
    (is (= recovered repeated))))

(deftest run-error-notice-is-idempotent
  (let [db (-> (base-db)
               (assoc-in [:ui/command "c1"]
                         {:command-id "c1" :kind :send-message :session-id "s1"
                          :snapshot {:text "x" :version 0}
                          :status :accepted}))
        event {:type "RUN_ERROR" :session-id "s1" :run-id "r1"
               :error {:code :SEARCH_TIMEOUT :message "搜索超时"}}
        once (:db (events/apply-event db event))
        twice (:db (events/apply-event once event))]
    (is (= 1 (count (:ui/notices once))))
    (is (= 1 (count (:ui/notices twice))))))

(deftest nudge-finish-keeps-user-run-streaming
  (let [db (-> (base-db)
               (assoc :ui/run {:run-id "user-run" :message-id "m1"})
               (assoc-in [:ui/stream "s1" :streaming?] true)
               (assoc :ui/nudge {:active? true :run-id "nudge-run"}))
        result (:db (events/apply-event db {:type "RUN_FINISHED"
                                            :session-id "s1"
                                            :run-id "nudge-run"
                                            :nudge? true}))
        stream (get-in result [:ui/stream "s1"])]
    (is (false? (get-in result [:ui/nudge :active?])))
    (is (true? (:streaming? stream)))
    (is (= "user-run" (get-in result [:ui/run :run-id])))))

(deftest nudge-start-does-not-replace-user-run
  (let [db (-> (base-db)
               (assoc :ui/run {:run-id "user-run" :message-id "m1"})
               (assoc-in [:ui/stream "s1" :streaming?] true)
               (assoc-in [:message/by-id "m1"]
                         {:message-id "m1" :role :assistant
                          :message/content "visible answer"
                          :message/status :completed})
               (assoc :message/order ["m1"]))
        result (events/apply-event db {:type "RUN_STARTED"
                                       :session-id "s1"
                                       :run-id "nudge-run"
                                       :nudge? true})
        db' (:db result)
        stream (get-in db' [:ui/stream "s1"])]
    (is (:handled? result))
    (is (true? (get-in db' [:ui/nudge :active?])))
    (is (= "nudge-run" (get-in db' [:ui/nudge :run-id])))
    (is (true? (:streaming? stream)))
    (is (= "nudge-run" (:run-id stream)))
    (is (= "user-run" (get-in db' [:ui/run :run-id]))
        "a nudge start must not replace the visible user run")
    (is (= "visible answer" (get-in db' [:message/by-id "m1" :message/content])))
    (is (= :completed (get-in db' [:message/by-id "m1" :message/status]))
        "a nudge start must not mark the visible answer as streaming")
    (is (= ["m1"] (get-in db' [:message/order])))))

(deftest cancelled-terminal-marks-stopped-not-failed
  (let [db (-> (base-db)
               (assoc-in [:ui/stream "s1"]
                         (stream/stop-requested (stream/initial) "now"))
               (assoc-in [:ui/run :message-id] "m1")
               (assoc-in [:message/by-id "m1"]
                         {:message-id "m1" :role :assistant :content "partial"}))
        result (:db (events/apply-event
                     db
                     {:type "CUSTOM"
                      :name "hc/run-finished"
                      :value {:session-id "s1"
                              :status :failed
                              :error {:kind :cancelled
                                      :message "attempt cancelled"}}}))]
    (is (= :stopped (get-in result [:ui/stream "s1" :stop-state])))
    (is (= :stopped (get-in result [:message/by-id "m1" :message/status])))
    (is (empty? (:ui/notices result)))
    (is (nil? (:ui/run result)))))

(deftest direct-run-error-cancellation-clears-run-state
  (let [db (-> (base-db)
               (assoc-in [:session/by-id "s1"]
                         {:id "s1" :run-status :running :unattended? true
                          :current-run-id "r1"})
               (assoc-in [:ui/stream "s1"]
                         (stream/stop-requested (stream/initial) "now"))
               (assoc-in [:ui/run :message-id] "m1")
               (assoc-in [:message/by-id "m1"]
                         {:message-id "m1" :role :assistant :content "partial"}))
        result (:db (events/apply-event
                     db
                     {:type "RUN_ERROR"
                      :session-id "s1"
                      :error {:kind :cancelled
                              :message "attempt cancelled"}}))]
    (is (= :stopped (get-in result [:ui/stream "s1" :stop-state])))
    (is (= :stopped (get-in result [:message/by-id "m1" :message/status])))
    (is (= :finished (get-in result [:session/by-id "s1" :run-status])))
    (is (nil? (get-in result [:session/by-id "s1" :current-run-id])))
    (is (empty? (:ui/notices result)))))

(deftest search-degraded-is-info-not-failure
  (let [db (-> (base-db)
               (assoc :ui/run {:run-id "r1" :message-id "m1"})
               (assoc-in [:message/by-id "m1"] {:message-id "m1"
                                                :role :assistant
                                                :content "answer"}))
        event {:type "RUN_FINISHED" :session-id "s1" :run-id "r1"
               :search-degraded? true}
        once (:db (events/apply-event db event))
        twice (:db (events/apply-event once event))]
    (is (= :completed (get-in once [:message/by-id "m1" :message/status])))
    (is (= :info (-> once :ui/notices first :tone)))
    (is (= 1 (count (:ui/notices once))))
    (is (= 1 (count (:ui/notices twice))))))

(deftest run-finished-records-the-real-search-status
  (let [db (base-db)
        ok (:db (events/apply-event db {:type "RUN_FINISHED" :session-id "s1"
                                        :run-id "r1" :search-status :ok}))
        skipped (:db (events/apply-event db {:type "RUN_FINISHED"
                                             :session-id "s1"
                                             :run-id "r2"
                                             :search-status :skipped}))]
    (is (= :ok (get-in ok [:ui/search-state "s1" :last-run :status])))
    (is (true? (get-in ok [:ui/search-state "s1" :last-run :searched?])))
    (is (= :skipped (get-in skipped [:ui/search-state "s1" :last-run :status])))
    (is (false? (get-in skipped [:ui/search-state "s1" :last-run :searched?])))
    (testing "a run without a search status leaves the panel alone"
      (let [untouched (:db (events/apply-event db {:type "RUN_FINISHED"
                                                   :session-id "s1"
                                                   :run-id "r3"}))]
        (is (nil? (get-in untouched [:ui/search-state "s1" :last-run])))))))

(deftest failed-search-run-keeps-the-source-list
  (let [db (base-db)
        cards [{:label "S1" :url "https://example.test/a" :title "A"
                :published-at-unknown? true}
               {:label "S2" :url "https://example.test/b" :title "B"
                :published-at "2025-01-02"}]
        result (:db (events/apply-event
                     db {:type "RUN_FINISHED" :session-id "s1" :run-id "r1"
                         :search-status :failed
                         :search-degraded? true
                         :search-context {:status :failed
                                          :source-cards cards
                                          :omitted-source-count 0}}))]
    (is (= :failed (get-in result [:ui/search-state "s1" :last-run :status])))
    (is (= cards (get-in result [:ui/search-state "s1"
                                 :last-run :source-cards])))
    (is (false? (get-in result [:ui/search-state "s1"
                                :last-run :searched?])))
    (is (= 0 (get-in result [:ui/search-state "s1"
                             :last-run :omitted-source-count])))
    (is (nil? (get-in result [:ui/search-state "s1"
                              :last-run :provenance]))
        "the whole public context is not copied into UI state")))

(deftest finished-search-attaches-source-cards-to-the-answer-message
  (let [cards [{:label "S1" :url "http://127.0.0.1:18096/doc1.html"
                :title "Doc One" :published-at-unknown? true}
               {:label "S2" :url "http://127.0.0.1:18096/missing.html"
                :title "Missing Source" :published-at-unknown? true}
               {:label "S3" :url "http://127.0.0.1:18096/doc2.html"
                :title "Doc Two" :published-at "2025-01-02T00:00:00Z"}]
        db (-> (base-db)
               (assoc-in [:session/by-id "s1" :message-ids] ["m1"])
               (assoc-in [:message/by-id "m1"]
                         {:message/id "m1" :session-id "s1"
                          :role :assistant
                          :message/content "ALPHA-ONE 来自 Doc One [S1]。"}))
        event {:type "RUN_FINISHED" :session-id "s1" :run-id "r1"
               :search-status :ok
               :search-context {:status :ok :source-cards cards}}
        result (:db (events/apply-event db event))]
    (is (= cards (get-in result [:message/by-id "m1" :message/sources]))
        "the answer message carries the run's public source cards")
    (is (= cards (get-in result [:ui/search-state "s1"
                                 :last-run :source-cards])))
    (testing "a repeated terminal does not duplicate the same cards"
      (let [twice (:db (events/apply-event result event))]
        (is (= cards (get-in twice [:message/by-id "m1"
                                    :message/sources])))))))

(deftest ask-resolved-is-not-an-unhandled-event
  (let [db (base-db)
        result (:db (events/apply-event
                     db {:type "CUSTOM" :name "hc/ask-resolved"
                         :value {:ask-id "ask-1" :lane :plan
                                 :tenure-id "tenure-1"}}))]
    (is (empty? (:ui/notices result))
        "the resumed confirmation must not add an unhandled-event notice")))

(deftest state-events-are-delegated
  (let [db (base-db)]
    (is (false? (:handled? (events/apply-event db {:type "STATE_SNAPSHOT"}))))
    (is (false? (:handled? (events/apply-event db {:type "STATE_DELTA"}))))
    (is (false? (:handled? (events/apply-event db {:type "MESSAGES_SNAPSHOT"}))))))

(deftest late-text-property
  (let [property (prop/for-all [chunks (gen/vector gen/string 0 20)]
                    (let [stopped (update-in (base-db) [:ui/stream "s1"]
                                             stream/stop-requested "now")
                          final (reduce (fn [db chunk]
                                          (:db (events/apply-event
                                                db {:type "TEXT_MESSAGE_CONTENT"
                                                    :message-id "m1"
                                                    :session-id "s1"
                                                    :delta chunk})))
                                        stopped
                                        chunks)]
                      (= "" (or (get-in final [:message/by-id "m1" :message/content]) ""))))]
    (is (:pass? (tc/quick-check 100 property)))))

(deftest receipt-version-property
  (let [property (prop/for-all [command-version gen/nat
                                 current-version gen/nat
                                 text gen/string]
                    (let [db (-> (base-db)
                                 (assoc-in [:ui/draft "s1"]
                                           (assoc draft/empty-draft
                                                  :text text
                                                  :version current-version))
                                 (assoc-in [:ui/command "c1"]
                                           {:command-id "c1"
                                            :kind :send-message
                                            :session-id "s1"
                                            :draft-version command-version
                                            :status :submitting}))
                          result (:db (events/apply-event
                                       db {:type "CUSTOM"
                                           :name "hc/receipt"
                                           :value {:command-id "c1"
                                                   :status "accepted"}}))]
                      (if (= command-version current-version)
                        (= "" (get-in result [:ui/draft "s1" :text]))
                        (= text (get-in result [:ui/draft "s1" :text])))))]
    (is (:pass? (tc/quick-check 200 property)))))

(deftest session-create-receipt-selects-new-session
  (let [db (-> (base-db)
               (assoc :session/active-id "s1")
               (assoc-in [:ui/command "c1"]
                         {:command-id "c1" :kind :session-create
                          :selection-seq 0 :status :submitting}))
        result (events/apply-event
                db
                {:type "CUSTOM"
                 :name "hc/command-receipt"
                 :value {:ok? true
                         :command-id "c1"
                         :receipt {:status :accepted :session-id "s2"}}})
        db' (:db result)]
    (is (= "s2" (get-in db' [:ui/selected-session-id])))
    (is (= "s1" (get-in db' [:session/active-id]))
        "a local selection never rewrites the server presentation pointer")
    (is (= :sessions (get-in db' [:ui/route])))
    (is (= 1 (get-in db' [:ui/selection-seq]))
        "a receipt-driven selection invalidates older captured sequences"))
  (testing "a manual switch after create is not overridden by a late receipt"
    (let [db (-> (base-db)
                 (assoc :ui/selection-seq 2)
                 (assoc-in [:ui/command "c1"]
                           {:command-id "c1" :kind :session-create
                            :selection-seq 0 :status :submitting}))
          result (:db (events/apply-event
                       db
                       {:type "CUSTOM"
                        :name "hc/command-receipt"
                        :value {:ok? true
                                :command-id "c1"
                                :receipt {:status :accepted
                                          :session-id "s2"}}}))]
      (is (not= "s2" (get-in result [:ui/selected-session-id])))))
  (testing "a create receipt does not retarget a settings form"
    (let [db (-> (base-db)
                 (assoc :ui/route :settings)
                 (assoc-in [:ui/command "c1"]
                           {:command-id "c1" :kind :session-create
                            :selection-seq 0 :status :submitting}))
          result (:db (events/apply-event
                       db
                       {:type "CUSTOM"
                        :name "hc/command-receipt"
                        :value {:ok? true
                                :command-id "c1"
                                :receipt {:status :accepted
                                          :session-id "s2"}}}))]
      (is (= "s1" (get-in result [:ui/selected-session-id]))
          "the operator keeps the session they were editing")
      (is (= :settings (get-in result [:ui/route])))
      (is (nil? (get-in result [:ui/selection-seq]))
          "a receipt that changes no selection must not bump the sequence"))))

(deftest branch-receipt-selects-the-branch-while-selection-is-current
  (let [db (-> (base-db)
               (assoc :ui/selected-session-id "source"
                      :ui/selection-seq 3)
               (assoc-in [:session/by-id "source"] {:id "source"})
               (assoc-in [:session/by-id "branch"] {:id "branch"})
               (assoc-in [:ui/command "branch-1"]
                         {:command-id "branch-1"
                          :kind :history/branch
                          :session-id "source"
                          :selection-seq 3
                          :status :submitting}))
        result (:db (events/apply-event
                     db
                     {:type "CUSTOM"
                      :name "hc/command-receipt"
                      :value {:command-id "branch-1"
                              :receipt {:status :accepted
                                        :session-id "branch"}}}))]
    (is (= "branch" (:ui/selected-session-id result)))
    (is (= :sessions (:ui/route result)))
    (is (= 4 (:ui/selection-seq result))
        "selecting the branch invalidates older captured sequences")))

(deftest late-branch-receipt-does-not-steal-a-newer-selection
  (let [db (-> (base-db)
               (assoc :ui/selected-session-id "source"
                      :ui/selection-seq 4
                      :ui/route :sessions)
               (assoc-in [:session/by-id "source"]
                         {:id "source" :title "源会话 A"
                          :message-ids ["m1"]})
               (assoc-in [:session/by-id "branch"]
                         {:id "branch" :title "Branch of 源会话 A"
                          :branch-of "source"})
               (assoc-in [:message/by-id "m1"]
                         {:id "m1" :session-id "source"
                          :role :user :content "PURGE 源消息"})
               (assoc :message/order ["m1"])
               (assoc-in [:ui/command "branch-1"]
                         {:command-id "branch-1"
                          :kind :history/branch
                          :session-id "source"
                          :selection-seq 3
                          :status :submitting}))
        result (:db (events/apply-event
                     db
                     {:type "CUSTOM"
                      :name "hc/command-receipt"
                      :value {:command-id "branch-1"
                              :receipt {:status :accepted
                                        :session-id "branch"}}}))]
    (is (= "source" (:ui/selected-session-id result))
        "a replayed branch receipt must not override the origin jump")
    (is (= "source" (model/selected-session-id result)))
    (is (= ["PURGE 源消息"]
           (mapv :content (model/messages-for-session result "source")))
        "the source history remains readable after the branch snapshot")
    (is (nil? (model/branch-origin result "source")))
    (is (= :accepted (get-in result [:ui/command "branch-1" :status])))))

(deftest branch-receipt-without-a-selection-guard-does-not-steal
  (let [db (-> (base-db)
               (assoc :ui/selected-session-id "source"
                      :ui/selection-seq 2)
               (assoc-in [:session/by-id "source"] {:id "source"})
               (assoc-in [:session/by-id "branch"] {:id "branch"})
               (assoc-in [:ui/command "branch-1"]
                         {:command-id "branch-1"
                          :kind :history/branch
                          :session-id "source"
                          :status :submitting}))
        result (:db (events/apply-event
                     db
                     {:type "CUSTOM"
                      :name "hc/command-receipt"
                      :value {:command-id "branch-1"
                              :receipt {:status :accepted
                                        :session-id "branch"}}}))]
    (is (= "source" (:ui/selected-session-id result))
        "an untracked legacy receipt is not evidence for a new selection")))

(deftest restore-click-migrates-the-deleted-row-into-normal-history
  (let [request (session-controls/command-request
                 :restore {:id "s2" :title "Beta" :status :deleted} {})
        db (-> (base-db)
               (assoc :ui/session-list
                      [{:session-id "s2" :title "Beta" :status :deleted
                        :command-epoch 7
                        :updated-at "2026-11-04T11:00:00Z"}])
               (assoc-in [:ui/command "restore-1"]
                         {:command-id "restore-1"
                          :kind (:kind request)
                          :session-id (:session-id request)
                          :status :submitting}))
        db' (:db (events/apply-event
                  db
                  {:type "CUSTOM"
                   :name "hc/receipt"
                   :value {:command-id "restore-1"
                           :receipt {:status :accepted
                                     :session-id "s2"}}}))]
    (is (= {:session-id "s2"
            :kind :session/restore
            :action :restore
            :payload {}}
           request)
        "the click carries the exact deleted session id")
    (is (= :accepted (get-in db' [:ui/command "restore-1" :status])))
    (is (empty? (:ui/session-list db'))
        "the restored row leaves the deleted list")
    (is (= :active (get-in db' [:session/by-id "s2" :status])))
    (is (nil? (get-in db' [:session/by-id "s2" :deleted-at])))
    (is (= ["s1" "s2"] (sort (mapv :id (model/sessions db'))))
        "the session re-enters normal history")
    (is (= "Beta"
           (some #(when (= "s2" (:id %)) (:title %))
                 (model/sessions db'))))))

(deftest restore-receipt-updates-an-existing-display-copy
  (let [db (-> (base-db)
               (assoc-in [:session/by-id "s2"]
                         {:id "s2" :title "Beta" :status :deleted
                          :deleted-at "2026-11-04T11:00:00Z"})
               (assoc :ui/session-list
                      [{:session-id "s2" :status :deleted}])
               (assoc-in [:ui/command "restore-2"]
                         {:command-id "restore-2"
                          :kind :session/restore
                          :session-id "s2"
                          :status :submitting}))
        db' (:db (events/apply-event
                  db
                  {:type "CUSTOM"
                   :name "hc/receipt"
                   :value {:command-id "restore-2"
                           :receipt {:status :applied
                                     :session-id "s2"}}}))]
    (is (= :active (get-in db' [:session/by-id "s2" :status])))
    (is (nil? (get-in db' [:session/by-id "s2" :deleted-at])))
    (is (empty? (:ui/session-list db')))))

(deftest purge-receipt-removes-the-deleted-row
  (let [db (-> (base-db)
               (assoc :ui/session-list
                      [{:session-id "s1" :status :deleted}
                       {:session-id "s2" :status :deleted}])
               (assoc-in [:session/by-id "s2"] {:id "s2"})
               (assoc-in [:ui/command "purge-1"]
                         {:command-id "purge-1"
                          :kind :session/purge
                          :session-id "s1"
                          :status :submitting}))
        result (events/apply-event
                db
                {:type "CUSTOM"
                 :name "hc/command-receipt"
                 :value {:command-id "purge-1"
                         :receipt {:status :accepted :session-id "s1"}}})
        db' (:db result)]
    (is (= :accepted (get-in db' [:ui/command "purge-1" :status])))
    (is (= ["s2"] (mapv :session-id (:ui/session-list db'))))
    (is (= ["s2"] (mapv :id (session-controls/deleted-sessions
                              (:ui/session-list db')))))
    (is (nil? (get-in db' [:session/by-id "s1"])))
    (is (some? (get-in db' [:session/by-id "s2"])))
    (is (nil? (:ui/selected-session-id db')))
    (testing "a stale restore receipt cannot resurrect a purged session"
      (let [resurrected (:db (events/apply-event
                              (assoc-in db' [:ui/command "restore-stale"]
                                        {:command-id "restore-stale"
                                         :kind :session/restore
                                         :session-id "s1"
                                         :status :submitting})
                              {:type "CUSTOM"
                               :name "hc/receipt"
                               :value {:command-id "restore-stale"
                                       :receipt {:status :accepted
                                                 :session-id "s1"}}}))]
        (is (nil? (get-in resurrected [:session/by-id "s1"])))
        (is (= ["s2"] (mapv :id (session-controls/deleted-sessions
                                 (:ui/session-list resurrected)))))))))

(deftest session-list-receipt-preserves-the-deleted-row-epoch
  (let [db (-> (base-db)
               (assoc-in [:ui/command "list-1"]
                         {:command-id "list-1"
                          :kind :session/list
                          :status :submitting}))
        result (events/apply-event
                db
                {:type "CUSTOM"
                 :name "hc/command-receipt"
                 :value {:command-id "list-1"
                         :receipt {:status :accepted
                                   :sessions [{:session-id "s1"
                                               :status :deleted
                                               :command-epoch 11}]}}})
        db' (:db result)]
    (is (= 11 (get-in db' [:ui/session-list 0 :command-epoch]))
        "the display list keeps the Owner epoch for restore and purge")
    (is (= 11 (model/session-command-epoch db' "s1")))))

(deftest non-send-receipts-never-touch-the-draft
  (doseq [kind [:stop-run :answer-ask :settings/save]]
    (let [db (-> (base-db)
                 (assoc-in [:ui/draft "s1"] (assoc draft/empty-draft
                                                   :text "keep me" :version 2))
                 (assoc-in [:ui/draft-reset "s1"] 1)
                 (assoc-in [:ui/command "c1"]
                           {:command-id "c1" :kind kind :session-id "s1"
                            :draft-version 0 :status :submitting}))
          accepted (:db (events/apply-event
                         db {:type "CUSTOM" :name "hc/command-receipt"
                             :value {:command-id "c1"
                                     :receipt {:status :accepted
                                               :command-id "c1"}}}))
          rejected (:db (events/apply-event
                         db {:type "CUSTOM" :name "hc/command-receipt"
                             :value {:command-id "c1"
                                     :receipt {:status :rejected
                                               :command-id "c1"
                                               :message "no"}}}))]
      (doseq [result [accepted rejected]]
        (is (= "keep me" (get-in result [:ui/draft "s1" :text])) (pr-str kind))
        (is (= 1 (get-in result [:ui/draft-reset "s1"])) (pr-str kind))))))

(deftest stop-receipt-closes-the-optimistic-freeze
  (let [db (-> (base-db)
               (update-in [:ui/stream "s1"] stream/stop-requested "now")
               (assoc-in [:ui/command "stop-1"]
                         {:command-id "stop-1"
                          :kind :stop-run
                          :session-id "s1"
                          :status :submitting
                          :payload {:kind :stop-run
                                    :session-id "s1"
                                    :origin :operator-stop}}))
        accepted (:db (events/apply-event
                       db
                       {:type "CUSTOM"
                        :name "hc/receipt"
                        :value {:command-id "stop-1"
                                :session-id "s1"
                                :receipt {:status :accepted}}}))
        rejected (:db (events/apply-event
                       db
                       {:type "CUSTOM"
                        :name "hc/receipt"
                        :value {:command-id "stop-1"
                                :session-id "s1"
                                :receipt {:status :rejected
                                          :error {:code :RUN_TERMINAL
                                                  :message "nothing to stop"}}}}))]
    (is (= :stopped (get-in accepted [:ui/stream "s1" :stop-state])))
    (is (= :accepted (get-in accepted [:ui/command "stop-1" :status])))
    (is (= :idle (get-in rejected [:ui/stream "s1" :stop-state]))
        "a refused stop must not leave the composer frozen")
    (is (some? (get-in rejected [:ui/stream "s1" :stop-error])))))

(deftest late-run-start-does-not-reopen-a-pending-stop
  (let [db (-> (base-db)
               (assoc-in [:ui/stream "s1"]
                         (stream/stop-requested (stream/initial)
                                                "now" "run-1"))
               (assoc-in [:ui/command "stop-1"]
                         {:command-id "stop-1"
                          :kind :stop-run
                          :session-id "s1"
                          :status :submitting
                          :payload {:kind :stop-run
                                    :session-id "s1"
                                    :run-id "run-1"}}))
        started (:db (events/apply-event
                      db
                      {:type "RUN_STARTED"
                       :session-id "s1"
                       :run-id "run-1"
                       :message-id "m1"}))]
    (is (= :stopping (get-in started [:ui/stream "s1" :stop-state]))
        "a late start for the same run must not clear the stop freeze")
    (let [confirmed (:db (events/apply-event
                          started
                          {:type "CUSTOM"
                           :name "hc/receipt"
                           :value {:command-id "stop-1"
                                   :session-id "s1"
                                   :receipt {:status :accepted
                                             :result {:run-id "run-1"}}}}))]
      (is (= :stopped (get-in confirmed [:ui/stream "s1" :stop-state])))
      (is (false? (get-in confirmed [:ui/stream "s1" :streaming?])))
      (testing "a genuinely new run after the stop still starts"
        (let [next-run (:db (events/apply-event
                             confirmed
                             {:type "RUN_STARTED"
                              :session-id "s1"
                              :run-id "run-2"
                              :message-id "m2"}))]
          (is (= :idle (get-in next-run [:ui/stream "s1" :stop-state])))
          (is (true? (get-in next-run [:ui/stream "s1" :streaming?]))))))))

(deftest accepted-stop-releases-an-idle-visible-stream
  (let [db (assoc-in (base-db) [:ui/command "stop-idle"]
                     {:command-id "stop-idle"
                      :kind :stop-run
                      :session-id "s1"
                      :status :submitting
                      :payload {:kind :stop-run :session-id "s1"}})
        result (:db (events/apply-event
                     db
                     {:type "CUSTOM"
                      :name "hc/receipt"
                      :value {:command-id "stop-idle"
                              :session-id "s1"
                              :receipt {:status :accepted}}}))]
    (is (= :stopped (get-in result [:ui/stream "s1" :stop-state])))
    (is (false? (get-in result [:ui/stream "s1" :streaming?]))
        "an accepted stop must not leave the composer waiting on an idle stream")))

(deftest old-stop-receipt-does-not-freeze-a-newer-run
  (let [db (-> (base-db)
               (assoc-in [:ui/stream "s1"]
                         (stream/stop-requested (stream/initial)
                                                "now" "run-1"))
               (assoc-in [:ui/command "stop-1"]
                         {:command-id "stop-1"
                          :kind :stop-run
                          :session-id "s1"
                          :status :submitting
                          :payload {:kind :stop-run
                                    :session-id "s1"
                                    :run-id "run-1"}}))
        next-run (:db (events/apply-event
                       db
                       {:type "RUN_STARTED"
                        :session-id "s1"
                        :run-id "run-2"
                        :message-id "m2"}))
        after-receipt (:db (events/apply-event
                            next-run
                            {:type "CUSTOM"
                             :name "hc/receipt"
                             :value {:command-id "stop-1"
                                     :session-id "s1"
                                     :receipt {:status :accepted
                                               :result {:run-id "run-1"}}}}))]
    (is (= :idle (get-in after-receipt [:ui/stream "s1" :stop-state])))
    (is (true? (get-in after-receipt [:ui/stream "s1" :streaming?])))
    (is (= "run-2" (get-in after-receipt [:ui/stream "s1" :run-id])))
    (is (= :accepted (get-in after-receipt [:ui/command "stop-1" :status])))))

(deftest text-for-a-new-assistant-message-unfreezes-after-stop
  (let [stopped (-> (stream/initial)
                    (stream/stop-requested "now" "run-1")
                    (stream/stop-confirmed {})
                    (assoc :active-message-id "assistant-old"))
        db (assoc-in (base-db) [:ui/stream "s1"] stopped)
        result (:db (events/apply-event
                     db
                     {:type "TEXT_MESSAGE_CONTENT"
                      :message-id "assistant-new"
                      :session-id "s1"
                      :delta "tick-01"}))]
    (is (= "tick-01" (get-in result [:message/by-id "assistant-new"
                                     :message/content])))
    (is (= :idle (get-in result [:ui/stream "s1" :stop-state])))
    (is (true? (get-in result [:ui/stream "s1" :streaming?])))))

(deftest start-run-with-a-new-run-id-clears-a-stopped-stream
  (let [stopped (-> (stream/initial)
                    (stream/stop-requested "now" "run-1")
                    (stream/stop-confirmed {}))
        next-run (stream/start-run stopped {:run-id "run-2"})]
    (is (= :idle (:stop-state next-run)))
    (is (true? (:streaming? next-run)))
    (is (= "run-2" (:run-id next-run)))
    (is (nil? (:stop-run-id next-run)))))

(deftest stopped-run-identity-wins-over-an-overwritten-run-id
  (let [stopped (-> (stream/initial)
                    (stream/stop-requested "now" "run-1")
                    (stream/stop-confirmed {})
                    (assoc :run-id "run-2"))
        next-run (stream/start-run stopped {:run-id "run-2"})]
    (is (= :idle (:stop-state next-run)))
    (is (true? (:streaming? next-run)))
    (is (= "run-2" (:run-id next-run)))))

(deftest transient-not-found-receipt-is-ignored
  (let [db (assoc-in (base-db) [:ui/command "c1"]
                     {:command-id "c1" :kind :session-create :status :submitting})
        result (events/apply-event
                db
                {:type "CUSTOM" :name "hc/command-receipt"
                 :value {:ok? true :command-id "c1"
                         :receipt {:status :not-found :command-id "c1"}}})
        db' (:db result)]
    (is (= :submitting (get-in db' [:ui/command "c1" :status])))
    (is (nil? (get-in db' [:ui/command "c1" :receipt])))
    (is (nil? (get-in db' [:ui/command "c1" :message])))))

(deftest not-found-does-not-downgrade-an-accepted-command
  (let [db (assoc-in (base-db) [:ui/command "c1"]
                     {:command-id "c1" :kind :session-create :status :accepted
                      :receipt {:status :accepted}})
        result (events/apply-event
                db
                {:type "CUSTOM" :name "hc/command-receipt"
                 :value {:ok? true :command-id "c1"
                         :receipt {:status :not-found :command-id "c1"}}})
        command (get-in (:db result) [:ui/command "c1"])]
    (is (= :accepted (:status command)))
    (is (= :accepted (get-in command [:receipt :status])))))

(deftest enospc-command-failure-uses-standard-chinese-message
  (let [db (assoc (base-db)
                  :ui/command
                  {"c1" {:command-id "c1" :kind :session/create
                         :status :submitting}})
        result (events/apply-event
                db
                {:type "CUSTOM"
                 :name "hc/command-receipt"
                 :value {:ok? false
                         :command-id "c1"
                         :error {:code :COMMAND_STORE_FAILED
                                 :message "command state could not be committed"
                                 :details {:cause "java.io.IOException: No space left on device"}}}})
        command (get-in (:db result) [:ui/command "c1"])]
    (is (= :rejected (:status command)))
    (is (= (str "空间不足，暂时无法保存更多内容。请释放空间或先导出草稿。"
                "（未确认保存；COMMAND_STORE_FAILED）")
           (:message command))
        "the English commit failure is replaced by the standard Chinese text")))

(deftest forty-delta-chunks-append-without-seq
  (let [db (reduce (fn [db i]
                     (:db (events/apply-event
                           db
                           {:type "TEXT_MESSAGE_CONTENT"
                            :session-id "s1"
                            :message-id "m1"
                            :delta (str i ",")})))
                   (base-db)
                   (range 40))]
    (is (= (apply str (map #(str % ",") (range 40)))
           (get-in db [:message/by-id "m1" :message/content]))
        "every no-seq delta appends to the active assistant message")))

(deftest branch-receipt-activates-and-merges-the-copied-path
  (let [db (-> (base-db)
               (assoc :ui/selection-seq 0)
               (assoc-in [:ui/command "c1"]
                         {:command-id "c1" :kind :history/branch
                          :selection-seq 0 :status :submitting}))
        result (events/apply-event
                db
                {:type "CUSTOM"
                 :name "hc/command-receipt"
                 :value {:ok? true
                         :command-id "c1"
                         :receipt {:status :accepted
                                   :session-id "b1"
                                   :result
                                   {:session {:session-id "b1"
                                              :title "分支"
                                              :message-ids ["m1"]
                                              :message-order ["m1"]}
                                    :messages {"m1" {:message/id "m1"
                                                     :message/session-id "b1"
                                                     :message/role :user
                                                     :message/content "分支起点"}}}}}})
        db' (:db result)]
    (is (= "b1" (get-in db' [:ui/selected-session-id])))
    (is (= "分支" (get-in db' [:session/by-id "b1" :title])))
    (is (= "分支起点" (get-in db' [:message/by-id "m1" :message/content])))
    (is (some #(= "m1" %) (get-in db' [:message/order])))))
