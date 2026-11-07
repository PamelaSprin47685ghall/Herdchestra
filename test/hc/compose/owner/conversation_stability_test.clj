(ns hc.compose.owner.conversation-stability-test
  "A turn that renders the shared conversation must not open mid-stream.

  The defect these pin: `open-user-turn-guard` froze `(conversation-tail
  state)` into the call's snapshot.  When another lane was streaming, that
  snapshot captured the assistant message's PARTIAL text.  The chain then
  recorded the partial digest, and the next request that read the final text
  reported `:PREFIX_DRIFT` at that assistant index — a genuine content change,
  but one caused by a torn read rather than by any reader.

  The fix gates turn creation on `conversation-stable?`, which is about the
  SHARED conversation only.  Independent workers keep their own `:context`,
  and Nudge calls are ephemeral (they carry `:nudge-id`), so neither is
  serialized by this."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.guards :as guards]
            [hc.compose.owner.state :as state]))

(defn- base-state
  []
  (state/initial-state {:session-id "conversation-stability"}))

(defn- with-pending-turn
  [s agent-id]
  (assoc s :pending-turns [{:role (if (= :plan agent-id) :plan :execution)
                            :agent-id agent-id
                            :tenure-id "tenure-1"}]))

(defn- streaming-run
  "One claimed model call with a started run, built by the real functions."
  [s agent-id]
  (let [[s call] (state/create-call
                  s
                  {:kind :model
                   :role (if (= :plan agent-id) :plan :execution)
                   :lane (if (= :plan agent-id) :plan :execution)
                   :agent-id agent-id
                   :request {:snapshot {:messages []}}})
        call (assoc call :state :running :attempt-id :attempt-1)
        s (state/put-call s call)
        s (state/start-run s call :attempt-1 1)]
    [s (state/run-id (:call-id call) :attempt-1)]))

(defn- open-turn
  "What the guard's enabled? predicate decides for this state."
  [s]
  ((:enabled? (guards/open-user-turn-guard)) s {}))

(deftest stability-follows-the-run-lifecycle
  (let [s (base-state)]
    (is (true? (state/conversation-stable? s))
        "a session with no runs is stable")
    (let [[s run-id] (streaming-run s :execution)]
      (is (false? (state/conversation-stable? s))
          "a started run owns a message whose text can still change")
      (let [s (state/append-run-text s run-id "partial" 2)]
        (is (false? (state/conversation-stable? s))
            "streaming is still mutable")
        (let [s (state/finish-run s run-id :cancelled 3
                                  {:cancel-reason :user-cancel})]
          (is (true? (state/conversation-stable? s))
              "a terminal run's text is fixed"))))))

(deftest another-lane-cannot-open-a-turn-mid-stream
  ;; The reported defect.  The pending turn belongs to :plan, while the live
  ;; run belongs to :execution, so `live-model-for-agent?` does not block it.
  ;; Only conversation stability can.
  (let [[s _run-id] (streaming-run (base-state) :execution)
        s (with-pending-turn s :plan)]
    (is (false? (state/live-model-for-agent? s :plan))
        "the plan lane owns no model call of its own")
    (is (nil? (open-turn s))
        "a plan turn must not freeze the execution lane's partial text")))

(deftest the-turn-opens-once-the-stream-is-terminal
  (let [[s run-id] (streaming-run (base-state) :execution)
        s (with-pending-turn s :plan)
        s (state/append-run-text s run-id "final answer" 2)
        s (state/finish-run s run-id :finished 3 {:text "final answer"})
        work (open-turn s)]
    (is (some? work) "the turn opens after the stream settles")
    (is (= :plan (:agent-id work)))))

(deftest the-same-agent-still-waits-for-its-own-live-model-call
  (let [s (with-pending-turn (base-state) :execution)
        [s _run-id] (streaming-run s :execution)]
    (is (nil? (open-turn s)))))

(deftest a-pending-cancel-still-blocks-before-stability-is-consulted
  (let [s (with-pending-turn (base-state) :plan)
        s (assoc s :pending-cancels [{:command-id "stop-1"
                                      :origin :operator-stop}])]
    (is (nil? (open-turn s)))))

