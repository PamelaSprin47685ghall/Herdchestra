(ns hc.compose.fleet.router-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.fleet.router :as router]
            [hc.compose.fleet.service :as service]))

(deftest routes-each-fleet-command-to-the-service
  (let [calls (atom [])]
    (with-redefs [service/fork (fn [_ slices]
                                 (swap! calls conj [:fork slices])
                                 [{:swarm-id "swarm-1"
                                   :slice-id :s
                                   :workspace "/worktrees/swarm-1"}])
                  service/fork! (fn [_ _]
                                  (throw (ex-info "fork! must not be used" {})))
                  service/join! (fn [_ opts]
                                  (swap! calls conj [:join opts])
                                  {:swarm-id "swarm-1"})
                  service/complete! (fn [_ swarm-id report opts]
                                      (swap! calls conj [:complete swarm-id report opts])
                                      {:ok? true})
                  service/abandon! (fn [_ swarm-id reason]
                                     (swap! calls conj [:abandon swarm-id reason])
                                     {:ok? true})
                  service/fail! (fn [_ swarm-id error]
                                  (swap! calls conj [:fail swarm-id error])
                                  {:ok? true})
                  service/gc! (fn [_] (swap! calls conj [:gc]) {:ok? true})
                  service/observation (fn [_] {:fleet-version 1})
                  service/status (fn [_] {:available? true})]
      (is (= {:ok? true
              :result [{:swarm-id "swarm-1"
                        :slice-id :s
                        :workspace "/worktrees/swarm-1"}]}
             (router/handle :svc {:kind :fleet/fork :slices [:s]})))
      (is (= {:ok? true :result {:swarm-id "swarm-1"}}
             (router/handle :svc {:kind :fleet/join :timeout-ms 5})))
      (is (:ok? (router/handle :svc {:kind :fleet/complete
                                     :swarm-id "swarm-1"
                                     :report "r"
                                     :board-version 2})))
      (is (:ok? (router/handle :svc {:kind :fleet/abandon
                                     :swarm-id "swarm-1"
                                     :reason :stale})))
      (is (:ok? (router/handle :svc {:kind :fleet/fail
                                     :swarm-id "swarm-1"
                                     :error {:code :X}})))
      (is (:ok? (router/handle :svc {:kind :fleet/gc})))
      (is (= {:ok? true :observation {:fleet-version 1 :swarms [] :events []}
              :status {:available? true}}
             (router/handle :svc {:kind :fleet/state})))
      (is (= [[:fork [:s]]
              [:join {:timeout-ms 5}]
              [:complete "swarm-1" "r" {:board-version 2}]
              [:abandon "swarm-1" :stale]
              [:fail "swarm-1" {:code :X}]
              [:gc]]
             @calls)))))

(deftest join-errors-and-unknown-commands-are-explicit
  (with-redefs [service/join! (fn [_ _] {:fleet/error {:code :JOIN_TIMEOUT}})]
    (is (= {:ok? false :error {:code :JOIN_TIMEOUT}}
           (router/handle :svc {:kind :fleet/join}))))
  (is (= :UNKNOWN_FLEET_COMMAND
         (get-in (router/handle :svc {:kind :fleet/nope}) [:error :code]))))

(deftest fleet-state-is-a-bounded-summary-without-board-detail
  (let [events (mapv (fn [i] {:type :result-queued :i i})
                     (range 0 (+ service/max-observation-events 5)))
        observation
        {:mission-id "m"
         :fleet-version 7
         :master-version 3
         :swarms
         [{:swarm-id "swarm-1"
           :slice-id "a"
           :worktree "/worktrees/swarm-1"
           :status :joined
           :root-ready 1
           :root-waiting 0
           :root-done 2
           :queued-count 0
           :last-event :joined
           :master-version 3
           :report "full report"
           :board {:tasks {:t1 {:goal "private"}}
                   :actions {:a1 {:phase :finished}}}}
          {:swarm-id "swarm-2"
           :slice-id "b"
           :worktree "/worktrees/swarm-2"
           :status :running
           :private-detail "drop"
           :board {:tasks {:t2 {:goal "private"}}}}]
         :events events}
        service-map {:observation (fn [_] observation)
                     :status (fn [_] {:available? true})}
        result (router/handle service-map {:kind :fleet/state})]
    (is (:ok? result))
    (is (= 1000 (count (get-in result [:observation :events]))))
    (is (= "full report" (get-in result [:observation :swarms 0 :report])))
    (is (nil? (get-in result [:observation :swarms 0 :board])))
    (is (nil? (get-in result [:observation :swarms 1 :board])))
    (is (nil? (get-in result [:observation :swarms 1 :private-detail])))
    (is (= #{:swarm-id :slice-id :worktree :status
             :root-ready :root-waiting :root-done
             :queued-count :last-event :master-version :report}
           (set (keys (get-in result [:observation :swarms 0])))))))

(deftest fleet-commands-return-receipts-without-control-state
  (let [service-map
        {:complete! (fn [_ _ _ _]
                      {:ok? true
                       :state {:huge true}
                       :receipt {:status :queued :swarm-id "swarm-1"}
                       :persisted? true})
         :abandon! (fn [_ _ _]
                     {:ok? true
                      :state {:huge true}
                      :receipt {:status :abandoned :swarm-id "swarm-1"}})
         :fail! (fn [_ _ _]
                  {:ok? true
                   :state {:huge true}
                   :receipt {:status :failed :swarm-id "swarm-1"}})
         :gc! (fn [_]
                {:ok? true :removed ["swarm-1"] :failed [] :state {:huge true}})}]
    (is (= {:ok? true
            :receipt {:status :queued :swarm-id "swarm-1"}
            :persisted? true}
           (router/handle service-map {:kind :fleet/complete
                                       :swarm-id "swarm-1"
                                       :report "r"
                                       :board-version 1})))
    (is (= {:ok? true :receipt {:status :abandoned :swarm-id "swarm-1"}}
           (router/handle service-map {:kind :fleet/abandon
                                       :swarm-id "swarm-1"
                                       :reason :stale})))
    (is (= {:ok? true :receipt {:status :failed :swarm-id "swarm-1"}}
           (router/handle service-map {:kind :fleet/fail
                                       :swarm-id "swarm-1"
                                       :error {:code :X}})))
    (is (= {:ok? true :removed ["swarm-1"] :failed []}
           (router/handle service-map {:kind :fleet/gc})))))

(deftest fork-resource-shortage-and-exception-are-explicit
  (with-redefs [service/fork (fn [_ _] :INSUFFICIENT_WORKTREES)]
    (is (= :INSUFFICIENT_WORKTREES
           (get-in (router/handle :svc {:kind :fleet/fork :slices [:s]})
                   [:error :code]))))
  (with-redefs [service/fork (fn [_ _]
                               (throw (ex-info "no space"
                                               {:fleet/error {:code :DISK_FULL}})))]
    (is (= :DISK_FULL
           (get-in (router/handle :svc {:kind :fleet/fork :slices [:s]})
                   [:error :code])))))
