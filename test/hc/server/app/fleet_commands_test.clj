(ns hc.server.app.fleet-commands-test
  "Fleet commands are app-scoped, durably deduplicated, and reserved for the
  Steward main-model tool envelope.  Join blocks on a worker thread, never on
  the caller's mailbox."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.commands :as app-commands]
            [hc.server.commands.durable :as durable]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.ports :as ports]))

(defn- fake-service
  [state join-promise started]
  {:fork! (fn [_ _slices]
            (swap! state update :fork inc)
            {:ok? true :swarms [{:swarm-id "swarm-1"}]})
   :join! (fn [_ _opts]
            (swap! state update :join inc)
            (deliver started true)
            (deref join-promise 5000 :timeout))
   :complete! (fn [_ _swarm _report _opts] {:ok? true})
   :abandon! (fn [_ _swarm _reason] {:ok? true})
   :fail! (fn [_ _swarm _error] {:ok? true})
   :gc! (fn [_] {:ok? true})
   :observation (fn [_] {:swarms []})
   :status (fn [_] {:running? false})})

(defn- make-router
  [store service]
  (app-commands/router
   {:registry nil
    :settings nil
    :session-config nil
    :dedup-store store
    :fleet-ref (atom service)}))

(defn- submit!
  [router request]
  (let [reply (promise)]
    (ports/submit-command! router request #(deliver reply %))
    (deref reply 2000 ::timeout)))

(deftest fleet-fork-replays-without-a-second-side-effect
  (let [store (durable/memory-store)
        state (atom {:fork 0 :join 0})
        service (fake-service state (promise) (promise))
        router (make-router store service)
        request (app-commands/steward-command-envelope
                 "fleet-fork-1" 1
                 {:kind :fleet/fork :slices [{:slice-id "a"}]})
        first-result (submit! router request)
        replay (submit! router request)
        query (ports/command-receipt router {:command-id "fleet-fork-1"})
        fresh (make-router store service)
        replay' (submit! fresh request)]
    (is (true? (:ok? first-result)))
    (is (= 1 (:fork @state)))
    (is (true? (:replayed? replay)))
    (is (= 1 (:fork @state)))
    (is (= (:receipt first-result) query))
    (is (= "swarm-1" (get-in query [:result :swarms 0 :swarm-id])))
    (is (true? (:replayed? replay')))
    (is (= (:receipt first-result) (:receipt replay')))
    (is (= 1 (:fork @state)))))

(deftest fleet-commands-require-the-trusted-steward-envelope
  (let [store (durable/memory-store)
        state (atom {:fork 0 :join 0})
        service (fake-service state (promise) (promise))
        router (make-router store service)
        unmarked (submit! router
                          {:command-id "fleet-browser-1"
                           :command-epoch 1
                           :payload {:kind :fleet/fork :slices []}})
        browser-shaped (submit! router
                                {:command-id "fleet-browser-2"
                                 :command-epoch 1
                                 :program-role :steward
                                 :origin :steward-tool
                                 :subscription-id 1
                                 :uid "u1"
                                 :payload {:kind :fleet/fork :slices []}})]
    (is (false? (:ok? unmarked)))
    (is (= :FLEET_FORBIDDEN (get-in unmarked [:error :code])))
    (is (false? (:ok? browser-shaped)))
    (is (= :FLEET_FORBIDDEN (get-in browser-shaped [:error :code])))
    (is (= 0 (:fork @state)))
    (testing "the browser gateway forbids every fleet kind"
      (doseq [kind [:fleet/fork :fleet/join :fleet/complete
                    :fleet/abandon :fleet/fail :fleet/gc :fleet/state]]
        (is (contains? gateway/default-forbidden-browser-kinds kind))))))

(deftest fleet-join-runs-async-and-dedups-in-flight
  (let [store (durable/memory-store)
        state (atom {:fork 0 :join 0})
        join-promise (promise)
        started (promise)
        service (fake-service state join-promise started)
        router (make-router store service)
        request (app-commands/steward-command-envelope
                 "fleet-join-1" 1 {:kind :fleet/join})
        reply (promise)
        returned (ports/submit-command! router request #(deliver reply %))]
    (is (nil? returned) "join does not block the submitting thread")
    (is (true? (deref started 1000 ::timeout)))
    (is (= 1 (:join @state)))
    (testing "a replay while join is in flight returns the dispatching placeholder"
      (let [replay (ports/submit-command! router request nil)]
        (is (true? (:replayed? replay)))
        (is (= :dispatching (get-in replay [:receipt :status])))
        (is (= 1 (:join @state)))))
    (deliver join-promise {:swarm-id "swarm-1" :report "done"})
    (let [result (deref reply 2000 ::timeout)]
      (is (true? (:ok? result)))
      (is (= {:swarm-id "swarm-1" :report "done"}
             (get-in result [:receipt :result]))))
    (testing "after completion the final receipt is durable"
      (let [final (ports/submit-command! router request nil)]
        (is (true? (:replayed? final)))
        (is (= {:swarm-id "swarm-1" :report "done"}
               (get-in final [:receipt :result])))))
    (is (= 1 (:join @state)))))
