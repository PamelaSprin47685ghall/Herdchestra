(ns hc.compose.owner.multi-client-test
  "Real Owner checks for the multi-client tmux contract (AGENTS.md §5.1).

  One session has one controller and any number of observers.  The first
  client that submits work becomes the controller; later clients observe
  until an explicit `:session/takeover`.  Observers may not send, stop,
  answer, review, or rotate the command epoch.  Presence changes never
  cancel a run: closing a page (detach) and reopening (attach) only move
  control and read the current run state."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]
            [hc.compose.owner.state :as state]))

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

(defn- command!
  ([handle command-id client-id payload]
   (command! handle command-id client-id 1 payload))
  ([handle command-id client-id epoch payload]
   ((:submit-command! handle)
    {:command-id command-id
     :command-epoch epoch
     :client-id client-id
     :payload payload})))

(defn- start-owner
  [requests cancels]
  (owner/start-owner!
   {:session-id (str "multi-client-" (random-uuid))
    :ephemeral? true
    :models {:main "main-model" :task "task-model"}
    :config {:swarm-slots []}
    :model-gateway
    {:start! (fn [request _deliver!]
               (swap! requests conj request)
               {:attempt-id (:attempt-id request)})
     :cancel! (fn [handle] (swap! cancels conj handle))}}))

(defn- presence
  [state]
  (select-keys state [:controller-id :observer-ids]))

(defn- run-state
  [state]
  (let [run-id (get-in state [:cancel :active-run-id])]
    (get-in state [:cancel :nodes run-id :state])))

(deftest first-client-controls-and-takeover-transfers-control
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (testing "the first client becomes the controller"
        (let [receipt (command! handle "a-send" "a"
                                {:kind :send-message :text "hello"})]
          (is (= :accepted (:status receipt)) (pr-str receipt)))
        (is (wait-for #(= 1 (count @requests)) 3000))
        (is (= {:controller-id "a" :observer-ids []}
               (presence (owner-state handle)))))

      (testing "a later client is an observer and cannot send"
        (let [receipt (command! handle "b-send" "b"
                                {:kind :send-message :text "sneak"})]
          (is (= :refused (:status receipt)) (pr-str receipt))
          (is (= :NOT_CONTROLLER (get-in receipt [:error :code])))
          (is (= "a" (get-in receipt [:error :details :controller-id])))
          (is (= :takeover (get-in receipt [:error :details :action]))))
        (is (= 1 (count (filter #(= :user (:role %))
                                (:conversation (owner-state handle)))))
            "an observer command must not append a user message"))

      (testing "attach records the observer"
        (let [receipt (command! handle "b-attach" "b"
                                {:kind :session/attach-client})]
          (is (= :accepted (:status receipt)))
          (is (= :observer (:outcome receipt)))
          (is (= ["b"] (:observer-ids receipt)))))
      (is (= {:controller-id "a" :observer-ids ["b"]}
             (presence (owner-state handle))))

      (testing "the controller reconnecting with the same id keeps control"
        (let [receipt (command! handle "a-reattach" "a"
                                {:kind :session/attach-client})]
          (is (= :accepted (:status receipt)))
          (is (= :controller (:outcome receipt)))
          (is (= "a" (:controller-id receipt)))
          (is (= ["b"] (:observer-ids receipt))
              "reattaching must not duplicate or reorder the observers")))

      (testing "takeover moves control and queues the old controller"
        (let [receipt (command! handle "b-takeover" "b"
                                {:kind :session/takeover})]
          (is (= :accepted (:status receipt)))
          (is (= :taken-over (:outcome receipt)))
          (is (= "a" (:previous-controller-id receipt)))
          (is (= "b" (:controller-id receipt)))
          (is (= ["a"] (:observer-ids receipt)))))
      (is (= {:controller-id "b" :observer-ids ["a"]}
             (presence (owner-state handle))))

      (testing "the old controller can no longer stop"
        (let [receipt (command! handle "a-stop" "a"
                                {:kind :stop-run
                                 :origin :operator-stop
                                 :reason :user-cancel})]
          (is (= :refused (:status receipt)))
          (is (= :NOT_CONTROLLER (get-in receipt [:error :code]))))
        (is (= :running (run-state (owner-state handle)))))

      (testing "the new controller stops the run"
        (let [receipt (command! handle "b-stop" "b"
                                {:kind :stop-run
                                 :origin :operator-stop
                                 :reason :user-cancel})]
          (is (= :accepted (:status receipt)) (pr-str receipt)))
        (is (wait-for #(contains? #{:cancelling :cancelled}
                                  (run-state (owner-state handle)))
                      3000))
        (is (wait-for #(seq @cancels) 3000)))
      (finally
        ((:stop! handle))))))

(deftest takeover-puts-the-old-controller-at-the-queue-front
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (command! handle "a-attach" "a" {:kind :session/attach-client})
      (command! handle "b-attach" "b" {:kind :session/attach-client})
      (command! handle "c-attach" "c" {:kind :session/attach-client})
      (is (= {:controller-id "a" :observer-ids ["b" "c"]}
             (presence (owner-state handle))))
      (let [receipt (command! handle "c-takeover" "c"
                              {:kind :session/takeover})]
        (is (= :accepted (:status receipt)))
        (is (= :taken-over (:outcome receipt)))
        (is (= "a" (:previous-controller-id receipt)))
        (is (= "c" (:controller-id receipt)))
        (is (= ["a" "b"] (:observer-ids receipt))
            "the old controller goes to the front; the rest keep their order"))
    (finally
      ((:stop! handle))))))

(deftest observers-cannot-submit-session-work
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (command! handle "a-attach" "a" {:kind :session/attach-client})
      (command! handle "b-attach" "b" {:kind :session/attach-client})
      (let [before (owner-state handle)]
        (doseq [[id payload]
                [["b-stop" {:kind :stop-run
                            :origin :operator-stop
                            :reason :user-cancel}]
                 ["b-answer" {:kind :answer-ask
                              :text "approved"
                              :tenure-id "tenure-1"}]
                 ["b-review" {:kind :review/submit
                              :lane :plan
                              :tenure-id "tenure-1"
                              :findings []}]
                 ["b-board" {:kind :board/command
                             :command {:op :claim}}]
                 ["b-epoch" {:kind :session/issue-epoch}]]]
          (let [receipt (command! handle id "b" payload)]
            (is (= :refused (:status receipt)) (pr-str [id receipt]))
            (is (= :NOT_CONTROLLER (get-in receipt [:error :code]))
                (pr-str [id receipt]))))
        (is (= before (owner-state handle))
            "every observer refusal must leave the committed state unchanged"))
      (finally
        ((:stop! handle))))))

(deftest detach-never-stops-and-reopen-reads-the-real-run
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (command! handle "a-attach" "a" {:kind :session/attach-client})
      (command! handle "b-attach" "b" {:kind :session/attach-client})
      (command! handle "a-send" "a" {:kind :send-message :text "run"})
      (is (wait-for #(= 1 (count @requests)) 3000))

      (testing "detaching the controller promotes the observer and keeps the run"
        (let [receipt (command! handle "a-detach" "a"
                                {:kind :session/detach-client})]
          (is (= :accepted (:status receipt)))
          (is (= :detached (:outcome receipt)))
          (is (= "b" (:promoted-controller-id receipt)))
          (is (= "b" (:controller-id receipt))))
        (is (= :running (run-state (owner-state handle))))
        (is (empty? @cancels)
            "detach must not cancel the model attempt")
        (is (= 1 (count @requests))))

      (testing "reopening attaches as an observer and reads the run state"
        (let [receipt (command! handle "a-reopen" "a"
                                {:kind :session/attach-client})]
          (is (= :accepted (:status receipt)))
          (is (= :observer (:outcome receipt)))
          (is (= :running (:run-state receipt)))
          (is (= "b" (:controller-id receipt)))))

      (testing "the controller stops; the observer sees the stop, not a restart"
        (let [receipt (command! handle "b-stop" "b"
                                {:kind :stop-run
                                 :origin :operator-stop
                                 :reason :user-cancel})]
          (is (= :accepted (:status receipt)) (pr-str receipt)))
        (is (wait-for #(= :cancelled (run-state (owner-state handle))) 3000))
        (let [receipt (command! handle "a-resync" "a"
                                {:kind :session/attach-client})]
          (is (= :cancelled (:run-state receipt))
              "reopening reports the real stopped run")
          (is (= 1 (count @requests))
              "reopening must not start a new model request")))
      (finally
        ((:stop! handle))))))

(deftest epoch-retirement-refuses-old-commands-without-executing
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (command! handle "a-send" "a" {:kind :send-message :text "run"})
      (is (wait-for #(= 1 (count @requests)) 3000))
      (let [issued (command! handle "a-epoch" "a" 1
                             {:kind :session/issue-epoch})]
        (is (= :accepted (:status issued)))
        (is (= 2 (:epoch issued))))
      (let [before (owner-state handle)
            retired (command! handle "a-stop-old" "a" 1
                              {:kind :stop-run
                               :origin :operator-stop
                               :reason :user-cancel})]
        (is (= :refused (:status retired)))
        (is (= :COMMAND_EPOCH_RETIRED (get-in retired [:error :code])))
        (is (= :resync-state (get-in retired [:error :details :action])))
        (is (= before (owner-state handle))
            "a retired epoch must never execute or mutate")
        (is (= :running (run-state (owner-state handle)))))
      (testing "the same client succeeds after re-reading the epoch"
        (let [receipt (command! handle "a-stop-new" "a" 2
                                {:kind :stop-run
                                 :origin :operator-stop
                                 :reason :user-cancel})]
          (is (= :accepted (:status receipt)) (pr-str receipt)))
        (is (wait-for #(contains? #{:cancelling :cancelled}
                                  (run-state (owner-state handle)))
                      3000)))
      (finally
        ((:stop! handle))))))

(deftest command-id-replay-and-conflict-include-the-client
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (let [first-receipt (command! handle "same-id" "a"
                                    {:kind :session/attach-client})
            replay (command! handle "same-id" "a"
                             {:kind :session/attach-client})
            other-client (command! handle "same-id" "b"
                                   {:kind :session/attach-client})]
        (is (= :accepted (:status first-receipt)))
        (is (= :accepted (:status replay)))
        (is (true? (:replayed? replay)))
        (is (= :refused (:status other-client))
            "the same id from another client is not a replay")
        (is (= :COMMAND_ID_PAYLOAD_CONFLICT
               (get-in other-client [:error :code]))))
      (finally
        ((:stop! handle))))))

(deftest attach-needs-an-id-and-the-last-controller-can-leave
  (let [requests (atom [])
        cancels (atom [])
        handle (start-owner requests cancels)]
    (try
      (let [anonymous (command! handle "anon" nil
                                {:kind :session/attach-client})]
        (is (= :refused (:status anonymous)))
        (is (= :CLIENT_ID_REQUIRED (get-in anonymous [:error :code]))))
      (command! handle "a-attach" "a" {:kind :session/attach-client})
      (let [detached (command! handle "a-detach" "a"
                               {:kind :session/detach-client})]
        (is (= :detached (:outcome detached)))
        (is (nil? (:controller-id detached)))
        (is (= [] (:observer-ids detached)))
        (is (nil? (:promoted-controller-id detached))))
      (is (= {:controller-id nil :observer-ids []}
             (presence (owner-state handle))))
      (let [reattach (command! handle "a-again" "a"
                               {:kind :session/attach-client})]
        (is (= :controller (:outcome reattach)))
        (is (= "a" (:controller-id reattach))))
      (finally
        ((:stop! handle))))))

(deftest restart-clears-connection-scoped-presence
  (let [initial (assoc (state/initial-state {:session-id "restart-presence"})
                       :controller-id "old-controller"
                       :observer-ids ["old-observer"])
        restored (state/restore initial)]
    (is (true? (:ok? restored)) (pr-str restored))
    (is (= {:controller-id nil :observer-ids []}
           (select-keys (:state restored)
                        [:controller-id :observer-ids]))
        "a restart has no live clients; stale presence must not fence the next one")))
