(ns hc.server.app.materials-command-receipt-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.commands :as app-commands]
            [hc.server.commands.durable :as durable]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.feed :as feed]
            [hc.server.materials.session :as materials-session]
            [hc.server.materials.store :as materials-store]
            [hc.server.sessions.ids :as ids]
            [hc.server.sessions.ports :as ports]
            [hc.server.sessions.registry :as registry]))

(defn- fake-owner
  [window]
  {:snapshot (fn []
               {:state {:commands (merge {:receipts {}} window)}})
   :stop! (fn [] {:ok? true :stopped? true :drained? true})
   :submit-command! (fn [_]
                      {:status :accepted
                       :receipt {:status :accepted}})})

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

(defn- receipt-events
  [gw]
  (->> @(:out gw)
       (map second)
       (map second)
       (filter #(= "CUSTOM" (:type %)))
       (filter #(= "hc/command-receipt" (:name %)))
       (mapv :value)))

(deftest settings-update-receipt-through-gateway
  (let [persistence (ports/memory-persistence)
        reg (registry/registry
             {:persistence persistence
              :id-source (ids/counter-source)
              :now-fn (constantly 1000)
              :owner-factory (fn [_] (fake-owner {:command-epoch 1
                                                  :retired-through 0}))})
        materials (materials-session/service (materials-store/memory-store))
        _ (materials-session/initialize! materials)
        router (app-commands/router
                {:registry reg
                 :settings nil
                 :session-config nil
                 :materials materials
                 :dedup-store (durable/memory-store)})
        gw (make-gw router)]
    (app-commands/register-owner! router "s1"
                                  (fake-owner {:command-epoch 1
                                               :retired-through 0}))
    (subscribe! gw "u1" 1)
    (let [ack (command! gw "u1"
                        {:command-id "cmd-settings-1"
                         :command-epoch 1
                         :subscription-id 1
                         :payload {:kind :materials/settings/update
                                   :session-id "s1"
                                   :memory-read? true
                                   :memory-suggest? true}})
          receipts (receipt-events gw)]
      (is (= :submitted (:status ack)))
      (is (= 1 (count receipts)) "exactly one CUSTOM receipt")
      (let [receipt (first receipts)]
        (is (= "cmd-settings-1" (:command-id receipt)))
        (is (true? (:ok? receipt)))
        (is (= :accepted (get-in receipt [:receipt :status])))))))
