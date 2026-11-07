(ns hc.compose.fleet.conflict-property-test
  "Property tests for conflict resolution: a resolved decision stages only
  the requested worktree; stage failure or a throwing holder abandons only
  that swarm."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.conflict :as conflict]
            [hc.compose.fleet.git :as git]))

(defspec resolved-decision-stages-only-the-requested-worktree 50
  (prop/for-all [workspace (gen/such-that seq gen/string-alphanumeric 100)
                 stage-status (gen/elements [:ok :failed])]
    (let [staged (atom [])
          resolver (conflict/make-resolver
                    {:git {}
                     :holder-resolve! (fn [_] :resolved)})]
      (with-redefs [git/stage-all! (fn [_ ws]
                                     (swap! staged conj ws)
                                     {:status stage-status})]
        (let [result (resolver {:swarm-id "s" :workspace workspace})]
          (and (= [workspace] @staged)
               (if (= :ok stage-status)
                 (= :resolved result)
                 (and (= :abandoned (:status result))
                      (= :CONFLICT_STAGE_FAILED
                         (get-in result [:reason :code]))))))))))

(defspec throwing-holder-abandons-only-that-swarm 50
  (prop/for-all [swarm-id (gen/such-that seq gen/string-alphanumeric 100)]
    (let [resolver (conflict/make-resolver
                    {:git {}
                     :holder-resolve! (fn [_] (throw (ex-info "boom" {})))})
          result (resolver {:swarm-id swarm-id :workspace "/w"})]
      (and (= :abandoned (:status result))
           (= swarm-id (:swarm-id result))
           (= :CONFLICT_HOLDER_FAILED (get-in result [:reason :code]))))))
