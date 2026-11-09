(ns hc.compose.owner.stop-command-test
  "Real Owner command-gate checks for the operator stop path.

  D7 from `plans/e2e-0.0.1-results.md`: a stop was refused with
  `command-id was already accepted with a different payload`, the run kept
  streaming, and the Nudge kept requesting.  The client generated sequential
  command ids (`cmd-1`, `cmd-2`, ...), so a reloaded tab or a second client
  reused an id the Owner had already accepted for a different payload.

  The Owner rule is correct and stays strict: the same id with the same
  payload replays, and the same id with a different payload is refused
  (§5.1).  The fix belongs to the client, which now uses a unique id per
  command and freezes the stop payload.  These tests prove the server side
  of that contract: a fresh id stops the active run, and the resulting
  cancelled admission never injects a Nudge."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else false))))

(defn- owner-state
  [handle]
  (:state ((:snapshot handle))))

(defn- run-state
  [state]
  (get-in state [:cancel :nodes
                 (get-in state [:cancel :active-run-id])
                 :state]))

(defn- command!
  [handle command-id payload]
  ((:submit-command! handle)
   {:command-id command-id
    :command-epoch 1
    :payload payload}))

(defn- hold-gateway
  [requests cancels]
  {:start! (fn [request _deliver!]
             (swap! requests conj request)
             {:attempt-id (:attempt-id request)})
   :cancel! (fn [handle]
              (swap! cancels conj handle))})

(defn- start-owner
  [requests cancels]
  (owner/start-owner!
   {:session-id (str "stop-command-" (random-uuid))
    :ephemeral? true
    :models {:main "main-model" :task "task-model"}
    ;; No actor slots: the test observes only the operator turn.
    :config {:swarm-slots []}
    :model-gateway (hold-gateway requests cancels)}))

(deftest stop-after-send-needs-a-fresh-command-id
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (testing "the send is accepted under its own command id"
        (let [receipt (command! handle "cmd-send"
                                {:kind :send-message :text "hello"})]
          (is (= :accepted (:status receipt)) (pr-str receipt))))
      (is (wait-for #(= 1 (count @requests)) 3000)
          "the user turn starts one model request")
      (is (wait-for #(= 1 (get-in ((:stats handle)) [:dispatch :live])) 3000)
          "the model attempt is registered before the operator stops it")
      (let [run-id (get-in (owner-state handle) [:cancel :active-run-id])]
        (testing "the same id with a stop payload is still refused"
          ;; This is the exact D7 rejection.  It must stay: accepting a
          ;; different payload under an accepted id would replay the wrong
          ;; receipt and hide a client bug.
          (let [conflict (command! handle "cmd-send"
                                   {:kind :stop-run
                                    :origin :operator-stop
                                    :reason :user-cancel})]
            (is (= :refused (:status conflict)) (pr-str conflict))
            (is (= :COMMAND_ID_PAYLOAD_CONFLICT
                   (get-in conflict [:error :code]))
                (pr-str conflict))))
        (testing "a fresh id is accepted and cancels the active run"
          (let [receipt (command! handle "cmd-stop"
                                  {:kind :stop-run
                                   :origin :operator-stop
                                   :reason :user-cancel})]
            (is (= :accepted (:status receipt)) (pr-str receipt))))
          (is (wait-for
               #(contains? #{:cancelling :cancelled}
                           (get-in (owner-state handle)
                                   [:cancel :nodes run-id :state]))
               3000)
              "the operator stop moves the run out of :running")
          (is (wait-for #(seq @cancels) 3000)
              "the live model attempt is signalled"))
        (testing "a later natural turn end does not inject a Nudge"
          ((:submit! handle)
           {:type :turn/ended
            :role :plan
            :tenure-id "tenure-1"
            :agent-id :plan})
          (is (wait-for
               #(some? (last (:turn-history (owner-state handle))))
               3000))
          (let [state (owner-state handle)]
            (is (empty? (filter :nudge? (vals (:calls state))))
                "a stopped run owes no Nudge")
            (is (contains? #{:operator-cancelled :admission-closed}
                           (:reason (last (:turn-history state))))
                (pr-str (:turn-history state)))))
      (finally
        ((:stop! handle))))))

(deftest same-id-same-payload-replays-the-original-receipt
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (let [first-receipt (command! handle "cmd-send"
                                    {:kind :send-message :text "hello"})
            replay (command! handle "cmd-send"
                             {:kind :send-message :text "hello"})]
        (is (= :accepted (:status first-receipt)))
        (is (= :accepted (:status replay)))
        (is (true? (:replayed? replay)))
        (is (= 1 (count (filter #(= "hello" (:text %))
                                (:conversation (owner-state handle)))))
            "a replay must not append a second user message"))
      (finally
        ((:stop! handle))))))

(deftest stop-clears-queued-turns-and-does-not-replay-them
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (command! handle "cmd-first" {:kind :send-message :text "first"})
      (is (wait-for #(= 1 (count @requests)) 3000)
          "the first turn starts one model request")

      ;; The plan model is still live, so this turn waits in `:pending-turns`.
      (command! handle "cmd-queued" {:kind :send-message :text "queued"})
      (is (wait-for #(some (fn [turn] (= "queued" (:text turn)))
                           (:pending-turns (owner-state handle)))
                    3000)
          "the second turn is queued behind the live model")

      (let [receipt (command! handle "cmd-stop"
                              {:kind :stop-run
                               :origin :operator-stop
                               :reason :user-cancel})]
        (is (= :accepted (:status receipt)) (pr-str receipt))
        (is (empty? (:pending-turns (owner-state handle)))
            "a stop discards every queued turn")
        (is (some #(= "queued" (:text %))
                  (:conversation (owner-state handle)))
            "the committed message stays in the conversation")
        (is (= 1 (count @requests))
            "no queued turn is dispatched by the stop"))

      (is (wait-for
           #(contains? #{:cancelling :cancelled}
                       (run-state (owner-state handle)))
           3000)
          "the stop reaches the run")

      (command! handle "cmd-after-stop"
                {:kind :send-message :text "after-stop"})
      (is (not-any? #(= "queued" (:text %))
                    (:pending-turns (owner-state handle)))
          "the new message cannot revive the old queued turn")
      ;; If the new turn is served, every later model request must belong to
      ;; the new command, never to the stopped queue.
      (let [served? (wait-for #(>= (count @requests) 2) 3000)]
        (is (not-any? #(= "cmd-queued"
                          (get-in % [:snapshot :turn-command-id]))
                      (rest @requests))
            "no model request is built from the stopped queue")
        (when served?
          (is (= "cmd-after-stop"
                 (get-in (last @requests)
                         [:snapshot :turn-command-id])))))
      (finally
        ((:stop! handle))))))
