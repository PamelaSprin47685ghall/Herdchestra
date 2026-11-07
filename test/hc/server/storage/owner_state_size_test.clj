(ns hc.server.storage.owner-state-size-test
  "The Owner keeps the whole session state in one current-state document.

  These tests pin the two sides of the size guard:

  - a large program source or a burst of call records must not turn a normal
    Owner commit into `:OWNER_ERROR`;
  - an actually unbounded document is still refused with the actionable
    `:OWNER_STATE_TOO_LARGE` receipt, never silently truncated.

  The last test compacts a 4 MB call record and commits the result through
  the real Owner store adapter, which is the path that produced the observed
  `document is too large for the current-state store` error."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.compact :as compact]
            [hc.compose.owner.store :as owner-commit]
            [hc.server.app.owner-store :as owner-store]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support]))

(defn- error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (storage/error-code e))))

(defn- big-state
  [chars]
  {:session-id "s1"
   :calls {"call-1" {:kind :model
                     :state :succeeded
                     :result {:status :succeeded
                              :value {:source (apply str (repeat chars "x"))}}}}})

(deftest a-two-megabyte-owner-state-commits
  (support/with-temp-store [store {}]
    (let [os (owner-store/owner-store store "s1")
          state (big-state 2500000)
          result (owner-commit/commit! os 0 state)]
      (is (:ok? result) "2.5 MB is below the 8 MB guard")
      (is (= 1 (:version result)))
      (let [loaded (owner-store/load-state os)]
        (is (= 1 (:version loaded)))
        (is (= 2500000 (count (get-in loaded [:state :calls "call-1"
                                              :result :value :source]))))))))

(deftest the-guard-still-refuses-an-unbounded-document
  (support/with-temp-store [store {}]
    (let [os (owner-store/owner-store store "s1")
          state (big-state 9000000)
          result (owner-commit/commit! os 0 state)]
      (is (false? (:ok? result)))
      (is (= :OWNER_STATE_TOO_LARGE (get-in result [:error :code])))
      (is (= 8000000 (get-in result [:error :limit])))
      (is (string? (get-in result [:error :hint]))))))

(deftest compaction-keeps-a-four-megabyte-call-record-small
  (let [store (compact/memory-blob-store)
        source (apply str (repeat 200000 "y"))
        state {:session-id "s1"
               :calls (into {}
                            (for [i (range 20)]
                              [(str "call-" i)
                               {:kind :tool
                                :state :succeeded
                                :result {:status :succeeded
                                         :value {:source source
                                                 :raw-source source
                                                 :numbered-source source}}}]))
               :conversation [{:role :assistant :content "visible reply"}]}
        before (count (pr-str state))
        [compacted stats] (compact/compact-state state store)
        after (count (pr-str compacted))]
    (is (> before 4000000))
    (is (< after 1000000)
        "twenty 200 KB sources compact below one megabyte")
    (is (= before (:before stats)))
    (is (= after (:after stats)))
    (is (pos? (:saved stats)))
    (is (= "visible reply"
           (get-in compacted [:conversation 0 :content]))
        "conversation text is not touched")
    (is (map? (get-in compacted [:calls "call-0" :result :value :source])))
    (support/with-temp-store [store* {}]
      (let [os (owner-store/owner-store store* "s1")
            result (owner-commit/commit! os 0 compacted)]
        (is (:ok? result)
            "the compacted 4 MB record commits without OWNER_ERROR")))))

(deftest a-large-owner-state-does-not-produce-an-owner-error
  (support/with-temp-store [store {}]
    (let [os (owner-store/owner-store store "s1")
          state (assoc {:session-id "s1"}
                       :conversation
                       [{:role :assistant
                         :content (apply str (repeat 3000000 "c"))}]
                       :calls
                       (into {}
                             (for [i (range 10)]
                               [(str "call-" i)
                                {:kind :tool
                                 :state :succeeded
                                 :result {:value
                                          {:source
                                           (apply str (repeat 700000 "z"))}}}])))]
      (testing "12 MB of inline sources is still refused with the guard"
        (let [result (owner-commit/commit! os 0 state)]
          (is (false? (:ok? result)))
          (is (= :OWNER_STATE_TOO_LARGE (get-in result [:error :code])))))
      (testing "the same state after compaction commits"
        (let [[compacted _] (compact/compact-state state
                                                   (compact/memory-blob-store))
              result (owner-commit/commit! os 0 compacted)]
          (is (:ok? result))
          (is (= 1 (:version result))))))))
