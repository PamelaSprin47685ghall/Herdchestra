(ns hc.compose.fleet.launcher-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.fleet.launcher :as launcher]))

(defn- actor
  [effects]
  {:state {:swarm-id "swarm-1"}
   :effects effects})

(deftest sequences-real-model-effects-and-ignores-rebirth
  (let [started (atom [])
        observed (atom [])
        launch (launcher/make
                {:start-effect! (fn [effect] (swap! started conj effect))
                 :on-effect! (fn [effect] (swap! observed conj effect))})
        result (launch {:swarm-id "swarm-1"
                        :actor (actor [{:type :actor/rebirth
                                        :slot-id :slot-a
                                        :agent-id :agent-2}
                                       {:type :model/start
                                        :slot-id :slot-a
                                        :attempt-id "a-1"
                                        :request {:workspace "/wt"}}
                                       {:type :model/start
                                        :slot-id :slot-b
                                        :attempt-id "b-1"
                                        :request {:workspace "/wt"}}])})]
    (is (:ok? result))
    (is (= 2 (:started result)))
    (is (= ["a-1" "b-1"] (mapv :attempt-id @started)))
    (is (every? #(= "swarm-1" (:swarm-id %)) @started))
    (is (= 3 (count @observed)))
    (is (= :actor/rebirth (:type (first @observed))))))

(deftest forwards-model-cancel-before-the-new-start
  (let [started (atom [])
        cancelled (atom [])
        launch (launcher/make
                {:start-effect! (fn [effect] (swap! started conj effect))
                 :cancel-effect! (fn [effect]
                                   (swap! cancelled conj effect)
                                   {:ok? true})})
        result (launch {:swarm-id "swarm-1"
                        :actor (actor [{:type :model/cancel
                                        :attempt-id "old-1"
                                        :reason :actor-dead}
                                       {:type :model/start
                                        :attempt-id "new-1"}])})]
    (is (:ok? result))
    (is (= ["old-1"] (mapv :attempt-id @cancelled)))
    (is (= "swarm-1" (:swarm-id (first @cancelled))))
    (is (= ["new-1"] (mapv :attempt-id @started)))
    (is (= 1 (:started result)))
    (is (= 1 (:cancelled result)))
    (is (= [] (:cancel-errors result)))))

(deftest a-refused-cancel-does-not-hide-a-successful-start
  (let [started (atom [])
        launch (launcher/make
                {:start-effect! (fn [effect] (swap! started conj effect))
                 :cancel-effect! (fn [_]
                                   {:ok? false
                                    :error {:code :CANCEL_REFUSED}})})
        result (launch {:swarm-id "swarm-1"
                        :actor (actor [{:type :model/cancel
                                        :attempt-id "old-1"}
                                       {:type :model/start
                                        :attempt-id "new-1"}])})]
    (is (:ok? result))
    (is (= 1 (:started result)))
    (is (= 1 (:cancelled result)))
    (is (= 1 (count (:cancel-errors result))))
    (is (= ["new-1"] (mapv :attempt-id @started)))))

(deftest a-gateway-rejection-is-an-explicit-launch-failure
  (let [launch (launcher/make
                {:start-effect! (fn [_] (throw (ex-info "no gateway" {})))})]
    (let [result (launch {:swarm-id "swarm-1"
                          :actor (actor [{:type :model/start
                                          :attempt-id "a-1"}])})]
      (is (= :failed (:status result)))
      (is (= :SWARM_MODEL_START_FAILED (get-in result [:error :code])))
      (is (= "swarm-1" (get-in result [:error :details :swarm-id]))))))

(deftest a-false-gateway-result-is-a-launch-failure
  (let [launch (launcher/make {:start-effect! (fn [_] false)})
        result (launch {:swarm-id "swarm-1"
                        :actor (actor [{:type :model/start
                                        :attempt-id "a-1"}])})]
    (is (= :failed (:status result)))
    (is (= :SWARM_MODEL_START_FAILED (get-in result [:error :code])))))

(deftest missing-actor-is-explicit
  (let [launch (launcher/make {:start-effect! (fn [_] true)})
        result (launch {:swarm-id "swarm-1"})]
    (is (= :failed (:status result)))
    (is (= :SWARM_ACTOR_MISSING (get-in result [:error :code])))))

(deftest start-effect-is-required
  (is (thrown? clojure.lang.ExceptionInfo
               (launcher/make {}))))
