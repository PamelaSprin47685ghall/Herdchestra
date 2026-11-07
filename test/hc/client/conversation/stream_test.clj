(ns hc.client.conversation.stream-test
  "Regression tests for the stop, late-arrival, version-projection, and
  export rules that the conversation surface exposes to the workbench.

  These tests are deliberately independent of the workbench so a state-machine
  fix can be verified before the shell is wired to it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.client.conversation :as conv]
            [hc.client.conversation.ask :as ask]
            [hc.client.conversation.composer :as composer]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.export :as export]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.surface :as surface]
            [hc.client.conversation.timeline :as timeline]))

(defn- base
  []
  (-> conv/initial-state
      (assoc :command-epoch 1)
      (conv/apply-action {:type :session/select
                          :session-id "s1"
                          :session {:id "s1"
                                    :title "Session One"
                                    :model {:id "m1" :display-name "Main"}}
                          :model {:id "m1" :display-name "Main"}
                          :connection {:phase :synced}})
      (conv/apply-action {:type :platform/set :platform :desktop})))

(defn- edit
  [state text]
  (conv/apply-action state {:type :draft/edit
                            :session-id "s1"
                            :text text
                            :cursor (count text)
                            :at "t"}))

(defn- send!
  [state]
  (conv/apply-action state {:type :composer/send :session-id "s1" :at "t"}))

;; ---------------------------------------------------------------------------
;; Stop before a run id is known

(deftest late-run-start-does-not-reopen-a-stop-before-run-id
  (let [stopping (stream/stop-requested (stream/initial) "t")
        started (stream/start-run stopping {:run-id "r1"})
        late (stream/chunk started {:seq 1 :text "late" :message-id "m1"})]
    (is (stream/stopping? started))
    (is (= "r1" (:stop-run-id started))
        "the first run id is adopted as the stopped run")
    (is (= :ignored (get-in started [:last-outcome :result])))
    (is (= :late-text (get-in late [:last-outcome :result]))
        "a late chunk for the adopted run stays frozen")
    (is (= 1 (:late-text-count late)))))

(deftest late-run-start-does-not-reopen-a-confirmed-stop-without-run-id
  (let [stopped (stream/stop-confirmed (stream/stop-requested (stream/initial) "t")
                                       {:reason :operator :at "t"})
        started (stream/start-run stopped {:run-id "r1"})
        late (stream/chunk started {:seq 1 :text "late" :message-id "m1"})]
    (is (stream/stopped? started))
    (is (= "r1" (:stopped-run-id started)))
    (is (= :late-text (get-in late [:last-outcome :result])))))

(deftest explicit-force-reopens-a-new-visible-run
  (let [stopped (stream/stop-confirmed (stream/stop-requested (stream/initial) "t")
                                       {:reason :operator :at "t"})
        forced (stream/start-run stopped {:run-id "r2" :force? true})]
    (is (stream/streaming? forced))
    (is (not (stream/stopped? forced)))
    (is (= "r2" (:run-id forced)))
    (is (= :applied (get-in forced [:last-outcome :result])))))

;; ---------------------------------------------------------------------------
;; Rejected stop on an idle session

(deftest rejected-idle-stop-restores-streaming-state
  (let [stopping (stream/stop-requested (stream/initial) "t")
        rejected (stream/stop-rejected stopping :refused)
        blocker (composer/send-blocker
                 {:draft (assoc draft/empty-draft :text "hello")
                  :stream rejected
                  :model {:id "m1"}
                  :connection {:phase :synced}})]
    (is (false? (:streaming? rejected))
        "an idle stream must not stay in :generating after a refused stop")
    (is (= :idle (:stop-state rejected)))
    (is (nil? (:stop-run-id rejected)))
    (is (nil? blocker)
        "the composer is not blocked by a refused stop on an idle session")))

(deftest rejected-stop-keeps-an-active-run-streaming
  (let [active (assoc (stream/initial) :streaming? true :run-id "r1")
        rejected (stream/stop-rejected (stream/stop-requested active "t" "r1") :refused)]
    (is (true? (:streaming? rejected))
        "a run that was producing before the stop may still produce after it")))

;; ---------------------------------------------------------------------------
;; Chunk sequence and late tool results

(deftest duplicate-chunk-sequence-is-stale
  (let [first-chunk (stream/chunk (stream/initial)
                                  {:seq 1 :text "a" :message-id "m1"})
        next-chunk (stream/chunk first-chunk
                                 {:seq 1 :text "b" :message-id "m1"})
        replay (stream/chunk next-chunk
                             {:seq 1 :text "b" :message-id "m1"})
        older (stream/chunk first-chunk
                            {:seq 0 :text "c" :message-id "m1"})]
    (is (= :appended (get-in first-chunk [:last-outcome :result])))
    (is (= :appended (get-in next-chunk [:last-outcome :result]))
        "a same-seq chunk with new text is appended, not dropped")
    (is (= :stale (get-in replay [:last-outcome :result]))
        "an identical same-seq replay is idempotent")
    (is (= :stale (get-in older [:last-outcome :result])))
    (is (= 1 (:last-chunk-seq next-chunk)))))

(deftest late-tool-result-carries-message-and-truthful-label
  (let [stopping (stream/stop-requested
                  (assoc (stream/initial) :active-message-id "m1") "t")
        late (stream/tool-result stopping
                                 {:tool-call-id "t1"
                                  :status :succeeded
                                  :completed-before-stop? false})
        record (first (:late-tool-results late))]
    (is (= "m1" (:message-id record)))
    (is (= "停止时仍未完成，结果后来返回" (:label record)))
    (is (= "停止前已执行，结果后来返回"
           (:label (first (:late-tool-results
                           (stream/tool-result stopping
                                               {:tool-call-id "t2"
                                                :status :succeeded
                                                :completed-before-stop? true}))))))))

(deftest new-run-keeps-late-tool-results
  (let [stopped (assoc (stream/initial)
                       :late-tool-results [{:tool-call-id "t1"
                                            :message-id "m1"}])
        started (stream/start-run stopped {:run-id "r2"})]
    (is (= [{:tool-call-id "t1" :message-id "m1"}]
           (:late-tool-results started)))))

;; ---------------------------------------------------------------------------
;; Stop-and-send uses the captured snapshot

(deftest stop-and-send-sends-the-captured-snapshot
  (let [state (-> (base) (edit "first") send!)
        state (edit state "second")
        stopping (conv/apply-action state {:type :composer/stop-and-send
                                           :session-id "s1"
                                           :at "t"})
        state (edit stopping "third")
        confirmed (conv/apply-action state {:type :stream/stop-confirmed
                                            :session-id "s1"
                                            :reason :operator
                                            :at "t"})
        sends (filterv #(= :send (:kind %)) (:outbox confirmed))]
    (is (= ["first" "second"] (mapv #(get-in % [:payload :text]) sends)))
    (is (= "third" (get-in confirmed [:drafts "s1" :text]))
        "text typed while the stop was in flight survives")
    (is (= 3 (get-in confirmed [:drafts "s1" :submitted-version]))
        "the live draft records which captured version was sent")))

;; ---------------------------------------------------------------------------
;; Surface attribution and version projection

(deftest late-tool-results-are-attributed-to-one-message
  (let [stream-state (assoc (stream/initial)
                            :late-tool-results
                            [{:tool-call-id "t1"
                              :message-id "m1"
                              :label "停止前已执行，结果后来返回"}])
        view-model (surface/surface
                    {:session-id "s1"
                     :session {:id "s1" :model {:id "m1"}}
                     :draft draft/empty-draft
                     :stream stream-state
                     :connection {:phase :synced}
                     :messages [{:id "m1" :role :assistant :content "a"}
                                {:id "m2" :role :assistant :content "b"}]
                     :callbacks {}})]
    (is (= 1 (count (get-in view-model [:messages 0 :late-tool-results]))))
    (is (empty? (get-in view-model [:messages 1 :late-tool-results])))))

(deftest version-projects-tool-records-usage-and-assistant-edit
  (let [view (timeline/message-view
              {:id "m1"
               :role :assistant
               :content "answer"
               :versions [{:version-id "m1-v1"
                           :content "answer"
                           :tool-records [{:id "t1"
                                           :status :succeeded
                                           :arguments-complete? true}]
                           :usage {:input-tokens 3 :output-tokens 4}
                           :manual-edit? true}]
               :current-version 0})]
    (is (= ["t1"] (mapv :id (:process view))))
    (is (= {:input-tokens 3 :output-tokens 4} (:usage view)))
    (is (true? (:manual-edit? view)))
    (is (some #{:edit} (:operations view))
        "an assistant message can be manually edited")))

(deftest ordered-messages-accepts-a-sequential-order
  (let [db {:message/by-id {"a" {:id "a"} "b" {:id "b"}}
            :message/order '("b" "a")}]
    (is (= ["b" "a"] (mapv :id (timeline/ordered-messages db))))))

;; ---------------------------------------------------------------------------
;; Draft version guard and ask receipts

(deftest attachment-and-quote-edits-bump-the-draft-version
  (let [d0 (assoc draft/empty-draft :version 1)
        d1 (draft/add-attachment d0 {:id "a1"})]
    (is (= 2 (:version d1)))
    (is (= 3 (:version (draft/remove-attachment d1 "a1"))))
    (is (= 2 (:version (draft/remove-attachment d1 "missing")))
        "a no-op removal does not invent a new version")
    (is (= 2 (:version (draft/add-quote d0 {:id "q1"}))))))

(deftest stale-ask-receipt-does-not-close-a-newer-answer
  (let [card {:ask-id "a1"
              :status :submitting
              :draft {:text "" :selected #{}}
              :answers [{:record-id "a1-a1" :command-id "c1" :status :submitting}
                        {:record-id "a1-a2" :command-id "c2" :status :submitting}]}
        acked (ask/ack card "c1" true "t")]
    (is (= :submitting (:status acked)))
    (is (= :confirmed (get-in acked [:answers 0 :status])))
    (is (= :submitting (get-in acked [:answers 1 :status])))))

;; ---------------------------------------------------------------------------
;; S1 labels and skip

(defn- ask-state
  []
  (conv/apply-action (base)
                     {:type :ask/show
                      :session-id "s1"
                      :ask {:ask-id "ask-1"
                            :name "ask-target"
                            :question "兼容 v0 还是 v1？"
                            :options [{:value "v0" :label "旧配置"}
                                      {:value "v1" :label "新配置"}]
                            :input? false
                            :created-at "t1"}}))

(deftest ask-answer-records-carry-option-labels
  (let [state (-> (ask-state)
                  (conv/apply-action {:type :ask/edit
                                      :session-id "s1"
                                      :ask-id "ask-1"
                                      :selected #{"v1"}
                                      :at "t2"}))
        submitted (conv/apply-action state {:type :ask/submit
                                            :session-id "s1"
                                            :ask-id "ask-1"
                                            :at "t3"})
        answer (get-in submitted [:asks "s1" "ask-1" :answers 0])]
    (is (= "新配置" (:label answer))
        "the visible record names the option, not its internal value")
    (is (= "v1" (:value answer)))
    (testing "text and option labels combine"
      (let [card (-> (ask/new-ask {:ask-id "a2"
                                   :question "q"
                                   :options [{:value "v0" :label "零"}]
                                   :input? true})
                     (ask/edit {:text "备注" :selected #{"v0"}}))]
        (is (= "备注；零" (ask/answer-label card (ask/answer-value card))))))))

(deftest ask-skip-is-recorded-and-freezes
  (let [skipped (conv/apply-action (ask-state)
                                   {:type :ask/skip
                                    :session-id "s1"
                                    :ask-id "ask-1"
                                    :at "t2"})
        command (last (:outbox skipped))
        card (get-in skipped [:asks "s1" "ask-1"])
        answer (first (:answers card))]
    (is (= :unpark (:kind command)))
    (is (= {:skipped? true} (get-in command [:payload :value])))
    (is (= "跳过" (:label answer)))
    (is (true? (:skipped? answer)))
    (is (= :submitting (:status card)))
    (let [acked (conv/apply-action skipped {:type :composer/ack
                                            :command-id (:command-id command)
                                            :status :accepted
                                            :at "t3"})
          final (get-in acked [:asks "s1" "ask-1"])]
      (is (= :skipped (:status final)))
      (is (false? (ask/skippable? final)))
      (is (= :blocked
             (get-in (conv/apply-action acked {:type :ask/skip
                                               :session-id "s1"
                                               :ask-id "ask-1"
                                               :at "t4"})
                     [:last-outcome :result]))))))

(deftest answered-ask-card-is-frozen-with-label
  (let [state (-> (ask-state)
                  (conv/apply-action {:type :ask/edit
                                      :session-id "s1"
                                      :ask-id "ask-1"
                                      :selected #{"v0"}
                                      :at "t2"}))
        submitted (conv/apply-action state {:type :ask/submit
                                            :session-id "s1"
                                            :ask-id "ask-1"
                                            :at "t3"})
        command (last (:outbox submitted))
        acked (conv/apply-action submitted {:type :composer/ack
                                            :command-id (:command-id command)
                                            :status :accepted
                                            :at "t4"})
        card (get-in acked [:asks "s1" "ask-1"])]
    (is (= :answered (:status card)))
    (is (= "旧配置" (:label (first (:answers card)))))
    (is (false? (ask/skippable? card)))))

(deftest surface-ask-view-exposes-draft-selection-and-skip
  (let [card (-> (ask/new-ask {:ask-id "a1"
                               :question "q"
                               :options [{:value "v0" :label "零"}]
                               :input? true})
                 (assoc :draft {:text "备注" :selected #{"v0"} :version 3}))
        view-model (surface/surface
                    {:session-id "s1"
                     :session {:id "s1" :model {:id "m1"}}
                     :draft draft/empty-draft
                     :stream (stream/initial)
                     :connection {:phase :synced}
                     :messages []
                     :ask {"a1" card}
                     :callbacks {:submit-ask! identity
                                 :skip-ask! identity}})
        ask-view (first (:asks view-model))]
    (is (= "备注" (:text ask-view)))
    (is (= ["v0"] (:selected ask-view)))
    (is (= 3 (:draft-version ask-view)))
    (is (true? (get-in ask-view [:options 0 :selected?])))
    (is (= "跳过" (get-in ask-view [:skip :label])))
    (is (false? (get-in ask-view [:skip :disabled?])))))

;; ---------------------------------------------------------------------------
;; Translation selection

(deftest translation-context-snapshots-and-clamps-the-selection
  (let [context (surface/translation-context
                 {:composer {:value "abcdef"
                             :cursor 4
                             :selection {:start 1 :end 3}
                             :draft-version 9}
                  :draft {:version 9}})]
    (is (= {:start 1 :end 3 :text "bc" :draft-version 9}
           (:draft-selection context)))
    (is (= "abcdef" (:draft-text context))))
  (let [context (surface/translation-context
                 {:composer {:value "abc" :cursor 9 :selection nil}
                  :draft {:version 2}})]
    (is (= {:start 3 :end 3 :text "" :draft-version 2}
           (:draft-selection context))
        "a collapsed or out-of-range cursor becomes a clamped zero-width range")))

(deftest translation-replace-action-never-overwrites-a-stale-range
  (let [request {:start 0 :end 3 :expected-text "abc" :text "译文"}
        current {:start 0 :end 3 :text "abc" :draft-version 1}]
    (is (= {:action :replace
            :payload {:start 0 :end 3 :expected-text "abc" :text "译文"}}
           (surface/translation-replace-action
            {:request request :current current
             :replace? true :insert? true})))
    (testing "a moved or changed selection falls back to insert at the caret"
      (is (= {:action :insert :payload {:insert-at 4 :text "译文"}}
             (surface/translation-replace-action
              {:request request
               :current {:start 4 :end 6 :text "def" :draft-version 2}
               :replace? true :insert? true})))
      (is (= :insert
             (:action (surface/translation-replace-action
                       {:request request
                        :current {:start 0 :end 3 :text "xyz" :draft-version 2}
                        :replace? true :insert? true})))
          "same offsets but different selected text is not a replace target"))
    (testing "without an insert path the result is copied"
      (is (= {:action :copy :text "译文"}
             (surface/translation-replace-action
              {:request request :current current
               :replace? false :insert? false}))))))

;; ---------------------------------------------------------------------------
;; Export formats

(deftest plain-text-and-source-option
  (let [message {:id "a1"
                 :role :assistant
                 :body "answer"
                 :sources [{:title "Example"
                            :url "https://example.test/x"
                            :published-at "2026-01-01"}]}
        without-sources (export/plain-text [message])
        with-sources (export/plain-text [message] {:include-sources? true})]
    (is (str/includes? without-sources "answer"))
    (is (not (str/includes? without-sources "Example")))
    (is (str/includes? with-sources "Example"))
    (is (str/includes? with-sources "2026-01-01"))
    (is (not (str/includes? (export/markdown [message]) "Example"))
        "Markdown keeps the same default")
    (is (str/includes? (export/markdown [message] {:include-sources? true})
                       "Example"))))

(deftest message-body-prefers-the-newest-prefix-consistent-text
  (testing "a stale :content does not hide a longer streamed :text"
    (is (= "hello world"
           (:body (timeline/message-view
                   {:id "a1" :role :assistant
                    :content "hello"
                    :text "hello world"})))))
  (testing "a local append-only copy survives a stale server boundary"
    (is (= "hello world"
           (:body (timeline/message-view
                   {:id "a1" :role :assistant
                    :content "hello"
                    :message/content "hello world"})))))
  (testing "an edit that is not a prefix extension wins over the old text"
    (is (= "rewritten"
           (:body (timeline/message-view
                   {:id "a1" :role :assistant
                    :content "rewritten"
                    :text "hello world"}))))))
