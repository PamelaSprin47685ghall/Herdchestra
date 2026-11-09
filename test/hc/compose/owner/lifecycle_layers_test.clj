(ns hc.compose.owner.lifecycle-layers-test
  "Four-layer lifecycle guards for one Owner.

  The tests use the real Owner runtime, a real memory store, and fake model
  and executor ports.  No HTTP server is started.  Pure cancel/guard
  transitions are used where the Owner runtime has no public command for the
  internal node (for example an :applying workspace action)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.cancel :as cancel]
            [hc.compose.cancel.model :as cancel-model]
            [hc.compose.guards.scheduler :as scheduler]
            [hc.compose.owner :as owner]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.compose.retry.planner :as retry]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 10) (recur))
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

;; ---------------------------------------------------------------------------
;; 1. State enumeration

(deftest lifecycle-state-enums-are-complete-and-separate
  (let [call-states (:call cancel-model/states-by-kind)
        run-states (:run cancel-model/states-by-kind)
        attempt-states (:attempt cancel-model/states-by-kind)]
    (testing "the authoritative call set is the retry planner's phase set"
      (is (= retry/call-phases call-states))
      (is (= #{:ready :dispatching :running :retry-wait
               :succeeded :failed :cancelled :outcome-unknown}
             call-states)))
    (testing "the run set is the cancellation run set"
      (is (= #{:preparing :running :cancelling :cancelled
               :succeeded :failed}
             run-states)))
    (testing "each kind accepts its own full set"
      (is (every? #(cancel-model/legal-state? :call %) call-states))
      (is (every? #(cancel-model/legal-state? :run %) run-states))
      (is (every? #(cancel-model/legal-state? :attempt %) attempt-states)))
    (testing "call-only and run-only states do not cross the boundary"
      (is (not (contains? run-states :ready)))
      (is (not (contains? run-states :retry-wait)))
      (is (not (contains? call-states :preparing)))
      (is (not (contains? call-states :cancelling)))
      (is (not (cancel-model/legal-state? :run :ready)))
      (is (not (cancel-model/legal-state? :call :preparing))))
    (testing "a call node carrying a run-only state is structurally invalid"
      (let [valid (-> (cancel/new-state {:session-id "enum" :run-id "run-1"})
                      (cancel/add-node {:node-id "call-1" :kind :call
                                        :parent-id "run-1"
                                        :state :running}))
            invalid (assoc-in valid [:nodes "call-1" :state] :preparing)
            problems (cancel-model/validate-state invalid)]
        (is (some #(= :illegal-state (:problem %)) problems))))))

;; ---------------------------------------------------------------------------
;; 2. call-id / attempt-id separation

(deftest attempt-id-changes-while-call-id-stays-and-late-old-terminal-is-ignored
  (let [requests (atom [])
        pending (atom {})
        gateway {:start! (fn [request deliver!]
                          (swap! requests conj request)
                          (if (= 1 (count @requests))
                            (do
                              (deliver! {:event :failed
                                         :call-id (:call-id request)
                                         :attempt-id (:attempt-id request)
                                         :error {:kind :timeout
                                                 :message "retry me"}})
                              {:attempt-id (:attempt-id request)})
                            (do
                              (swap! pending assoc (:attempt-id request)
                                     deliver!)
                              {:attempt-id (:attempt-id request)})))
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "lifecycle-attempt"
       :store (owner/memory-store)
       :models {:main "main-model" :task "task-model"}
       :config {:swarm-slots []}
       :retry-policy {:base-delay-ms 0 :jitter-ms 0
                      :deadline-ms 60000
                      :max-attempts 3 :max-retries 2}
       :model-gateway gateway}
      (fn [handle]
        (is (= :accepted
               (:status ((:submit-command! handle)
                         {:command-id "send-1" :command-epoch 1
                          :payload {:kind :send-message :text "retry"}}))))
        (is (wait-for #(>= (count @requests) 2) 5000)
            (pr-str (mapv #(select-keys % [:call-id :attempt-id])
                          @requests)))
        (let [[request-1 request-2] @requests
              call-id (:call-id request-1)
              attempt-1 (:attempt-id request-1)
              attempt-2 (:attempt-id request-2)]
          (is (= call-id (:call-id request-2)))
          (is (not= (str attempt-1) (str attempt-2)))
          (is (wait-for
               #(and (contains? #{:dispatching :running}
                                (call-state handle call-id))
                     (= (str attempt-2)
                        (str (get-in (snapshot handle)
                                     [:state :calls call-id :attempt-id]))))
               5000))
          (testing "a late terminal for the old attempt is diagnostic only"
            (let [before-late (get-in (snapshot handle)
                                      [:state :calls call-id])]
            ((:submit! handle)
             {:type :model/event
              :event {:event :completed
                      :call-id call-id
                      :attempt-id attempt-1
                      :text "late old answer"}})
            (is (wait-for
                 #(some (fn [diag]
                          (contains? #{:LATE_MODEL_EVENT
                                       :STALE_MODEL_ATTEMPT
                                       :LATE_MODEL_EVENT_UNKNOWN_RUN}
                                     (:code diag)))
                        (get-in (snapshot handle) [:state :diagnostics]))
                 3000))
            (let [call (get-in (snapshot handle) [:state :calls call-id])]
              (is (= (:state before-late) (:state call)))
              (is (contains? #{:dispatching :running} (:state call)))
              (is (= (str attempt-2) (str (:attempt-id call))))
              (is (not= :succeeded (:state call))))))
          (let [deliver-2 (get @pending attempt-2)]
            (is (fn? deliver-2))
            (deliver-2 {:event :completed
                        :call-id call-id
                        :attempt-id attempt-2
                        :text "final answer"}))
          (is (wait-for #(= :succeeded (call-state handle call-id)) 5000))))))
  (testing "the pure handler ordering keeps a running call running"
    (let [base (state/initial-state {:session-id "lifecycle-attempt-pure"})
          [base call] (state/create-call
                       base
                       {:kind :model :role :plan :lane :plan
                        :agent-id :plan
                        :request {:snapshot {:messages []}}})
          call-1 (assoc call :state :running :attempt-id "attempt-1")
          base-1 (-> base
                     (state/put-call call-1)
                     (state/add-attempt-node call-1 "attempt-1")
                     (state/start-run call-1 "attempt-1" 1))
          call-2 (assoc call-1 :state :running :attempt-id "attempt-2")
          base-2 (-> base-1
                     (state/put-call call-2)
                     (state/add-attempt-node call-2 "attempt-2")
                     (state/start-run call-2 "attempt-2" 2))
          result (handlers/apply-message
                  base-2
                  {:type :model/event
                   :event {:event :completed
                           :call-id (:call-id call)
                           :attempt-id "attempt-1"
                           :text "late old"}}
                  {:now 3})
          after (:state result)
          call-after (get-in after [:calls (:call-id call)])]
      (is (= :running (:state call-after)))
      (is (= "attempt-2" (:attempt-id call-after)))
      (is (some #(contains? #{:LATE_MODEL_EVENT :STALE_MODEL_ATTEMPT}
                            (:code %))
                (:diagnostics after))))))

;; ---------------------------------------------------------------------------
;; 3. Terminal idempotence

(defn- running-model-call
  [session-id]
  (let [base (state/initial-state {:session-id session-id})
        [base call] (state/create-call
                     base
                     {:kind :model :role :plan :lane :plan
                      :agent-id :plan
                      :request {:snapshot {:messages []}}})
        call (assoc call :state :running :attempt-id "attempt-1")
        base (-> base
                 (state/put-call call)
                 (state/add-attempt-node call "attempt-1")
                 (state/start-run call "attempt-1" 1))]
    [base call]))

(defn- accept-call-terminal
  [base call status]
  (let [queued (:state (handlers/apply-message
                        base
                        {:type :call/terminal
                         :call-id (:call-id call)
                         :attempt-id "attempt-1"
                         :status status
                         :payload {:text (name status)}}
                        {:now 2}))
        prepared (scheduler/step
                  (scheduler/catalog [(guards/accept-call-terminal-guard)])
                  queued {:now 3}
                  (scheduler/new-scheduler {:quota 8}))]
    (:state (:transition prepared))))

(deftest terminal-is-fixed-by-the-first-observation
  (let [[base call] (running-model-call "lifecycle-terminal")
        after-success (accept-call-terminal base call :succeeded)
        after-failure (accept-call-terminal after-success call :failed)
        call-after (get-in after-failure [:calls (:call-id call)])
        attempt-node (get-in after-failure
                             [:cancel :nodes
                              (state/attempt-node-id (:call-id call)
                                                     "attempt-1")])]
    (testing "the first terminal fixes both call and attempt"
      (is (= :succeeded (:state call-after)))
      (is (= :succeeded (:outcome attempt-node)))
      (is (= :succeeded (:state attempt-node))))
    (testing "a later different terminal is late evidence"
      (is (= :succeeded (:state call-after)))
      (is (some #(= :failed (:status %)) (:late-results call-after)))
      (is (some #(= :LATE_TERMINAL_AFTER_CANCEL (:code %))
                (:diagnostics after-failure))))
    ;; Negative: the later failure must not rewrite the fixed success.
    (is (not= :failed (:state call-after)))
    (is (not= :failed (:outcome attempt-node)))))

;; ---------------------------------------------------------------------------
;; 4. Command id / epoch gate

(deftest command-id-and-epoch-gate-replays-and-retires
  (let [requests (atom [])
        gateway {:start! (fn [request _deliver!]
                          (swap! requests conj request)
                          {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}]
    (with-owner
      {:session-id "lifecycle-command"
       :store (owner/memory-store)
       :models {:main "main-model" :task "task-model"}
       :config {:swarm-slots []}
       :model-gateway gateway}
      (fn [handle]
        (let [payload {:kind :send-message :text "hello"}
              first-receipt ((:submit-command! handle)
                             {:command-id "send-1" :command-epoch 1
                              :payload payload})
              replay ((:submit-command! handle)
                      {:command-id "send-1" :command-epoch 1
                       :payload payload})
              conflict ((:submit-command! handle)
                        {:command-id "send-1" :command-epoch 1
                         :payload {:kind :send-message :text "other"}})
              issued ((:submit-command! handle)
                      {:command-id "epoch-1" :command-epoch 1
                       :payload {:kind :session/issue-epoch}})
              retired ((:submit-command! handle)
                       {:command-id "send-1" :command-epoch 1
                        :payload payload})
              state (:state (snapshot handle))]
          (testing "same id and payload replay the stored receipt"
            (is (= :accepted (:status first-receipt)))
            (is (= :accepted (:status replay)))
            (is (true? (:replayed? replay))))
          (testing "same id with a different payload is refused"
            (is (= :refused (:status conflict)))
            (is (= :COMMAND_ID_PAYLOAD_CONFLICT
                   (get-in conflict [:error :code]))))
          (testing "the epoch advances and the old id is retired"
            (is (= :accepted (:status issued)))
            (is (= 2 (:epoch issued)))
            (is (= 2 (get-in state [:commands :command-epoch])))
            (is (= :refused (:status retired)))
            (is (= :COMMAND_EPOCH_RETIRED (get-in retired [:error :code])))
            (is (= :resync-state (get-in retired [:error :details :action]))))
          (testing "the retired command never executes again"
            (is (= 1 (count @requests)))
            (is (= 1 (count (filter #(= "hello" (:text %))
                                    (:conversation state)))))))))))

;; ---------------------------------------------------------------------------
;; 5. outcome-unknown is not automatically reclaimed

(deftest outcome-unknown-is-not-reclaimed
  (let [base (state/initial-state {:session-id "lifecycle-unknown"})
        [base retry-call] (state/create-call
                           base
                           {:kind :model :role :plan :lane :plan
                            :agent-id :plan :request {}})
        [base unknown-call] (state/create-call
                             base
                             {:kind :model :role :plan :lane :plan
                              :agent-id :plan :request {}})
        retry-call (assoc retry-call
                          :state :retry-wait
                          :retry {:phase :retry-wait :retry-at 1})
        unknown-call (assoc unknown-call
                            :state :outcome-unknown
                            :retry {:phase :outcome-unknown :retry-at 1})
        positive (-> base
                     (state/put-call retry-call))
        negative (-> base
                     (state/put-call unknown-call))
        catalog (scheduler/catalog [(guards/claim-due-retry-guard)])
        ctx {:now 10 :retry-policy (:retry-policy base)}
        positive-candidate
        (scheduler/next-candidate catalog positive ctx
                                 (scheduler/new-scheduler {:quota 8}))
        negative-candidate
        (scheduler/next-candidate catalog negative ctx
                                 (scheduler/new-scheduler {:quota 8}))]
    (testing "the guard can select a due retry"
      (is (some? positive-candidate))
      (is (= (:call-id retry-call)
             (get-in positive-candidate [:work :call-id]))))
    (testing "an outcome-unknown call is not selected"
      (is (nil? negative-candidate))
      (is (= :outcome-unknown
             (get-in negative [:calls (:call-id unknown-call) :state]))))
    ;; Negative: unknown is a terminal outcome, not a live retry state.
    (is (not (contains? state/live-states :outcome-unknown)))
    (is (not= :retry-wait
              (get-in negative [:calls (:call-id unknown-call) :state])))))

;; ---------------------------------------------------------------------------
;; 6. Run/call cancellation and the deferred :applying action

(deftest run-cancel-layers-call-and-preserves-applying-action
  (testing "real owner cancellation keeps a live shell as outcome-unknown"
    (let [started (promise)
          release (promise)
          cancel-called (atom 0)]
      (with-owner
        {:session-id "lifecycle-cancel-live"
         :store (owner/memory-store)
         :models {:main "main-model" :task "task-model"}
         :config {:swarm-slots []}
         :model-gateway
         {:start! (fn [request deliver!]
                    (deliver! {:event :tool-call
                               :call-id "tool-1"
                               :payload
                               {:executor :shell :name "shell" :cmd "sleep"
                                :scope {:kind :reset-scope
                                        :new-scope-goal "test"
                                        :new-scope-retention-budget 8}}})
                    (deliver! {:event :completed :text "spawned"})
                    {:attempt-id (:attempt-id request)})
          :cancel! (fn [_] nil)}
         :executors
         {:shell {:run (fn [_]
                        (deliver started true)
                        (deref release 30000 :timeout)
                        {:status :cancelled :payload {:exit 143}})
                  :cancel (fn [_]
                            (swap! cancel-called inc)
                            {:ok? false :live? true})}}}
        (fn [handle]
          (try
            (is (= :accepted
                   (:status ((:submit-command! handle)
                             {:command-id "send-1" :command-epoch 1
                              :payload {:kind :send-message :text "run"}}))))
            (is (true? (deref started 3000 false)))
            (is (= :accepted
                   (:status ((:submit-command! handle)
                             {:command-id "stop-1" :command-epoch 1
                              :payload {:kind :stop-run
                                        :origin :operator-esc}}))))
            (is (wait-for
                 #(= :cancelling
                     (get-in (snapshot handle)
                             [:state :cancel :nodes
                              "lifecycle-cancel-live-run" :state]))
                 5000))
            (is (wait-for
                 #(= :outcome-unknown
                     (call-state handle "call-1/tool-0"))
                 5000))
            (is (pos? @cancel-called))
            (deliver release :exit)
            (is (wait-for
                 #(= :cancelled
                     (get-in (snapshot handle)
                             [:state :cancel :nodes
                              "lifecycle-cancel-live-run" :state]))
                 5000))
            (is (= :outcome-unknown
                   (call-state handle "call-1/tool-0")))
            (finally
              (deliver release :cleanup)))))))
  (let [tree (-> (cancel/new-state {:session-id "cancel-session"
                                    :run-id "run-1"})
                 (cancel/add-node
                  {:node-id "call-1" :kind :call :parent-id "run-1"
                   :state :running :side-effecting? true
                   :current-attempt-id "a2"})
                 (cancel/add-node
                  {:node-id "action-1" :kind :action :parent-id "run-1"
                   :state :applying :side-effecting? true}))
        cancelled (cancel/accept-operator-cancel
                   tree {:session-id "cancel-session"
                         :run-id "run-1"
                         :command-id "cancel-1"
                         :origin :operator-esc
                         :reason :user-cancel
                         :at 10})
        after-cancel (:state cancelled)
        call-node (cancel-model/node after-cancel "call-1")
        action-node (cancel-model/node after-cancel "action-1")
        run-node (cancel-model/node after-cancel "run-1")
        before-terminal (cancel/finish-run after-cancel {:run-id "run-1" :at 11})
        call-terminal (cancel/accept-terminal
                       after-cancel
                       {:node-id "call-1" :attempt-id "a2"
                        :outcome :failed :at 12})
        action-terminal (cancel/accept-terminal
                         (:state call-terminal)
                         {:node-id "action-1" :outcome :succeeded :at 13})
        after-terminal (:state action-terminal)
        action-after (cancel-model/node after-terminal "action-1")
        finished (cancel/finish-run after-terminal
                                    {:run-id "run-1" :at 14})]
    (testing "the run and its call are cancelled in layers"
      (is (:ok? cancelled))
      (is (= :cancelling (:state run-node)))
      (is (= :closed (:admission run-node)))
      (is (= :outcome-unknown (:state call-node))))
    (testing "the :applying action is deferred, not discarded"
      (is (= :applying (:state action-node)))
      (is (true? (:cancel-after-apply? action-node)))
      (is (some? action-node)))
    (testing "the live action blocks run closure until its terminal"
      (is (false? (:ok? before-terminal)))
      (is (= :RESOURCES_NOT_QUIESCENT (get-in before-terminal [:error :code]))))
    (testing "the action terminal records the deferred cancellation"
      (is (= :finished (:state action-after)))
      (is (= :succeeded (:outcome action-after)))
      (is (true? (:cancel-applied-after-commit? action-after))))
    (testing "the call's unknown outcome is preserved after its settlement"
      (is (= :outcome-unknown
             (:state (cancel-model/node after-terminal "call-1"))))
      (is (false? (:resource-alive?
                   (cancel-model/node after-terminal "call-1")))))
    (testing "the run closes only after the action settles"
      (is (:ok? finished))
      (is (= :cancelled (:state (cancel-model/node (:state finished)
                                                   "run-1")))))
    ;; Negative: the action node is not removed and the call is not faked
    ;; into :cancelled while its side effect is unknown.
    (is (contains? (:nodes after-terminal) "action-1"))
    (is (not= :cancelled (:state call-node)))
    (is (empty? (cancel-model/validate-state after-terminal)))))

;; ---------------------------------------------------------------------------
;; 7. Property: out-of-order terminals never overwrite or cross attempts

(defn- terminal-property-state
  [initial-call-state]
  (-> (cancel/new-state {:session-id "terminal-property" :run-id "run-1"})
      (cancel/add-node {:node-id "call-1" :kind :call :parent-id "run-1"
                        :state initial-call-state
                        :current-attempt-id "a2"
                        :side-effecting? true})
      (cancel/add-node {:node-id "call-1#a1" :kind :attempt
                        :parent-id "call-1"
                        :state :succeeded :outcome :succeeded
                        :attempt-id "a1"
                        :side-effecting? true
                        :resource-alive? false})
      (cancel/add-node {:node-id "call-1#a2" :kind :attempt
                        :parent-id "call-1"
                        :state :running :attempt-id "a2"
                        :side-effecting? true})))

(defspec out-of-order-terminals-never-overwrite-or-cross-attempts 50
  (prop/for-all
   [initial-state (gen/elements [:dispatching :running :retry-wait])
    ops (gen/vector
         (gen/tuple (gen/elements ["a1" "a2"])
                    (gen/elements
                     (cancel-model/terminal-states-by-kind :call)))
         1 12)]
   (let [base (terminal-property-state initial-state)
         final (reduce (fn [s [attempt-id outcome]]
                         (:state
                          (cancel/accept-terminal
                           s {:node-id "call-1"
                              :attempt-id attempt-id
                              :outcome outcome
                              :at 1})))
                       base
                       ops)
         first-a2 (some (fn [[attempt-id outcome]]
                          (when (= "a2" attempt-id)
                            outcome))
                        ops)
         expected-state (or first-a2 initial-state)
         call-node (cancel-model/node final "call-1")
         old-node (cancel-model/node final "call-1#a1")]
     (and (= expected-state (:state call-node))
          (if first-a2
            (= expected-state (:outcome call-node))
            true)
          (= :succeeded (:state old-node))
          (= :succeeded (:outcome old-node))
          (cancel-model/legal-state? :call (:state call-node))
          (empty? (cancel-model/validate-state final))))))
