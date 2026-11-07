(ns hc.server.backup.maintenance-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.backup.maintenance :as maintenance]))

(defn- stopped
  []
  {:stopped? true})

(defn- started
  []
  {:ok? true})

(deftest complete-quiescent-operation
  (let [calls (atom [])
        result (maintenance/run!
                {:stop! #(do (swap! calls conj :stop) (stopped))
                 :restore! #(do (swap! calls conj :restore) {:status :complete})
                 :start! #(do (swap! calls conj :start) (started))})]
    (is (= :complete (:status result)))
    (is (= [:stop :restore :start] @calls))
    (is (true? (:restore-attempted? result)))
    (is (true? (:restart-attempted? result)))
    (is (true? (get-in result [:stop :confirmed?])))))

(deftest absent-stop-is-refused
  (let [calls (atom [])
        result (maintenance/run!
                {:restore! #(swap! calls conj :restore)
                 :start! #(swap! calls conj :start)})]
    (is (= :stop-failed (:status result)))
    (is (= [] @calls))
    (is (false? (:restore-attempted? result)))
    (is (= :BACKUP_QUIESCE_REQUIRED (get-in result [:stop :error :code])))))

(deftest unconfirmed-stop-does-not-restore
  (let [calls (atom [])
        result (maintenance/run!
                {:stop! #(do (swap! calls conj :stop) {:stopped? false})
                 :restore! #(swap! calls conj :restore)
                 :start! #(swap! calls conj :start)})]
    (is (= :stop-failed (:status result)))
    (is (= [:stop] @calls))
    (is (false? (:restore-attempted? result)))
    (is (= :BACKUP_QUIESCE_NOT_CONFIRMED (get-in result [:stop :error :code])))
    (is (= {:stopped? false} (get-in result [:stop :value])))))

(deftest contradictory-stop-result-does-not-restore
  (let [calls (atom [])
        result (maintenance/run!
                {:stop! #(do (swap! calls conj :stop)
                             {:stopped? true :status :failed})
                 :restore! #(swap! calls conj :restore)})]
    (is (= :stop-failed (:status result)))
    (is (= [:stop] @calls))
    (is (= :BACKUP_QUIESCE_NOT_CONFIRMED (get-in result [:stop :error :code])))))

(deftest stop-with-error-is-not-confirmed
  (let [result (maintenance/run!
                {:stop! (constantly {:stopped? true :error {:code :TEST}})
                 :restore! (constantly {:status :complete})})]
    (is (= :stop-failed (:status result)))
    (is (= :BACKUP_QUIESCE_NOT_CONFIRMED (get-in result [:stop :error :code])))))

(deftest stop-failure-does-not-restore
  (let [calls (atom [])
        result (maintenance/run!
                {:stop! #(throw (ex-info "stop failed" {:error :TEST_STOP}))
                 :restore! #(swap! calls conj :restore)
                 :start! #(swap! calls conj :start)})]
    (is (= :stop-failed (:status result)))
    (is (= [] @calls))
    (is (false? (:restore-attempted? result)))
    (is (= :TEST_STOP (get-in result [:stop :error :cause-code])))))

(deftest restore-failure-still-restarts
  (let [calls (atom [])
        result (maintenance/run!
                {:stop! #(do (swap! calls conj :stop) (stopped))
                 :restore! #(throw (ex-info "restore failed" {:error :TEST_RESTORE}))
                 :start! #(do (swap! calls conj :start) (started))})]
    (is (= :failed (:status result)))
    (is (= [:stop :start] @calls))
    (is (true? (:needs-attention? result)))
    (is (= :TEST_RESTORE (get-in result [:restore :error :cause-code])))))

(deftest result-valued-restore-failure-is-not-success
  (let [calls (atom [])
        restore-value {:status :failed :error {:code :TEST_RESTORE}}
        result (maintenance/run!
                {:stop! #(do (swap! calls conj :stop) (stopped))
                 :restore! #(do (swap! calls conj :restore) restore-value)
                 :start! #(do (swap! calls conj :start) (started))})]
    (is (= :failed (:status result)))
    (is (= [:stop :restore :start] @calls))
    (is (true? (:needs-attention? result)))
    (is (= restore-value (get-in result [:restore :value])))
    (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
           (get-in result [:restore :error :code])))))

(deftest cancelled-restore-is-not-success
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:status :cancelled})
                 :start! started})]
    (is (= :failed (:status result)))
    (is (true? (:needs-attention? result)))
    (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
           (get-in result [:restore :error :code])))))

(deftest restore-ok-false-is-not-success
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:ok? false :status :complete})
                 :start! started})]
    (is (= :failed (:status result)))
    (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
           (get-in result [:restore :error :code])))))

(deftest restore-with-needs-attention-is-not-success
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:status :complete
                                        :needs-attention? true})
                 :start! started})]
    (is (= :failed (:status result)))
    (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
           (get-in result [:restore :error :code])))))

(deftest restore-with-error-is-not-success
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:status :complete
                                        :error {:code :TEST}})
                 :start! started})]
    (is (= :failed (:status result)))
    (is (= :BACKUP_MAINTENANCE_RESTORE_FAILED
           (get-in result [:restore :error :code])))))

(deftest restart-failure-is-never-complete
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:status :complete})
                 :start! #(throw (ex-info "start failed" {:error :TEST_START}))})]
    (is (= :restart-failed (:status result)))
    (is (true? (:needs-attention? result)))
    (is (= :TEST_START (get-in result [:restart :error :cause-code])))))

(deftest restart-result-failure-is-never-complete
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:status :complete})
                 :start! (constantly {:ok? false :blocked? true})})]
    (is (= :restart-failed (:status result)))
    (is (true? (:needs-attention? result)))
    (is (= :BACKUP_MAINTENANCE_RESTART_FAILED
           (get-in result [:restart :error :code])))))

(deftest cancelled-start-result-is-restart-failure
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:status :complete})
                 :start! (constantly {:status :cancelled})})]
    (is (= :restart-failed (:status result)))
    (is (true? (:needs-attention? result)))
    (is (= :BACKUP_MAINTENANCE_RESTART_FAILED
           (get-in result [:restart :error :code])))))

(deftest start-with-error-is-restart-failure
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! (constantly {:status :complete})
                 :start! (constantly {:ok? true :error {:code :TEST}})})]
    (is (= :restart-failed (:status result)))
    (is (= :BACKUP_MAINTENANCE_RESTART_FAILED
           (get-in result [:restart :error :code])))))

(deftest both-restore-and-restart-failures-are-explicit
  (let [result (maintenance/run!
                {:stop! (constantly true)
                 :restore! #(throw (ex-info "restore failed" {:error :TEST_RESTORE}))
                 :start! #(throw (ex-info "start failed" {:error :TEST_START}))})]
    (is (= :failed-needs-attention (:status result)))
    (is (true? (:needs-attention? result)))
    (is (= :TEST_RESTORE (get-in result [:restore :error :cause-code])))
    (is (= :TEST_START (get-in result [:restart :error :cause-code])))))
