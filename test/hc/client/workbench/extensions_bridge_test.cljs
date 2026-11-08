(ns hc.client.workbench.extensions-bridge-test
  "Assistant projection regression for the extensions bridge.

  E2E 13 bound a skill to an assistant through a `<select>`, but the bridge
  read `:assistants` out of the settings initial state — an empty map that is
  truthy in Clojure — so the live server projection never reached the panel
  and the view rendered a free-text `<input>` instead.  These tests pin the
  `seq`-guarded source order."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.extensions.model :as extensions-model]
            [hc.client.workbench.extensions-bridge :as bridge]
            [hc.client.workbench.sync-bridge :as sync-bridge]
            [hc.client.workbench.transport :as transport]))

(comp/defsc FauxRoot
  [_this _props]
  {:query [:ui/extensions :ui/command :ui/notices :ui/connection
           :ui/settings :ui/selected-session-id :ui/session-list]
   :ident (fn [] [:root/id :root])}
  nil)

(defn- app-with-db
  [db]
  (application/headless-synchronous-app FauxRoot {:initial-db db}))

(defn- app-state
  [app]
  (:ui/extensions (application/current-state app)))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(def server-assistant
  {"e2e13-assistant" {:id "e2e13-assistant"
                      :assistant-id "e2e13-assistant"
                      :name "E2E13 助手"
                      :revision 1
                      :enabled? true}})

(defn- extensions-state
  []
  (assoc (extensions-model/initial-state)
         :sync {:phase :synced :command-epoch 7}
         :transport-available? true))

(def expected-assistant
  [{:id "e2e13-assistant"
    :assistant-id "e2e13-assistant"
    :name "E2E13 助手"
    :revision 1
    :enabled? true}])

(deftest live-settings-projection-wins-over-empty-dead-key
  (let [db {:ui/settings {:assistants {}
                          :server {:assistants server-assistant}}
            :settings/assistants server-assistant}]
    (is (= expected-assistant
           (bridge/assistants-from-db db))
        "the dead initial-state `:assistants {}` must not short-circuit the
         live `[:server :assistants]` projection")))

(deftest committed-snapshot-copy-is-the-fallback
  (let [db {:ui/settings {:assistants {}}
            :settings/assistants server-assistant}]
    (is (= expected-assistant
           (bridge/assistants-from-db db)))))

(deftest dead-key-still-falls-through-to-snapshot-when-live-is-empty
  (let [db {:ui/settings {:assistants {}
                          :server {:assistants {}}}
            :settings/assistants server-assistant}]
    (is (= ["e2e13-assistant"]
           (mapv :id (bridge/assistants-from-db db))))))

(deftest empty-sources-yield-empty-vector
  (is (= [] (bridge/assistants-from-db
             {:ui/settings {:assistants {}
                            :server {:assistants {}}}
              :settings/assistants {}}))))

(deftest sync-from-db-projects-assistants-into-ui-extensions
  (let [app (app-with-db
             {:ui/extensions (extensions-state)
              :ui/settings {:assistants {}
                            :server {:assistants server-assistant}}
              :ui/connection {:phase :synced
                              :command-allowed? true
                              :subscription "sub-1"
                              :command-epoch 7}
              :ui/command {}
              :ui/notices []})]
    (reset! bridge/app-ref app)
    (reset! bridge/last-state nil)
    (reset! bridge/fetched? false)
    (reset! bridge/last-subscription nil)
    (reset! bridge/seen-receipts #{})
    (reset! bridge/queried-receipts #{})
    (reset! sync-bridge/adapter
            {:phase :synced :subscription "sub-1" :epoch 3 :seq 1
             :server {:sync {:command-epoch 7}}})
    (with-redefs [transport/command! (fn [_] true)]
      (bridge/sync-from-db! (application/current-state app))
      (try
        (application/render! app)
        (catch :default _ nil)))
    (is (= ["e2e13-assistant"]
           (mapv :id (:assistants (app-state app))))
        "the live assistant reaches the extensions panel state")))

(deftest sync-from-db-refreshes-assistants-on-every-db-change
  ;; Regression for the frozen-bundle bug: `sync-from-db!` used to consult
  ;; `state-from-db` only while `@last-state` was empty, so a later
  ;; `/settings/assistants` STATE_DELTA never recomputed `:assistants` and
  ;; the binding form kept rendering the free-text input.
  (let [app (app-with-db
             {:ui/extensions (extensions-state)
              :ui/settings {:assistants {}
                            :server {:assistants {}}}
              :ui/connection {:phase :synced
                              :command-allowed? true
                              :subscription "sub-1"
                              :command-epoch 7}
              :ui/command {}
              :ui/notices []})]
    (reset! bridge/app-ref app)
    (reset! bridge/last-state nil)
    (reset! bridge/fetched? false)
    (reset! bridge/last-subscription nil)
    (reset! bridge/seen-receipts #{})
    (reset! bridge/queried-receipts #{})
    (reset! sync-bridge/adapter
            {:phase :synced :subscription "sub-1" :epoch 3 :seq 1
             :server {:sync {:command-epoch 7}}})
    (with-redefs [transport/command! (fn [_] true)]
      ;; First sync: no assistants yet; the panel gets an empty list.
      (bridge/sync-from-db! (application/current-state app))
      (is (= [] (:assistants @bridge/last-state))
          "empty projection stays empty before the delta")
      ;; Simulate the STATE_DELTA: the settings projection gains the
      ;; assistant while `@last-state` is already populated.
      (swap! (:com.fulcrologic.fulcro.application/state-atom app)
             assoc-in [:ui/settings :server :assistants] server-assistant)
      (bridge/sync-from-db! (application/current-state app))
      (is (= ["e2e13-assistant"]
             (mapv :id (:assistants @bridge/last-state)))
          "a later STATE_DELTA must refresh :assistants even when
           @last-state is already populated"))))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.extensions-bridge-test))

(set! *main-cli-fn* -main)
