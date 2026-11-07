(ns hc.compose.fleet.conflict-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.fleet.conflict :as conflict]
            [hc.compose.fleet.support :as support]
            [hc.core.swarm-stm :as swarm]))

(defn- request
  []
  {:swarm-id "swarm-1"
   :workspace "/worktrees/swarm-1"
   :master-ref "master"
   :round 1
   :conflict {:status :conflict}})

(deftest resolved-holder-edits-are-staged-before-continue
  (let [fake (support/fake-git {})
        resolver (conflict/make-resolver
                  {:git (:git fake)
                   :holder-resolve! (fn [_] :resolved)})]
    (is (= :resolved (resolver (request))))
    (is (some #(= :stage-all (first %)) @(:calls fake)))
    (is (= "/worktrees/swarm-1" (second (first (filter #(= :stage-all (first %))
                                                       @(:calls fake))))))))

(deftest holder-abandon-does-not-stage
  (let [fake (support/fake-git {})
        resolver (conflict/make-resolver
                  {:git (:git fake)
                   :holder-resolve! (fn [_] :abandon)})
        result (resolver (request))]
    (is (= :abandoned (:status result)))
    (is (= :CONFLICT_HOLDER_ABANDONED (get-in result [:reason :code])))
    (is (not-any? #(= :stage-all (first %)) @(:calls fake)))))

(deftest holder-failure-and-stage-failure-abandon-only-this-swarm
  (let [throwing (conflict/make-resolver
                  {:git (:git (support/fake-git {}))
                   :holder-resolve! (fn [_] (throw (ex-info "holder died" {})))})
        stage-failing (conflict/make-resolver
                       {:git (:git (support/fake-git
                                    {:stage-results [{:status :failed
                                                      :code :STAGE_FAILED}]}))
                        :holder-resolve! (fn [_] :resolved)})
        no-holder (conflict/make-resolver
                   {:git (:git (support/fake-git {}))})]
    (is (= :CONFLICT_HOLDER_FAILED
           (get-in (throwing (request)) [:reason :code])))
    (is (= :CONFLICT_STAGE_FAILED
           (get-in (stage-failing (request)) [:reason :code])))
    (is (= :CONFLICT_HOLDER_UNAVAILABLE
           (get-in (no-holder (request)) [:reason :code])))
    (is (= "swarm-1" (:swarm-id (no-holder (request)))))))

(deftest git-is-required
  (is (thrown? clojure.lang.ExceptionInfo
               (conflict/make-resolver {}))))

(defn- holder-kernel
  [holder?]
  (let [kernel (swarm/create {})
        board (-> (swarm/empty-board)
                  (assoc-in [:actors :agent-1]
                            {:agent-id :agent-1
                             :slot-id :slot-a
                             :role (if holder? :holder :normal)
                             :life-state :alive
                             :active-action-id nil
                             :generation 1})
                  (assoc-in [:token-slot]
                            (if holder?
                              {:holder-id :agent-1 :term-id 1 :state :held}
                              {:holder-id nil :term-id 0 :state :free})))]
    (swarm/reset-board! kernel board)
    kernel))

(defn- holder-service
  [kernel done?]
  {:runtime (fn [_] {:kernel kernel :control-agent-id :agent-1})
   :board-snapshot (fn [_] (swarm/snapshot kernel))
   :wake-actors!
   (fn [_]
     (when done?
       (let [board (swarm/snapshot kernel)
             task (->> (:tasks board)
                       vals
                       (filter #(and (:needs-token %) (= :ready (:state %))))
                       first)]
         (when task
           (swarm/reset-board! kernel
                               (assoc-in board [:tasks (:task-id task) :state]
                                         :done)))))
     {:ok? true :effects []})})

(deftest holder-gets-a-real-board-task-and-finishes-it
  (let [kernel (holder-kernel true)
        resolver (conflict/holder-resolver
                  {:service (holder-service kernel true)
                   :timeout-ms 1000
                   :poll-ms 5})
        result (resolver {:swarm-id "swarm-1"
                          :slice-id "slice-a"
                          :workspace "/worktrees/swarm-1"
                          :round 1
                          :conflict {:status :conflict}})]
    (is (= :resolved result))
    (is (= 1 (count (filter #(and (:needs-token %)
                                  (= :done (:state %)))
                            (vals (:tasks (swarm/snapshot kernel)))))))))

(deftest no-live-holder-abandons-without-creating-a-task
  (let [kernel (holder-kernel false)
        resolver (conflict/holder-resolver
                  {:service (holder-service kernel true)
                   :timeout-ms 100
                   :poll-ms 5})
        result (resolver {:swarm-id "swarm-1"
                          :slice-id "slice-a"
                          :workspace "/worktrees/swarm-1"
                          :round 1})]
    (is (= :abandoned (:status result)))
    (is (= :NO_LIVE_HOLDER (get-in result [:reason :code])))
    (is (empty? (:tasks (swarm/snapshot kernel))))))

(deftest holder-timeout-abandons-explicitly
  (let [kernel (holder-kernel true)
        resolver (conflict/holder-resolver
                  {:service (holder-service kernel false)
                   :timeout-ms 25
                   :poll-ms 5})
        result (resolver {:swarm-id "swarm-1"
                          :slice-id "slice-a"
                          :workspace "/worktrees/swarm-1"
                          :round 1})]
    (is (= :abandoned (:status result)))
    (is (= :HOLDER_CONFLICT_TIMEOUT (get-in result [:reason :code])))
    (is (= 1 (count (:tasks (swarm/snapshot kernel))))
        "the unresolved conflict task remains visible evidence")))

(deftest holder-wait-interrupt-abandons-without-spinning
  (let [kernel (holder-kernel true)
        resolver (conflict/holder-resolver
                  {:service (holder-service kernel false)
                   :timeout-ms 60000
                   :poll-ms 50})
        result (atom nil)
        thread (Thread. (fn [] (reset! result (resolver (request)))))]
    (.start thread)
    (Thread/sleep 150)
    (.interrupt thread)
    (.join thread 2000)
    (is (= :abandoned (:status @result)))
    (is (= :HOLDER_CONFLICT_INTERRUPTED (get-in @result [:reason :code])))
    (is (= 1 (count (:tasks (swarm/snapshot kernel))))
        "the unresolved conflict task remains visible evidence")))
