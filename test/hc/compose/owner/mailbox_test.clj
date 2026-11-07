(ns hc.compose.owner.mailbox-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner.mailbox :as mailbox]))

(deftest processes-messages-in-submission-order
  (let [seen (atom [])
        box (mailbox/create {:id "order"
                             :handler (fn [message]
                                        (swap! seen conj (:n message)))})]
    (try
      (is (:ok? (mailbox/start! box)))
      (doseq [n (range 50)]
        (is (:ok? (mailbox/submit! box {:n n}))))
      (is (true? (:idle? (mailbox/await-idle! box 5000))))
      (is (= (range 50) @seen))
      (finally
        (mailbox/stop! box)))))

(deftest handler-exception-does-not-stop-the-loop
  (let [seen (atom [])
        errors (atom [])
        box (mailbox/create
             {:id "errors"
              :handler (fn [message]
                         (when (= 1 (:n message))
                           (throw (ex-info "boom" {:n 1})))
                         (swap! seen conj (:n message)))
              :on-error (fn [report] (swap! errors conj report))})]
    (try
      (mailbox/start! box)
      (mailbox/submit! box {:n 1})
      (mailbox/submit! box {:n 2})
      (is (true? (:idle? (mailbox/await-idle! box 5000))))
      (is (= [2] @seen))
      (is (= 1 (count @errors)))
      (is (= 1 (:errors (mailbox/stats box))))
      (finally
        (mailbox/stop! box)))))

(deftest stop-drains-accepted-messages
  (let [seen (atom [])
        box (mailbox/create {:id "drain"
                             :handler (fn [message]
                                        (swap! seen conj (:n message)))})]
    (mailbox/start! box)
    (dotimes [n 10]
      (mailbox/submit! box {:n n}))
    (let [result (mailbox/stop! box {:timeout-ms 5000})]
      (is (:stopped? result))
      (is (:drained? result))
      (is (= (range 10) @seen))
      (is (= :MAILBOX_CLOSED
             (get-in (mailbox/submit! box {:n 11}) [:error :code]))))))

(deftest bounded-queue-refuses-overflow
  (let [box (mailbox/create {:id "bounded"
                             :capacity 1
                             :handler (fn [_] nil)})]
    (is (:ok? (mailbox/submit! box {:n 1})))
    (is (= :MAILBOX_FULL
           (get-in (mailbox/submit! box {:n 2}) [:error :code])))
    (mailbox/stop! box)))

(deftest default-queue-is-bounded
  (let [box (mailbox/create {:id "default-bound"
                             :handler (fn [_] nil)})]
    (try
      (is (= mailbox/default-capacity (:capacity box)))
      (is (every? (fn [n] (:ok? (mailbox/submit! box {:n n})))
                  (range mailbox/default-capacity)))
      (is (= :MAILBOX_FULL
             (get-in (mailbox/submit! box {:n :overflow})
                     [:error :code])))
      (finally
        (mailbox/stop! box)))))

(deftest owner-thread-is-the-only-handler-thread
  (let [checks (atom [])
        box-ref (atom nil)
        box (mailbox/create {:id "thread"
                             :handler (fn [_]
                                        (swap! checks conj
                                               (mailbox/owner-thread? @box-ref)))})]
    (reset! box-ref box)
    (try
      (mailbox/start! box)
      (mailbox/submit! box {:n 1})
      (is (true? (:idle? (mailbox/await-idle! box 5000))))
      (is (= [true] @checks))
      (is (false? (mailbox/owner-thread? box)))
      (finally
        (mailbox/stop! box)))))

(defspec every-accepted-message-is-processed
  20
  (prop/for-all [messages (gen/vector gen/small-integer 1 40)]
    (let [seen (atom [])
          box (mailbox/create {:id "prop"
                               :handler (fn [message]
                                          (swap! seen conj (:n message)))})]
      (try
        (mailbox/start! box)
        (doseq [n messages]
          (mailbox/submit! box {:n n}))
        (mailbox/await-idle! box 5000)
        (= messages @seen)
        (finally
          (mailbox/stop! box))))))
