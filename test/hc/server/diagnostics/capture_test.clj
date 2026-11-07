(ns hc.server.diagnostics.capture-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.diagnostics.capture :as capture]
            [hc.server.model-port.contract :as contract]))

(defn- fake-port
  [event]
  (reify contract/ModelPort
    (start-request! [_ _request receiver]
      (contract/deliver! receiver event)
      {:attempt-id (:attempt-id event)})
    (cancel-request! [_ _handle] true)))

(defn- receiver
  []
  (reify contract/Receiver
    (deliver! [_ _event] true)))

(deftest terminal-attempt-is-recorded-asynchronously
  (let [recorded (promise)
        event (contract/event {:kind :completed
                               :attempt-id :a1
                               :call-id :c1
                               :usage {:coverage :final
                                       :input-tokens 10
                                       :output-tokens 2}
                               :finish-reason :stop})
        wrapped (capture/recording-port
                 (fake-port event)
                 #(deliver recorded %)
                 {:now-fn (constantly 1234)})
        request (contract/request {:attempt-id :a1
                                   :call-id :c1
                                   :snapshot {:session-id "s1"
                                              :model "m1"
                                              :service-id "svc1"}})]
    (try
      (contract/start-request! (:port wrapped) request (receiver))
      (let [result (deref recorded 2000 :timeout)]
        (is (= "a1" (:request-id result)))
        (is (= "c1" (:turn-id result)))
        (is (= "s1" (:session-id result)))
        (is (= "m1" (:model result)))
        (is (= "svc1" (:service-id result)))
        (is (= :succeeded (:result result)))
        (is (= 1234 (:at result)))
        (is (= 10 (get-in result [:usage :input-tokens]))))
      (finally
        ((:stop! wrapped))))))

(deftest failed-attempt-is-recorded-with-failure-result
  (let [recorded (promise)
        event (contract/event {:kind :failed
                               :attempt-id :a2
                               :call-id :c2
                               :error {:kind :server-error}})
        wrapped (capture/recording-port (fake-port event)
                                        #(deliver recorded %))]
    (try
      (contract/start-request! (:port wrapped)
                               (contract/request {:attempt-id :a2
                                                  :call-id :c2
                                                  :snapshot {}})
                               (receiver))
      (let [result (deref recorded 2000 :timeout)]
        (is (= :failed (:result result)))
        (is (= {:kind :server-error} (:error result))))
      (finally
        ((:stop! wrapped))))))
