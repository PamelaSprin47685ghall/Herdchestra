(ns hc.server.app.http-test
  (:require [clojure.test :refer [deftest is]]
            [hc.server.app.http :as http]))

(deftest listener-stop-reports-explicit-status
  (let [listener (http/start! {:handler (fn [_] {:status 200 :body "ok"})
                               :host "127.0.0.1"
                               :port 0})]
    (try
      (is (pos? (:port listener)))
      (finally
        (let [result ((:stop! listener))]
          (is (= :stopped (:status result)))
          (is (true? (:stopped? result))))))))
