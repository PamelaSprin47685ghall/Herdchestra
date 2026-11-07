(ns hc.server.materials.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.materials :as materials]
            [hc.server.materials.store :as store]))

(deftest missing-state-is-not-silently-created
  (let [s (store/memory-store)
        loaded (materials/load-state s)]
    (is (true? (:ok? loaded)))
    (is (false? (:found? loaded)))
    (is (nil? (:state loaded)))
    (testing "initialize is explicit"
      (let [initialized (materials/initialize! s)]
        (is (true? (:ok? initialized)))
        (is (true? (:created? initialized)))
        (is (= 0 (get-in initialized [:state :revision])))
        (is (empty? (materials/check-invariants (:state initialized))))))))

(deftest compare-and-set-refuses-stale-writers
  (let [s (store/memory-store)
        state (:state (materials/initialize! s))
        next-state (update state :revision inc)
        first (materials/commit! s 0 next-state)
        stale (materials/commit! s 0 next-state)]
    (is (true? (:ok? first)))
    (is (= 1 (:revision first)))
    (is (false? (:ok? stale)))
    (is (= :revision-conflict (:reason stale)))
    (is (= 1 (:current-revision stale)))
    (is (= 1 (get-in (materials/load-state s) [:state :revision])))))

(deftest commit-validates-state-and-revision
  (let [s (store/memory-store)
        _ (materials/initialize! s)
        state (:state (materials/load-state s))
        corrupt (assoc-in state [:memories "bad"] {:id "bad"})
        bad-revision (assoc state :revision 7)]
    (is (= :MATERIALS_STATE_CORRUPT
           (get-in (materials/commit! s 0 corrupt) [:error :code])))
    (is (= :MATERIALS_REVISION_INVALID
           (get-in (materials/commit! s 0 bad-revision) [:error :code])))
    (is (= :MATERIALS_REVISION_INVALID
           (get-in (materials/commit! s nil (assoc state :revision 1))
                   [:error :code])))))

(deftest transact-loads-and-commits-one-transition
  (let [s (store/memory-store)
        result (materials/transact!
                s
                (fn [state]
                  (materials/remember state
                                      {:id "m1"
                                       :body "User prefers Chinese."
                                       :category :expression-preference
                                       :scope :global
                                       :epistemic :preference})))
        loaded (materials/load-state s)]
    (is (true? (:ok? result)))
    (is (true? (get-in result [:commit :ok?])))
    (is (true? (:found? loaded)))
    (is (= 1 (get-in loaded [:state :revision])))
    (is (= :active (get-in loaded [:state :memories "m1" :status])))
    (is (empty? (materials/check-invariants (:state loaded))))))

(deftest corrupt-stored-state-is-refused
  (let [s (store/memory-store)
        state (materials/new-state)
        stored (assoc state :v 999)]
    (is (true? (:ok? (store/commit-snapshot! s nil stored))))
    (let [loaded (materials/load-state s)]
      (is (false? (:ok? loaded)))
      (is (= :MATERIALS_STATE_CORRUPT (get-in loaded [:error :code])))
      (is (seq (get-in loaded [:error :details :violations]))))
    (testing "initialize refuses to replace a damaged library"
      (let [initialized (materials/initialize! s)]
        (is (false? (:ok? initialized)))
        (is (= :MATERIALS_STATE_CORRUPT (get-in initialized [:error :code])))))))
