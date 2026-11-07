(ns hc.compose.owner.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.owner.store :as store]))

(defn- state
  [n]
  {:session-id "s1" :n n})

(deftest memory-store-compare-and-set
  (let [s (store/memory-store)]
    (testing "an empty store reports version zero and no state"
      (is (= {:version 0 :state nil} (store/load-state s))))
    (testing "a commit at the current version wins"
      (is (= {:ok? true :version 1}
             (store/commit! s 0 (state 1)))))
    (testing "a stale expected version is refused"
      (let [result (store/commit! s 0 (state 2))]
        (is (false? (:ok? result)))
        (is (= :VERSION_CONFLICT (get-in result [:error :code])))
        (is (= {:version 1 :state (state 1)} (store/load-state s)))))
    (testing "an invalid state is refused"
      (is (= :INVALID_STATE
             (get-in (store/commit! s 1 {:n 3}) [:error :code]))))
    (testing "close is idempotent and refuses later commits"
      (is (true? (:ok? (store/close! s))))
      (is (true? (:ok? (store/close! s))))
      (is (= :STORE_CLOSED
             (get-in (store/commit! s 1 (state 3)) [:error :code]))))))

(defspec compare-and-set-matches-version
  100
  (prop/for-all [expected (gen/choose 0 20)
                 actual (gen/choose 0 20)]
    (let [s (if (zero? actual)
              (store/memory-store)
              (store/memory-store {:initial-state (state actual)
                                   :initial-version actual}))
          result (store/commit! s expected (state (inc expected)))]
      (if (= expected actual)
        (and (:ok? result)
             (= (inc actual) (:version result)))
        (and (false? (:ok? result))
             (= :VERSION_CONFLICT (get-in result [:error :code])))))))

(deftest memory-store-rejects-inconsistent-initial-state
  (is (thrown? clojure.lang.ExceptionInfo
               (store/memory-store {:initial-state (state 1)})))
  (is (thrown? clojure.lang.ExceptionInfo
               (store/memory-store {:initial-state {:n 1}
                                    :initial-version 2}))))
