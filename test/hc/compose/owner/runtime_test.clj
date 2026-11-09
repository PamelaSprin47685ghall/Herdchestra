(ns hc.compose.owner.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.cancel :as cancel]
            [hc.compose.guards.scheduler :as scheduler]
            [hc.compose.owner :as owner]
            [hc.compose.owner.board :as owner-board]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.dispatch :as dispatch]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.runtime :as runtime]
            [hc.compose.owner.state :as state]
            [hc.compose.owner.store :as store]
            [hc.core.swarm-stm.board :as swarm-board]
            [hc.core.swarm-stm.kernel :as swarm-kernel]
            [hc.core.swarm-stm-test-util :as tu]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- snapshot
  [handle]
  ((:snapshot handle)))

(defn- call-state
  [handle call-id]
  (get-in (snapshot handle) [:state :calls call-id :state]))

(defn- with-owner
  [deps f]
  (let [handle (owner/start-owner! deps)]
    (try
      (f handle)
      (finally
        ((:stop! handle))))))

(deftest user-message-runs-a-model-turn
  (with-owner
    {:session-id "s1"
     :ephemeral? true
     :model-gateway
     {:start! (fn [request deliver!]
                (deliver! {:event :completed :text "hello back"})
                {:attempt-id (:attempt-id request)})
      :cancel! (fn [_] nil)}}
    (fn [handle]
      (let [receipt ((:submit-command! handle)
                     {:command-id "c1" :command-epoch 1
                      :payload {:kind :send-message :text "hello"}})]
        (is (= :accepted (:status receipt)))
        (is (wait-for #(and (= "hello back"
                               (get-in (snapshot handle)
                                       [:state :conversation 1 :text]))
                            (= :succeeded (call-state handle "call-1")))
                      5000))
        (is (= :succeeded (call-state handle "call-1")))))))

(deftest tool-retry-succeeds-on-the-second-attempt
  (let [attempts (atom 0)
        starts (atom 0)]
    (with-owner
      {:session-id "s1"
       :ephemeral? true
       :model-gateway
       {:start! (fn [request deliver!]
                  (if (= 1 (swap! starts inc))
                    (do
                      (deliver! {:event :tool-call
                                 :call-id "tool-1"
                                 :payload
                                 {:executor :tool :name "tool" :n 1
                                  :scope
                                  {:kind :reset-scope
                                   :new-scope-goal "test"
                                   :new-scope-retention-budget 8}}})
                      (deliver! {:event :completed :text "spawned"}))
                    ;; Later Nudge turns complete as text.  They must not
                    ;; emit another tool call or this test would count their
                    ;; executor attempts as retries of the first call.
                    (deliver! {:event :completed :text "nudged"}))
                  {:attempt-id (:attempt-id request)})
        :cancel! (fn [_] nil)}
       :executors
       {:tool (fn [_]
                (if (= 1 (swap! attempts inc))
                  {:status :failed :error {:kind :timeout :message "jitter"}}
                  {:status :succeeded :payload {:ok true}}))}}
      (fn [handle]
        (try
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "use a tool"}})
        (is (wait-for #(= :succeeded (call-state handle "call-1/tool-0"))
                      8000))
        (is (= 2 @attempts)))))))

(deftest operator-cancel-closes-the-run
  ;; The full chain: the stop reaches a live Shell, the executor reports its
  ;; own exit, and only then does the cancellation domain close.  A signal
  ;; alone must never close it, so the shell is released after Esc instead
  ;; of sleeping through the assertion.
  (let [started (promise)
        release (promise)]
    (with-owner
      {:session-id "s1"
       :ephemeral? true
       :model-gateway
       {:start! (fn [request deliver!]
                  (deliver! {:event :tool-call
                             :call-id "tool-1"
                             :payload {:executor :shell :name "shell" :cmd "sleep"
                                       :scope {:kind :reset-scope :new-scope-goal "test" :new-scope-retention-budget 8}}})
                  (deliver! {:event :completed :text "spawned"})
                  {:attempt-id (:attempt-id request)})
        :cancel! (fn [_] nil)}
       :executors
       {:shell {:run (fn [_]
                       (deliver started true)
                       ;; Cancellation asks the executor to stop; the
                       ;; executor answers with its own terminal.
                       (deref release 30000 :timeout)
                       {:status :cancelled :payload {:exit 143}})
                ;; The task is still running, so the stop must not be
                ;; reported as a completed resource.
                :cancel (fn [_] {:ok? false :live? true})}}}
      (fn [handle]
        (try
          ((:submit-command! handle)
           {:command-id "c1" :command-epoch 1
            :payload {:kind :send-message :text "run"}})
          (is (true? (deref started 3000 false)))
          (is (= :accepted
                 (:status ((:submit-command! handle)
                           {:command-id "c2" :command-epoch 1
                            :payload {:kind :stop-run
                                      :origin :operator-esc}}))))
          (is (= :cancelling
                 (get-in (snapshot handle)
                         [:state :cancel :nodes "s1-run" :state]))
              "a live Shell keeps the cancellation domain open")
          (deliver release :exit)
          (is (wait-for #(= :cancelled
                            (get-in (snapshot handle)
                                    [:state :cancel :nodes "s1-run" :state]))
                        5000))
          (is (= :outcome-unknown (call-state handle "call-1/tool-0")))
          (is (some (fn [entry]
                      (and (= :cancel/resource (:type entry))
                           (= :shell (:kind entry))
                           (= :attempt (:node-kind entry))
                           (= "call-1/tool-0" (:call-id entry))))
                    (get-in (snapshot handle) [:state :outbox]))
              "the fallback hook must receive the executor kind, not the node kind")
          (finally
            ;; Always release the executor, including on an assertion
            ;; failure: otherwise its 30s wait leaks into later test
            ;; namespaces.
            (deliver release :cleanup)))))))

(deftest execution-relay-barrier-fires-after-root-finish
  (with-owner
    {:session-id "s1"
     :ephemeral? true
     :plan-milestone :pending
     :draft {:path "plans/demo.md" :content "# plan"}}
    (fn [handle]
      ((:submit-command! handle)
       {:command-id "r0" :command-epoch 1
        :payload {:kind :review/submit :lane :plan :tenure-id "tenure-1"
                  :findings []}})
      ((:submit-command! handle)
       {:command-id "r1" :command-epoch 1
        :payload {:kind :review/submit :lane :execution :tenure-id "tenure-2"
                  :findings [{:key "k1" :value "make it work"}]}})
      (let [root (first (filter #(= "k1" (:ledger-key %))
                                (vals (get-in (snapshot handle)
                                              [:state :board :tasks]))))
            task-id (:task-id root)]
        ((:execute-board! handle) {:op :register-actor :slot-id "w1"})
        ((:execute-board! handle) {:op :claim
                                   :actor-id :agent-2
                                   :task-id task-id
                                   :atomicity-reason "atomic"})
        (let [claim-id (get-in (snapshot handle)
                               [:state :board :tasks task-id :claim-id])]
          (is (= :accepted
                 (:status ((:execute-board! handle)
                           {:op :finish
                            :actor-id :agent-2
                            :task-id task-id
                            :claim-id claim-id
                            :result-summary "done"})))))
        (is (wait-for #(= 1 (count (get-in (snapshot handle)
                                           [:state :relay :barriers])))
                      3000))
        (is (= ["tenure-3"]
               (keys (get-in (snapshot handle)
                             [:state :relay :lanes :execution]))))))))

(deftest committed-state-survives-a-restart
  (let [store (store/memory-store)
        handle (owner/start-owner! {:session-id "s1"
                                    :store store
                                    :model-gateway
                                    {:start! (fn [request deliver!]
                                               (deliver! {:event :completed
                                                          :text "saved"})
                                               {:attempt-id (:attempt-id request)})
                                     :cancel! (fn [_] nil)}})]
    (try
      ((:submit-command! handle)
       {:command-id "c1" :command-epoch 1
        :payload {:kind :send-message :text "persist me"}})
      (is (wait-for #(= "saved" (get-in (snapshot handle)
                                        [:state :conversation 1 :text]))
                    5000))
      (finally
        ((:stop! handle) {:close-store? false})))
    (let [restarted (owner/start-owner! {:session-id "s1" :store store})]
      (try
        (let [snap (snapshot restarted)]
          (is (= "persist me"
                 (get-in snap [:state :conversation 0 :text])))
          (is (pos? (:version snap))))
        (finally
          ((:stop! restarted)))))))

(deftest stop-with-close-store-false-keeps-the-store-open
  (let [store (store/memory-store)
        handle (owner/start-owner! {:session-id "keep-store"
                                    :store store})]
    (try
      ((:submit-command! handle)
       {:command-id "k1" :command-epoch 1
        :payload {:kind :send-message :text "kept"}})
      (let [stopped ((:stop! handle) {:close-store? false})]
        (is (nil? (:store stopped))
            "an explicit :close-store? false must not close the store")
        (is (nil? (:close-skipped stopped))))
      (let [loaded (store/load-state store)]
        (is (= "keep-store" (get-in loaded [:state :session-id])))
        (is (pos? (:version loaded))))
      (let [restarted (owner/start-owner! {:session-id "keep-store"
                                           :store store})]
        (try
          (is (= "kept" (get-in (snapshot restarted)
                                [:state :conversation 0 :text])))
          (finally
            ((:stop! restarted)))))
    (finally
      ;; If an assertion above failed before the first stop, this is the
      ;; first stop; the store is intentionally left open for inspection.
      ((:stop! handle) {:close-store? false})))))

(deftest subscribers-receive-committed-notifications
  (let [notifications (atom [])]
    (with-owner
      {:session-id "s1" :ephemeral? true}
      (fn [handle]
        ((:subscribe! handle) (fn [notification]
                                (swap! notifications conj notification)))
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "notify"}})
        (is (wait-for #(seq (filter (fn [n]
                                      (= :owner/committed (:type n)))
                                    @notifications))
                      3000))
        (is (= :accepted (-> @notifications last :receipt :status)))))))


(defn- complete-mission!
  "Drive one mission to :completed through the real Owner and mission host."
  [handle]
  ((:submit-command! handle)
   {:command-id "plan-handoff" :command-epoch 1
    :payload {:kind :review/submit :lane :plan :tenure-id "tenure-1"
              :findings []}})
  ((:submit-command! handle)
   {:command-id "swarm-start" :command-epoch 1
    :payload {:kind :review/submit :lane :execution :tenure-id "tenure-2"
              :findings [{:key "k1" :value "root work"}]}})
  (let [registered ((:execute-board! handle)
                    {:op :register-actor :tenure-id "tenure-2"
                     :slot-id "w1"})
        actor (:agent-id registered)
        root (first (filter #(= "k1" (:ledger-key %))
                            (vals (get-in ((:snapshot handle))
                                          [:state :board :tasks]))))
        task-id (:task-id root)]
    ((:execute-board! handle) {:op :claim :tenure-id "tenure-2"
                               :actor-id actor :task-id task-id
                               :atomicity-reason "atomic"})
    ((:execute-board! handle)
     {:op :finish :tenure-id "tenure-2" :actor-id actor
      :task-id task-id
      :claim-id (get-in ((:snapshot handle))
                        [:state :board :tasks task-id :claim-id])
      :result-summary "done"}))
  ((:submit-command! handle)
   {:command-id "promote-closing" :command-epoch 1
    :payload {:kind :review/submit :lane :execution :tenure-id "tenure-3"
              :findings []}})
  ((:submit! handle) {:type :git/ff-ready :ready? true})
  ((:await-idle! handle) 2000)
  ((:submit-command! handle)
   {:command-id "complete" :command-epoch 1
    :payload {:kind :review/submit :lane :execution :tenure-id "tenure-3"
              :findings []}})
  ((:await-idle! handle) 2000)
  handle)

(deftest completed-mission-reopens-only-for-an-operator
  (let [requests (atom [])
        gateway {:start! (fn [request deliver!]
                           (swap! requests conj request)
                           (deliver! {:event :completed :text "reopened-ok"})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "s1"
       :ephemeral? true
       :plan-milestone :pending
       :draft {:path "plans/demo.md" :content "# plan"}
       :model-gateway gateway}
      (fn [handle]
        (complete-mission! handle)
        (let [before (count (get-in (snapshot handle)
                                    [:state :relay :lanes :execution]))]
          (is (true? (state/mission-completed?
                      (:state (snapshot handle)))))
          (testing "an agent review cannot reopen"
            (let [receipt ((:submit-command! handle)
                           {:command-id "agent-review" :command-epoch 1
                            :payload {:kind :review/submit
                                      :lane :execution
                                      :tenure-id "tenure-3"
                                      :findings []}})]
              (is (= :refused (:status receipt)))
              (is (= :MISSION_COMPLETED (get-in receipt [:error :code])))))
          (testing "an unmarked message cannot reopen"
            (is (= :MISSION_COMPLETED_OPERATOR_ONLY
                   (get-in ((:submit-command! handle)
                            {:command-id "plain-message" :command-epoch 1
                             :payload {:kind :send-message :text "no"}})
                           [:error :code])))
            (is (= before (count (get-in (snapshot handle)
                                         [:state :relay :lanes :execution])))))
          (testing "a real operator message reopens"
            (let [receipt ((:submit-command! handle)
                           {:command-id "operator-reopen" :command-epoch 1
                            :operator? true
                            :payload {:kind :send-message :text "reopen"}})]
              (is (= :accepted (:status receipt)))
              (is (:reopened? receipt))
              (is (= (inc before)
                     (count (get-in (snapshot handle)
                                    [:state :relay :lanes :execution]))))
              (is (wait-for #(= "reopened-ok"
                                (get-in (snapshot handle)
                                        [:state :conversation 1 :text]))
                            5000))
              (is (some #(= "tenure-4" (get-in % [:snapshot :tenure-id]))
                        @requests))
              (is (false? (get-in (snapshot handle)
                                  [:state :relay :lanes :execution "tenure-4"
                                   :initial?])))
              (is (false? (get-in (snapshot handle)
                                  [:state :relay :lanes :execution "tenure-4"
                                   :first-review?])))
              (testing "replaying the same command does not reopen again"
                (let [after (count (get-in (snapshot handle)
                                           [:state :relay :lanes :execution]))
                      replay ((:submit-command! handle)
                              {:command-id "operator-reopen"
                               :command-epoch 1
                               :operator? true
                               :payload {:kind :send-message
                                         :text "reopen"}})]
                  (is (:replayed? replay))
                  (is (= after (count (get-in (snapshot handle)
                                              [:state :relay :lanes
                                               :execution]))))))
          (testing "a late model event is diagnostic only"
            (let [after (count (get-in (snapshot handle)
                                       [:state :relay :lanes :execution]))]
              ((:submit! handle)
               {:type :model/event
                :event {:event :completed :attempt-id :stale
                        :model-call-id "missing" :text "late"}})
              ((:await-idle! handle) 1000)
              (is (= after (count (get-in (snapshot handle)
                                          [:state :relay :lanes
                                           :execution])))))))))))))

(deftest model-streaming-updates-conversation-incrementally
  (let [texts (atom [])
        gateway {:start! (fn [request deliver!]
                           (future
                             (deliver! {:event :chunk :text "Hel"})
                             (Thread/sleep 40)
                             (deliver! {:event :chunk :text "lo"})
                             (Thread/sleep 40)
                             (deliver! {:event :completed :text "Hello"}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "stream" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:subscribe! handle)
         (fn [notification]
           (let [message (last (filter #(= :assistant (:role %))
                                       (get-in notification
                                               [:state :conversation])))]
             (when message
               (swap! texts conj (:text message))))))
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "hi"}})
        (is (wait-for #(= "Hello"
                          (get-in (snapshot handle)
                                  [:state :conversation 1 :text]))
                      5000))
        (is (wait-for #(false? (get-in (snapshot handle)
                                       [:state :conversation 1 :streaming?]))
                      2000))
        (is (some #(= "Hel" %) @texts))
        (is (some #(= "Hello" %) @texts))
        (let [snap (snapshot handle)
              run (val (first (get-in snap [:state :runs])))]
          (is (= :finished (:status run)))
          (is (= "Hello" (:text run)))
          (is (= (:message-id run)
                 (get-in snap [:state :conversation 1 :message-id]))))))))

(deftest streaming-cancellation-finalizes-partial-content
  (let [started (promise)
        release (promise)
        gateway {:start! (fn [request deliver!]
                           (deliver! {:event :chunk :text "partial "})
                           (deliver started true)
                           (future
                             @release
                             (deliver! {:event :chunk :text "LATE"}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] (deliver release true))}]
    (with-owner
      {:session-id "cancel-stream" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "hi"}})
        (is (true? (deref started 3000 false)))
        (is (wait-for #(= "partial "
                          (get-in (snapshot handle)
                                  [:state :conversation 1 :text]))
                      3000))
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c2" :command-epoch 1
                          :payload {:kind :stop-run
                                    :origin :operator-esc}}))))
        (is (wait-for #(= :cancelled
                          (-> (snapshot handle) :state :runs vals first :status))
                      3000))
        (let [snap (snapshot handle)
              run (val (first (get-in snap [:state :runs])))
              message (get-in snap [:state :conversation 1])]
          (is (= "partial " (:text run)))
          (is (false? (:streaming? message)))
          (is (:cancelled? message))
          (is (= "partial " (:text message))))
        (deliver release true)
        ((:await-idle! handle) 1500)
        (let [snap (snapshot handle)
              run (val (first (get-in snap [:state :runs])))]
          (is (= "partial " (:text run)))
          (is (some #(= :chunk (:event %)) (:late-events run))))))))

(deftest chat-turn-streams-text-without-tools-and-esc-stops-it
  ;; E2E 08: a `:chat?` user turn is a plain assistant-text answer.  The
  ;; frozen request carries no tool surface even though the Owner config
  ;; prepares Plan tools, the streamed text is Esc-interruptible, and the
  ;; partial answer is preserved exactly like a program turn's.
  (let [requests (atom [])
        started (promise)
        release (promise)
        gateway {:start! (fn [request deliver!]
                           (let [nudge? (some? (get-in request
                                                     [:snapshot :nudge-id]))]
                             (swap! requests conj request)
                             (if nudge?
                               (deliver! {:event :completed :text "nudge"})
                               (do
                                 (deliver! {:event :chunk
                                            :text "partial answer "})
                                 (deliver started true)
                                 (future
                                   @release
                                   (deliver! {:event :chunk
                                              :text "LATE"})))))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] (deliver release true))}]
    (with-owner
      {:session-id "chat-esc" :ephemeral? true :model-gateway gateway
       :config {:plan-tools [{:name "devops"
                              :description "Plan investigation"
                              :parameters {:type "object"}}]}}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "what is S1" :chat? true}})
        (is (true? (deref started 3000 false)))
        (let [user-request (first (filter
                                   #(get-in % [:snapshot :user-turn?])
                                   @requests))]
          (is (some? user-request))
          (is (true? (get-in user-request [:snapshot :chat?]))
              "the chat marker is frozen into the request snapshot")
          (is (= :clj-plan (get-in user-request [:snapshot :program-role]))
              "the program role stays for audit")
          (is (nil? (get-in user-request [:snapshot :tools]))
              "no plan-tools, extension tools, or program schema"))
        (is (wait-for #(= "partial answer "
                          (get-in (snapshot handle)
                                  [:state :conversation 1 :text]))
                      3000))
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c2" :command-epoch 1
                          :payload {:kind :stop-run
                                    :origin :operator-esc}}))))
        (is (wait-for #(= :cancelled
                          (-> (snapshot handle) :state :runs vals first
                              :status))
                      3000))
        (let [snap (snapshot handle)
              run (val (first (get-in snap [:state :runs])))
              message (get-in snap [:state :conversation 1])]
          (is (= "partial answer " (:text run)))
          (is (= "partial answer " (:text message)))
          (is (:cancelled? message))
          (is (false? (:streaming? message))))
        (deliver release true)
        ((:await-idle! handle) 1500)
        (let [snap (snapshot handle)
              run (val (first (get-in snap [:state :runs])))]
          (is (= "partial answer " (:text run)))
          (is (some #(= :chunk (:event %)) (:late-events run))))))))

(deftest program-turn-still-carries-the-plan-tool-surface
  ;; The same Owner config must still hand `:tools` to a normal Plan turn:
  ;; `:chat?` is an explicit per-message switch, not a global mode.
  (let [requests (atom [])
        gateway {:start! (fn [request deliver!]
                           (swap! requests conj request)
                           (if (get-in request [:snapshot :nudge-id])
                             (deliver! {:event :completed :text "nudge"})
                             (deliver! {:event :completed :text "done"}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "program-tools" :ephemeral? true :model-gateway gateway
       :config {:plan-tools [{:name "devops"
                              :description "Plan investigation"
                              :parameters {:type "object"}}]}}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "draft the plan"}})
        (is (wait-for #(= "done"
                          (get-in (snapshot handle)
                                  [:state :conversation 1 :text]))
                      5000))
        (let [user-request (first (filter
                                   #(get-in % [:snapshot :user-turn?])
                                   @requests))]
          (is (some? user-request))
          (is (not (contains? (get-in user-request [:snapshot]) :chat?)))
          (is (= ["devops"]
                 (mapv :name (get-in user-request [:snapshot :tools])))
              "the Plan tool surface is unchanged for program turns"))))))


(deftest stop-then-send-runs-the-queued-turn-after-the-run-closes
  ;; Esc closes the run; the next real message must still produce a model
  ;; request.  The turn is committed while the old run is :cancelling, so the
  ;; only thing that can strand it is a guard or runtime gate that still reads
  ;; the closed run as blocking after it became :cancelled.
  (let [requests (atom 0)
        release (promise)
        gateway {:start! (fn [request deliver!]
                           (let [n (swap! requests inc)
                                 nudge? (some? (get-in request
                                                       [:snapshot :nudge-id]))]
                             (case n
                               ;; The first turn streams a chunk and then
                               ;; waits for Esc, which cancels its attempt.
                               1 (do
                                   (deliver! {:event :chunk :text "partial "})
                                   (future
                                     @release
                                     (deliver! {:event :completed
                                                :text "first answered"})))
                               ;; The queued user turn carries material, so a
                               ;; Nudge request is the only call to ignore.
                               (when-not nudge?
                                 (deliver! {:event :completed :text "done"})))
                             {:attempt-id (:attempt-id request)}))
                 :cancel! (fn [_] (deliver release true))}]
    (with-owner
      {:session-id "stop-then-send" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "first"}})
        (is (wait-for #(= "partial "
                          (get-in (snapshot handle)
                                  [:state :conversation 1 :text]))
                      3000))
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c2" :command-epoch 1
                          :payload {:kind :stop-run
                                    :origin :operator-esc}}))))
        (is (wait-for #(= :cancelled
                          (get-in (snapshot handle)
                                  [:state :cancel :nodes "stop-then-send-run"
                                   :state]))
                      5000))
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c3" :command-epoch 1
                          :payload {:kind :send-message :text "second"}}))))
        ;; Every user turn appends its own assistant reply, so the second
        ;; turn's answer is slot 3, not 2: slot 2 is its own user message.
        (is (wait-for #(= "done"
                          (get-in (snapshot handle)
                                  [:state :conversation 3 :text]))
                      5000)
            "the queued turn must reach the model gateway after the stop")
        (let [snap (snapshot handle)
              run-id (get-in snap [:state :cancel :active-run-id])
              new-run (get-in snap [:state :cancel :nodes run-id])]
          (is (not= "stop-then-send-run" run-id))
          (is (= :running (:state new-run)))
          (is (= :open (:admission new-run)))
          (is (empty? (get-in snap [:state :pending-turns])))
          (is (some #(and (= :model (:kind %))
                          (:user-turn? %)
                          (= "c3" (:turn-command-id %)))
                    (vals (get-in snap [:state :calls])))))))))

(deftest send-after-a-closed-stop-runs-the-queued-turn
  ;; The model turn must end before Esc, or the operator stop correctly
  ;; abandons a turn that has no completion left to serve.  The queued turn
  ;; then arrives while the run is already `:cancelled`.  It must still reach
  ;; the model gateway, and the successor run must not open before then.
  ;; Count only the call that answers a user message.  A call id can carry a
  ;; Nudge or a retried attempt after its first answer, so a plain per-call
  ;; counter would spend the first-answer slot on the wrong request.
  (let [answers (atom 0)
        user-turn? (fn [request]
                     (and (get-in request [:snapshot :user-turn?])
                          (nil? (get-in request [:snapshot :nudge-id]))))
        gateway {:start! (fn [request deliver!]
                           (when (user-turn? request)
                             (deliver! {:event :completed
                                        :text (if (= 1 (swap! answers inc))
                                                "first answered"
                                                "done")}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "stop-then-send" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "first"}})
        (is (wait-for #(= "first answered"
                          (get-in (snapshot handle)
                                  [:state :conversation 1 :text]))
                      5000))
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c2" :command-epoch 1
                          :payload {:kind :stop-run
                                    :origin :operator-esc}}))))
        (is (wait-for #(= :cancelled
                          (get-in (snapshot handle)
                                  [:state :cancel :nodes "stop-then-send-run"
                                   :state]))
                      5000))
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "c3" :command-epoch 1
                          :payload {:kind :send-message :text "second"}}))))
        ;; Every user turn appends its own assistant reply, so the second
        ;; turn's answer is slot 3, not 2: slot 2 is its own user message.
        (is (wait-for #(= "done"
                          (get-in (snapshot handle)
                                  [:state :conversation 3 :text]))
                      5000)
            "the queued turn must reach the model gateway after the stop")
        (let [snap (snapshot handle)
              run-id (get-in snap [:state :cancel :active-run-id])]
          (is (not= "stop-then-send-run" run-id))
          (is (= :open (get-in snap [:state :cancel :nodes run-id :admission])))
          (is (empty? (get-in snap [:state :pending-turns]))))))))

(deftest send-during-live-cancellation-runs-after-the-resource-settles
  ;; The operator sends while the cancelled Shell is still live.  No second
  ;; message arrives.  The successor run may open only after the Shell reports
  ;; its exit, and the queued turn must still reach the model gateway.
  ;;
  ;; The Shell latch is released in an outer `finally` for every outcome, so
  ;; the test never leaves a blocked executor behind for later namespaces.
  (let [started (promise)
        release (promise)
        shell-exit-observed (promise)
        model-requests (atom 0)
        gateway {:start! (fn [request deliver!]
                           (let [n (swap! model-requests inc)
                                 nudge? (some? (get-in request
                                                       [:snapshot :nudge-id]))]
                             (case (long n)
                               1 (deliver! {:event :tool-call
                                            :call-id "shell-live"
                                            :text "checking the workspace"
                                            :payload
                                            {:executor :shell
                                             :name "shell"
                                             :cmd "sleep"
                                             :scope
                                             {:kind :reset-scope
                                              :new-scope-goal "test"
                                              :new-scope-retention-budget 8}}})
                               (when-not nudge?
                                 (deliver! {:event :completed
                                            :text "answered while draining"})))
                             (when (= 1 n)
                               ;; The user turn ends only when the model
                               ;; answers; Esc cancels that attempt once the
                               ;; tool call has committed.
                               (future
                                 @release
                                 (deliver! {:event :completed
                                            :text "first answered"})))
                             {:attempt-id (:attempt-id request)}))
                 :cancel! (fn [_] (deliver release true))}]
    (with-owner
      {:session-id "drain-send" :ephemeral? true :model-gateway gateway
       ;; The shell ignores cancellation and only returns when the test
       ;; releases it, so its executor declares a cancel that refuses and
       ;; reports the resource as still live.  That keeps the cancellation
       ;; domain open until the release, which is the only way the test can
       ;; observe the queued turn waiting on a closed admission.
       :cancel-resource! (fn [_entry] {:ok? true :live? true})
       :executors
       {:shell {:run (fn [_]
                       (deliver started true)
                       (deref shell-exit-observed)
                       {:status :succeeded :payload {:exit 0}})
                :cancel (fn [_] {:ok? false :live? true})}}}
      (fn [handle]
        ;; The release belongs to the test, not to the assertions: an outer
        ;; `try` keeps the executor from outliving a failed expectation.
        (try
          ((:submit-command! handle)
           {:command-id "c1" :command-epoch 1
            :payload {:kind :send-message :text "run a shell"}})
          (is (true? (deref started 5000 false)))
          ;; Let the tool call event commit before the stop, so the shell is
          ;; already authorized when Esc arrives.
          (is (wait-for #(contains? (get-in (snapshot handle) [:state :calls])
                                    "call-1/tool-0")
                        5000))
          (is (= :accepted
                 (:status ((:submit-command! handle)
                           {:command-id "c2" :command-epoch 1
                            :payload {:kind :stop-run
                                      :origin :operator-esc}}))))
          (is (wait-for #(= :cancelling
                            (get-in (snapshot handle)
                                    [:state :cancel :nodes "drain-send-run"
                                     :state]))
                        5000)
              "the run stays cancelling while the shell is live")
          ;; The stop walk must reach the shell that is already alive.
          (is (wait-for #(= :outcome-unknown
                            (get-in (snapshot handle)
                                    [:state :calls "call-1/tool-0" :state]))
                        3000))
          ;; Esc signals the model attempt and the shell, but the shell
          ;; executor is still blocked: the abort is a request, not an exit.
          ;; Its cancellation node must keep `:resource-alive? true` and the
          ;; run must stay `:cancelling` until the executor returns.
          ;; Clearing this flag here would open a successor under a live
          ;; process.
          (let [signalled (snapshot handle)]
            (is (= :outcome-unknown
                   (get-in signalled
                           [:state :cancel :nodes "call-1/tool-0" :state])))
            (is (true? (get-in signalled
                               [:state :cancel :nodes "call-1/tool-0"
                                :resource-alive?]))
                "a signalled but unexited shell keeps its live resource")
            (is (= :cancelling
                   (get-in signalled
                           [:state :cancel :nodes "drain-send-run" :state])))
            (is (not= :dispatching
                      (get-in signalled [:state :calls "call-1" :state]))))
          (testing "before the queued turn is sent, no run serves it"
            (let [waiting (snapshot handle)]
              (is (= "drain-send-run"
                     (get-in waiting [:state :cancel :active-run-id]))
                  "the still-live shell keeps the old run active")
              (is (= :closed (get-in waiting [:state :cancel :nodes
                                              "drain-send-run" :admission])))))
          (is (= :accepted
                 (:status ((:submit-command! handle)
                           {:command-id "c3" :command-epoch 1
                            :payload {:kind :send-message
                                      :text "keep going"}}))))
          (is (wait-for #(or (= 1 (count (get-in (snapshot handle)
                                                  [:state :pending-turns])))
                             (= "drain-send-run-r2"
                                (get-in (snapshot handle)
                                        [:state :cancel :active-run-id])))
                        5000)
              "the queued turn must be either waiting or served by a successor")
          (let [queued (snapshot handle)
                run-id (get-in queued [:state :cancel :active-run-id])]
            (testing "the successor never precedes the quiescent cancel domain"
              ;; The successor may open only through `cancel/finish-run`'s
              ;; quiescence proof, so the old run must already be
              ;; `:cancelled` at the instant `active-run-id` moves.  When it
              ;; has not moved, the queued turn must still be waiting.
              (if (= "drain-send-run" run-id)
                (do
                  (is (= :closed (get-in queued [:state :cancel :nodes
                                                 "drain-send-run" :admission])))
                  (is (= 1 (count (get-in queued [:state :pending-turns]))))
                  (is (state/successor-run-pending? (:state queued))))
                (is (= :cancelled (get-in queued [:state :cancel :nodes
                                                  "drain-send-run" :state]))))
              ;; When the successor is still pending here, no call may serve
              ;; `c3`; once it opened, the call is expected.
              (when (= "drain-send-run" run-id)
                (is (not-any? #(and (= :model (:kind %))
                                    (:user-turn? %)
                                    (= "c3" (:turn-command-id %)))
                              (vals (get-in queued [:state :calls])))
                    "the queued turn must wait for the cancellation domain"))))
          (deliver shell-exit-observed :exit)
          (is (wait-for #(some (fn [message]
                                 (and (= :assistant (:role message))
                                      (= "answered while draining"
                                         (:text message))))
                               (get-in (snapshot handle) [:state :conversation]))
                        8000)
              "the queued turn must reach the model gateway after the stop")
          (let [final (snapshot handle)
                run-id (get-in final [:state :cancel :active-run-id])]
            (is (not= "drain-send-run" run-id))
            (is (= :open (get-in final [:state :cancel :nodes run-id
                                        :admission])))
            (is (false? (state/successor-run-pending? (:state final))))
            (is (empty? (get-in final [:state :pending-turns]))))
          (finally
            (deliver release true)
            (deliver shell-exit-observed :cleanup)))))))

(deftest restart-keeps-outbox-numbering-monotonic
  ;; The persisted outbox is cleared on restart because the dispatcher owns no
  ;; matching attempts, but the sequence counter is committed state: it must
  ;; survive, or the next effect is renumbered at 1 while the drain pointer
  ;; sits above it and the entry is skipped silently.
  ;;
  ;; A shell tool call is still live when the owner stops, so the committed
  ;; counter is above zero and the persisted node carries
  ;; `:outcome-unknown` with a live claim.  Recovery must drop that claim (no
  ;; handle exists in this process) while keeping the evidence, close the
  ;; domain, and never re-signal the old resource.  The monotonic-numbering
  ;; property is then checked with a fresh input, whose effect must be
  ;; numbered above the preserved counter.
  (let [calls (atom [])
        store (store/memory-store)
        started (promise)
        release (promise)
        gateway {:start! (fn [request deliver!]
                           (deliver! {:event :tool-call
                                      :call-id "restart-shell"
                                      :payload
                                      {:executor :shell
                                       :name "shell"
                                       :cmd "sleep"
                                       :scope
                                       {:kind :reset-scope
                                        :new-scope-goal "restart"
                                        :new-scope-retention-budget 8}}})
                           (deliver! {:event :completed :text "spawned"})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        ;; The shell ignores cancellation and only exits on release, so the
        ;; executor must refuse with `:live? true`; otherwise the default hook
        ;; reports a terminal and the node loses `:resource-alive?` before the
        ;; stop, leaving the restarted owner nothing to re-signal.
        executor {:run (fn [_]
                         (deliver started true)
                         (deref release 10000 :timeout)
                         {:status :succeeded :payload {:exit 0}})
                  :cancel (fn [_] {:ok? false :live? true})}
        hook (fn [entry] (swap! calls conj entry) {:ok? true :cancelled? false})
        deps {:session-id "outbox-restart"
              :store store
              :model-gateway gateway
              :executors {:shell executor}
              :cancel-resource! hook}
        handle (owner/start-owner! deps)]
    (try
      ((:submit-command! handle)
       {:command-id "c1" :command-epoch 1
        :payload {:kind :send-message :text "run"}})
      (is (true? (deref started 5000 false)))
      (is (= :accepted
             (:status ((:submit-command! handle)
                       {:command-id "c2" :command-epoch 1
                        :payload {:kind :stop-run
                                  :origin :operator-stop}}))))
      (is (wait-for #(= :outcome-unknown
                        (get-in (snapshot handle)
                                [:state :calls "call-1/tool-0" :state]))
                    5000)
          "the operator stop must reach the live shell call")
      ;; The persisted shape after the stop: the call settles as
      ;; `:outcome-unknown` and keeps `:resource-alive? true` because no
      ;; executor ever reported an exit.  A cancel signal does not end a
      ;; resource, so the cancellation domain must still be `:cancelling`
      ;; when the owner stops; `finish-run` refuses a close while any live
      ;; resource remains.  That persisted live node is what the restarted
      ;; owner must be able to re-signal.
      ;; The walk commits in several steps: call state, then the live flag,
      ;; then the run's decision.  Wait for the shape instead of sampling one
      ;; instant after the receipt, which races the guard round.
      (is (wait-for #(let [snap (snapshot handle)]
                       (and (= :outcome-unknown
                               (get-in snap [:state :cancel :nodes
                                             "call-1/tool-0" :state]))
                            (true? (get-in snap [:state :cancel :nodes
                                                 "call-1/tool-0"
                                                 :resource-alive?]))
                            (= :cancelling
                               (get-in snap [:state :cancel :nodes
                                             "outbox-restart-run" :state]))))
                    5000)
          "an unobserved shell stays live and keeps the domain open")
      (finally
        ;; Do not release the shell before the stop: the live claim is the
        ;; state the persisted tree must carry into recovery.
        ((:stop! handle) {:close-store? false})))
    (let [counter (get-in (store/load-state store) [:state :counters
                                                    :outbox-seq])
          before (count @calls)
          restarted (owner/start-owner! deps)]
      (try
        (is (pos? counter))
        (is (empty? (get-in (snapshot restarted) [:state :outbox]))
            "old advisories are never replayed")
        (is (= counter (get-in (snapshot restarted)
                               [:state :counters :outbox-seq]))
            "the counter is committed state, not a process counter")
        ;; Recovery holds no handle on the previous process's resources.  The
        ;; honest post-restart shape is therefore: the unknown outcome stays
        ;; as evidence, the process-local live claim is dropped, and the
        ;; domain closes.  Keeping the claim would make `finish-run` wait for
        ;; a Shell this process can never observe, and re-signalling it would
        ;; replay a stop against an unknown target.
        (is (wait-for #(let [snap (snapshot restarted)]
                         (and (= :outcome-unknown
                                 (get-in snap [:state :cancel :nodes
                                               "call-1/tool-0" :state]))
                              (false? (get-in snap [:state :cancel :nodes
                                                    "call-1/tool-0"
                                                    :resource-alive?]))
                              (= :cancelled
                                 (get-in snap [:state :cancel :nodes
                                               "outbox-restart-run"
                                               :state]))))
                      5000)
            "recovery closes an unobservable resource as unknown, keeping the evidence")
        (is (not-any? (fn [entry] (= "call-1/tool-0" (:call-id entry)))
                      (drop before @calls))
            "a restart must not replay a stop against a resource it cannot observe")
        ;; A fresh input after the restart is a new effect: it must be
        ;; numbered above the preserved counter, never renumbered at 1.
        (is (= :accepted
               (:status ((:submit-command! restarted)
                         {:command-id "c4" :command-epoch 1
                          :payload {:kind :send-message :text "again"}}))))
        (is (wait-for #(some (fn [entry]
                               (> (long (or (:outbox-seq entry) 0))
                                  (long counter)))
                             (get-in (snapshot restarted) [:state :outbox]))
                      5000)
            "post-restart effects are numbered above the preserved counter")
        (finally
          (deliver release :exit)
          ((:stop! restarted)))))))


(deftest late-node-cancel-is-not-done-against-a-stale-run
  ;; The late-node signal must follow the run that is cancelling now.  A
  ;; `:retry-wait` call under a live (not cancelling) run is not touched, and
  ;; the guard only acts while the run is `:cancelling`.
  (let [base (state/initial-state {:session-id "late-scope" :now 1000})
        run-id (get-in base [:cancel :active-run-id])
        state (-> base
                  (assoc-in [:calls "waiting"]
                            {:call-id "waiting" :kind :tool
                             :agent-id :agent-x :state :retry-wait
                             :attempt-id "a1"})
                  (update :cancel cancel/add-node
                          {:node-id "waiting" :kind :call
                           :parent-id run-id :state :retry-wait
                           :side-effecting? true
                           :current-attempt-id "a1"}))
        guard (first (filter #(= :cancel-late-nodes (:id %))
                             (guards/catalog)))]
    (is (nil? ((:enabled? guard) state {}))
        "a running domain is not a cancellation domain")
    (let [cancelling (assoc-in state [:cancel :nodes run-id :state]
                               :cancelling)]
      (is (= "waiting" (:node-id ((:enabled? guard) cancelling {})))))))

(deftest late-node-cancel-effect-is-delivered
  ;; A P0 prepare returns no claim and no dispatch, so the runtime runs only
  ;; what the guard numbered into `:outbox`.  The call is created by the
  ;; runtime on its normal tool-call path and then Esc'd while it is still
  ;; live; a node seeded into a stored state would be reclassified by
  ;; `restore` and never reach the tree.
  (let [seen (atom [])
        started (promise)
        release (promise)
        gateway {:start! (fn [request deliver!]
                           (deliver! {:event :tool-call
                                      :call-id "late-call"
                                      :payload {:executor :shell
                                                :name "shell"
                                                :cmd "sleep"
                                                :scope
                                                {:kind :reset-scope
                                                 :new-scope-goal "test"
                                                 :new-scope-retention-budget
                                                 8}}})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        handle (owner/start-owner!
                {:session-id "late-outbox"
                 :ephemeral? true
                 :model-gateway gateway
                :executors
                {:shell {:run (fn [_]
                                (deliver started true)
                                ;; Stay live until Esc, then report the real
                                ;; exit.  The report is what ends the
                                ;; resource: a cancel signal alone must not.
                                (deref release 5000 nil)
                                {:status :cancelled :payload {:exit 143}})
                         ;; A live dispatcher task ignores the stop; the
                         ;; executor reports its own terminal when it exits.
                         :cancel (fn [_] {:ok? false :live? true})}}
                 :cancel-resource! (fn [entry]
                                     (swap! seen conj entry)
                                     {:ok? true})})]
    (try
      ((:submit-command! handle)
       {:command-id "c1" :command-epoch 1
        :payload {:kind :send-message :text "run a shell"}})
      (is (true? (deref started 5000 false)))
      (is (wait-for #(contains? (get-in (snapshot handle)
                                        [:state :cancel :nodes])
                                "call-1/tool-0")
                    5000)
          "the tool call must create its cancellation node")
      (is (= :accepted
             (:status ((:submit-command! handle)
                       {:command-id "c2" :command-epoch 1
                        :payload {:kind :stop-run
                                  :origin :operator-esc}}))))
      (is (wait-for #(some (fn [entry]
                             ;; The hook receives the cancellation effect,
                             ;; not the outbox entry: it carries `:kind` and
                             ;; `:reason`, never `:type`.
                             (and (= :shell (:kind entry))
                                  (= :user-cancel (:reason entry))
                                  (= "call-1/tool-0" (:call-id entry))))
                           @seen)
                    5000)
          "the stop signal must be drained, not only recorded")
      ;; The shell is still running, so the call keeps a live resource and
      ;; the run must stay `:cancelling`; `finish-run` would refuse a close
      ;; with `:RESOURCES_NOT_QUIESCENT`.  Closing before the real exit
      ;; would be a false completion.
      (let [snap (snapshot handle)
            run-id "late-outbox-run"]
        (is (= :cancelling
               (get-in snap [:state :cancel :nodes run-id :state]))
            "a live resource keeps the cancellation domain open"))
      (deliver release :exit)
      (is (wait-for #(= :cancelled
                        (get-in (snapshot handle)
                                [:state :cancel :nodes "late-outbox-run"
                                 :state]))
                    5000)
          "the cancellation domain must close after the late node reports")
      (finally
        ;; The dispatcher no longer cancels a side-effecting task; if an
        ;; assertion above fails, the shell would wait on this promise and
        ;; leak into later namespaces.
        (deliver release :cleanup)
        ((:stop! handle))))))

(deftest required-search-failure-blocks-nudge
  (let [calls (atom 0)
        gateway {:start! (fn [request deliver!]
                           (swap! calls inc)
                           (deliver! {:event :failed
                                      :error {:kind :search-required-failed
                                              :category :permanent
                                              :message "required search 500"}})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "search-blocked" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "search"}})
        (Thread/sleep 300)
        (is (= 1 @calls))
        (let [snap (snapshot handle)
              call (get-in snap [:state :calls "call-1"])]
          (is (= :failed (:state call)))
          (is (some #(= :NUDGE_BLOCKED_PERMANENT_FAILURE (:code %))
                    (get-in snap [:state :diagnostics])))
          (is (not-any? :nudge? (vals (get-in snap [:state :calls])))))))))

(deftest model-start-failure-blocks-nudge
  (let [calls (atom 0)
        gateway {:start! (fn [_request _deliver!]
                           (swap! calls inc)
                           (throw (ex-info "search required but failed"
                                           {:kind :search-required-failed})))
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "start-fail" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "search"}})
        (Thread/sleep 300)
        (is (= 1 @calls))
        (let [snap (snapshot handle)
              call (get-in snap [:state :calls "call-1"])]
          (is (= :failed (:state call)))
          (is (some #(= :NUDGE_BLOCKED_PERMANENT_FAILURE (:code %))
                    (get-in snap [:state :diagnostics])))
          (is (not-any? :nudge? (vals (get-in snap [:state :calls])))))))))

(deftest search-error-flag-blocks-nudge-and-is-visible
  (let [calls (atom 0)
        gateway {:start! (fn [request deliver!]
                           (swap! calls inc)
                           (deliver! {:event :failed
                                      :search-error? true
                                      :error {:kind :unknown
                                              :message "search 500"}})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "search-flag" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "search"}})
        (Thread/sleep 300)
        (is (= 1 @calls))
        (let [snap (snapshot handle)
              call (get-in snap [:state :calls "call-1"])
              run (val (first (get-in snap [:state :runs])))]
          (is (= :failed (:state call)))
          (is (true? (:search-error? (:error run))))
          (is (some #(= :NUDGE_BLOCKED_PERMANENT_FAILURE (:code %))
                    (get-in snap [:state :diagnostics])))
          (is (not-any? :nudge? (vals (get-in snap [:state :calls])))))))))

(deftest auto-search-degraded-keeps-answer-and-allows-nudge
  (let [calls (atom 0)
        gateway {:start! (fn [request deliver!]
                           (swap! calls inc)
                           (deliver! {:event :completed
                                      :text "answer with failed search"
                                      :search-status :failed
                                      :search-degraded? true})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "search-degraded" :ephemeral? true :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "auto search"}})
        (Thread/sleep 300)
        (is (>= @calls 1))
        (let [snap (snapshot handle)
              call (get-in snap [:state :calls "call-1"])
              message (last (get-in snap [:state :conversation]))]
          (is (= :succeeded (:state call)))
          (is (false? (:streaming? message)))
          (is (= :failed (:search-status message)))
          (is (true? (:search-degraded? message)))
          (is (not (some #(= :NUDGE_BLOCKED_PERMANENT_FAILURE (:code %))
                         (get-in snap [:state :diagnostics]))))
          (is (not (some #(= :NUDGE_SUPPRESSED_SEARCH_DEGRADED (:code %))
                         (get-in snap [:state :diagnostics])))))))))


(deftest completed-model-call-carries-public-search-context
  (let [cards [{:label "S1" :url "https://example.test/a" :title "A"
                :published-at-unknown? true}]
        gateway {:start! (fn [request deliver!]
                           (deliver! {:event :completed
                                      :text "answer"
                                      :search-status :ok
                                      :search-context
                                      {:status :ok
                                       :source-cards cards
                                       :omitted-source-count 0}})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "search-context" :ephemeral? true
       :model-gateway gateway}
      (fn [handle]
        ((:submit-command! handle)
         {:command-id "c1" :command-epoch 1
          :payload {:kind :send-message :text "search"}})
        (Thread/sleep 300)
        (let [snap (snapshot handle)
              call (get-in snap [:state :calls "call-1"])
              message (last (get-in snap [:state :conversation]))]
          (is (= :succeeded (:state call)))
          (is (= cards
                 (get-in call [:result :search-context :source-cards]))
              "the terminal payload keeps the bounded public context")
          (is (= cards (:citations message))
              "the answer message carries the source list for cards/export"))))))

(deftest duplicate-owner-start-is-refused
  (let [handle (owner/start-owner! {:session-id "duplicate-owner"
                                    :ephemeral? true})]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already running"
           (owner/start-owner! {:session-id "duplicate-owner"
                                :ephemeral? true})))
      (finally
        ((:stop! handle))))))

(deftest board-facts-accept-host-write-lock-and-rebase
  (let [state (assoc (state/initial-state {:session-id "facts"})
                     :config {:board-facts {:pending-rebase 2
                                            :holds-write-lock? true}})
        facts (state/board-facts state)]
    (is (= 2 (:pending-rebase facts)))
    (is (true? (:holds-write-lock? facts)))))

(deftest recovery-preserves-a-queued-terminal
  (let [base (state/initial-state {:session-id "queued-terminal"})
        [base call] (state/create-call
                     base
                     {:kind :tool
                      :role :plan
                      :lane :plan
                      :agent-id :plan
                      :request {}})
        call-id (:call-id call)
        base (-> base
                 (assoc-in [:calls call-id :state] :running)
                 (assoc-in [:calls call-id :attempt-id] "a1")
                 (state/add-attempt-node call "a1")
                 (assoc :terminals [{:call-id call-id
                                     :attempt-id "a1"
                                     :status :succeeded}]))
        recovered (state/recover-state base 2)]
    (is (= :running (get-in recovered [:calls call-id :state]))
        "a committed terminal is left for P1, not overwritten as unknown")
    (is (= 1 (count (:terminals recovered))))))

(deftest outbox-drains-each-sequence-once
  (let [calls (atom [])
        dispatcher (dispatch/create
                    {:post! (fn [_] {:ok? true})
                     :cancel-resource! (fn [entry]
                                         (swap! calls conj entry)
                                         {:ok? true})})
        outbox [{:type :cancel/resource
                 :call-id "c1"
                 :node-id "c1"
                 :node-kind :call
                 :kind :tool
                 :outbox-seq 1}
                {:type :cancel/resource
                 :call-id "c2"
                 :node-id "c2"
                 :node-kind :call
                 :kind :tool
                 :outbox-seq 2}]
        runtime {:state (atom {:version 1
                               :state {:outbox outbox
                                       :counters {:outbox-seq 2}}})
                 :outbox-drained (atom 0)
                 :dispatcher dispatcher
                 :mailbox nil
                 :stats (atom {})
                 :config {}
                 :notifications (atom [])}]
    (try
      (#'runtime/drain-outbox! runtime)
      (is (= 2 (count @calls)))
      (#'runtime/drain-outbox! runtime)
      (is (= 2 (count @calls))
          "a second drain must not replay already delivered sequences")
      (finally
        (dispatch/stop! dispatcher)))))

(deftest recover-lost-terminal-sees-a-normalized-queued-terminal
  (let [base (state/initial-state {:session-id "lost-terminal"})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :plan
                      :lane :plan
                      :agent-id :plan
                      :request {:snapshot {}}})
        call-id (:call-id call)
        base (-> base
                 (assoc-in [:calls call-id :state] :running)
                 (assoc-in [:calls call-id :attempt-id] :attempt-1)
                 (assoc-in [:calls call-id :started-at] 0)
                 (assoc :terminals [{:call-id call-id
                                     :attempt-id "attempt-1"
                                     :status :succeeded}]))
        guard (guards/recover-lost-model-terminal-guard)]
    (is (nil? ((:enabled? guard) base {:now 6000 :live-attempt-ids #{}}))
        "a keyword call attempt and string queued terminal are the same attempt")))

(deftest recover-lost-terminal-closes-the-attempt-node
  (let [base (state/initial-state {:session-id "lost-node"})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :plan
                      :lane :plan
                      :agent-id :plan
                      :request {:snapshot {}}})
        call-id (:call-id call)
        base (-> base
                 (assoc-in [:calls call-id :state] :running)
                 (assoc-in [:calls call-id :attempt-id] :attempt-1)
                 (assoc-in [:calls call-id :started-at] 0)
                 (state/add-attempt-node call :attempt-1))
        guard (guards/recover-lost-model-terminal-guard)
        work ((:enabled? guard) base {:now 6000 :live-attempt-ids #{}})
        prepared (:state ((:prepare guard) base {:now 6000} work nil))
        node-id (state/attempt-node-id call-id :attempt-1)]
    (is (some? work))
    (is (= :outcome-unknown (get-in prepared [:cancel :nodes node-id :state])))
    (is (false? (get-in prepared [:cancel :nodes node-id :resource-alive?])))
    (is (= :outcome-unknown (get-in prepared [:calls call-id :state])))))

(deftest a-loaded-state-without-a-command-gate-gets-one
  (let [base (state/initial-state {:session-id "s1"})
        seeded (dissoc base :commands)]
    (with-owner
      {:session-id "s1"
       :ephemeral? true
       :initial-state seeded
       :model-gateway
       {:start! (fn [_request _deliver!] {:attempt-id "a1"})
        :cancel! (fn [_] nil)}}
      (fn [handle]
        (is (= 1 (get-in (snapshot handle)
                         [:state :commands :command-epoch]))
            "a seeded state gets a positive command epoch")
        (let [receipt ((:submit-command! handle)
                       {:command-id "c-seeded"
                        :command-epoch 1
                        :payload {:kind :send-message :text "hello"}})]
          (is (= :accepted (:status receipt)) (pr-str receipt)))
        (testing "a second attach keeps the same gate"
          (let [ensure (resolve 'hc.compose.owner.runtime/ensure-command-gate)
                state {:commands {:command-epoch 3 :retired-through 2}}]
            (is (identical? state (ensure state nil))
                "an existing valid gate is never replaced")))))))

(deftest a-restored-invalid-mission-state-starts-with-a-fresh-relay
  (let [invalid (-> (state/initial-state {:session-id "s1"})
                    (assoc :mission {:boards {} :bindings #{}}))
        mem (store/memory-store)]
    (store/commit! mem 0 invalid)
    (let [runtime (owner/start-owner! {:session-id "s1"
                                       :store mem
                                       :model-gateway
                                       {:start! (fn [_ _] {:attempt-id "a1"})
                                        :cancel! (fn [_] nil)}})]
      (try
        (is (map? (get-in ((:snapshot runtime))
                          [:state :mission :relay]))
            "the invalid committed mission is replaced by a fresh relay")
        (is (= 1 (get-in ((:snapshot runtime))
                         [:state :commands :command-epoch])))
        (let [receipt ((:submit-command! runtime)
                       {:command-id "c-invalid"
                        :command-epoch 1
                        :payload {:kind :send-message :text "hello"}})]
          (is (= :accepted (:status receipt)) (pr-str receipt)))
        (finally ((:stop! runtime)))))))

(deftest outbound-context-provider-is-frozen-on-the-model-call
  (with-owner
    {:session-id "s1"
     :ephemeral? true
     :outbound-context-fn
     (fn [_]
       {:ok? true
        :context {:system {:prompt-bytes 1 :modules ["test"]}
                  :estimates {:tokens :unknown :kind :unknown}}})
     :model-gateway
     {:start! (fn [_request _deliver!] {:attempt-id "a1"})
      :cancel! (fn [_] nil)}}
    (fn [handle]
      (let [receipt ((:submit-command! handle)
                     {:command-id "c-ctx"
                      :command-epoch 1
                      :payload {:kind :send-message :text "hello"}})]
        (is (= :accepted (:status receipt)) (pr-str receipt))
        (is (wait-for #(some? (get-in (snapshot handle)
                                      [:state :calls "call-1" :context]))
                      5000)
            "the provider context is bound before dispatch")
        (is (= 1 (get-in (snapshot handle)
                         [:state :calls "call-1" :context :system
                          :prompt-bytes])))
        (let [call (get-in (snapshot handle) [:state :calls "call-1"])]
          (is (= (:attempt-id call)
                 (get-in call [:context :attempt-id]))
              "the snapshot is bound to the Owner attempt id, not the gateway handle")
          (is (= (:attempt-id call) (:context-attempt-id call))))))))

(deftest t09-cancel-intent-keeps-a-running-shell-as-responsibility
  ;; T09: A runs a managed Shell for task X.  The user cancels X while the
  ;; session is still inside W.  The board records the cancellation intent
  ;; (a comment), refuses to remove X while the action is live, keeps the
  ;; action in R(a), refuses ordinary commits on X, and retires A only after
  ;; the Shell publishes its terminal and X is settled.
  (let [k (swarm-kernel/create)
        a (tu/spawn! k :a)
        x (:task-id (tu/create-task! k a {:goal "business task X"
                                          :remaining-work "run the managed shell"
                                          :done-when "the shell reports"
                                          :needs-token true}))
        _ (tu/exec-ok! k {:op :take-or-die :actor-id a
                          :confirm :i-hereby-confirm-i-die-without-regret})
        claim (tu/claim! k a x)
        handle {:execute-board! (fn
                                  ([command] (swarm-kernel/execute! k command))
                                  ([command _timeout-ms _opts]
                                   (swarm-kernel/execute! k command)))
                :board-snapshot #(swarm-kernel/snapshot k)}
        port (owner-board/make-port handle)
        shell (owner-board/shell-port port)
        term-id (get-in (swarm-kernel/snapshot k) [:token-slot :term-id])
        request {:actor-id a :task-id x :claim-id (:claim-id claim)
                 :token-term term-id :request-id "t09-shell"}
        eligibility ((:check-eligibility! shell) request)
        permit ((:begin-run! shell)
                (assoc request :expected-eligibility eligibility)
                {:command "sleep 60"})
        action-id (:action-id permit)]
    (is (= :running (:phase permit)))
    ;; The user records the cancellation intent while the session is live.
    (let [intent (tu/exec-ok! k {:op :comment :actor-id a :task-id x
                                 :text "user cancelled X"})]
      (is (= 1 (:comment-count intent)))
      (is (= ["user cancelled X"]
             (mapv :text (get-in (swarm-kernel/snapshot k) [:comments x]))))
      (is (some #(= :comment-added (:type %))
                (:events (swarm-kernel/snapshot k)))))
    ;; The kernel must not pretend that X is cancelled while it runs.
    (let [before (swarm-kernel/snapshot k)
          refused (tu/exec! k {:op :cancel :actor-id a :task-id x
                               :reason "user cancelled"})]
      (is (not (:ok? refused)))
      (is (= :ACTION_IN_FLIGHT (:error refused)))
      (is (some? (swarm-kernel/task k x)))
      (is (= before (swarm-kernel/snapshot k))
          "a refused cancellation must not mutate the board"))
    ;; The running session remains A's open responsibility.
    (let [snapshot (swarm-kernel/snapshot k)]
      (is (contains? (swarm-board/responsibility snapshot a) action-id))
      (is (contains? (swarm-board/responsibility snapshot a) x)))
    ;; W is not open to ordinary commits on X.
    (doseq [command [{:op :finish :actor-id a :task-id x
                      :claim-id (:claim-id claim)
                      :result-summary "premature"}
                     {:op :release :actor-id a :task-id x
                      :claim-id (:claim-id claim)}
                     {:op :begin-action :actor-id a :task-id x
                      :claim-id (:claim-id claim) :kind :shell}]]
      (is (= :ACTION_IN_FLIGHT (:error (tu/exec! k command)))
          (pr-str command)))
    ;; The holder does not die while the action or X remains open.
    (is (= :alive (get-in (swarm-kernel/snapshot k)
                          [:actors a :life-state])))
    ;; The session ends.  The action leaves R(a); X does not.
    (is (:ok? ((:publish-finished! shell) permit {:exit 0 :stdout ""})))
    (let [snapshot (swarm-kernel/snapshot k)]
      (is (= :finished (get-in snapshot [:actions action-id :phase])))
      (is (not (contains? (swarm-board/responsibility snapshot a) action-id)))
      (is (contains? (swarm-board/responsibility snapshot a) x)))
    ;; Only after the business task is settled may A retire.
    (is (= :alive (get-in (swarm-kernel/snapshot k)
                          [:actors a :life-state])))
    (let [settled (tu/exec-ok! k {:op :cancel :actor-id a :task-id x
                                  :reason "user cancelled"})]
      (is (true? (:retired? settled)))
      (is (nil? (swarm-kernel/task k x)))
      (is (some #(= :task-cancelled (:type %))
                (:events (swarm-kernel/snapshot k)))))
    (let [snapshot (swarm-kernel/snapshot k)]
      (is (= :dead (get-in snapshot [:actors a :life-state])))
      (is (= :free (get-in snapshot [:token-slot :state])))
      (is (empty? (swarm-board/responsibility snapshot a))))
    (tu/assert-invariants! k)))
