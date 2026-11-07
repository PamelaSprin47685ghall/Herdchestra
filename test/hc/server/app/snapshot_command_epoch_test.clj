(ns hc.server.app.snapshot-command-epoch-test
  "Session command epochs must be visible in the client baseline.

  The global `:sync :command-epoch` is the app command epoch.  It never
  authorizes a session command.  Each session publishes its own Owner epoch
  under `:session/by-id/<id>/:command-epoch`, including non-active sessions."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.snapshot :as snapshot]))

(defn- baseline
  [active-session app-epoch]
  (snapshot/baseline
   {:sessions [{:session-id "s1" :title "one"}
               {:session-id "s2" :title "two"}]
    :session-records []
    :owner-snapshots {"s1" {:state {:commands {:command-epoch 3}}}
                      "s2" {:state {:commands {:command-epoch 5}}}}
    :active-session active-session
    :config {}
    :sync {:phase :synced :command-epoch app-epoch}}))

(deftest each-session-publishes-its-own-command-epoch
  (let [base (baseline "s1" 9)]
    (is (= 9 (get-in base [:sync :command-epoch])))
    (is (= 3 (get-in base [:session/by-id "s1" :command-epoch])))
    (is (= 5 (get-in base [:session/by-id "s2" :command-epoch]))))
  (testing "the app epoch does not follow the active session"
    (let [base (baseline "s2" 9)]
      (is (= 9 (get-in base [:sync :command-epoch])))
      (is (= 3 (get-in base [:session/by-id "s1" :command-epoch])))
      (is (= 5 (get-in base [:session/by-id "s2" :command-epoch]))))))

(deftest a-missing-owner-does-not-invent-a-command-epoch
  (let [base (snapshot/baseline
              {:sessions [{:session-id "s1"}
                          {:session-id "s2"}]
               :session-records []
               :owner-snapshots {"s1" {:state {:commands {:command-epoch 3}}}}
               :active-session "s1"
               :config {}
               :sync {:phase :synced :command-epoch 9}})]
    (is (= 9 (get-in base [:sync :command-epoch])))
    (is (= 3 (get-in base [:session/by-id "s1" :command-epoch])))
    (is (nil? (get-in base [:session/by-id "s2" :command-epoch])))))
