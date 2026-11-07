(ns hc.server.commands-gateway-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.commands :as app-commands]
            [hc.server.commands.dispatch :as dispatch]
            [hc.server.commands.durable :as durable]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.feed :as feed]
            [hc.server.gateway.ports :as ports]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as session-ports]
            [hc.server.sessions.registry :as session-registry]))

(defn- make-gw
  [owner]
  (let [out (atom [])
        gw {:feed (feed/create {:now-fn (constantly "2026-11-03T00:00:00Z")})
            :owner owner
            :store nil
            :socket {:send-fn (fn [uid event] (swap! out conj [uid event]))}
            :out out
            :stopped? (atom false)
            :opts {}}]
    gw))

(defn- subscribe!
  [gw uid sid]
  (let [reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid uid
                            :?reply-fn #(reset! reply %)
                            :event [:hc/subscribe {:subscription-id sid}]})
    @reply))

(defn- command!
  [gw uid request]
  (let [reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid uid
                            :?reply-fn #(reset! reply %)
                            :event [:hc/command request]})
    @reply))

(defn- query-receipt!
  [gw uid command-id]
  (let [reply (atom nil)]
    (gateway/handle-event! gw
                           {:uid uid
                            :?reply-fn #(reset! reply %)
                            :event [:hc/receipt {:command-id command-id}]})
    @reply))

(defn- receipt-events
  [gw]
  (->> @(:out gw)
       (map second)
       (map second)
       (filter #(= "CUSTOM" (:type %)))
       (filter #(= "hc/command-receipt" (:name %)))
       (mapv :value)))

(defn- wrapper-for
  [store window calls]
  (let [gate (durable/gate store)
        wrapper (dispatch/wrap
                 {:gate gate
                  :window-fn (constantly window)
                  :scope-fn (constantly "s1")
                  :dispatch! (fn [_ reply]
                               (swap! calls inc)
                               (let [result {:ok? true
                                             :receipt {:status :accepted
                                                       :session-id "s-new"}}]
                                 (reply result)
                                 result))})]
    {:gate gate :wrapper wrapper}))

(deftest gateway-command-replay-survives-a-new-gate
  (let [store (durable/memory-store)
        window {:command-epoch 1 :retired-through 0}
        calls (atom 0)
        request {:command-id "cmd-1"
                 :command-epoch 1
                 :payload {:kind :session/create :title "New"}}
        first-run (wrapper-for store window calls)
        first-owner (ports/owner-port
                     (fn [req reply]
                       ((:submit! (:wrapper first-run)) req reply))
                     (fn [query]
                       (let [command-id (if (map? query)
                                          (:command-id query)
                                          query)]
                         ((:receipt (:wrapper first-run)) command-id))))
        first-gw (make-gw first-owner)
        _ (subscribe! first-gw "u1" 1)
        first-ack (command! first-gw "u1" request)
        first-receipt (last (receipt-events first-gw))
        replay-ack (command! first-gw "u1" request)
        replay-receipt (last (receipt-events first-gw))
        query (query-receipt! first-gw "u1" "cmd-1")]
    (is (= :submitted (:status first-ack)))
    (is (= :submitted (:status replay-ack)))
    (is (= 1 @calls))
    (is (true? (:ok? first-receipt)))
    (is (false? (:replayed? first-receipt)))
    (is (true? (:replayed? replay-receipt)))
    (is (= (:receipt first-receipt) (:receipt replay-receipt)))
    (is (= {:ok? true
            :command-id "cmd-1"
            :receipt {:status :accepted :session-id "s-new"}}
           query))

    (testing "a fresh gate reads the durable receipt and does not dispatch"
      (let [second-run (wrapper-for store window calls)
            second-owner (ports/owner-port
                          (fn [req reply]
                            ((:submit! (:wrapper second-run)) req reply))
                          (fn [query]
                            (let [command-id (if (map? query)
                                               (:command-id query)
                                               query)]
                              ((:receipt (:wrapper second-run)) command-id))))
            second-gw (make-gw second-owner)
            _ (subscribe! second-gw "u1" 1)
            query' (query-receipt! second-gw "u1" "cmd-1")
            replay (command! second-gw "u1" request)
            replay' (last (receipt-events second-gw))]
        (is (= {:ok? true
                :command-id "cmd-1"
                :receipt {:status :accepted :session-id "s-new"}}
               query'))
        (is (= :submitted (:status replay)))
        (is (= 1 @calls))
        (is (true? (:replayed? replay')))
        (is (= {:status :accepted :session-id "s-new"}
               (:receipt replay')))
        (durable/close! (:gate second-run))))))

(defn- session-count
  [registry]
  (let [result (session-registry/list-sessions registry
                                               {:include-deleted? false})]
    (when (= :ok (:status result))
      (count (:sessions result)))))

(deftest app-router-replay-through-the-gateway
  (let [persistence (session-ports/memory-persistence)
        registry (session-registry/registry
                  {:persistence persistence
                   :id-source (ids/counter-source)
                   :now-fn (constantly 1000)})
        router (app-commands/router {:registry registry
                                     :settings nil
                                     :session-config nil
                                     :dedup-store (durable/memory-store)})
        gw (make-gw router)
        _ (subscribe! gw "u1" 1)
        request {:command-id "cmd-create"
                 :command-epoch 1
                 :payload {:kind :session/create :title "New"}}
        first-ack (command! gw "u1" request)
        first-receipt (last (receipt-events gw))
        replay-ack (command! gw "u1" request)
        replay-receipt (last (receipt-events gw))
        query (query-receipt! gw "u1" "cmd-create")]
    (is (= :submitted (:status first-ack)))
    (is (= :submitted (:status replay-ack)))
    (is (true? (:ok? first-receipt)))
    (is (false? (:replayed? first-receipt)))
    (is (true? (:replayed? replay-receipt)))
    (is (= (:receipt first-receipt) (:receipt replay-receipt)))
    (is (= {:ok? true
            :command-id "cmd-create"
            :receipt (:receipt first-receipt)}
           query))
    (is (= 1 (session-count registry)))))
