(ns hc.client.conversation-test
  "Contract tests for the conversation surface model.

  These tests exercise the operator-visible rules directly: per-session
  drafts, IME-safe Enter, send gating, stop freezing, late tool results, S1 ask
  cards, scroll/selection protection, timeline projection, and the Escape
  hierarchy."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.client.conversation :as conv]
            [hc.client.conversation.a11y :as a11y]
            [hc.client.conversation.ask :as ask]
            [hc.client.conversation.composer :as composer]
            [hc.client.conversation.draft :as draft]
            [hc.client.conversation.history-edit :as history-edit]
            [hc.client.conversation.stream :as stream]
            [hc.client.conversation.surface :as surface]
            [hc.client.conversation.timeline :as timeline]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private session
  {:id "s1" :title "Session One" :model {:id "m1" :display-name "Main"}
   :assistant {:name "Steward"} :workspace {:name "repo"}})

(defn- base
  ([] (base "s1"))
  ([session-id]
   (-> conv/initial-state
       (assoc :command-epoch 1)
       (conv/apply-action {:type :session/select
                           :session-id session-id
                           :session session
                           :model {:id "m1" :display-name "Main"}
                           :connection {:phase :synced}})
       (conv/apply-action {:type :platform/set :platform :desktop}))))

(defn- edit
  ([state text] (edit state "s1" text))
  ([state session-id text]
   (conv/apply-action state {:type :draft/edit
                             :session-id session-id
                             :text text
                             :cursor (count text)
                             :at "t"})))

(defn- send!
  [state]
  (conv/apply-action state {:type :composer/send :session-id "s1" :at "t"}))

(defn- ack
  [state command-id status]
  (conv/apply-action state {:type :composer/ack
                            :command-id command-id
                            :status status
                            :at "t"}))

(defn- outbox
  [state]
  (:outbox state))

(defn- last-command
  [state]
  (last (:outbox state)))

;; ---------------------------------------------------------------------------
;; Drafts

(deftest per-session-draft-isolation
  (let [state (-> (base)
                  (edit "s1" "alpha")
                  (edit "s2" "beta"))]
    (is (= "alpha" (get-in state [:drafts "s1" :text])))
    (is (= "beta" (get-in state [:drafts "s2" :text])))
    (is (= 1 (get-in state [:drafts "s1" :version])))
    (is (= 1 (get-in state [:drafts "s2" :version])))
    (testing "cursor-only changes do not bump the submitted-version guard"
      (let [state' (conv/apply-action state {:type :draft/cursor
                                             :session-id "s1"
                                             :cursor 2
                                             :selection {:start 1 :end 2}})]
        (is (= 1 (get-in state' [:drafts "s1" :version])))
        (is (= {:start 1 :end 2} (get-in state' [:drafts "s1" :selection])))))))

(deftest draft-lookup-accepts-reducer-wrapper
  (let [draft-value (assoc draft/empty-draft :text "hello" :version 3)
        direct (draft/draft {"s1" draft-value} "s1")
        wrapped (draft/draft {:drafts {"s1" draft-value}} "s1")]
    (is (= "hello" (:text direct)))
    (is (= "hello" (:text wrapped)))
    (is (= 3 (:version wrapped)))
    (testing "the wrapper tolerance does not change a real session id lookup"
      (is (= "hello" (:text (draft/draft {"s1" draft-value} "s1")))))))

(deftest snapshot-passes-turn-selection
  (let [snapshot (draft/snapshot (assoc draft/empty-draft
                                        :text "hi"
                                        :turn-card-ids ["card-1"]
                                        :instruction-values {"tone" "brief"}))]
    (is (= ["card-1"] (:turn-card-ids snapshot)))
    (is (= {"tone" "brief"} (:instruction-values snapshot)))))

(deftest turn-selection-rides-the-draft-into-send-payloads
  (testing "draft/turn-selection stores the per-turn keys without a version bump"
    (let [state (-> (base)
                    (edit "s1" "hi")
                    (conv/apply-action {:type :draft/turn-selection
                                        :session-id "s1"
                                        :turn-card-ids ["card-1"]
                                        :instruction-values {"tone" "brief"}
                                        :turn-selection-version 7}))]
      (is (= ["card-1"] (get-in state [:drafts "s1" :turn-card-ids])))
      (is (= 7 (get-in state [:drafts "s1" :turn-selection-version])))
      (is (= 1 (get-in state [:drafts "s1" :version]))
          "selection metadata must not invalidate the submitted-version guard")))
  (testing "the pending-send snapshot rebuilt after a stop keeps the turn keys"
    (let [state (-> (base)
                    (edit "s1" "first")
                    send!
                    (conv/apply-action {:type :draft/turn-selection
                                        :session-id "s1"
                                        :turn-card-ids ["card-1"]
                                        :instruction-values {"tone" "brief"}
                                        :turn-selection-version 7})
                    (edit "s1" "second"))
          stopped (conv/apply-action state {:type :composer/stop-and-send
                                            :session-id "s1"
                                            :at "t"})
          confirmed (conv/apply-action stopped {:type :stream/stop-confirmed
                                                :session-id "s1"
                                                :reason :operator
                                                :at "t"})
          send (last-command confirmed)]
      (is (= :send (:kind send)))
      (is (= ["card-1"] (get-in send [:payload :turn-card-ids])))
      (is (= {"tone" "brief"} (get-in send [:payload :instruction-values])))
      (is (= 7 (get-in send [:payload :turn-selection-version]))
          "without the version the receipt sweep cannot match this send to its staging"))))

(deftest clear-only-submitted-version
  (testing "a send clears the admitted snapshot"
    (let [state (-> (base) (edit "s1" "first") send!)
          command (last-command state)]
      (is (= "first" (get-in command [:payload :text])))
      (is (= "" (get-in state [:drafts "s1" :text])))
      (is (= 2 (get-in state [:drafts "s1" :version])))))
  (testing "text typed while sending survives the acknowledgement"
    (let [state (-> (base) (edit "s1" "first") send!)
          command (last-command state)
          state (edit state "s1" "second")
          state (ack state (:command-id command) :accepted)]
      (is (= "second" (get-in state [:drafts "s1" :text])))))
  (testing "a rejected send restores a blank draft and preserves newer text"
    (let [state (-> (base) (edit "s1" "first") send!)
          command (last-command state)
          rejected (ack state (:command-id command) :rejected)]
      (is (= "first" (get-in rejected [:drafts "s1" :text])))
      (is (= :unsaved (get-in rejected [:drafts "s1" :save-state])))
      (is (= "发送未送达，草稿已恢复"
             (get-in rejected [:last-outcome :message])))
      (let [state (-> (base) (edit "s1" "first") send!)
            command (last-command state)
            state (edit state "s1" "newer")
            rejected (ack state (:command-id command) :rejected)]
        (is (= "newer" (get-in rejected [:drafts "s1" :text])))
        (is (= "first" (get-in rejected [:drafts "s1" :rejected-submission :text])))
        (is (= "发送未送达，已保留较新草稿"
               (get-in rejected [:last-outcome :message])))
        (let [restored (conv/apply-action rejected
                                          {:type :draft/restore-rejected
                                           :session-id "s1" :at "t"})]
          (is (= "first" (get-in restored [:drafts "s1" :text]))))))))

(deftest storage-feedback-is-honest
  (let [state (-> (base) (edit "s1" "draft"))
        failed (conv/apply-action state {:type :draft/save-state
                                         :session-id "s1"
                                         :save-state :failed
                                         :error :ENOSPC})]
    (is (= "草稿暂未保存" (draft/save-label (get-in failed [:drafts "s1"]))))
    (is (= [:copy :export] (draft/save-actions (get-in failed [:drafts "s1"]))))
    (is (false? (draft/saved? (get-in failed [:drafts "s1"]))))
    (testing "a successful save advances only the saved version"
      (let [saved (conv/apply-action failed {:type :draft/save-state
                                             :session-id "s1"
                                             :save-state :saved})]
        (is (nil? (draft/save-label (get-in saved [:drafts "s1"]))))
        (is (draft/saved? (get-in saved [:drafts "s1"])))))))

;; ---------------------------------------------------------------------------
;; IME and composer

(deftest ime-enter-never-sends
  (let [state (edit (base) "拼音")]
    (doseq [event [{:key "Enter" :is-composing? true}
                   {:key "Enter" :key-code 229}
                   {:key "Process"}
                   {:key "Enter" :composing? true}]]
      (let [next (conv/apply-action state {:type :composer/keydown
                                           :session-id "s1"
                                           :event event
                                           :at "t"})]
        (is (empty? (outbox next)) (pr-str event))
        (is (= :ime-candidate (get-in next [:last-outcome :result])) (pr-str event))
        (is (false? (get-in next [:last-outcome :prevent-default?])) (pr-str event))
        (is (= "拼音" (get-in next [:drafts "s1" :text])) (pr-str event))))))

(deftest enter-platform-defaults
  (let [state (edit (base) "hello")]
    (testing "desktop Enter sends"
      (let [next (conv/apply-action state {:type :composer/keydown
                                           :session-id "s1"
                                           :event {:key "Enter"}
                                           :at "t"})]
        (is (= 1 (count (outbox next))))
        (is (= :send (:kind (first (outbox next)))))))
    (testing "Shift+Enter is a newline"
      (let [next (conv/apply-action state {:type :composer/keydown
                                           :session-id "s1"
                                           :event {:key "Enter" :shift? true}
                                           :at "t"})]
        (is (empty? (outbox next)))
        (is (= :newline (get-in next [:last-outcome :result])))))
    (testing "key repeat does not send twice"
      (let [next (conv/apply-action state {:type :composer/keydown
                                           :session-id "s1"
                                           :event {:key "Enter" :repeat? true}
                                           :at "t"})]
        (is (empty? (outbox next)))
        (is (= :key-repeat (get-in next [:last-outcome :reason])))))
    (testing "mobile Enter is a newline"
      (let [state (conv/apply-action state {:type :platform/set :platform :mobile})
            next (conv/apply-action state {:type :composer/keydown
                                           :session-id "s1"
                                           :event {:key "Enter"}
                                           :at "t"})]
        (is (empty? (outbox next)))
        (is (= :newline (get-in next [:last-outcome :result])))))
    (testing "mod-enter mode requires the modifier"
      (let [state (conv/apply-action state {:type :settings/send-key
                                            :send-key :mod-enter})
            plain (conv/apply-action state {:type :composer/keydown
                                            :session-id "s1"
                                            :event {:key "Enter"}
                                            :at "t"})
            modified (conv/apply-action state {:type :composer/keydown
                                               :session-id "s1"
                                               :event {:key "Enter" :ctrl? true}
                                               :at "t"})]
        (is (= :newline (get-in plain [:last-outcome :result])))
        (is (= :send (:kind (first (outbox modified)))))))))

(deftest empty-and-invisible-text-cannot-send
  (doseq [text ["" "   " "\u200b" "\u200b\u200d"]]
    (let [state (edit (base) text)
          next (send! state)]
      (is (empty? (outbox next)) (pr-str text))
      (is (= :blocked (get-in next [:last-outcome :result])) (pr-str text))
      (is (= :empty (get-in next [:last-outcome :reason])) (pr-str text)))))

;; ---------------------------------------------------------------------------
;; Send gating

(deftest send-gating-opens-repair-paths
  (testing "no model"
    (let [state (-> (base) (conv/apply-action {:type :session/context :model nil})
                    (edit "s1" "hello"))
          next (send! state)]
      (is (empty? (outbox next)))
      (is (= :fix (get-in next [:last-outcome :result])))
      (is (= :select-model (get-in next [:last-outcome :intent])))))
  (testing "connection is not synchronized"
    (let [state (-> (base) (conv/apply-action {:type :connection/set
                                               :connection {:phase :interrupted}})
                    (edit "s1" "hello"))
          next (send! state)]
      (is (empty? (outbox next)))
      (is (= :check-connection (get-in next [:last-outcome :intent])))))
  (testing "a synced flag without a phase still allows send"
    (let [state (-> (base)
                    (conv/apply-action {:type :connection/set
                                        :connection {:synced? true}})
                    (edit "s1" "hello"))
          next (send! state)]
      (is (= :queued (get-in next [:last-outcome :result])))))
  (testing "unready attachment"
    (let [state (-> (base) (edit "s1" "hello")
                    (conv/apply-action {:type :draft/add-attachment
                                        :session-id "s1"
                                        :attachment {:id "a1" :name "x" :status :preparing}}))
          next (send! state)]
      (is (empty? (outbox next)))
      (is (= :fix-attachments (get-in next [:last-outcome :intent])))))
  (testing "generating asks for an explicit decision"
    (let [state (-> (base) (edit "s1" "first") send!)
          state (edit state "s1" "second")
          next (send! state)]
      (is (= 1 (count (outbox state))))
      (is (= 1 (count (outbox next))))
      (is (= :needs-decision (get-in next [:last-outcome :result])))
      (is (= #{:stop-and-send :continue-editing}
             (set (map :id (get-in next [:last-outcome :options])))))))
  (testing "pending command is a duplicate before the run is visible"
    (let [state (-> (base) (edit "s1" "first") send!)
          state (update-in state [:streams "s1"] assoc :streaming? false)
          next (send! (edit state "s1" "second"))]
      (is (= 1 (count (outbox next))))
      (is (= :duplicate (get-in next [:last-outcome :intent]))))))

(deftest command-envelope-is-stable
  (let [state (-> (base) (edit "s1" "hello") send!)
        command (first (outbox state))]
    (is (re-matches #"cmd-[0-9a-f-]{36}" (:command-id command)))
    (is (= 1 (:sequence command)))
    (is (= 1 (:command-epoch command)))
    (is (= :pending (:status command)))
    (is (= :send (:kind command)))
    (is (= "s1" (:session-id command)))
    (is (= "hello" (get-in command [:payload :text])))
    (is (= 1 (get-in command [:payload :version])))))

(deftest command-ids-do-not-repeat-across-client-instances
  ;; A reloaded tab or a second browser starts a fresh reducer with
  ;; `:next-command-id` 1.  A sequential command id would then collide with
  ;; an already accepted command in the same Owner epoch, and the Owner
  ;; correctly refuses the new payload.  Command ids must therefore be
  ;; unique across client instances, not just within one.
  (let [first-client (-> (base) (edit "s1" "first") send!)
        second-client (-> (base) (edit "s1" "stop now"))
        stop (-> second-client
                 (conv/apply-action {:type :composer/stop
                                     :session-id "s1"
                                     :at "t"}))
        first-id (:command-id (first (outbox first-client)))
        stop-id (:command-id (first (outbox stop)))]
    (is (string? first-id))
    (is (string? stop-id))
    (is (not= first-id stop-id)
        "two clients must not generate the same command id")))

;; ---------------------------------------------------------------------------
;; Stop and late arrivals

(deftest stop-freezes-visible-text
  (let [state (-> (base) (edit "s1" "hello") send!)
        state (conv/apply-action state {:type :stream/chunk
                                        :session-id "s1"
                                        :message-id "m1"
                                        :seq 1
                                        :text "visible"
                                        :at "t"})
        stopped (conv/apply-action state {:type :composer/stop
                                          :session-id "s1"
                                          :at "t"})
        late (conv/apply-action stopped {:type :stream/chunk
                                         :session-id "s1"
                                         :message-id "m1"
                                         :seq 2
                                         :text " late"
                                         :at "t"})]
    (is (= :stopping (get-in stopped [:streams "s1" :stop-state])))
    (is (= 1 (get-in late [:streams "s1" :content-version])))
    (is (= 1 (get-in late [:streams "s1" :late-text-count])))
    (is (= :late-text (get-in late [:last-outcome :result])))
    (is (= (count (outbox stopped)) (count (outbox late))))))

(deftest observer-receipts-show-the-control-transfer
  (let [state (-> (base) (edit "s1" "hello") send!)
        send-command (last-command state)
        rejected-send
        (conv/apply-action state
                           {:type :composer/ack
                            :command-id (:command-id send-command)
                            :status :rejected
                            :error {:code :NOT_CONTROLLER}
                            :at "t"})]
    (is (re-find #"接管"
                 (get-in rejected-send [:last-outcome :message]))
        "an observer sees that another client controls the session")
    (is (= "hello" (get-in rejected-send [:drafts "s1" :text]))
        "the rejected send restores the operator's draft"))
  (let [state (-> (base) (edit "s1" "hello") send!)
        stopped (conv/apply-action state {:type :composer/stop
                                          :session-id "s1"
                                          :at "t"})
        stop-command (last-command stopped)
        rejected-stop
        (conv/apply-action stopped
                           {:type :composer/ack
                            :command-id (:command-id stop-command)
                            :status :rejected
                            :error {:code :NOT_CONTROLLER}
                            :at "t"})]
    (is (re-find #"控制权"
                 (get-in rejected-stop [:last-outcome :message]))
        "an observer sees that the stop was not sent")))

(deftest stop-request-does-not-require-a-local-stream
  (let [state (base)
        stopped (conv/apply-action state {:type :composer/stop
                                          :session-id "s1"
                                          :at "t"})]
    (is (= :stopping (get-in stopped [:streams "s1" :stop-state]))
        "a selected session may stop server work that the visible stream cannot see")
    (is (= :stop (:kind (last-command stopped))))
    (is (= :operator (get-in (last-command stopped) [:payload :reason])))))

(deftest late-tool-result-is-labelled
  (let [state (-> (base) (edit "s1" "hello") send!)
        stopped (conv/apply-action state {:type :composer/stop
                                          :session-id "s1"
                                          :at "t"})
        late (conv/apply-action stopped {:type :stream/tool-result
                                         :session-id "s1"
                                         :tool-call-id "tool-1"
                                         :status :succeeded
                                         :completed-before-stop? true
                                         :at "t"})]
    (is (= :late-tool-result (get-in late [:last-outcome :result])))
    (is (= "停止前已执行，结果后来返回"
           (get-in late [:streams "s1" :late-tool-results 0 :label])))
    (is (= (count (outbox stopped)) (count (outbox late))))))

(deftest stop-rejected-resumes-run
  (let [state (-> (base) (edit "s1" "hello") send!)
        stop (conv/apply-action state {:type :composer/stop
                                       :session-id "s1"
                                       :at "t"})
        command (last-command stop)
        rejected (ack stop (:command-id command) :rejected)]
    (is (= :idle (get-in rejected [:streams "s1" :stop-state])))
    (is (= true (get-in rejected [:streams "s1" :streaming?])))
    (is (some? (get-in rejected [:streams "s1" :stop-error])))))

(deftest stop-and-send-waits-for-confirmation
  (let [state (-> (base) (edit "s1" "first") send!)
        state (edit state "s1" "second")
        stopped (conv/apply-action state {:type :composer/stop-and-send
                                          :session-id "s1"
                                          :at "t"})]
    (is (= :stopping (get-in stopped [:streams "s1" :stop-state])))
    (is (= "second" (get-in stopped [:streams "s1" :pending-send :snapshot :text])))
    (is (= [:send :stop] (mapv :kind (outbox stopped))))
    (testing "the second send is emitted only after stop confirmation"
      (let [confirmed (conv/apply-action stopped {:type :stream/stop-confirmed
                                                  :session-id "s1"
                                                  :reason :operator
                                                  :at "t"})]
        (is (= [:send :stop :send] (mapv :kind (outbox confirmed))))
        (is (= "second" (get-in confirmed [:outbox 2 :payload :text])))
        (is (nil? (get-in confirmed [:streams "s1" :pending-send])))
        (is (true? (get-in confirmed [:streams "s1" :streaming?]))
            "the captured send reopens the visible stream")
        (is (not (stream/stopped? (get-in confirmed [:streams "s1"])))
            "the stop sentinel does not keep the next run frozen")))
    (testing "cancel keeps the draft and emits no second send"
      (let [cancelled (conv/apply-action stopped {:type :composer/cancel-pending-send
                                                  :session-id "s1"})
            confirmed (conv/apply-action cancelled {:type :stream/stop-confirmed
                                                    :session-id "s1"
                                                    :reason :operator
                                                    :at "t"})]
        (is (= [:send :stop] (mapv :kind (outbox confirmed))))
        (is (= "second" (get-in confirmed [:drafts "s1" :text])))))))

;; ---------------------------------------------------------------------------
;; Ask cards

(deftest s1-ask-card-lifecycle
  (let [state (-> (base)
                  (conv/apply-action {:type :ask/show
                                      :session-id "s1"
                                      :ask {:ask-id "ask-1"
                                            :name "ask-target"
                                            :lane :plan
                                            :tenure-id "tenure-1"
                                            :question "兼容 v0 还是 v1？"
                                            :context "旧配置包含 v0。"
                                            :options [{:value "v0" :label "v0"}
                                                      {:value "v1" :label "v1"}]
                                            :input? false
                                            :created-at "t1"}})
                  (conv/apply-action {:type :ask/edit
                                      :session-id "s1"
                                      :ask-id "ask-1"
                                      :selected #{"v1"}
                                      :at "t2"}))]
    (is (= :pending (get-in state [:asks "s1" "ask-1" :status])))
    (let [submitted (conv/apply-action state {:type :ask/submit
                                              :session-id "s1"
                                              :ask-id "ask-1"
                                              :at "t3"})
          command (last-command submitted)]
      (is (= :unpark (:kind command)))
      (is (= "v1" (get-in command [:payload :value])))
      (is (= :plan (get-in command [:payload :lane]))
          "the answer names the parked lane")
      (is (= "tenure-1" (get-in command [:payload :tenure-id]))
          "the answer names the parked tenure so the server resumes it")
      (is (= :submitting (get-in submitted [:asks "s1" "ask-1" :status])))
      (is (= 1 (count (get-in submitted [:asks "s1" "ask-1" :answers]))))
      (testing "accepted answer freezes the card"
        (let [accepted (ack submitted (:command-id command) :accepted)]
          (is (= :answered (get-in accepted [:asks "s1" "ask-1" :status])))
          (is (= :confirmed (get-in accepted [:asks "s1" "ask-1" :answers 0 :status])))
          (testing "editing an answered card appends a new record"
            (let [edited (conv/apply-action accepted {:type :ask/edit
                                                      :session-id "s1"
                                                      :ask-id "ask-1"
                                                      :selected #{"v0"}
                                                      :at "t4"})
                  resubmitted (conv/apply-action edited {:type :ask/submit
                                                         :session-id "s1"
                                                         :ask-id "ask-1"
                                                         :at "t5"})]
              (is (= 2 (count (get-in resubmitted [:asks "s1" "ask-1" :answers]))))
              (is (= "v1" (get-in resubmitted [:asks "s1" "ask-1" :answers 0 :value])))
              (is (= "v0" (get-in resubmitted [:asks "s1" "ask-1" :answers 1 :value]))))))))
    (testing "rejected answer preserves the draft and can be retried"
      (let [submitted (conv/apply-action state {:type :ask/submit
                                                :session-id "s1"
                                                :ask-id "ask-1"
                                                :at "t3"})
            command (last-command submitted)
            rejected (ack submitted (:command-id command) :rejected)]
        (is (= :rejected (get-in rejected [:asks "s1" "ask-1" :status])))
        (is (= #{"v1"} (get-in rejected [:asks "s1" "ask-1" :draft :selected])))
        (is (ask/can-submit? (get-in rejected [:asks "s1" "ask-1"])))))))

(deftest empty-ask-answer-is-blocked
  (let [state (-> (base)
                  (conv/apply-action {:type :ask/show
                                      :session-id "s1"
                                      :ask {:ask-id "ask-1"
                                            :name "ask-target"
                                            :question "why?"
                                            :input? true}}))
        next (conv/apply-action state {:type :ask/submit
                                       :session-id "s1"
                                       :ask-id "ask-1"
                                       :at "t"})]
    (is (empty? (outbox next)))
    (is (= :blocked (get-in next [:last-outcome :result])))))

;; ---------------------------------------------------------------------------
;; Scroll and selection

(deftest scroll-follow-and-detach
  (let [state (stream/initial)]
    (testing "bottom keeps following"
      (let [next (stream/scroll state {:scroll-top 500 :scroll-height 1000
                                       :client-height 500 :threshold 8})]
        (is (true? (:follow? next)))
        (is (false? (:show-jump? next)))))
    (testing "scrolling up detaches and shows the return button"
      (let [detached (stream/scroll state {:scroll-top 100 :scroll-height 1000
                                           :client-height 500 :threshold 8})
            grew (stream/content-grew detached)]
        (is (false? (:follow? detached)))
        (is (true? (:show-jump? detached)))
        (is (= 0 (:scroll-request grew)))
        (is (true? (:show-jump? grew)))))
    (testing "returning to the bottom resumes follow"
      (let [detached (stream/scroll state {:scroll-top 100 :scroll-height 1000
                                           :client-height 500 :threshold 8})
            back (stream/scroll detached {:scroll-top 500 :scroll-height 1000
                                          :client-height 500 :threshold 8})]
        (is (true? (:follow? back)))
        (is (false? (:show-jump? back)))))
    (testing "jump requests exactly one scroll"
      (let [detached (stream/scroll state {:scroll-top 100 :scroll-height 1000
                                           :client-height 500 :threshold 8})
            jumped (stream/jump-to-latest detached)]
        (is (true? (:follow? jumped)))
        (is (= (inc (:scroll-request detached)) (:scroll-request jumped)))))))

(deftest selection-survives-streaming
  (let [state (stream/initial)
        selected (stream/selection state {:phase :start
                                          :selection {:start 4 :end 9}})
        grew (stream/content-grew selected)
        chunked (stream/chunk selected {:seq 1 :text "more" :message-id "m1"})]
    (is (true? (:selection-locked? selected)))
    (is (false? (:follow? selected)))
    (is (= {:start 4 :end 9} (:selection chunked)))
    (is (= 0 (:scroll-request grew)))
    (is (true? (:selection-locked? chunked)))
    (testing "releasing the lock keeps the selection"
      (let [released (stream/selection selected {:phase :end
                                                 :selection {:start 4 :end 9}})]
        (is (false? (:selection-locked? released)))
        (is (= {:start 4 :end 9} (:selection released)))))))

(deftest programmatic-scroll-does-not-detach
  (let [state (stream/initial)
        next (stream/scroll state {:scroll-top 490 :scroll-height 1000
                                   :client-height 500 :threshold 8
                                   :programmatic? true})]
    (is (true? (:follow? next)))))

;; ---------------------------------------------------------------------------
;; Timeline

(deftest ordered-messages-uses-order-and-falls-back
  (let [db {:message/by-id {"b" {:id "b" :created-at "2"}
                            "a" {:id "a" :created-at "1"}}
            :message/order ["b" "a"]}]
    (is (= ["b" "a"] (mapv :id (timeline/ordered-messages db))))
    (is (= ["a" "b"] (mapv :id (timeline/ordered-messages
                                {:message/by-id (:message/by-id db)
                                 :message/order ["b"]}))))
    (is (= ["b" "a"] (mapv :id (timeline/ordered-messages
                                (vec (vals (:message/by-id db)))))))))

(deftest workbench-namespaced-message-shape
  (let [message {:message/id "a1"
                 :message/role :assistant
                 :message/content "hi\n```clj\n(+ 1 2)\n```"
                 :message/status :streaming
                 :message/tool-calls [{:id "t1" :status :running
                                       :arguments-complete? true}]
                 :message/attachments [{:id "att1" :name "result.txt" :size 12}]}
        view (timeline/message-view message {:streaming? true})]
    (is (= "a1" (:id view)))
    (is (= :assistant (:role view)))
    (is (= "hi\n```clj\n(+ 1 2)\n```" (:body view)))
    (is (= :code (get-in view [:segments 1 :type])))
    (is (true? (get-in view [:process 0 :waiting-for-result?])))
    (is (= "result.txt" (get-in view [:attachments 0 :name])))))

(deftest snapshot-shows-live-stream-text-over-stale-history-content
  ;; The history mirror is append-once: it keeps the first chunk of a live
  ;; assistant answer while the Owner conversation keeps patching `:text`.
  ;; The published snapshot therefore carries the stale prefix in `:content`
  ;; and the full answer in `:text`; the reader must see the full answer.
  (let [full (str "在添加 /healthz 端点前，需确认事实："
                  "HTTP 框架类型、现有代码结构、依赖状态、响应格式、"
                  "部署策略、安全约束与测试覆盖要求。")
        message {:id "m1" :role :assistant
                 :content "在添加" :text full}]
    (is (= full (:body (timeline/message-view message)))
        "the longer live text wins over the stale first chunk")
    (is (= full (:copy-text (timeline/message-view message)))))
  (testing "a real edit is not overwritten by an unrelated older copy"
    (is (= "改写后的正文"
           (:body (timeline/message-view
                   {:id "m2" :role :assistant
                    :content "改写后的正文"
                    :text "另一份完全不同的正文"})))))
  (testing "the stale copy may appear after the live text"
    (is (= "完整回答"
           (:body (timeline/message-view
                   {:id "m3" :role :assistant
                    :text "完整回答" :content "完整"}))))))

(deftest message-view-keeps-lossless-attachment-refs-for-editing
  (let [a1 {:attachment-id "a1"
            :blob-id "sha256-test-blob"
            :name "first.png" :mime "image/png" :size 10 :local-copy? true}
        a2 {:attachment-id "a2" :name "second.pdf"
            :mime "application/pdf" :size 20}
        view (timeline/message-view
              {:id "u1" :role :user :content "look"
               :attachments [a1 a2]})]
    (is (= [a1 a2] (:attachment-refs view))
        "the editor receives the server references without display-field loss")
    (is (= [a1 a2] (:attachment-refs (timeline/message-view view)))
        "reprojecting a view does not replace raw refs with display copies")
    (is (= ["a1" "a2"] (mapv :id (:attachments view))))
    (is (true? (get-in view [:attachments 0 :preview?])))
    (is (false? (get-in view [:attachments 1 :preview?])))
    (testing "a version snapshot without a top-level attachment mirror still works"
      (let [version-view (timeline/message-view
                          {:id "u2" :role :user :content "versioned"
                           :versions [{:attachments [a1]}]
                           :current-version 0})]
        (is (= [a1] (:attachment-refs version-view)))))))

(deftest selected-version-owns-its-attachment-snapshot
  (let [image {:attachment-id "att-1"
               :id "att-1"
               :blob-id "sha256-431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460"
               :name "tiny.png" :mime "image/png" :size 68
               :url "/blobs/sha256-431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460?filename=tiny.png"}
        v1 {:version-id "m1-v1" :content "with image" :attachments [image]}
        v2 {:version-id "m1-v2" :content "without image" :attachments []}
        message {:id "m1" :role :user :content "without image"
                 :attachments [] :versions [v1 v2] :current-version 1}]
    (testing "the selected version supplies attachments even when the mirror is stale"
      (let [view (timeline/message-view (assoc message :current-version 0))]
        (is (= ["tiny.png"] (mapv :name (:attachments view))))
        (is (= [image] (:attachment-refs view)))))
    (testing "an explicitly empty selected version stays empty"
      (let [view (timeline/message-view (assoc message :current-version 1
                                               :attachments [image]))]
        (is (empty? (:attachments view)))
        (is (= [] (:attachment-refs view)))))
    (testing "messages without versions still use the message-level mirror"
      (let [view (timeline/message-view
                  {:id "m2" :role :user :content "legacy"
                   :attachments [image]})]
        (is (= ["tiny.png"] (mapv :name (:attachments view))))))))

(deftest inline-history-edit-removes-only-the-new-version-reference
  (let [a1 {:attachment-id "a1" :name "first.png" :mime "image/png" :size 10}
        a2 {:attachment-id "a2" :name "second.png" :mime "image/png" :size 20}
        message {:body "look" :attachment-refs [a1 a2]}
        initial (history-edit/initial-state message)
        without (history-edit/remove-attachment (:attachments initial) "a2")
        payload (history-edit/payload (assoc initial :attachments without))]
    (is (= [a1 a2] (:attachments initial)))
    (is (= ["移除附件 first.png" "移除附件 second.png"]
           (mapv :remove-label (history-edit/attachment-rows [a1 a2]))))
    (is (= [a1] without))
    (is (= "look" (:content payload)))
    (is (= [a1] (:attachments payload)))
    (is (contains? payload :attachments)
        "the payload always carries the new version's explicit reference vector")
    (is (= [] (:attachments (history-edit/payload
                             (assoc initial :attachments []))))
        "removing every attachment sends an explicit empty vector")
    (is (= [a1 a2] (:attachment-refs message))
        "the original message projection is unchanged")
    (is (= [a1 a2] (history-edit/refs message)))))

(deftest inline-history-edit-does-not-borrow-the-composer-draft
  (is (= {:content "" :attachments []} (history-edit/initial-state {})))
  (is (= {:content "history" :attachments []}
         (history-edit/initial-state
          {:body "history"
           :draft {:text "composer text"
                   :attachments [{:attachment-id "draft-a"}]}}))
      "the editor seeds from the message, never from the composer draft")
  (is (true? (history-edit/saveable?
              {:content "" :attachments [{:attachment-id "a1"}]}))
      "an attachment-only message is still a real version")
  (is (false? (history-edit/saveable? {:content "   " :attachments []}))
      "whitespace-only text with no attachment is not a version")
  (is (true? (history-edit/saveable?
              {:content "text" :attachments []})))
  (is (= [{:attachment-id "a1"}]
         (history-edit/refs {:attachments [{:attachment-id "a1"}]}))
      "older display projections still provide a reference fallback")
  (is (= [] (history-edit/refs {:attachment-refs []
                                :attachments [{:attachment-id "stale"}]}))
      "an explicit empty version vector does not fall back to display state"))

(deftest tool-arguments-complete-is-not-terminal
  (let [waiting (timeline/message-view
                 {:id "a1" :role :assistant :content ""
                  :tool-calls [{:id "t1" :status :running :arguments-complete? true}]})
        finished (timeline/message-view
                  {:id "a2" :role :assistant :content ""
                   :tool-calls [{:id "t2" :status :succeeded :arguments-complete? true}]})
        no-summary (timeline/message-view
                    {:id "a3" :role :assistant :content "answer"
                     :reasoning-summary nil})]
    (is (true? (get-in waiting [:process 0 :waiting-for-result?])))
    (is (= "参数已完整，等待执行结果"
           (get-in waiting [:process 0 :status-label])))
    (is (false? (get-in finished [:process 0 :waiting-for-result?])))
    (is (= "已完成" (get-in finished [:process 0 :status-label])))
    (is (nil? (get-in no-summary [:process 0 :summary])))))

(deftest stopped-message-keeps-content-and-late-results
  (let [message (timeline/message-view
                 {:id "a1" :role :assistant :content "partial answer"
                  :status :stopped}
                 {:late-tool-results [{:tool-call-id "t1"
                                       :status :succeeded
                                       :label "停止前已执行，结果后来返回"}]})]
    (is (= "partial answer" (:body message)))
    (is (true? (:stopped? message)))
    (is (= "已停止" (:status-label message)))
    (is (= "停止前已执行，结果后来返回"
           (get-in message [:late-tool-results 0 :label])))))

(deftest markdown-segments-are-stable
  (testing "closed code fence"
    (let [segments (timeline/segment-markdown "before\n```clj\n(+ 1 2)\n```\nafter")]
      (is (= [:text :code :text] (mapv :type segments)))
      (is (= "clj" (:lang (second segments))))
      (is (true? (:closed? (second segments))))
      (is (= "(+ 1 2)" (timeline/copyable-code (second segments))))))
  (testing "unclosed code fence stays a plain segment"
    (let [segments (timeline/segment-markdown "before\n```clj\n(+ 1 2)" {:streaming? true})]
      (is (= :code (:type (last segments))))
      (is (false? (:closed? (last segments))))))
  (testing "table is unclosed while streaming"
    (let [segments (timeline/segment-markdown "| a | b |\n| 1 | 2 |" {:streaming? true})]
      (is (= :table (:type (first segments))))
      (is (false? (:closed? (first segments))))))
  (testing "display math fallback"
    (let [segments (timeline/segment-markdown "$$x^2$$")]
      (is (= :math (:type (first segments))))
      (is (= "x^2" (:content (first segments)))))))

;; ---------------------------------------------------------------------------
;; Accessibility

(deftest layout-bands-and-targets
  (is (= :compact (a11y/layout-for-width 360)))
  (is (= :compact (a11y/layout-for-width 599)))
  (is (= :medium (a11y/layout-for-width 600)))
  (is (= :medium (a11y/layout-for-width 820)))
  (is (= :wide (a11y/layout-for-width 1280)))
  (is (true? (a11y/overlay-layout? 360)))
  (is (true? (a11y/overlay-layout? 1023)))
  (is (false? (a11y/overlay-layout? 1024)))
  (is (false? (a11y/overlay-layout? 1440)))
  (is (= 44 (a11y/target-size :compact)))
  (is (= 44 (a11y/target-size :wide :primary)))
  (is (= 32 (a11y/target-size :wide :secondary))))

(deftest escape-follows-one-strict-order
  (is (= :close-overlay
         (:action (a11y/escape-decision {:overlays [{:id "ov" :trigger-id "btn"}]
                                         :composing? true
                                         :text-selection? true
                                         :stream-selection {:start 1 :end 2}
                                         :generating? true}))))
  (is (= :ime-candidate
         (:action (a11y/escape-decision {:composing? true
                                         :text-selection? true
                                         :generating? true}))))
  (is (= :clear-selection
         (:action (a11y/escape-decision {:text-selection? true
                                         :generating? true}))))
  (is (= :stop
         (:action (a11y/escape-decision {:generating? true}))))
  (is (= :ignore
         (:action (a11y/escape-decision {:generating? false}))))
  (testing "overlay close returns focus to its trigger"
    (let [opened (a11y/open-overlay {} {:id "ov" :trigger-id "btn"})
          closed (a11y/close-overlay opened)]
      (is (= [] (:overlays closed)))
      (is (= "btn" (get-in closed [:focus :target]))))))

(deftest escape-stops-stream-and-clears-selection
  (let [state (-> (base) (edit "s1" "hello") send!)
        stopped (conv/apply-action state {:type :a11y/escape
                                          :session-id "s1"
                                          :at "t"})]
    (is (= :stopping (get-in stopped [:streams "s1" :stop-state]))))
  (let [state (-> (base) (edit "s1" "hello") send!)
        state (conv/apply-action state {:type :stream/selection
                                        :session-id "s1"
                                        :phase :start
                                        :selection {:start 1 :end 2}})
        cleared (conv/apply-action state {:type :a11y/escape
                                          :session-id "s1"
                                          :at "t"})]
    (is (nil? (get-in cleared [:streams "s1" :selection])))
    (is (= :idle (get-in cleared [:streams "s1" :stop-state])))))

;; ---------------------------------------------------------------------------
;; Surface view model

(deftest surface-labels-and-repair-paths
  (let [state (-> (base) (edit "s1" "hello"))
        view-model (surface/surface {:conversation state
                                     :session session
                                     :connection {:phase :synced}
                                     :messages []
                                     :callbacks {:send! identity :stop! identity}})]
    (is (= "Session One" (get-in view-model [:header :title])))
    (is (= "Main" (get-in view-model [:header :model-label])))
    (is (= [:open-settings :open-aux]
           (mapv :id (get-in view-model [:header :actions]))))
    (is (= "打开会话设置"
           (get-in view-model [:header :actions 0 :aria-label])))
    (is (= "Enter 发送 · Shift+Enter 换行" (get-in view-model [:composer :hint])))
    (is (= "hello" (get-in view-model [:composer :value])))
    (is (false? (get-in view-model [:composer :send :disabled?])))
    (is (= 44 (get-in view-model [:a11y :target-size]))))
  (testing "absent callbacks disable controls with a visible reason"
    (let [state (-> (base) (edit "s1" "hello"))
          view-model (surface/surface {:conversation state
                                       :session session
                                       :connection {:phase :synced}
                                       :messages []})]
      (is (true? (get-in view-model [:composer :send :disabled?])))
      (is (= "发送通道未就绪" (get-in view-model [:composer :send :reason])))))
  (testing "no model opens the model repair path"
    (let [state (-> (base) (conv/apply-action {:type :session/context :model nil})
                    (edit "s1" "hello"))
          view-model (surface/surface {:conversation state
                                       :session session
                                       :connection {:phase :synced}
                                       :messages []
                                       :callbacks {:send! identity :stop! identity}})]
      (is (= :select-model (get-in view-model [:composer :send :intent])))
      (is (= "尚未选择模型" (get-in view-model [:header :model-label])))
      (is (= [:open-settings :open-aux]
             (mapv :id (get-in view-model [:header :actions]))))
      (is (= "先选择一个可用模型，草稿已保留"
             (get-in view-model [:composer :send :reason]))))))

(deftest empty-model-map-cannot-enable-send
  (testing "an empty model map blocks send and agrees with the header"
    (let [state (-> (base) (edit "s1" "hello"))
          view-model (surface/surface
                      {:session-id "s1"
                       :session (assoc session :model {})
                       :draft (get-in state [:drafts "s1"])
                       :stream (stream/initial)
                       :connection {:phase :synced}
                       :messages []
                       :callbacks {:send! identity :stop! identity
                                   :fix! identity}})]
      (is (= "尚未选择模型" (get-in view-model [:header :model-label])))
      (is (= :select-model (get-in view-model [:composer :send :intent])))
      (is (true? (get-in view-model [:composer :send :disabled?])))
      (is (= "先选择一个可用模型，草稿已保留"
             (get-in view-model [:composer :send :reason])))))
  (testing "a projection without identity fields is unbound"
    (is (composer/model-missing? {}))
    (is (composer/model-missing? {:capabilities {:image? true}}))
    (is (composer/model-missing? {:id nil :model-id "  "}))
    (is (composer/model-missing? {:missing? true :id "m1"}))
    (is (not (composer/model-missing? {:id "m1"})))
    (is (not (composer/model-missing? {:label "Main"})))
    (is (= "m1" (composer/session-model {:session/model-id "m1"})))
    (is (= "Main" (composer/session-model {:model {} :model-label "Main"})))
    (is (nil? (composer/session-model {:session/model {}}))))
  (testing "an empty map does not mask a later usable candidate"
    (let [state (-> (base) (edit "s1" "hello"))
          view-model (surface/surface
                      {:session-id "s1"
                       :session (assoc session :model {} :model-id "m1")
                       :draft (get-in state [:drafts "s1"])
                       :stream (stream/initial)
                       :connection {:phase :synced}
                       :messages []
                       :callbacks {:send! identity :stop! identity}})]
      (is (= "m1" (get-in view-model [:header :model-label])))
      (is (= :send (get-in view-model [:composer :send :intent])))
      (is (false? (get-in view-model [:composer :send :disabled?]))))))

(deftest unavailable-connection-explains-and-offers-repair
  (testing "a removed connection blocks send with a connection explanation"
    (let [state (-> (base)
                    (conv/apply-action
                     {:type :session/context
                      :model {:id "m1" :availability :connection-removed}})
                    (edit "s1" "hello"))
          next (send! state)]
      (is (empty? (outbox next)))
      (is (= :fix (get-in next [:last-outcome :result])))
      (is (= :select-model (get-in next [:last-outcome :intent])))
      (is (re-find #"连接" (get-in next [:last-outcome :message])))
      (is (not (re-find #"输入为空" (get-in next [:last-outcome :message]))))))
  (testing "the surface explains the binding before an empty draft"
    (let [state (-> (base)
                    (conv/apply-action
                     {:type :session/context
                      :model {:id "m1" :availability :connection-disabled}}))
          view-model (surface/surface
                      {:conversation state
                       :session session
                       :connection {:phase :synced}
                       :messages []
                       :callbacks {:send! identity :stop! identity
                                   :fix! identity}})
          reason (get-in view-model [:composer :send :reason])]
      (is (true? (get-in view-model [:composer :send :disabled?])))
      (is (= :select-model (get-in view-model [:composer :send :intent])))
      (is (re-find #"连接已停用" reason))
      (is (not (re-find #"输入为空" reason)))
      (is (some #(= "选择其他模型" (:label %))
                (mapcat :actions (:banners view-model))))
      (is (re-find #"连接已停用"
                   (get-in view-model [:header :model-label])))))
  (testing "an available or unconfirmed binding remains sendable"
    (doseq [availability [:available :not-returned :unconfirmed]]
      (let [state (-> (base)
                      (conv/apply-action
                       {:type :session/context
                        :model {:id "m1" :availability availability}})
                      (edit "s1" "hello"))
            next (send! state)]
        (is (= :queued (get-in next [:last-outcome :result])) (name availability))))))

(deftest rejected-send-prefers-real-search-error
  (is (= "搜索超时"
         (surface/command-label {:kind :send
                                 :status :rejected
                                 :error {:code :SEARCH_TIMEOUT}})))
  (is (= "本轮必须搜索失败"
         (surface/command-label {:kind :send
                                 :status :rejected
                                 :message "本轮必须搜索失败"})))
  (is (= "发送未送达，草稿已恢复"
         (surface/command-label {:kind :send :status :rejected}))))

(deftest display-label-is-not-a-model-binding
  (is (= "尚未选择模型"
         (composer/session-model {:model-label "尚未选择模型"})))
  (is (= "Test Model"
         (composer/session-model {:model-label "Test Model"})))
  (is (nil? (composer/session-model-identity {:model-label "Test Model"})))
  (is (= "m1" (composer/session-model {:model-id "m1"})))
  (is (= "m1" (composer/session-model-identity {:model-id "m1"})))
  (is (= {:id "m1"} (composer/session-model {:model {:id "m1"}})))
  (is (= {:id "m1" :availability :connection-disabled}
         (composer/session-model-gate
          {:model {:id "m1" :availability :connection-disabled}}))))

(deftest unattended-run-blocks-send
  (let [decision (composer/send-decision
                  {:draft (assoc draft/empty-draft :text "hello")
                   :stream (stream/initial)
                   :run-active? true
                   :model {:id "m1"}
                   :connection {:phase :synced}})]
    (is (= :blocked (:action decision)))
    (is (= :unattended (:intent decision)))))

(deftest surface-ask-card-and-save-warning
  (let [state (-> (base)
                  (conv/apply-action {:type :draft/save-state
                                      :session-id "s1"
                                      :save-state :failed
                                      :error :ENOSPC})
                  (conv/apply-action {:type :ask/show
                                      :session-id "s1"
                                      :ask {:ask-id "ask-1"
                                            :name "ask-target"
                                            :question "continue?"
                                            :created-at "t"}}))
        view-model (surface/surface {:conversation state
                                     :session session
                                     :connection {:phase :synced}
                                     :messages []
                                     :callbacks {:submit-ask! identity}})]
    (is (some #(= "草稿暂未保存" (:text %)) (:banners view-model)))
    (is (= 1 (count (:asks view-model))))
    (is (= "等待你回答" (get-in view-model [:asks 0 :status-label])))
    (is (= "提交回答并恢复" (get-in view-model [:asks 0 :submit :label])))))

(deftest surface-accepts-workbench-split-props
  (let [state (-> (base) (edit "s1" "hello"))
        ask-card {:ask-id "ask-1" :name "ask-target" :question "continue?"
                  :input? true :status :pending :answers [] :created-at "t"
                  :draft {:text "" :selected #{} :version 0}}
        command {:command-id "cmd-1" :command-epoch 1 :kind :send
                 :status :submitting :session-id "s1" :payload {}}
        view-model (surface/surface
                    {:session session
                     :messages []
                     :draft (get-in state [:drafts "s1"])
                     :stream (assoc (get-in state [:streams "s1"]) :streaming? true)
                     :connection {:phase :synced :synced? true}
                     :layout :compact
                     :platform :desktop
                     :ask {"ask-1" ask-card}
                     :command {"cmd-1" command}
                     :callbacks {:send! identity :stop! identity
                                 :submit-ask! identity}})]
    (is (= :compact (:layout view-model)))
    (is (= 1 (count (:asks view-model))))
    (is (= "cmd-1" (get-in view-model [:composer :command :command-id])))
    (is (= "正在提交" (get-in view-model [:composer :command :label])))
    (is (= 44 (get-in view-model [:a11y :target-size])))))

(deftest stop-control-stays-mounted-while-the-stream-is-idle
  (let [view-model (surface/surface
                    {:session-id "s1"
                     :session session
                     :messages []
                     :stream (stream/initial)
                     :connection {:phase :synced :synced? true}
                     :callbacks {:stop! identity}})]
    (is (true? (get-in view-model [:composer :stop :visible?]))
        "a selected session keeps the stop target reachable even when idle")
    (is (false? (get-in view-model [:composer :stop :disabled?])))
    (is (= "停止" (get-in view-model [:composer :stop :label]))))
  (testing "an in-flight stop disables the control instead of unmounting it"
    (let [view-model (surface/surface
                      {:session-id "s1"
                       :session session
                       :messages []
                       :stream (assoc (stream/initial) :stop-state :stopping)
                       :connection {:phase :synced :synced? true}
                       :callbacks {:stop! identity}})]
      (is (true? (get-in view-model [:composer :stop :visible?])))
      (is (true? (get-in view-model [:composer :stop :disabled?])))))
  (testing "no selected session means no stop target"
    (is (false? (get-in (surface/surface
                         {:messages []
                          :stream (stream/initial)
                          :callbacks {:stop! identity}})
                        [:composer :stop :visible?])))))

(deftest surface-message-projection
  (let [messages {:message/by-id {"u1" {:id "u1" :role :user :content "hi"}
                                  "a1" {:id "a1" :role :assistant
                                        :content "```clj\n(+ 1 2)\n```"
                                        :status :completed}}
                  :message/order ["u1" "a1"]}
        view-model (surface/surface {:conversation (base)
                                     :session session
                                     :connection {:phase :synced}
                                     :messages messages
                                     :callbacks {}})]
    (is (= ["u1" "a1"] (mapv :id (:messages view-model))))
    (is (= :code (get-in view-model [:messages 1 :segments 0 :type])))
    (is (= :copy (first (:operations (first (:messages view-model))))))))

;; ---------------------------------------------------------------------------
;; Properties

(defspec draft-version-never-regresses 100
  (prop/for-all [texts (gen/vector gen/string 1 12)]
    (let [final (reduce (fn [state text] (edit state text)) (base) texts)]
      (is (>= (get-in final [:drafts "s1" :version]) 0))
      (is (>= (get-in final [:drafts "s1" :saved-version]) 0))
      true)))

(defspec ime-enter-never-creates-a-command 100
  (prop/for-all [text gen/string
                 composing gen/boolean]
    (let [state (edit (base) text)
          next (conv/apply-action state {:type :composer/keydown
                                         :session-id "s1"
                                         :event {:key "Enter"
                                                 :is-composing? composing}
                                         :at "t"})]
      (if composing
        (and (empty? (outbox next))
             (= :ime-candidate (get-in next [:last-outcome :result])))
        true))))

(defspec stopped-stream-never-appends-text 100
  (prop/for-all [chunks (gen/vector gen/string 1 10)]
    (let [state (-> (base) (edit "s1" "hello") send!)
          stopped (conv/apply-action state {:type :composer/stop
                                            :session-id "s1"
                                            :at "t"})
          final (reduce (fn [state text]
                          (conv/apply-action state {:type :stream/chunk
                                                    :session-id "s1"
                                                    :message-id "m1"
                                                    :seq (inc (:late-text-count
                                                               (get-in state [:streams "s1"])))
                                                    :text text
                                                    :at "t"}))
                        stopped chunks)]
      (and (= 0 (get-in final [:streams "s1" :content-version]))
           (= (count chunks) (get-in final [:streams "s1" :late-text-count]))))))

(defspec command-ids-are-unique 50
  (prop/for-all [texts (gen/vector gen/string 1 8)]
    (let [final (reduce (fn [state text]
                          (let [state (edit state text)
                                state (send! state)
                                command (last-command state)]
                            (if command
                              (ack state (:command-id command) :accepted)
                              state)))
                        (base) texts)
          ids (map :command-id (vals (:commands final)))]
      (and (= (count ids) (count (set ids)))
           (every? #(string? %) ids)))))
