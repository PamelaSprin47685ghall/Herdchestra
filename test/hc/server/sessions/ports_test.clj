(ns hc.server.sessions.ports-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ports :as ports]))

(deftest memory-persistence-uses-compare-and-set
  (let [store (ports/memory-persistence)
        session {:session-id "s-1" :revision 0 :persisted-revision 0}
        first-commit (ports/commit-session! store 0 session)
        second-commit (ports/commit-session! store 1 (assoc session :revision 1))
        current (ports/read-session store "s-1")]
    (is (= :committed (:status first-commit)))
    (is (= :conflict (:status second-commit)))
    (is (= 0 (:revision second-commit)))
    (is (= :found (:status current)))
    (is (= 0 (get-in current [:session :revision])))))

(deftest memory-persistence-lists-and-deletes
  (let [store (ports/memory-persistence)]
    (ports/commit-session! store 0 {:session-id "s-2" :revision 0})
    (ports/commit-session! store 0 {:session-id "s-1" :revision 0})
    (is (= {:status :ok :ids ["s-1" "s-2"]}
           (ports/list-session-ids store)))
    (is (= :deleted (:status (ports/delete-session! store 0 "s-1"))))
    (is (= :missing (:status (ports/read-session store "s-1"))))
    (is (= :missing (:status (ports/delete-session! store 0 "s-1"))))
    (is (= :conflict (:status (ports/delete-session! store 1 "s-2"))))))

(deftest memory-persistence-stores-config-with-the-same-revision-rules
  (let [store (ports/memory-persistence)
        config {:revision 0 :connections {}}]
    (is (= :committed (:status (ports/commit-config! store 0 config))))
    (is (= :found (:status (ports/read-config store))))
    (is (= :conflict (:status (ports/commit-config! store 0 config))))))

(deftest an-unknown-commit-is-never-reported-as-committed
  (let [store (reify ports/SessionPersistence
                (read-session [_ _] {:status :missing})
                (commit-session! [_ _ session]
                  {:status :unknown
                   :error {:code :NETWORK_LOST}})
                (list-session-ids [_] {:status :ok :ids []})
                (delete-session! [_ _ _] {:status :missing})
                (read-config [_] {:status :missing})
                (commit-config! [_ _ _] {:status :unknown}))
        session {:session-id "s-1"
                 :revision 0
                 :persisted-revision nil}
        result (sessions/commit-session! store session)]
    (is (= :unknown (:status result)))
    (is (= session (:session result)))
    (is (= :NETWORK_LOST (get-in result [:error :code])))
    (is (sessions/dirty? (:session result)))))

(deftest recording-owner-records-internal-messages
  (let [owner (ports/recording-owner)
        receipt-1 (ports/enqueue! owner {:kind :session/close :session-id "s-1"})
        receipt-2 (ports/enqueue! owner {:kind :session/stop :session-id "s-1"})]
    (is (= :accepted (:status receipt-1)))
    (is (= 0 (:index receipt-1)))
    (is (= 1 (:index receipt-2)))
    (is (= [{:kind :session/close :session-id "s-1"}
            {:kind :session/stop :session-id "s-1"}]
           (ports/recorded owner)))
    (ports/clear-recorded! owner)
    (is (empty? (ports/recorded owner)))))

(deftest submit-effects-submits-one-message-per-effect
  (let [owner (ports/recording-owner)
        receipts (ports/submit-effects! owner
                                        [{:kind :persist-session}
                                         {:kind :cancel-attempt}])]
    (is (= 2 (count receipts)))
    (is (= 2 (count (ports/recorded owner))))))

(deftest compose-owner-adapter-delegates-to-the-mailbox
  (let [commands (atom [])
        messages (atom [])
        port (ports/owner-port
              {:submit-command! (fn [request]
                                  (swap! commands conj request)
                                  {:status :accepted :receipt {:ok? true}})
               :submit! (fn [message]
                          (swap! messages conj message)
                          {:status :accepted})})]
    (is (= :accepted (:status (ports/submit-command! port {:command-id "1"}))))
    (is (= :accepted (:status (ports/enqueue! port {:kind :session/stop}))))
    (is (= [{:command-id "1"}] @commands))
    (is (= [{:kind :session/stop}] @messages))))
