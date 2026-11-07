(ns hc.server.app.cancel-hook-test
  "The Owner cancel hook never turns an unobserved stop into a terminal.

  A stop signal, a registered stop, and an unconfirmed stop prove only that
  the signal was sent.  Only an observed end may become a call terminal,
  because P1 clears `:resource-alive?` for a settlement and `finish-run`
  then closes the cancellation domain."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.system :as system]))

(defn- shell-terminal
  "Call the private mapping through `resolve`; `#'` on a private var does
  not compile from another namespace."
  [result]
  ((resolve 'hc.server.app.system/shell-stop-terminal) result))

(defn- program-terminal
  [result]
  ((resolve 'hc.server.app.system/program-stop-terminal) result))

(defn- program-message
  [result]
  ((resolve 'hc.server.app.system/program-terminal-message) result))

(deftest shell-stop-without-an-observed-end-yields-no-terminal
  (testing "a registered, sent, or unconfirmed stop is not evidence"
    (is (nil? (shell-terminal {:status :STOP_REGISTERED :request-id "r1"})))
    (is (nil? (shell-terminal {:status :STOP_SIGNAL_SENT :alive [123]})))
    (is (nil? (shell-terminal {:status :STOP_UNCONFIRMED
                               :reason :unknown-session}))))
  (testing "SESSION_ENDED without a usable result proves nothing"
    (is (nil? (shell-terminal {:status :SESSION_ENDED :result nil})))
    (is (nil? (shell-terminal {:status :SESSION_ENDED :result :not-a-map})))
    (is (nil? (shell-terminal {:status :SESSION_ENDED
                               :result {:status :STOPPING}}))
        "an unrecognized inner status is unknown, not cancelled"))
  (testing "an unknown inner outcome is never a settlement"
    (is (= :outcome-unknown
           (:status (shell-terminal {:status :SESSION_ENDED
                                     :result {:status :UNCERTAIN}})))))
  (testing "a completed session maps to its observed exit"
    (is (= :succeeded
           (:status (shell-terminal
                     {:status :SESSION_ENDED
                      :result {:status :COMPLETED
                               :exit-code 0
                               :cancelled? false}}))))
    (is (= :cancelled
           (:status (shell-terminal
                     {:status :SESSION_ENDED
                      :result {:status :COMPLETED
                               :exit-code 0
                               :cancelled? true}})))
        "a cancelled completion is a settlement, not a success")
    (is (= :cancelled
           (:status (shell-terminal
                     {:status :SESSION_ENDED
                      :result {:status :COMPLETED :exit-code 137}}))))
    (is (= :succeeded
           (:status (shell-terminal
                     {:status :SESSION_ENDED
                      :result {:status :COMPLETED
                               :exit-code 0
                               :cancelled? false
                               :published? false}})))
        "a pending board publication is not a live process")))

(deftest program-stop-without-a-fixed-terminal-yields-no-terminal
  (is (nil? (program-terminal {:status :PROGRAM_UNKNOWN})))
  (is (nil? (program-terminal {:status :CANCELLING
                               :cancel-pending? true}))
      "a still-running call is not an end")
  (is (nil? (program-terminal {:status :CANCELLED
                               :cancel-pending? true}))
      "a pending cancellation acknowledgement is not a terminal")
  (is (nil? (program-terminal {:status :CANCELLED
                               :pending-operations ["call-1:call:1"]}))
      "a queued operation is not an observed end")
  (is (nil? (program-terminal {:status :OUTCOME_UNKNOWN
                               :cancel-unconfirmed? true}))
      "an unconfirmed call keeps the resource responsible")
  (is (nil? (program-terminal {:status :CANCELLED
                               :cancel-after-apply? true}))
      "a protected apply has not ended; its batch still owns the write")
  (is (= :cancelled (:status (program-terminal {:status :CANCELLED}))))
  (is (nil? (program-terminal {:status :COMPLETED}))
      "an already-completed program needs no synthesized cancellation"))

(deftest program-terminal-message-maps-real-observations
  (is (= :cancelled
         (:status (program-message {:call-id "c1"
                                    :status :CANCELLED
                                    :cancel-requested? true}))))
  (is (= :outcome-unknown
         (:status (program-message {:call-id "c1"
                                    :status :OUTCOME_UNKNOWN
                                    :cancel-unconfirmed? true})))
      "an unconfirmed end stays unknown; it never becomes cancelled")
  (is (= :succeeded
         (:status (program-message {:call-id "c1"
                                    :status :COMPLETED}))))
  (is (nil? (program-message {:call-id "c1"
                              :status :CANCELLING
                              :cancel-pending? true}))
      "a pending cancellation is not a terminal message")
  (is (nil? (program-message {:status :CANCELLED}))
      "a terminal message needs a call id"))
