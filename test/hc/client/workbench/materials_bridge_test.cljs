(ns hc.client.workbench.materials-bridge-test
  "Consumer-path regression for the materials pending-command sweep.

  D-26: a `:materials/settings/update` command whose transport send was
  acknowledged but whose receipt never arrived stayed `:submitting`
  forever.  `recover-pending-materials-commands!` only ran inside
  `sync-from-db!`, and sync only fires on server-revision/connection
  changes, so the pending lock (`:ui/materials :commands` key
  `[:materials/settings]`) refused every later command on the same key.

  These tests drive the real bridge against a headless Fulcro app with a
  stubbed transport: the sweep must query receipts without a sync event,
  the gateway's `:not-found` answer must release the lock, and the
  transport-level `:submitted` ack must not release it early."
  (:require [cljs.test :refer-macros [async deftest is testing run-tests]]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.materials.model :as model]
            [hc.client.workbench.materials-bridge :as bridge]
            [hc.client.workbench.sync-bridge :as sync-bridge]
            [hc.client.workbench.transport-test]
            [hc.client.workbench.transport :as transport]))

(comp/defsc FauxRoot
  [_this _props]
  {:query [:ui/materials :ui/command :ui/notices :session/by-id
           :ui/selected-session-id :ui/session-list]
   :ident (fn [] [:root/id :root])}
  nil)

(defn- test-app
  [materials-state]
  (application/headless-synchronous-app
   FauxRoot
   {:initial-db {:ui/materials materials-state
                 :ui/command {}
                 :ui/notices []}}))

(defn- synced-materials-state
  []
  (assoc (model/initial-state)
         :sync {:phase :synced :command-epoch 7}
         :transport-available? true))

(defn- queued-settings-state
  "A state with one `:materials/settings/update` command already
  `:submitting`, as `flush-outbox!` leaves it after a successful send."
  []
  (let [state (assoc (synced-materials-state)
                     :selected-session-id "s-1")
        queued (:state (model/queue-command
                        state model/settings-update-kind
                        {:memory-read? true :memory-suggest? true}
                        {:key [:materials/settings]}))
        command-id (->> (:commands queued)
                        (filter (fn [[_ c]] (= [:materials/settings]
                                               (:key c))))
                        first val :command-id)]
    {:state (model/mark-submitting queued command-id)
     :command-id command-id}))

(defn- settle!
  "Run the deferred `js/setTimeout` hops the bridge schedules."
  [ms]
  (js/Promise.
   (fn [resolve _reject]
     (js/setTimeout resolve ms))))

(defn- app-state
  [app]
  (:ui/materials (application/current-state app)))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(deftest pending-receipt-requests-cover-only-pending-commands
  (let [{:keys [state command-id]} (queued-settings-state)
        commands (:commands state)
        commands (assoc commands
                        "done-1" {:command-id "done-1" :status :accepted}
                        "failed-1" {:command-id "failed-1" :status :failed})
        state (assoc state :commands commands)
        requests (bridge/pending-receipt-requests state)]
    (is (= 1 (count requests)))
    (is (= command-id (:command-id (first requests))))
    (is (= "s-1" (:session-id (first requests)))
        "the session id rides along so the query stays session-scoped")
    ;; `pending-receipt-requests` already filters on `model/pending-statuses`;
    ;; the count and command-id assertions above pin that contract.
    (is (every? :command-id requests))))

;; `recover-pending-materials-commands!` accepts a `send-receipt!` seam so
;; tests can capture requests synchronously; the deferred `js/setTimeout`
;; still delivers them on the next tick.
(deftest sweep-queries-receipts-without-a-sync-event
  (async done
         (let [{:keys [state command-id]} (queued-settings-state)
               app (test-app state)
               receipts (atom [])]
           (reset! bridge/app-ref app)
           (reset! bridge/receipt-sweep-at nil)
           (bridge/recover-pending-materials-commands!
            state 1000
            (fn [request]
              (swap! receipts conj request)
              true))
           ;; The gateway answers the query with `:not-found` because the
           ;; durable gate never accepted the lost command.
           (bridge/handle-event!
            {:type "CUSTOM"
             :name "hc/command-receipt"
             :value {:ok? true
                     :command-id command-id
                     :status :not-found
                     :receipt nil
                     :action :resync-state}})
           (-> (settle! 20)
               (.then
                (fn [_]
                  (is (= 1 (count @receipts))
                      "the sweep asked the gateway for the receipt")
                  (is (= command-id
                         (:command-id (first @receipts))))
                  (is (= :refused
                         (get-in (app-state app)
                                 [:commands command-id :status]))
                      "the :not-found answer released the pending lock")
                  (let [retry (model/queue-command
                               (app-state app)
                               model/settings-update-kind
                               {:memory-read? true :memory-suggest? false}
                               {:key [:materials/settings]})]
                    (is (true? (:ok? retry))
                        "a refused command frees the key for a retry"))
                  (done)))))))

(deftest submitted-ack-does-not-release-the-pending-lock
  (async done
         (let [{:keys [state command-id]} (queued-settings-state)
               app (test-app state)]
           (reset! bridge/app-ref app)
           (with-redefs [transport/command! (fn [_] true)]
             ;; The gateway's transport acknowledgement arrives first.
             (bridge/handle-event!
              {:type "CUSTOM"
               :name "hc/receipt"
               :value {:ok? true
                       :command-id command-id
                       :command-epoch 7
                       :subscription-id 3
                       :status :submitted}})
             (-> (settle! 20)
                 (.then
                  (fn [_]
                    (let [current (app-state app)]
                      (is (= :submitting
                             (get-in current [:commands command-id
                                              :status]))
                          "a transport ack is not a domain receipt")
                      (is (empty? (:outbox current))
                          "no reconcile snapshot is queued early"))
                    ;; The domain receipt still resolves the command.
                    (bridge/handle-event!
                     {:type "CUSTOM"
                      :name "hc/command-receipt"
                      :value {:ok? true
                              :command-id command-id
                              :receipt {:status :accepted
                                        :command-id command-id
                                        :result {:settings
                                                 {:memory-read? true
                                                  :memory-suggest? true}}}}})
                    (-> (settle! 20)
                        (.then
                         (fn [_]
                           (is (= :accepted
                                  (get-in (app-state app)
                                          [:commands command-id :status])))
                           (done)))))))))))

(deftest receipt-sweep-throttles-repeat-queries
  (async done
         (let [{:keys [state]} (queued-settings-state)
               receipts (atom [])]
           (reset! bridge/receipt-sweep-at nil)
           (let [send! (fn [request]
                         (swap! receipts conj request)
                         true)]
             (bridge/recover-pending-materials-commands! state 1000 send!)
             ;; Inside the 2s throttle window a second call sends nothing.
             (bridge/recover-pending-materials-commands! state 1500 send!)
             ;; Past the window the sweep queries again.
             (bridge/recover-pending-materials-commands! state 3500 send!)
             (-> (settle! 20)
                 (.then
                  (fn [_]
                    (is (= 2 (count @receipts))
                        "the middle call was throttled; two sweeps ran")
                    (done))))))))

(deftest move-receipt-envelope-sets-last-move
  (async done
         (let [state0 (assoc (synced-materials-state)
                             :selected-session-id "s-1")
               queued (:state
                       (model/queue-command
                        state0 model/entry-move-kind
                        {:entry-ids ["wbe-1"] :book-id "wb-2"}
                        {:key [:materials/worldbook-entry "wbe-1"]
                         :command-id "move-gw"}))
               command-id "move-gw"
               app (test-app (model/mark-submitting queued command-id))]
           (reset! bridge/app-ref app)
           ;; D-28 regression: the real gateway envelope keeps
           ;; `:command-id` outside and nests the domain receipt under
           ;; `:receipt`; `:result`/`:undo` exist only inside it.
           (bridge/handle-event!
            {:type "CUSTOM"
             :name "hc/command-receipt"
             :value {:ok? true
                     :command-id command-id
                     :command-epoch 7
                     :receipt {:status :accepted
                               :command-id command-id
                               :command-epoch 7
                               :result {:moved ["wbe-1"]
                                        :undo {:entry-ids ["wbe-1"]
                                               :book-ids {"wbe-1" "wb-1"}}}}}})
           (-> (settle! 20)
               (.then
                (fn [_]
                  (let [current (app-state app)]
                    (is (= :accepted
                           (get-in current [:commands command-id :status])))
                    (is (= {:entry-ids ["wbe-1"]
                            :book-ids {"wbe-1" "wb-1"}}
                           (:last-move current))
                        "the gateway envelope must set :last-move so the undo button renders")
                    (is (some #(= :materials/snapshot (:kind %))
                              (vals (:commands current)))
                        "an accepted write still queues its reconcile snapshot"))
                  (done)))))))

(deftest receipt-behind-its-command-parks-as-orphan
  (async done
         (let [app (test-app (synced-materials-state))]
           (reset! bridge/app-ref app)
           ;; A receipt can beat the transaction that records its command:
           ;; dropping it here would hold the pending lock forever once the
           ;; command lands, because nothing re-delivers the receipt.
           (bridge/handle-event!
            {:type "CUSTOM"
             :name "hc/command-receipt"
             :value {:ok? true
                     :command-id "move-early"
                     :receipt {:status :accepted
                               :command-id "move-early"
                               :result {:moved ["wbe-1"]
                                        :undo {:entry-ids ["wbe-1"]
                                               :book-ids {"wbe-1" "wb-1"}}}}}})
           (-> (settle! 20)
               (.then
                (fn [_]
                  (let [current (app-state app)]
                    (is (= 1 (count (:orphan-receipts current)))
                        "the early receipt is parked, not dropped")
                    ;; The command lands later in the same state; the next
                    ;; sync drains the parked receipt through the normal
                    ;; apply path.
                    (let [command-id "move-early"
                          queued (:state
                                  (model/queue-command
                                   current model/entry-move-kind
                                   {:entry-id "wbe-1" :to-book-id "wb-2"}
                                   {:key [:materials/worldbook-entry "wbe-1"]
                                    :command-id command-id}))
                          landed (model/mark-submitting queued command-id)
                          drained (model/drain-orphan-receipts landed)]
                      (is (= :accepted
                             (get-in drained [:commands command-id :status])))
                      (is (= {:entry-ids ["wbe-1"]
                              :book-ids {"wbe-1" "wb-1"}}
                             (:last-move drained))
                          "the drained receipt still sets :last-move")))
                  (done)))))))

(deftest memory-suggest-receipt-sets-result
  (async done
         (let [state0 (assoc (synced-materials-state)
                             :selected-session-id "s-1")
               queued (:state
                       (model/queue-command
                        state0
                        (keyword "materials" "memory/suggest")
                        {:source-range {:recent-rounds 10}
                         :categories [:identity-background]
                         :scope :global
                         :model-binding {:role :task}}
                        {:key [:materials/memory :suggest]
                         :command-id "suggest-1"}))
               app (test-app (model/mark-submitting queued "suggest-1"))]
           (reset! bridge/app-ref app)
           ;; The real gateway envelope: `:command-id` outside, the domain
           ;; receipt nested under `:receipt` with `:result :candidates`.
           (bridge/handle-event!
            {:type "CUSTOM"
             :name "hc/command-receipt"
             :value {:ok? true
                     :command-id "suggest-1"
                     :command-epoch 7
                     :receipt {:status :accepted
                               :command-id "suggest-1"
                               :command-epoch 7
                               :result {:candidates [{:body "喜欢简洁回答"
                                                      :category
                                                      :expression-preference}]
                                        :skipped 1
                                        :skipped-reasons {:empty 1}
                                        :dropped-message-count 0}}}})
           (-> (settle! 20)
               (.then
                (fn [_]
                  (let [current (app-state app)]
                    (is (= :accepted
                           (get-in current [:commands "suggest-1"
                                            :status])))
                    (is (some? (:memory-suggest-result current))
                        "the suggest receipt stores :memory-suggest-result")
                    (is (= 1
                           (count (:candidates
                                   (:memory-suggest-result current)))))
                    (is (some #(= :materials/snapshot (:kind %))
                              (vals (:commands current)))
                        "the accepted receipt queues a reconcile snapshot"))
                  (done)))))))

(deftest sync-from-db-preserves-last-move-behind-a-lagging-db
  (async done
         (let [state0 (assoc (synced-materials-state)
                             :selected-session-id "s-1")
               ;; The state a move receipt commits: command accepted and
               ;; `:last-move` set for the undo button.
               with-command (:state
                              (model/queue-command
                               state0 model/entry-move-kind
                               {:entry-id "wbe-1" :to-book-id "wb-2"}
                               {:key [:materials/worldbook-entry "wbe-1"]
                                :command-id "move-d28"}))
               committed (assoc (model/mark-submitting
                                 with-command "move-d28")
                                :last-move {:entry-ids ["wbe-1"]
                                            :book-ids {"wbe-1" "wb-1"}})
               ;; The database read can land before the queued transaction
               ;; commits: the projection still shows the command pending
               ;; and no `:last-move`.
               lagging (-> (synced-materials-state)
                           (assoc :selected-session-id "s-1")
                           (assoc :commands (:commands committed))
                           (assoc-in [:commands "move-d28" :status]
                                     :submitting))
               app (test-app lagging)]
           (reset! bridge/app-ref app)
           ;; `transact-state!` inside `sync-from-db!` commits this state,
           ;; so pin `@last-state` to what the receipt write left behind.
           (reset! bridge/last-state committed)
           (bridge/sync-from-db! nil)
           (-> (settle! 20)
               (.then
                (fn [_]
                  (let [current (app-state app)]
                    (is (= {:entry-ids ["wbe-1"]
                            :book-ids {"wbe-1" "wb-1"}}
                           (:last-move current))
                        "a sync over a lagging DB must keep :last-move")
                    (is (= :submitting
                           (get-in current [:commands "move-d28"
                                            :status])))
                  (done))))))))

(deftest sync-from-db-reads-live-adapter-connection
  (async done
         (let [;; The DB still carries the pre-sync `:ui/connection`; the
               ;; adapter atom is already synced.  A sync fired by the
               ;; `install-sync` commit itself must not stamp the stale
               ;; phase onto `:ui/materials :sync` (E2E11).
               lagging (assoc (synced-materials-state)
                              :selected-session-id "s-1"
                              :sync {:phase :disconnected :command-epoch nil})
               app (application/headless-synchronous-app
                    FauxRoot
                    {:initial-db {:ui/materials lagging
                                  :ui/command {}
                                  :ui/notices []
                                  :ui/selected-session-id "s-1"
                                  :session/by-id
                                  {"s-1" {:session-id "s-1"
                                          :command-epoch 9}}
                                  :ui/connection
                                  {:phase :disconnected
                                   :command-allowed? false}}})
               _ (reset! bridge/app-ref app)
               _ (reset! sync-bridge/adapter
                        {:phase :synced
                         :subscription 1
                         :epoch 7
                         :seq 3
                         :server {:sync {:command-epoch 9}}
                         :db {:session/by-id
                              {"s-1" {:session-id "s-1"
                                      :command-epoch 9}}
                              :sync {:command-epoch 9}
                              }})]
           (bridge/sync-from-db! nil)
           (-> (settle! 20)
               (.then
                (fn [_]
                  (let [current (app-state app)]
                    (is (= :synced (get-in current [:sync :phase]))
                        "the live adapter phase wins over the lagging DB")
                    (is (true? (:transport-available? current)))
                    ;; End-to-end: the materials command gate must accept a
                    ;; settings write instead of refusing it as
                    ;; "连接尚未完成同步".
                    (let [queued (model/queue-command
                                   current model/settings-update-kind
                                   {:memory-read? true :memory-suggest? true}
                                   {:key [:materials/settings]})]
                      (is (true? (:ok? queued))
                          "a synced adapter allows the settings command")))
                  (done)))))))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.materials-bridge-test
             'hc.client.workbench.transport-test))

(set! *main-cli-fn* -main)
