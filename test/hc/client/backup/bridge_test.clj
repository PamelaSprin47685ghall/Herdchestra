(ns hc.client.backup.bridge-test
  "The connection half of the backup projection.

  `bridge.cljs` is ClojureScript because it owns Sente callbacks, so the pure
  projection it rebuilds from lives in `hc.client.backup.model`.  The release
  build failed here twice: a second argument to the argument-free
  `sync-bridge/reconnect!`, and a projection that could report `:synced`
  while the transport was interrupted."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.client.backup.model :as model]))

(deftest synced-connection-projects-synced
  (testing "a confirmed connection keeps its command epoch"
    (let [connection (model/connection-projection
                      {:phase :synced :command-epoch 7})
          state (model/set-sync (model/initial-state) connection)]
      (is (= :synced (:phase connection)))
      (is (= 7 (:command-epoch connection)))
      (is (= :synced (get-in state [:sync :phase])))
      (is (= 7 (get-in state [:sync :command-epoch]))))))

(deftest interrupted-connection-never-authorizes-commands
  (testing "a dropped channel loses the command epoch"
    (let [connection (model/connection-projection
                      {:phase :interrupted :command-epoch 7
                       :reason :channel-closed})
          state (model/set-sync (model/initial-state) connection)]
      (is (= :interrupted (get-in state [:sync :phase])))
      (is (nil? (get-in state [:sync :command-epoch])))
      (is (false? (model/can-submit? state))))))

(deftest failed-reconnect-projects-interrupted
  (testing "a failed reconnect attempt reports interrupted, not synced"
    (let [connection (-> {:phase :synced :command-epoch 7}
                         (assoc :phase :interrupted :reason :reconnect-failed)
                         model/connection-projection)
          state (model/set-sync (model/initial-state) connection)]
      (is (= :interrupted (get-in state [:sync :phase])))
      (is (= :reconnect-failed (get-in state [:sync :reason])))
      (is (nil? (get-in state [:sync :command-epoch]))))))

(deftest missing-connection-projects-disconnected
  (testing "a database without :ui/connection starts disconnected"
    (let [state (model/set-sync (model/initial-state)
                                (model/connection-projection nil))]
      (is (= :disconnected (get-in state [:sync :phase])))
      (is (nil? (get-in state [:sync :command-epoch])))
      (is (false? (model/can-submit? state))))))
