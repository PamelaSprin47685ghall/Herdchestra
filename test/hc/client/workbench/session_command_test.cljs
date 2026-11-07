(ns hc.client.workbench.session-command-test
  "Consumer-path regression for a deleted session's Owner epoch.

  A deleted row is absent from the adapter's confirmed `:session/by-id` and
  survives only in the Fulcro display database's `:ui/session-list`.  The
  command bridge must read that display database, keep the lookup keyed by
  the exact session id, and never substitute the app epoch or another
  session's epoch."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.workbench.commands :as commands]
            [hc.client.workbench.sync-bridge :as sync-bridge]
            [hc.client.workbench.transport :as transport]))

(comp/defsc FauxRoot
  [_this _props]
  {:query [:ui/session-list :ui/command :session/by-id]
   :ident (fn [] [:root/id :root])}
  nil)

(defn- test-app
  [initial-db]
  (application/headless-synchronous-app
   FauxRoot
   {:initial-db initial-db}))

(defn- synced-adapter
  []
  {:phase :synced
   :subscription 1
   :epoch 7
   :seq 3
   :server {:sync {:command-epoch 9}}
   :db {:session/by-id {}
        :sync {:command-epoch 9}}})

(deftest deleted-row-epoch-is-read-from-the-display-database
  (let [app (test-app {:ui/session-list [{:session-id "s1"
                                          :status :deleted
                                          :command-epoch 11}
                                         {:session-id "s2"
                                          :status :deleted
                                          :command-epoch 13}]
                       :ui/command {}
                       :session/by-id {}})]
    (reset! sync-bridge/adapter (synced-adapter))
    (reset! sync-bridge/app-ref app)
    (testing "the exact deleted row supplies its Owner epoch"
      (is (= 11 (sync-bridge/command-epoch-for-session "s1")))
      (is (= 13 (sync-bridge/command-epoch-for-session "s2"))))
    (testing "a missing session borrows neither another row nor the app epoch"
      (is (nil? (sync-bridge/command-epoch-for-session "s3"))))))

(deftest purge-command-carries-the-deleted-row-epoch
  (let [app (test-app {:ui/session-list [{:session-id "s1"
                                          :status :deleted
                                          :command-epoch 11}]
                       :ui/command {}
                       :session/by-id {}})
        sent (atom nil)]
    (reset! sync-bridge/adapter (synced-adapter))
    (reset! sync-bridge/app-ref app)
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (let [result (commands/session-metadata! app "s1" :session/purge
                                               {:confirm? true})]
        (is (true? (:ok? result)))
        (is (= :session/purge (get-in result [:command :kind])))))
    (is (= 11 (:command-epoch @sent))
        "the deleted row's Owner epoch authorizes purge")
    (is (not= 9 (:command-epoch @sent))
        "the app epoch never authorizes a session command")
    (is (= :session/purge (get-in @sent [:payload :kind])))
    (is (= "s1" (get-in @sent [:payload :session-id])))
    (is (true? (get-in @sent [:payload :confirm?])))))

(deftest a-missing-session-blocks-instead-of-borrowing-an-epoch
  (let [app (test-app {:ui/session-list [{:session-id "s2"
                                          :status :deleted
                                          :command-epoch 13}]
                       :ui/command {}
                       :session/by-id {}})
        sent (atom nil)]
    (reset! sync-bridge/adapter (synced-adapter))
    (reset! sync-bridge/app-ref app)
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)
                  transport/request-resync! (fn [_] true)]
      (let [result (commands/session-metadata! app "s1" :session/purge
                                               {:confirm? true})]
        (is (false? (:ok? result)))
        (is (= :command-epoch-unknown (:reason result)))))
    (is (nil? @sent)
        "no command leaves the client without the same session's epoch")))

(deftest history-edit-command-keeps-an-explicit-empty-attachment-vector
  (let [app (test-app {:ui/session-list []
                       :ui/command {}
                       :session/by-id {"s1" {:session-id "s1"
                                              :command-epoch 11}}})
        sent (atom nil)]
    (reset! sync-bridge/adapter (synced-adapter))
    (reset! sync-bridge/app-ref app)
    (with-redefs [transport/command! (fn [envelope]
                                      (reset! sent envelope)
                                      true)]
      (let [result (commands/edit-message! app "s1" "m1" "edited" [] nil)]
        (is (true? (:ok? result)))
        (is (= :history/edit-message (get-in result [:command :kind])))))
    (is (contains? (get-in @sent [:payload]) :attachments)
        "omitting the vector would inherit the old version's images")
    (is (= [] (get-in @sent [:payload :attachments])))
    (is (= "m1" (get-in @sent [:payload :message-id])))
    (is (= "edited" (get-in @sent [:payload :content])))))

(deftest duplicate-send-is-blocked-until-a-receipt
  (let [app (test-app {:ui/session-list []
                       :ui/command {}
                       :session/by-id {"dup-1" {:session-id "dup-1"
                                                 :command-epoch 11
                                                 :model-binding
                                                 {:model-entry-id "m1"
                                                  :display-name "Model"}}}})
        sent (atom [])]
    (reset! sync-bridge/adapter (synced-adapter))
    (reset! sync-bridge/app-ref app)
    (with-redefs [transport/command! (fn [envelope]
                                      (swap! sent conj envelope)
                                      true)]
      (let [snapshot {:text "hello" :version 1}]
        (is (true? (:ok? (commands/send-snapshot! app "dup-1" snapshot))))
        (is (false? (:ok? (commands/send-snapshot! app "dup-1" snapshot)))
            "the synchronous claim blocks a second send before any receipt")))
    (is (= 1 (count @sent)))
    (is (= :send-message (get-in @sent [0 :payload :kind])))))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.session-command-test))

(set! *main-cli-fn* -main)
