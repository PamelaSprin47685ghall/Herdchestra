(ns hc.server.app.owner-store-retry-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.owner.store :as owner-store-protocol]
            [hc.server.app.owner-store :as owner-store]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- retry-state
  []
  {:phase :retry-wait
   :attempt-id "a1"
   :attempts-used 1
   :max-attempts 3
   :retries-used 0
   :max-retries 2
   :deadline 5000
   :deadline-at 5000
   :retry-at 2000
   :failure nil
   :effects {:status :none :repeat-safe? true}})

(defn- state
  [call-state]
  {:session-id "s1"
   :updated-at 1000
   :calls {"call-1" {:call-id "call-1"
                     :kind :model
                     :state call-state
                     :attempt-id :attempt-1
                     :retry (retry-state)}}
   :reports {:reports {}}})

(defn- ready-retry-state
  []
  (assoc-in (state :ready) [:calls "call-1" :retry-due?] true))

(defn- obligations
  [store]
  (:obligations (storage/list-open-obligations
                 store
                 {:partition "session/s1" :session-id "s1"})))

(deftest retry-wait-call-is-mirrored-and-removed
  (support/with-temp-store [store {}]
    (let [s (owner-store/owner-store store "s1")
          first-commit (owner-store-protocol/commit! s 0 (state :retry-wait))
          open (obligations store)
          obligation (first open)]
      (is (true? (:ok? first-commit)))
      (is (= ["retry/call-1"] (mapv :obligation/id open)))
      (is (= :retry-call (:obligation/kind obligation)))
      (is (= :retry-wait (:phase obligation)))
      (is (= "call-1" (:call-id obligation)))
      (is (= 2000 (:retry-at obligation)))
      (is (= 1 (:attempts-used obligation)))
      (is (= 3 (:max-attempts obligation)))
      (is (= 5000 (:deadline obligation)))
      (let [ready-commit (owner-store-protocol/commit! s
                                                       (:version first-commit)
                                                       (ready-retry-state))
            ready-obligations (obligations store)]
        (is (true? (:ok? ready-commit)))
        (is (= ["retry/call-1"] (mapv :obligation/id ready-obligations)))
        (is (= :ready (:phase (first ready-obligations)))
            "a recovered due retry keeps its index until the claim commits")
        (let [claimed-commit (owner-store-protocol/commit! s
                                                           (:version ready-commit)
                                                           (state :dispatching))]
          (is (true? (:ok? claimed-commit)))
          (is (empty? (obligations store))
              "the claim commit removes the durable index"))))))

(deftest retry-call-index-survives-reload
  (support/with-temp-store [store {}]
    (let [s (owner-store/owner-store store "s1")
          first-commit (owner-store-protocol/commit! s 0 (state :retry-wait))
          reloaded (owner-store/owner-store store "s1")]
      (is (true? (:ok? first-commit)))
      (is (= :retry-wait (get-in (owner-store/load-state reloaded)
                                 [:state :calls "call-1" :state]))
          "load-state returns {:version .. :state owner-doc}")
      (is (= ["retry/call-1"] (mapv :obligation/id (obligations store)))))))

(deftest state-too-large-error-is-actionable
  (is (= {:code :OWNER_STATE_TOO_LARGE
          :size 3000000
          :limit 2000000
          :remedy :compact-or-raise
          :hint "compact the owner state to blob refs or raise :max-doc-chars"}
         (owner-store/state-too-large-error {:chars 3000000
                                             :max-doc-chars 2000000}))))
