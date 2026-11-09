(ns hc.server.app.steward-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.steward :as steward]
            [hc.server.app.steward-store :as steward-store]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- git-commit
  [commit-id]
  {:kind :git-commit
   :commit commit-id
   :parents []
   :changed-paths ["src"]})

(deftest empty-store-loads-as-version-zero
  (support/with-temp-store [store-engine {}]
    (let [store (steward-store/steward-store store-engine)]
      (is (= {:version 0 :state nil}
             (steward-store/load-state store))))))

(deftest committed-state-roundtrips
  (support/with-temp-store [store-engine {}]
    (let [store (steward-store/steward-store store-engine)
          initial (steward/initial-state {:steward-id "repo-steward"})]
      (let [committed (steward-store/commit! store 0 initial)]
        (is (true? (:ok? committed)))
        (is (= 1 (:version committed))))
      (is (= {:version 1 :state initial}
             (steward-store/load-state store)))
      (testing "a second handle reads the same committed document"
        (let [reloaded (steward-store/steward-store store-engine)]
          (is (= {:version 1 :state initial}
                 (steward-store/load-state reloaded)))))
      (testing "the document identity is fixed"
        (let [document (storage/read-document
                        store-engine
                        {:partition "steward/repository"
                         :kind :steward-state
                         :id "current"})]
          (is (= :steward-state (:storage/kind document)))
          (is (= "current" (:storage/id document)))
          (is (= "steward/repository" (:storage/partition document)))
          (is (= initial
                 (dissoc document
                         :storage/id :storage/kind :storage/partition
                         :storage/session-id :storage/version
                         :storage/updated-at))))))))

(deftest commit-is-compare-and-set
  (support/with-temp-store [store-engine {}]
    (let [store (steward-store/steward-store store-engine)
          first-state (steward/initial-state {:steward-id "one"})
          second-state (assoc first-state :steward-id "two")]
      (is (true? (:ok? (steward-store/commit! store 0 first-state))))
      (testing "a stale expected version is refused"
        (let [conflict (steward-store/commit! store 0 second-state)]
          (is (false? (:ok? conflict)))
          (is (= :VERSION_CONFLICT (get-in conflict [:error :code])))
          (is (= {:expected 0 :actual 1}
                 (get-in conflict [:error :details])))
          (is (= first-state
                 (:state (steward-store/load-state store))))))
      (testing "the current version advances the document"
        (let [committed (steward-store/commit! store 1 second-state)]
          (is (true? (:ok? committed)))
          (is (= 2 (:version committed)))
          (is (= {:version 2 :state second-state}
                 (steward-store/load-state store))))))))

(deftest commit-refuses-invalid-input
  (support/with-temp-store [store-engine {}]
    (let [store (steward-store/steward-store store-engine)]
      (is (= :INVALID_VERSION
             (get-in (steward-store/commit! store -1 {}) [:error :code])))
      (is (= :INVALID_VERSION
             (get-in (steward-store/commit! store nil {}) [:error :code])))
      (is (= :INVALID_STATE
             (get-in (steward-store/commit! store 0 :not-a-map)
                     [:error :code])))
      (is (= {:version 0 :state nil}
             (steward-store/load-state store))))))

(deftest full-steward-state-roundtrips
  (support/with-temp-store [store-engine {}]
    (let [store (steward-store/steward-store store-engine)
          ingested (:state (steward/ingest-git-evidence
                            (steward/initial-state)
                            (git-commit "abc")))
          state (:state (steward/observe-world
                         ingested
                         {:intent {:revision 1
                                   :text "Keep the build green"
                                   :received-at 5}
                          :capacity {:slots-total 4
                                     :slots-allocated 1
                                     :min-spare 1
                                     :missions []}}))]
      (is (true? (:ok? (steward-store/commit! store 0 state))))
      (let [reloaded (steward-store/steward-store store-engine)]
        (is (= {:version 1 :state state}
               (steward-store/load-state reloaded)))))))

(deftest close-marks-only-this-handle
  (support/with-temp-store [store-engine {}]
    (let [store (steward-store/steward-store store-engine)
          initial (steward/initial-state)]
      (is (= {:ok? true :closed? true}
             (steward-store/close! store)))
      (is (= {:ok? true :closed? true}
             (steward-store/close! store))
          "close! is idempotent")
      (is (= :STORE_CLOSED
             (get-in (steward-store/commit! store 0 initial)
                     [:error :code])))
      (is (= {:version 0 :state nil}
             (steward-store/load-state store))
          "a closed handle can still read")
      (let [other (steward-store/steward-store store-engine)]
        (is (true? (:ok? (steward-store/commit! other 0 initial)))
            "closing one handle does not close the shared storage")))))

(deftest version-without-the-state-document-is-corruption
  (support/with-temp-store [store-engine {}]
    (support/commit! store-engine "seed" "steward/repository" nil
                     :other-document "seed" {})
    (let [store (steward-store/steward-store store-engine)]
      (try
        (steward-store/load-state store)
        (is false "load-state should report corruption")
        (catch clojure.lang.ExceptionInfo e
          (is (= :STEWARD_STORE_CORRUPT (:code (ex-data e))))
          (is (= 1 (:version (ex-data e)))))))))

(def gen-state
  (gen/let [steward-id (gen/not-empty gen/string-alphanumeric)
            n gen/nat]
    {:schema-version 1
     :steward-id steward-id
     :payload {:n n}}))

(defspec stored-state-roundtrips 10
  (prop/for-all [state gen-state]
    (support/with-temp-store [store-engine {}]
      (let [store (steward-store/steward-store store-engine)]
        (and (true? (:ok? (steward-store/commit! store 0 state)))
             (= {:version 1 :state state}
                (steward-store/load-state store)))))))

(defspec commits-follow-a-version-chain 10
  (prop/for-all [states (gen/vector gen-state 1 5)]
    (support/with-temp-store [store-engine {}]
      (let [store (steward-store/steward-store store-engine)]
        (loop [expected 0
               remaining states]
          (if-let [state (first remaining)]
            (let [result (steward-store/commit! store expected state)]
              (if (and (:ok? result)
                       (= (inc expected) (:version result)))
                (recur (inc expected) (next remaining))
                false))
            (and (= {:version (count states) :state (last states)}
                    (steward-store/load-state store))
                 (= :VERSION_CONFLICT
                    (get-in (steward-store/commit! store 0 (first states))
                            [:error :code])))))))))
