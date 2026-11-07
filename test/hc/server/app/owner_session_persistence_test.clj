(ns hc.server.app.owner-session-persistence-test
  "Owner session commit barrier against the real Datalevin store.

  The Owner state and the session record are separate documents.  These
  tests prove that an accepted metadata receipt is reported only after the
  session record holds the business change, that a failed session commit
  refuses the receipt, and that the accepted deletion survives a store
  reopen and a fresh Owner start."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]
            [hc.server.app.owner-store :as owner-store]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- new-session
  [session-id]
  (let [created (sessions/new-session
                 {:session-id session-id
                  :owner-id (str session-id "-owner")
                  :id-source (ids/counter-source)
                  :now 1000})]
    (is (:ok? created))
    (:session created)))

(defn- commit!
  [persistence session]
  (let [result (sessions/commit-session! persistence session)]
    (is (= :committed (:status result)))
    (:session result)))

(defn- start-owner!
  [store persistence session]
  (owner/start-owner!
   {:session-id (:session-id session)
    :store (owner-store/owner-store store (:session-id session))
    :initial-session session
    :session-port (sessions/session-port {:persistence persistence})
    :model-gateway {:start! (fn [_request _deliver!] {:ok? true})
                    :cancel! (fn [_handle] nil)}
    :close-store? false}))

(defn- submit!
  [handle command-id payload]
  ((:submit-command! handle)
   {:command-id command-id
    :command-epoch 1
    :payload payload}))

(deftest accepted-delete-commits-before-receipt-and-survives-restart
  (let [dir (support/temp-dir "hc-session-delete-")
        session-id "s-delete"]
    (try
      (testing "the first process commits the delete before the receipt"
        (let [store (storage/open-store {:path dir :create? true
                                         :durability :sync})]
          (try
            (let [persistence (session-persistence/persistence store)
                  session (commit! persistence (new-session session-id))
                  handle (start-owner! store persistence session)]
              (try
                (let [receipt (submit! handle "delete-1"
                                       {:kind :session/delete
                                        :session-id session-id})
                      stored (ports/read-session persistence session-id)]
                  (is (= :accepted (:status receipt)))
                  (is (= :deleted (get-in stored [:session :status]))
                      "the committed store already holds the accepted delete")
                  (is (pos? (long (or (get-in stored [:session :revision]) 0)))
                      "the Owner commit bumped the session revision"))
                (finally
                  ((:stop! handle {:close-store? false})))))
            (finally
              (storage/close-store! store)))))

      (testing "a reopened store still holds the deletion"
        (let [store (storage/open-store {:path dir})]
          (try
            (let [persistence (session-persistence/persistence store)
                  stored (ports/read-session persistence session-id)
                  session (:session stored)]
              (is (= :deleted (:status session)))
              (testing "a fresh Owner reconciles to the committed deletion"
                (let [handle (start-owner! store persistence session)]
                  (try
                    (is (= :deleted
                           (get-in ((:snapshot handle))
                                   [:state :session :status])))
                    (finally
                      ((:stop! handle {:close-store? false}))))))
              (testing "purge removes the committed deleted record"
                (let [reg (registry/registry {:persistence persistence})
                      result (registry/purge-session! reg session-id)]
                  (is (= :purged (:status result)))
                  (is (= :missing
                         (:status (ports/read-session persistence
                                                      session-id)))))))
            (finally
              (storage/close-store! store)))))
      (finally
        (support/delete-tree! dir)))))

(deftest session-commit-failure-refuses-the-accepted-receipt
  (let [persistence (ports/memory-persistence)
        session-id "s-commit-failure"
        session (commit! persistence (new-session session-id))
        port (assoc (sessions/session-port)
                    :commit-session!
                    (fn [_previous _session _now]
                      {:ok? false
                       :error {:code :INJECTED_COMMIT_FAILURE
                               :message "injected session commit failure"}}))
        handle (owner/start-owner!
                {:session-id session-id
                 :store (owner/memory-store)
                 :initial-session session
                 :session-port port
                 :model-gateway {:start! (fn [_request _deliver!] {:ok? true})
                                 :cancel! (fn [_handle] nil)}
                 :close-store? true})]
    (try
      (let [receipt (submit! handle "delete-fails"
                             {:kind :session/delete
                              :session-id session-id})
            stored (ports/read-session persistence session-id)]
        (is (= :refused (:status receipt)))
        (is (= :INJECTED_COMMIT_FAILURE (get-in receipt [:error :code])))
        (is (= :active (get-in stored [:session :status]))
            "the committed record is untouched")
        (is (= :active (get-in ((:snapshot handle)) [:state :session :status]))
            "the owner state was not committed after the session failure"))
      (finally
        ((:stop! handle))))))

(deftest a-session-change-without-persistence-is-refused
  (let [session-id "s-no-persistence"
        persistence (ports/memory-persistence)
        session (commit! persistence (new-session session-id))
        handle (owner/start-owner!
                {:session-id session-id
                 :store (owner/memory-store)
                 :initial-session session
                 :session-port (sessions/session-port)
                 :model-gateway {:start! (fn [_request _deliver!] {:ok? true})
                                 :cancel! (fn [_handle] nil)}
                 :close-store? true})]
    (try
      (let [receipt (submit! handle "delete-no-port"
                             {:kind :session/delete
                              :session-id session-id})]
        (is (= :refused (:status receipt)))
        (is (= :SESSION_PERSISTENCE_UNAVAILABLE
               (get-in receipt [:error :code])))
        (is (= :active
               (get-in (ports/read-session persistence session-id)
                       [:session :status]))))
      (finally
        ((:stop! handle))))))

(deftest startup-reconciliation-flushes-a-live-deletion
  (let [persistence (ports/memory-persistence)
        session-id "s-reconcile"
        active (commit! persistence (new-session session-id))
        live (assoc active :status :deleted :deleted-at 2000)
        result (sessions/reconcile-owner-session! persistence live 3000)
        stored (ports/read-session persistence session-id)]
    (is (:ok? result))
    (is (= :committed (:status result)))
    (is (= :deleted (get-in stored [:session :status])))
    (is (= 1 (get-in stored [:session :revision])))
    (is (= 1 (get-in result [:session :persisted-revision])))))

(deftest startup-reconciliation-adopts-a-newer-committed-record
  (let [persistence (ports/memory-persistence)
        session-id "s-adopt"
        active (commit! persistence (new-session session-id))
        renamed (-> active
                    (assoc :title "Committed title")
                    (update :revision inc))
        _ (is (= :committed
                 (:status (sessions/commit-session! persistence renamed))))
        stale (assoc active :title "Stale owner title")
        result (sessions/reconcile-owner-session! persistence stale 3000)]
    (is (:ok? result))
    (is (= :adopted (:status result)))
    (is (= "Committed title" (get-in result [:session :title])))
    (is (= 1 (get-in (ports/read-session persistence session-id)
                     [:session :revision])))))
