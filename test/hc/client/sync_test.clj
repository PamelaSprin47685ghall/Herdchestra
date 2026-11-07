(ns hc.client.sync-test
  "Contract tests for the pure AG-UI state sync adapter.

  JSON Pointer paths address normalized table keys through their escaped form:
  `/session~1by-id/s1/status`, not `/session/by-id/s1/status`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.client.sync :as sync]
            [hc.client.sync.json-patch :as json-patch]
            [hc.client.sync.normalize :as normalize]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private base-server
  {:session/by-id {"s1" {:id "s1" :status :running}}
   :swarm/by-id {"w1" {:id "w1" :slice-id "slice-1" :status :running}}
   :task/by-id {"t1" {:id "t1" :state :ready}}
   :action/by-id {"a1" {:id "a1" :state :running}}
   :message/by-id {"u1" {:id "u1" :role :user :content "hi"}
                   "a1" {:id "a1" :role :assistant :content "hello"}}
   :message/order ["u1" "a1"]
   :session/current-id "s1"})

(def ^:private message-server
  {:message/by-id {"u1" {:id "u1" :role :user :content "hi"}
                   "r1" {:id "r1" :role :reasoning :content "think"}
                   "act1" {:id "act1" :role :activity :content "run"}
                   "a1" {:id "a1" :role :assistant :content "hello"}}
   :message/order ["u1" "r1" "act1" "a1"]})

(defn- connected
  []
  (-> (sync/initial-state)
      sync/connect
      sync/channel-opened))

(defn- synced
  ([server]
   (synced server 1 10))
  ([server epoch seq]
   (sync/receive (connected)
                 1
                 {:type "STATE_SNAPSHOT"
                  :snapshot server
                  :epoch epoch
                  :seq seq
                  :at "t0"})))

(defn- outcome
  [state]
  (:last-outcome state))

(defn- result
  [state]
  (get-in state [:last-outcome :result]))

(defn- reason
  [state]
  (get-in state [:last-outcome :reason]))

(defn- event
  [type fields]
  (merge {:type type} fields))

;; ---------------------------------------------------------------------------
;; Connection phases

(deftest five-phase-connection-machine
  (testing "disconnected to synced"
    (let [s0 (sync/initial-state)]
      (is (= :disconnected (sync/phase s0)))
      (is (false? (sync/command-allowed? s0)))
      (let [s1 (sync/connect s0)]
        (is (= :connecting (sync/phase s1)))
        (is (= 1 (:subscription s1)))
        (let [s2 (sync/channel-opened s1)]
          (is (= :connected-awaiting-sync (sync/phase s2)))
          (is (false? (sync/command-allowed? s2)))
          (let [s3 (sync/receive s2 1
                                 (event "STATE_SNAPSHOT"
                                        {:snapshot base-server
                                         :epoch 1
                                         :seq 10
                                         :at "t0"}))]
            (is (= :synced (sync/phase s3)))
            (is (true? (sync/command-allowed? s3)))
            (is (= "t0" (:synced-at s3)))
            (is (= base-server (:server s3)))

            (testing "a duplicate channel-open is ignored"
              (let [s3' (sync/channel-opened s3)]
                (is (= :synced (sync/phase s3')))
                (is (= :ignored (result s3')))))

            (testing "interruption keeps the confirmed copy"
              (let [s4 (sync/channel-interrupted s3 :socket-closed)]
                (is (= :interrupted (sync/phase s4)))
                (is (false? (sync/command-allowed? s4)))
                (is (= base-server (:server s4)))
                (is (= "t0" (:synced-at s4)))
                (is (= :socket-closed (:reason s4)))))

            (testing "reconnect starts a new subscription and clears the boundary"
              (let [s4 (sync/channel-interrupted s3)
                    s5 (sync/connect s4)]
                (is (= :connecting (sync/phase s5)))
                (is (= 2 (:subscription s5)))
                (is (nil? (:epoch s5)))
                (is (nil? (:seq s5)))
                (is (= base-server (:server s5)))
                (is (= "t0" (:synced-at s5)))
                (let [s6 (sync/channel-opened s5)]
                  (is (= :connected-awaiting-sync (sync/phase s6)))
                  (is (false? (sync/command-allowed? s6))))))

            (testing "deliberate disconnect"
              (let [s4 (sync/disconnect s3)]
                (is (= :disconnected (sync/phase s4)))
                (is (false? (sync/command-allowed? s4)))
                (is (= base-server (:server s4)))))))))))

(deftest baseline-requires-a-channel-and-a-complete-boundary
  (let [s (sync/connect (sync/initial-state))]
    (testing "a snapshot before channel-open is dropped"
      (let [s' (sync/receive s 1 (event "STATE_SNAPSHOT"
                                        {:snapshot {}
                                         :epoch 1
                                         :seq 1}))]
        (is (= :connecting (sync/phase s')))
        (is (= :not-syncing (reason s')))))

    (testing "epoch and sequence are mandatory"
      (let [opened (sync/channel-opened s)]
        (doseq [ev [(event "STATE_SNAPSHOT" {:snapshot {} :seq 1})
                    (event "STATE_SNAPSHOT" {:snapshot {} :epoch 1})]]
          (let [s' (sync/receive opened 1 ev)]
            (is (= :connected-awaiting-sync (sync/phase s')))
            (is (= :dropped (result s')))
            (is (contains? #{:invalid-epoch :invalid-sequence}
                           (reason s')))))
        (testing "an unreadable snapshot asks for a fresh boundary"
          (let [s' (sync/receive opened 1
                                 (event "STATE_SNAPSHOT"
                                        {:snapshot [] :epoch 1 :seq 1}))]
            (is (= :connected-awaiting-sync (sync/phase s')))
            (is (= :resync-required (result s')))
            (is (= :invalid-snapshot (reason s')))))))))

(deftest nested-sync-envelope-is-accepted
  (let [s (-> (sync/initial-state) sync/connect sync/channel-opened)
        s' (sync/receive s 1
                         (event "STATE_SNAPSHOT"
                                {:snapshot {}
                                 :sync {:epoch 2 :seq 3}}))]
    (is (= :synced (sync/phase s')))
    (is (= 2 (:epoch s')))
    (is (= 3 (:seq s')))))

(deftest negative-epochs-are-invalid
  (let [s (synced base-server 1 10)
        delta (sync/receive s 1
                            (event "STATE_DELTA"
                                   {:delta []
                                    :epoch -1
                                    :seq 11}))
        snapshot (sync/receive s 1
                               (event "STATE_SNAPSHOT"
                                      {:snapshot {}
                                       :epoch -1
                                       :seq 11}))]
    (is (= :connected-awaiting-sync (sync/phase delta)))
    (is (= :delta-invalid (reason delta)))
    (is (= :synced (sync/phase snapshot)))
    (is (= :invalid-epoch (reason snapshot)))))

(deftest snapshot-stale-boundaries
  (let [s (synced base-server 2 20)]
    (testing "an older epoch is stale"
      (let [s' (sync/receive s 1 (event "STATE_SNAPSHOT"
                                        {:snapshot {:session/by-id {"s9" {:id "s9"}}}
                                         :epoch 1
                                         :seq 21}))]
        (is (= :synced (sync/phase s')))
        (is (= :dropped (result s')))
        (is (= :stale-epoch (reason s')))
        (is (= base-server (:server s')))))

    (testing "an older sequence in the same epoch is stale"
      (let [s' (sync/receive s 1 (event "STATE_SNAPSHOT"
                                        {:snapshot {}
                                         :epoch 2
                                         :seq 19}))]
        (is (= :synced (sync/phase s')))
        (is (= :stale-sequence (reason s')))
        (is (= base-server (:server s')))))

    (testing "an equal or newer sequence in the same epoch is accepted"
      (let [same (sync/receive s 1 (event "STATE_SNAPSHOT"
                                          {:snapshot {:session/by-id {"s2" {:id "s2"}}}
                                           :epoch 2
                                           :seq 20}))
            newer (sync/receive s 1 (event "STATE_SNAPSHOT"
                                           {:snapshot {:session/by-id {"s3" {:id "s3"}}}
                                            :epoch 2
                                            :seq 25}))]
        (is (= :synced (sync/phase same)))
        (is (= {:session/by-id {"s2" {:id "s2"}}} (:server same)))
        (is (= :synced (sync/phase newer)))
        (is (= 25 (:seq newer)))))

    (testing "a newer epoch may reset the sequence"
      (let [s' (sync/receive s 1 (event "STATE_SNAPSHOT"
                                        {:snapshot {:session/by-id {"s4" {:id "s4"}}}
                                         :epoch 3
                                         :seq 0}))]
        (is (= :synced (sync/phase s')))
        (is (= 3 (:epoch s')))
        (is (= 0 (:seq s')))
        (is (= {:session/by-id {"s4" {:id "s4"}}} (:server s')))))))

(deftest snapshot-replaces-collections-and-clears-removed-entities
  (let [s (synced base-server 1 10)
        next-server {:session/by-id {"s2" {:id "s2" :status :done}}
                     :message/by-id {"m2" {:id "m2" :role :user :content "new"}}
                     :message/order ["m2"]}
        s' (sync/receive s 1 (event "STATE_SNAPSHOT"
                                    {:snapshot next-server
                                     :epoch 1
                                     :seq 11
                                     :at "t1"}))]
    (is (= :synced (sync/phase s')))
    (is (= next-server (:server s')))
    (is (= {"s2" {:id "s2" :status :done}}
           (get-in s' [:db :session/by-id])))
    (is (= ["m2"] (get-in s' [:db :message/order])))
    (is (nil? (get-in s' [:db :message/by-id "u1"])))
    (is (not (contains? (:db s') :swarm/by-id)))
    (is (not (contains? (:db s') :task/by-id)))
    (is (not (contains? (:db s') :action/by-id)))
    (is (not (contains? (:server s') :swarm/by-id)))

    (testing "Fulcro merge replaces the server region but keeps local state"
      (let [local {:ui/draft "typing"
                   :swarm/by-id {"stale" {:id "stale"}}}
            merged (sync/merge-into-db local s')]
        (is (= "typing" (:ui/draft merged)))
        (is (not (contains? merged :swarm/by-id)))
        (is (= {"s2" {:id "s2" :status :done}}
               (:session/by-id merged)))))))

(deftest default-server-keys-own-every-published-table
  (let [server {:session/by-id {}
                :session/active-id "s1"
                :session/history []
                :session/history-active []
                :message/by-id {}
                :message/order []
                :swarm/by-id {}
                :task/by-id {}
                :action/by-id {}
                :agent/by-id {}
                :token-slot/by-id {}
                :write-gate/by-id {}
                :slot/by-id {}
                :board/version 3
                :settings/by-id {}
                :settings/models {}
                :settings/roles {}
                :instruction-turn {:turn 2 :card-ids ["c1"]}
                :sync {:phase :synced :command-epoch 1}
                :server/now 0
                :run-error nil}]
    (is (every? sync/default-server-keys (keys server))
        "every key the server snapshot can publish must be owned by the merge")
    (is (contains? sync/default-server-keys :slot/by-id))))

(deftest instruction-turn-is-owned-and-cleared-by-merge
  (let [s (synced (assoc base-server
                         :instruction-turn {:turn 2 :card-ids ["c1"]})
                  1 10)
        merged (sync/merge-into-db {} s)]
    (is (= {:turn 2 :card-ids ["c1"]}
           (get-in s [:db :instruction-turn])))
    (is (= {:turn 2 :card-ids ["c1"]} (:instruction-turn merged)))
    (testing "a later snapshot without the key clears the stale value"
      (let [s' (sync/receive s 1
                             (event "STATE_SNAPSHOT"
                                    {:snapshot base-server
                                     :epoch 1
                                     :seq 11}))
            merged' (sync/merge-into-db merged s')]
        (is (nil? (get-in s' [:db :instruction-turn])))
        (is (not (contains? merged' :instruction-turn)))))))

(deftest snapshot-removes-a-dropped-slot-table
  (let [s (synced (assoc base-server
                         :slot/by-id {"slot-1" {:id "slot-1" :swarm-id "w1"}})
                  1 10)
        s' (sync/receive s 1
                         (event "STATE_SNAPSHOT"
                                {:snapshot base-server
                                 :epoch 1
                                 :seq 11}))
        merged (sync/merge-into-db {:slot/by-id {"stale" {:id "stale"}}
                                    :ui/draft "kept"}
                                   s')]
    (is (nil? (get-in s' [:db :slot/by-id])))
    (is (not (contains? merged :slot/by-id))
        "a slot table absent from the new snapshot must not survive")
    (is (= "kept" (:ui/draft merged)))))

(deftest unreadable-snapshot-drops-the-synced-claim
  (let [s (synced base-server 1 10)
        s' (sync/receive s 1
                         (event "STATE_SNAPSHOT"
                                {:snapshot []
                                 :epoch 2
                                 :seq 0}))]
    (is (= :connected-awaiting-sync (sync/phase s')))
    (is (= :resync-required (result s')))
    (is (= 1 (:epoch s')) "the last confirmed boundary stays visible")
    (is (= 10 (:seq s')))
    (is (false? (sync/command-allowed? s')))))

(deftest command-epoch-is-separate-from-the-feed-epoch
  (let [s (synced {:sync {:command-epoch 9}} 7 40)
        string-keyed (synced {"sync" {"command-epoch" 11}} 7 41)
        missing (synced {:sync {:phase :synced}} 7 42)]
    (is (= 7 (:epoch s)))
    (is (= 9 (sync/command-epoch s)))
    (is (= 11 (sync/command-epoch string-keyed)))
    (is (nil? (sync/command-epoch missing)))))

(deftest snapshot-replacement-clears-a-removed-command-epoch
  (let [s (synced {:sync {:command-epoch 9}
                   :session/by-id {"s1" {:id "s1"}}}
                  1
                  1)
        s' (sync/receive s 1
                         (event "STATE_SNAPSHOT"
                                {:snapshot {:session/by-id {}}
                                 :epoch 1
                                 :seq 2}))
        merged (sync/merge-into-db {:sync {:command-epoch 9}
                                    :ui/draft "kept"}
                                   s')]
    (is (= :synced (sync/phase s')))
    (is (nil? (sync/command-epoch s')))
    (is (not (contains? merged :sync)))
    (is (= "kept" (:ui/draft merged)))))

;; ---------------------------------------------------------------------------
;; Deltas, epochs, and stale sequences

(deftest delta-applies-at-the-next-sequence
  (let [s (synced base-server 1 10)
        s' (sync/receive s 1
                         (event "STATE_DELTA"
                                {:delta [{:op "replace"
                                          :path "/session~1by-id/s1/status"
                                          :value :done}
                                         {:op "add"
                                          :path "/task~1by-id/t2"
                                          :value {:id "t2" :state :ready}}
                                         {:op "remove"
                                          :path "/action~1by-id/a1"}]
                                 :epoch 1
                                 :seq 11
                                 :at "t1"}))]
    (is (= :synced (sync/phase s')))
    (is (= :applied (result s')))
    (is (= 11 (:seq s')))
    (is (= "t1" (:synced-at s')))
    (is (= :done (get-in s' [:db :session/by-id "s1" :status])))
    (is (= {:id "t2" :state :ready}
           (get-in s' [:db :task/by-id "t2"])))
    (is (not (contains? (get-in s' [:db :action/by-id]) "a1")))))

(deftest delta-reverts-a-session-compression
  (let [server {:session/by-id {"s1" {:id "s1"
                                    :context {:compressions
                                              [{:compression-id "c1"
                                                :state :applied}]}}}}
        s (synced server 1 10)
        s' (sync/receive s 1
                         (event "STATE_DELTA"
                                {:delta [{:op "replace"
                                          :path "/session~1by-id/s1/context/compressions/0/state"
                                          :value :reverted}]
                                 :epoch 1
                                 :seq 11
                                 :at "t1"}))]
    (is (= :applied (result s')))
    (is (= :reverted
           (get-in s' [:db :session/by-id "s1" :context :compressions 0
                       :state])))
    (is (= :reverted
           (get-in (:server s') [:session/by-id "s1" :context
                                 :compressions 0 :state])))))

(deftest delta-can-add-the-first-entity-to-an-empty-table
  (let [s (synced {:session/by-id {}
                   :message/by-id {}
                   :message/order []}
                  1 1)
        s' (sync/receive s 1
                         (event "STATE_DELTA"
                                {:delta [{:op "add"
                                          :path "/session~1by-id/s1"
                                          :value {:id "s1" :status :running}}]
                                 :epoch 1
                                 :seq 2}))]
    (is (= :applied (result s')))
    (is (= {"s1" {:id "s1" :status :running}}
           (get-in s' [:db :session/by-id])))))

(deftest stale-deltas-are-dropped
  (let [s (synced base-server 1 10)
        stale-epoch (sync/receive s 1
                                  (event "STATE_DELTA"
                                         {:delta [{:op "add" :path "/x" :value 1}]
                                          :epoch 0
                                          :seq 11}))
        stale-seq (sync/receive s 1
                                (event "STATE_DELTA"
                                       {:delta [{:op "add" :path "/x" :value 1}]
                                        :epoch 1
                                        :seq 10}))
        older-seq (sync/receive s 1
                                (event "STATE_DELTA"
                                       {:delta [{:op "add" :path "/x" :value 1}]
                                        :epoch 1
                                        :seq 9}))]
    (doseq [state [stale-epoch stale-seq older-seq]]
      (is (= :synced (sync/phase state)))
      (is (= :dropped (result state)))
      (is (= base-server (:server state)))
      (is (= 10 (:seq state))))
    (is (= :stale-epoch (reason stale-epoch)))
    (is (= :stale-sequence (reason stale-seq)))
    (is (= :stale-sequence (reason older-seq)))))

(deftest gaps-and-epoch-advances-force-a-new-baseline
  (let [s (synced base-server 1 10)
        gap (sync/receive s 1
                          (event "STATE_DELTA"
                                 {:delta []
                                  :epoch 1
                                  :seq 12}))
        epoch-advance (sync/receive s 1
                                    (event "STATE_DELTA"
                                           {:delta []
                                            :epoch 2
                                            :seq 1}))]
    (testing "a sequence gap keeps the confirmed copy and blocks deltas"
      (is (= :connected-awaiting-sync (sync/phase gap)))
      (is (= :resync-required (result gap)))
      (is (= :delta-gap (reason gap)))
      (is (= base-server (:server gap)))
      (is (= 10 (:seq gap)))
      (let [late (sync/receive gap 1
                               (event "STATE_DELTA"
                                      {:delta []
                                       :epoch 1
                                       :seq 12}))]
        (is (= :connected-awaiting-sync (sync/phase late)))
        (is (= :dropped (result late)))
        (is (= :not-synced (reason late)))))

    (testing "an epoch advance requires a snapshot"
      (is (= :connected-awaiting-sync (sync/phase epoch-advance)))
      (is (= :epoch-changed (reason epoch-advance)))
      (is (= base-server (:server epoch-advance))))

    (testing "a fresh baseline resumes the stream"
      (let [recovered (sync/receive gap 1
                                    (event "STATE_SNAPSHOT"
                                           {:snapshot base-server
                                            :epoch 1
                                            :seq 12}))
            next (sync/receive recovered 1
                               (event "STATE_DELTA"
                                      {:delta []
                                       :epoch 1
                                       :seq 13}))]
        (is (= :synced (sync/phase recovered)))
        (is (= 12 (:seq recovered)))
        (is (= :applied (result next)))
        (is (= 13 (:seq next)))))))

(deftest invalid-patches-never-touch-confirmed-state
  (let [s (synced base-server 1 10)
        missing (sync/receive s 1
                              (event "STATE_DELTA"
                                     {:delta [{:op "remove" :path "/missing"}]
                                      :epoch 1
                                      :seq 11}))
        non-map (sync/receive s 1
                              (event "STATE_DELTA"
                                     {:delta [{:op "replace" :path "" :value []}]
                                      :epoch 1
                                      :seq 11}))
        invalid-op (sync/receive s 1
                                 (event "STATE_DELTA"
                                        {:delta [{:op "explode" :path "/x"}]
                                         :epoch 1
                                         :seq 11}))]
    (doseq [state [missing non-map invalid-op]]
      (is (= :connected-awaiting-sync (sync/phase state)))
      (is (= :resync-required (result state)))
      (is (= :delta-invalid (reason state)))
      (is (= base-server (:server state)))
      (is (= 10 (:seq state))))))

(deftest late-events-from-an-old-subscription-are-dropped
  (let [s (synced base-server 1 10)
        opened (sync/channel-opened (sync/connect s))
        old-snapshot (sync/receive opened 1
                                   (event "STATE_SNAPSHOT"
                                          {:snapshot {:x 1}
                                           :epoch 9
                                           :seq 99}))
        old-delta (sync/receive opened 1
                                (event "STATE_DELTA"
                                       {:delta []
                                        :epoch 1
                                        :seq 11}))
        tagged-old (sync/receive opened
                                 (event "STATE_DELTA"
                                        {:delta []
                                         :subscription-id 1
                                         :epoch 1
                                         :seq 11}))
        new-snapshot (sync/receive opened 2
                                   (event "STATE_SNAPSHOT"
                                          {:snapshot {:x 2}
                                           :epoch 2
                                           :seq 1}))]
    (doseq [state [old-snapshot old-delta tagged-old]]
      (is (= :connected-awaiting-sync (sync/phase state)))
      (is (= :dropped (result state)))
      (is (= :stale-subscription (reason state)))
      (is (= base-server (:server state))))
    (is (= :synced (sync/phase new-snapshot)))
    (is (= {:x 2} (:server new-snapshot)))
    (is (= 2 (:subscription new-snapshot)))))

(deftest request-resync-is-explicit
  (let [s (synced base-server 1 10)
        s' (sync/request-resync s :backlog-overflow)]
    (is (= :connected-awaiting-sync (sync/phase s')))
    (is (= :resync-required (result s')))
    (is (= :backlog-overflow (reason s')))
    (is (= base-server (:server s')))
    (is (= 10 (:seq s')))))

;; ---------------------------------------------------------------------------
;; MESSAGES_SNAPSHOT role rules

(deftest messages-snapshot-preserves-undeclared-special-roles
  (let [s (synced message-server 1 1)
        s' (sync/receive s 1
                         (event "MESSAGES_SNAPSHOT"
                                {:messages [{:id "u1" :role :user :content "hi2"}
                                            {:id "a1" :role :assistant :content "hello2"}]
                                 :epoch 1
                                 :seq 2}))]
    (is (= :synced (sync/phase s')))
    (is (= :applied (result s')))
    (is (= ["u1" "a1" "r1" "act1"]
           (get-in s' [:db :message/order])))
    (is (= "hi2" (get-in s' [:db :message/by-id "u1" :content])))
    (is (= "hello2" (get-in s' [:db :message/by-id "a1" :content])))
    (is (= "think" (get-in s' [:db :message/by-id "r1" :content])))
    (is (= "run" (get-in s' [:db :message/by-id "act1" :content])))))

(deftest messages-snapshot-declared-roles-clear-undeclared-special-roles
  (let [s (synced message-server 1 1)
        s' (sync/receive s 1
                         (event "MESSAGES_SNAPSHOT"
                                {:messages [{:id "u1" :role :user :content "hi"}
                                            {:id "a1" :role :assistant :content "hello"}]
                                 :hc/roles [:user :assistant]
                                 :epoch 1
                                 :seq 2}))]
    (is (= :applied (result s')))
    (is (= ["u1" "a1"] (get-in s' [:db :message/order])))
    (is (not (contains? (get-in s' [:db :message/by-id]) "r1")))
    (is (not (contains? (get-in s' [:db :message/by-id]) "act1")))))

(deftest messages-snapshot-declared-special-role-is-replaced
  (let [s (synced message-server 1 1)
        s' (sync/receive s 1
                         (event "MESSAGES_SNAPSHOT"
                                {:messages [{:id "u1" :role :user :content "hi"}
                                            {:id "r2" :role :reasoning :content "new"}]
                                 :epoch 1
                                 :seq 2}))]
    (is (= :applied (result s')))
    (is (= ["u1" "r2" "act1"] (get-in s' [:db :message/order])))
    (is (contains? (get-in s' [:db :message/by-id]) "r2"))
    (is (not (contains? (get-in s' [:db :message/by-id]) "r1")))
    (is (contains? (get-in s' [:db :message/by-id]) "act1"))))

(deftest messages-snapshot-explicit-clear-and-epoch-rebuild
  (let [s (synced message-server 1 1)]
    (testing "clear-roles wins over presence"
      (let [s' (sync/receive s 1
                             (event "MESSAGES_SNAPSHOT"
                                    {:messages [{:id "u1" :role :user :content "hi"}
                                                {:id "act2" :role :activity :content "new"}]
                                     :hc/clear-roles [:activity]
                                     :epoch 1
                                     :seq 2}))]
        (is (= :applied (result s')))
        (is (not (contains? (get-in s' [:db :message/by-id]) "act1")))
        (is (not (contains? (get-in s' [:db :message/by-id]) "act2")))
        (is (contains? (get-in s' [:db :message/by-id]) "r1"))))

    (testing "epoch rebuild clears special roles absent from the snapshot"
      (let [s' (sync/receive s 1
                             (event "MESSAGES_SNAPSHOT"
                                    {:messages [{:id "u1" :role :user :content "hi"}]
                                     :hc/epoch-rebuild? true
                                     :epoch 1
                                     :seq 2}))]
        (is (= :applied (result s')))
        (is (= ["u1"] (get-in s' [:db :message/order])))
        (is (not (contains? (get-in s' [:db :message/by-id]) "r1")))
        (is (not (contains? (get-in s' [:db :message/by-id]) "act1")))))

    (testing "a declared special role with no messages is cleared"
      (let [s' (sync/receive s 1
                             (event "MESSAGES_SNAPSHOT"
                                    {:messages [{:id "u1" :role :user :content "hi"}]
                                     :hc/roles [:user :reasoning :activity]
                                     :epoch 1
                                     :seq 2}))]
        (is (= :applied (result s')))
        (is (= ["u1"] (get-in s' [:db :message/order])))
        (is (not (contains? (get-in s' [:db :message/by-id]) "r1")))
        (is (not (contains? (get-in s' [:db :message/by-id]) "act1")))))))

(deftest messages-snapshot-explicit-order
  (let [s (synced message-server 1 1)
        s' (sync/receive s 1
                         (event "MESSAGES_SNAPSHOT"
                                {:messages [{:id "u1" :role :user :content "hi"}
                                            {:id "a1" :role :assistant :content "hello"}]
                                 :hc/order ["a1" "r1" "u1" "act1"]
                                 :epoch 1
                                 :seq 2}))
        invalid (sync/receive s 1
                              (event "MESSAGES_SNAPSHOT"
                                     {:messages [{:id "u1" :role :user}]
                                      :hc/order ["missing"]
                                      :epoch 1
                                      :seq 2}))]
    (is (= :applied (result s')))
    (is (= ["a1" "r1" "u1" "act1"]
           (get-in s' [:db :message/order])))
    (is (= :connected-awaiting-sync (sync/phase invalid)))
    (is (= :messages-invalid (reason invalid)))
    (is (= message-server (:server invalid)))))

(deftest messages-snapshot-rejects-invalid-input
  (let [s (synced message-server 1 1)]
    (doseq [ev [(event "MESSAGES_SNAPSHOT"
                       {:messages [{:id "u1" :role :user}
                                   {:id "u1" :role :assistant}]
                        :epoch 1
                        :seq 2})
                (event "MESSAGES_SNAPSHOT"
                       {:messages [{:id "u1"}]
                        :epoch 1
                        :seq 2})
                (event "MESSAGES_SNAPSHOT"
                       {:messages [{:id "u1" :role :user}]
                        :hc/roles [:assistant]
                        :epoch 1
                        :seq 2})]]
      (let [s' (sync/receive s 1 ev)]
        (is (= :connected-awaiting-sync (sync/phase s')))
        (is (= :messages-invalid (reason s')))
        (is (= message-server (:server s')))))))

(deftest messages-snapshot-advances-the-sequence
  (let [s (synced message-server 1 1)
        stale (sync/receive s 1
                            (event "MESSAGES_SNAPSHOT"
                                   {:messages [{:id "u1" :role :user}]
                                    :epoch 1
                                    :seq 1}))
        gap (sync/receive s 1
                          (event "MESSAGES_SNAPSHOT"
                                 {:messages [{:id "u1" :role :user}]
                                  :epoch 1
                                  :seq 3}))
        epoch-advance (sync/receive s 1
                                    (event "MESSAGES_SNAPSHOT"
                                           {:messages []
                                            :epoch 2
                                            :seq 2}))]
    (is (= :synced (sync/phase stale)))
    (is (= :dropped (result stale)))
    (is (= :stale-sequence (reason stale)))
    (is (= :connected-awaiting-sync (sync/phase gap)))
    (is (= :delta-gap (reason gap)))
    (is (= :connected-awaiting-sync (sync/phase epoch-advance)))
    (is (= :epoch-changed (reason epoch-advance)))))

(deftest state-snapshot-epoch-rebuild-clears-special-roles
  (let [s (synced message-server 1 1)
        s' (sync/receive s 1
                         (event "STATE_SNAPSHOT"
                                {:snapshot {:message/by-id {"u1" {:id "u1" :role :user}}
                                            :message/order ["u1"]}
                                 :epoch 2
                                 :seq 0}))]
    (is (= :synced (sync/phase s')))
    (is (not (contains? (get-in s' [:db :message/by-id]) "r1")))
    (is (not (contains? (get-in s' [:db :message/by-id]) "act1")))))

(deftest messages-snapshot-supports-raw-message-vectors
  (let [raw-server {:messages [{:id "u1" :role :user :content "hi"}
                               {:id "r1" :role :reasoning :content "think"}]}
        s (synced raw-server 1 1)
        s' (sync/receive s 1
                         (event "MESSAGES_SNAPSHOT"
                                {:messages [{:id "u2" :role :user :content "new"}]
                                 :epoch 1
                                 :seq 2}))]
    (is (= :applied (result s')))
    (is (= [{:id "u2" :role :user :content "new"}
            {:id "r1" :role :reasoning :content "think"}]
           (get-in s' [:server :messages])))
    (is (= ["u2" "r1"] (get-in s' [:db :message/order])))))

(deftest messages-snapshot-updates-explicit-raw-order
  (let [raw-server {:messages [{:id "u1" :role :user}
                               {:id "r1" :role :reasoning}]
                    :message/order ["u1" "r1"]}
        s (synced raw-server 1 1)
        s' (sync/receive s 1
                         (event "MESSAGES_SNAPSHOT"
                                {:messages [{:id "u2" :role :user}]
                                 :epoch 1
                                 :seq 2}))]
    (is (= :applied (result s')))
    (is (= ["u2" "r1"] (get-in s' [:server :message/order])))
    (is (= ["u2" "r1"] (get-in s' [:db :message/order])))))

;; ---------------------------------------------------------------------------
;; JSON Patch

(deftest json-patch-basic-operations
  (testing "add, replace, and remove"
    (is (= {:a 2 :b [1 2 3]}
           (:value (json-patch/apply-patch {:a 1 :b [1 2 3]}
                                           [{:op "replace" :path "/a" :value 2}]))))
    (is (= {:a 1 :b 2}
           (:value (json-patch/apply-patch {:a 1}
                                           [{:op "add" :path "/b" :value 2}]))))
    (is (= {:a 1}
           (:value (json-patch/apply-patch {:a 1 :b 2}
                                           [{:op "remove" :path "/b"}]))))
    (is (= [0 1 2 3]
           (:value (json-patch/apply-patch [0 1 3]
                                           [{:op "add" :path "/2" :value 2}]))))
    (is (= [0 1 2 3]
           (:value (json-patch/apply-patch [0 1 2]
                                           [{:op "add" :path "/-" :value 3}]))))
    (is (= [0 2]
           (:value (json-patch/apply-patch [0 1 2]
                                           [{:op "remove" :path "/1"}])))))

  (testing "copy, move, and test"
    (is (= {:a 1 :b 1}
           (:value (json-patch/apply-patch {:a 1}
                                           [{:op "copy" :from "/a" :path "/b"}]))))
    (is (= {"b" 1}
           (:value (json-patch/apply-patch {:a 1}
                                           [{:op "move" :from "/a" :path "/b"}]))))
    (is (= {:a 1}
           (:value (json-patch/apply-patch {:a 1}
                                           [{:op "move" :from "/a" :path "/a"}]))))
    (is (= :missing-path
           (get-in (json-patch/apply-patch {:a 1}
                                           [{:op "move"
                                             :from "/missing"
                                             :path "/missing"}])
                   [:error :code])))
    (is (:ok? (json-patch/apply-patch {:a 1}
                                      [{:op "test" :path "/a" :value 1}])))
    (is (= :test-failed
           (get-in (json-patch/apply-patch {:a 1}
                                           [{:op "test" :path "/a" :value 2}])
                   [:error :code]))))

  (testing "root operations"
    (is (= [1 2]
           (:value (json-patch/apply-patch {:a 1}
                                           [{:op "replace" :path "" :value [1 2]}]))))
    (is (nil? (:value (json-patch/apply-patch {:a 1}
                                              [{:op "remove" :path ""}])))))

  (testing "string-key operations are accepted"
    (is (= {"a" 2}
           (:value (json-patch/apply-patch {"a" 1}
                                           [{"op" "replace" "path" "/a" "value" 2}]))))))

(deftest json-patch-pointer-escapes
  (is (= {:a/b 2}
         (:value (json-patch/apply-patch {:a/b 1}
                                         [{:op "replace" :path "/a~1b" :value 2}]))))
  (is (= {"a~b" 2}
         (:value (json-patch/apply-patch {"a~b" 1}
                                         [{:op "replace" :path "/a~0b" :value 2}]))))
  (is (= [] (:value (json-patch/parse-pointer ""))))
  (is (= [""] (:value (json-patch/parse-pointer "/"))))
  (is (= ["a/b" "c~d"]
         (:value (json-patch/parse-pointer "/a~1b/c~0d"))))
  (is (= :invalid-pointer
         (get-in (json-patch/parse-pointer "/a~2b") [:error :code])))
  (is (= :invalid-pointer
         (get-in (json-patch/parse-pointer "a/b") [:error :code]))))

(deftest json-patch-failures-are-atomic
  (let [document {:a 1}
        result (json-patch/apply-patch document
                                       [{:op "add" :path "/b" :value 2}
                                        {:op "remove" :path "/missing"}])]
    (is (false? (:ok? result)))
    (is (not (contains? result :value)))
    (is (= :missing-path (get-in result [:error :code])))
    (is (= 1 (get-in result [:error :op-index]))))

  (doseq [[op expected]
          [[{:op "explode" :path "/a"} :invalid-op]
           [{:op "add" :path "/a/b" :value 1} :missing-path]
           [{:op "replace" :path "/b" :value 1} :missing-path]
           [{:op "remove" :path "/1"} :missing-path]
           [{:op "add" :path "/x"} :invalid-patch]
           [{:op "move" :from "/a" :path "/a/b"} :invalid-move]
           [{:op "test" :path "/b" :value 1} :missing-path]]]
    (let [document (if (= :missing-path expected)
                     (if (= "/1" (:path op)) [0] {:a 1})
                     {:a 1})
          result (json-patch/apply-patch document [op])]
      (is (false? (:ok? result)) (pr-str op))
      (is (= expected (get-in result [:error :code])) (pr-str op)))))

;; ---------------------------------------------------------------------------
;; Normalization

(deftest normalizes-compact-collections
  (let [result (normalize/to-db {:sessions [{:id "s1" :status :running}]
                                 :messages [{:id "m1" :role :user}]})]
    (is (:ok? result))
    (is (= {"s1" {:id "s1" :status :running}}
           (get-in result [:value :session/by-id])))
    (is (= {"m1" {:id "m1" :role :user}}
           (get-in result [:value :message/by-id])))
    (is (= ["m1"] (get-in result [:value :message/order])))
    (is (not (contains? (:value result) :sessions)))
    (is (not (contains? (:value result) :messages)))))

(deftest normalization-drops-messages-for-invisible-sessions
  (testing "a deleted session's messages cannot survive in the client table"
    (let [result (normalize/to-db
                  {:session/by-id {"s1" {:id "s1"}}
                   :message/by-id {"m1" {:id "m1" :session-id "s1"}
                                   "m2" {:id "m2" :session-id "deleted"}}
                   :message/order ["m1" "m2"]})]
      (is (:ok? result))
      (is (= {"m1" {:id "m1" :session-id "s1"}}
             (get-in result [:value :message/by-id])))
      (is (= ["m1"] (get-in result [:value :message/order])))))

  (testing "without a session table, messages are left to the server authority"
    (let [result (normalize/to-db
                  {:message/by-id {"m1" {:id "m1" :session-id "gone"}}
                   :message/order ["m1"]})]
      (is (:ok? result))
      (is (contains? (get-in result [:value :message/by-id]) "m1")))))

(deftest normalization-rejects-ambiguous-or-invalid-tables
  (testing "compact and normalized forms cannot coexist"
    (let [result (normalize/to-db {:sessions [{:id "s1"}]
                                   :session/by-id {"s1" {:id "s1"}}})]
      (is (false? (:ok? result)))
      (is (= :conflicting-representation (get-in result [:error :code])))))

  (testing "duplicate entity IDs are rejected"
    (let [result (normalize/to-db {:sessions [{:id "s1"}
                                              {:id "s1"}]})]
      (is (false? (:ok? result)))
      (is (= :duplicate-entity-id (get-in result [:error :code])))))

  (testing "entities need stable string IDs"
    (let [result (normalize/to-db {:sessions [{:status :running}]})]
      (is (false? (:ok? result)))
      (is (= :invalid-entity (get-in result [:error :code])))))

  (testing "normalized tables need string keys and entity-map values"
    (doseq [server [{:session/by-id {:s1 {:id "s1"}}}
                    {:session/by-id {"s1" :not-an-entity}}]]
      (let [result (normalize/to-db server)]
        (is (false? (:ok? result)))
        (is (= :invalid-entity (get-in result [:error :code]))))))

  (testing "message order must match the message table"
    (let [result (normalize/to-db {:message/by-id {"m1" {:id "m1"}}
                                   :message/order ["m2"]})]
      (is (false? (:ok? result)))
      (is (= :invalid-message-order (get-in result [:error :code]))))))

(deftest streamed-text-never-regresses-at-a-state-boundary
  (let [server {:message/by-id {"a1" {:id "a1" :role :assistant
                                      :content "hello"
                                      :text "hello"}}
                :message/order ["a1"]}
        s (synced server 1 10)
        local {:ui/draft "typing"
               :message/by-id {"a1" {:id "a1" :role :assistant
                                     :streaming? true
                                     :message/content "hello world"
                                     :message/content-version 3}}
               :message/order ["a1"]}
        stale (sync/receive s 1
                            (event "STATE_DELTA"
                                   {:delta [{:op "replace"
                                             :path "/message~1by-id/a1/text"
                                             :value "hello wor"}]
                                    :epoch 1
                                    :seq 11}))
        stale-db (sync/merge-into-db local stale)]
    (testing "an older full-text replace cannot roll back appended deltas"
      (is (= "hello world"
             (get-in stale-db [:message/by-id "a1" :content])))
      (is (= "hello world"
             (get-in stale-db [:message/by-id "a1" :message/content])))
      (is (= "typing" (:ui/draft stale-db))))
    (testing "a newer full-text replace wins"
      (let [newer (sync/receive stale 1
                                (event "STATE_DELTA"
                                       {:delta [{:op "replace"
                                                 :path "/message~1by-id/a1/text"
                                                 :value "hello world!"}]
                                        :epoch 1
                                        :seq 12}))
            merged (sync/merge-into-db stale-db newer)]
        (is (= "hello world!"
               (get-in merged [:message/by-id "a1" :content])))))
    (testing "a non-prefix edit replaces the streamed text"
      (let [edited (sync/receive stale 1
                                 (event "STATE_DELTA"
                                        {:delta [{:op "replace"
                                                  :path "/message~1by-id/a1/content"
                                                  :value "rewritten"}
                                                 {:op "replace"
                                                  :path "/message~1by-id/a1/text"
                                                  :value "rewritten"}]
                                         :epoch 1
                                         :seq 12}))
            merged (sync/merge-into-db stale-db edited)]
        (is (= "rewritten"
               (get-in merged [:message/by-id "a1" :content])))))))

(deftest merge-into-db-preserves-local-keys
  (let [s (synced {:session/by-id {"s1" {:id "s1"}}} 1 1)
        local {:ui/draft "draft"
               :ui/selected-panel :board
               :sessions [{:id "old"}]
               :session/by-id {"old" {:id "old"}}
               :swarm/by-id {"old" {:id "old"}}}
        merged (sync/merge-into-db local s)]
    (is (= "draft" (:ui/draft merged)))
    (is (= :board (:ui/selected-panel merged)))
    (is (= {"s1" {:id "s1"}} (:session/by-id merged)))
    (is (not (contains? merged :sessions)))
    (is (not (contains? merged :swarm/by-id)))))

;; ---------------------------------------------------------------------------
;; End-to-end smoke

(deftest end-to-end-sync-smoke
  (let [state (-> (sync/initial-state)
                  sync/connect
                  sync/channel-opened
                  (sync/receive 1
                                (event "STATE_SNAPSHOT"
                                       {:snapshot base-server
                                        :epoch 7
                                        :seq 40
                                        :at "2026-11-03T10:00:00Z"}))
                  (sync/receive 1
                                (event "STATE_DELTA"
                                       {:delta [{:op "replace"
                                                 :path "/swarm~1by-id/w1/status"
                                                 :value :joined}]
                                        :epoch 7
                                        :seq 41
                                        :at "2026-11-03T10:00:01Z"}))
                  (sync/receive 1
                                (event "MESSAGES_SNAPSHOT"
                                       {:messages [{:id "u1" :role :user :content "hi"}
                                                   {:id "a1" :role :assistant :content "done"}]
                                        :epoch 7
                                        :seq 42}))
                  sync/channel-interrupted
                  sync/connect
                  sync/channel-opened
                  (sync/receive 2
                                (event "STATE_SNAPSHOT"
                                       {:snapshot (assoc-in base-server
                                                            [:swarm/by-id "w1" :status]
                                                            :joined)
                                        :epoch 8
                                        :seq 1
                                        :at "2026-11-03T10:00:10Z"})))
        db (sync/merge-into-db {:ui/draft "still typing"} state)]
    (is (= :synced (sync/phase state)))
    (is (= 2 (:subscription state)))
    (is (= 8 (:epoch state)))
    (is (= 1 (:seq state)))
    (is (true? (sync/command-allowed? state)))
    (is (= "still typing" (:ui/draft db)))
    (is (= :joined (get-in db [:swarm/by-id "w1" :status])))
    (is (= "2026-11-03T10:00:10Z" (:synced-at state)))))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private gen-id
  (gen/fmap #(str "id-" %) (gen/choose 0 1000)))

(def ^:private gen-table-entry
  (gen/fmap (fn [[table id]]
              [table {id {:id id :table table}}])
            (gen/tuple (gen/elements [:session/by-id
                                      :swarm/by-id
                                      :task/by-id])
                       gen-id)))

(def ^:private gen-server
  (gen/fmap #(into {} %)
            (gen/vector gen-table-entry 0 3)))

(defspec snapshot-replacement-never-revives-removed-entities 60
  (prop/for-all [old-server gen-server
                 new-server gen-server]
    (let [s (synced old-server 1 1)
          s' (sync/receive s 1
                           (event "STATE_SNAPSHOT"
                                  {:snapshot new-server
                                   :epoch 1
                                   :seq 2}))]
      (and (= :synced (sync/phase s'))
           (= new-server (:server s'))
           (every? (fn [table]
                     (or (contains? new-server table)
                         (not (contains? (:db s') table))))
                   [:session/by-id :swarm/by-id :task/by-id])
           (every? (fn [[table by-id]]
                     (= by-id (get-in s' [:db table])))
                   new-server)))))

(def ^:private gen-stale-boundary
  (gen/tuple (gen/choose 0 1)
             (gen/choose 0 10)))

(defspec stale-delta-never-mutates-confirmed-state 80
  (prop/for-all [[epoch seq] gen-stale-boundary]
    (let [s (synced base-server 1 10)
          s' (sync/receive s 1
                           (event "STATE_DELTA"
                                  {:delta [{:op "add" :path "/x" :value 1}]
                                   :epoch epoch
                                   :seq seq}))]
      (and (= :synced (sync/phase s'))
           (= base-server (:server s'))
           (= 10 (:seq s'))
           (= :dropped (result s'))
           (contains? #{:stale-epoch :stale-sequence} (reason s'))))))

(defspec accepted-deltas-advance-by-one 80
  (prop/for-all [sequences (gen/vector (gen/choose 0 20) 0 20)]
    (loop [state (synced {} 1 0)
           remaining sequences
           last-seq 0]
      (if-let [sequence (first remaining)]
        (let [state' (sync/receive state 1
                                   (event "STATE_DELTA"
                                          {:delta []
                                           :epoch 1
                                           :seq sequence}))
              outcome (result state')]
          (case outcome
            :applied
            (and (= (inc last-seq) (:seq state'))
                 (recur state' (next remaining) (:seq state')))

            :dropped
            (and (= last-seq (:seq state'))
                 (recur state' (next remaining) last-seq))

            :resync-required
            (and (= last-seq (:seq state'))
                 (recur state' (next remaining) last-seq))

            false))
        true))))

(def ^:private gen-special-role
  (gen/elements [:activity :reasoning]))

(def ^:private gen-special-messages
  (gen/fmap (fn [roles]
              (mapv (fn [index role]
                      {:id (str "special-" index)
                       :role role
                       :content (str "content-" index)})
                    (range)
                    roles))
            (gen/vector gen-special-role 0 3)))

(defspec messages-snapshot-preserves-undeclared-special-roles-property 60
  (prop/for-all [special gen-special-messages]
    (let [server {:message/by-id (into {} (map (juxt :id identity)) special)
                  :message/order (mapv :id special)}
          s (synced server 1 1)
          s' (sync/receive s 1
                           (event "MESSAGES_SNAPSHOT"
                                  {:messages [{:id "u1" :role :user :content "hi"}]
                                   :epoch 1
                                   :seq 2}))]
      (and (= :synced (sync/phase s'))
           (every? #(contains? (get-in s' [:db :message/by-id]) (:id %))
                   special)
           (= (set (cons "u1" (map :id special)))
              (set (get-in s' [:db :message/order])))))))
