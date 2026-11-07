(ns hc.compose.fleet.actors-test
  (:require [clojure.test :refer [deftest is]]
            [hc.compose.fleet.actors :as actors]
            [hc.compose.fleet.git :as git]
            [hc.compose.fleet.runtime :as runtime]
            [hc.compose.fleet.support :as support]
            [hc.compose.mission.actors :as mission-actors]
            [hc.core.swarm-stm :as swarm]))

(defn- slice
  [id]
  {:slice-id id
   :goal (str "goal " id)
   :inputs []
   :depends-on []
   :done-when "done"})

(defn- start-runtime!
  [repo root id]
  (let [workspace (str root "/" id)]
    (git/create-worktree! (git/git-cli repo)
                          {:workspace workspace
                           :branch (str "hc/mission/" id)
                           :base "master"})
    (:runtime
     (runtime/start! {:swarm-id id
                      :slice-id (str "slice-" id)
                      :workspace workspace
                      :branch (str "hc/mission/" id)
                      :slice (slice (str "slice-" id))}))))

(deftest real-task-actors-are-bound-to-the-worktree-board
  (support/with-temp-dir [root "hc-fleet-actors-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [rt (start-runtime! repo root "swarm-1")
            started (actors/start! {:runtime rt
                                    :models {:main "main" :task "task"}
                                    :slot-ids [:slot-a :slot-b]})
            effects (:effects started)
            model-effects (filter #(= :model/start (:type %)) effects)
            board (actors/board (:state started))
            request (:request (first model-effects))]
        (is (:ok? started))
        (is (= 2 (count model-effects)))
        (is (= 2 (count (filter #(= :actor/rebirth (:type %)) effects))))
        (is (= "task" (:model-id request)))
        (is (= :task (:model-role request)))
        (is (= (:workspace rt) (:workspace request)))
        (is (= :free (get-in request [:board-snapshot :token-slot :state])))
        (is (= 3 (count (:actors board)))
            "control actor plus two @task slots")
        (is (= :free (get-in board [:token-slot :state])))
        (let [attempt-id (:attempt-id (first model-effects))
              chunked (actors/accept-event (:state started) attempt-id
                                           {:event :chunk :text "partial"})
              completed (actors/accept-event (:state chunked) attempt-id
                                             {:event :completed :text "done"})]
          (is (:ok? chunked))
          (is (:ok? completed))
          (is (= "partial" (get-in completed [:state :actors :calls attempt-id
                                              :output]))
              "chunks accumulate in the real call record")
          (is (= "done" (-> (get-in completed [:state :actors :tenures
                                               "fleet-swarm-1" :slots :slot-a
                                               :context])
                            last :text))
              "completion appends the assistant text to the slot context")
          (is (= 3 (count (:actors (actors/board (:state completed))))))
          (let [next-turn (actors/wake-all (:state completed))
                next-effects (filter #(= :model/start (:type %))
                                     (:effects next-turn))]
            (is (:ok? next-turn))
            (is (= 1 (count next-effects))
                "only the completed slot becomes idle and wakes again")
            (is (= "slot-a" (name (:slot-id (first next-effects)))))
            (is (= 3 (count (:actors (actors/board (:state next-turn))))))))))))

(deftest a-tool-result-is-appended-and-the-next-turn-starts
  (support/with-temp-dir [root "hc-fleet-actors-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [rt (start-runtime! repo root "swarm-1")
            started (actors/start! {:runtime rt
                                    :models {:main "main" :task "task"}
                                    :slot-ids [:slot-a :slot-b]})
            model-effect (first (filter #(= :model/start (:type %))
                                        (:effects started)))
            attempt-id (:attempt-id model-effect)
            continued (actors/continue-after-tool!
                       (:state started) attempt-id
                       {:event :tool-call
                        :call-id "tool-1"
                        :name "cat"
                        :output-index 0}
                       {:status :succeeded :value 7})
            next-effects (filter #(= :model/start (:type %))
                                 (:effects continued))
            context (get-in continued [:state :actors :tenures
                                       "fleet-swarm-1" :slots :slot-a :context])
            tool-message (last (filter #(= :tool (:role %)) context))]
        (is (:ok? continued))
        (is (= 1 (count next-effects)))
        (is (= :tool (:role tool-message)))
        (is (= "tool-1" (:tool-call-id tool-message)))
        (is (re-find #"7" (:text tool-message)))
        (is (= 3 (count (:actors (actors/board (:state continued))))))))))

(deftest actors-require-real-models
  (support/with-temp-dir [root "hc-fleet-actors-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [rt (start-runtime! repo root "swarm-1")
            started (actors/start! {:runtime rt :models nil})]
        (is (false? (:ok? started)))
        (is (= :INVALID_MODELS (get-in started [:error :code])))))))

(defn- concurrent-board-task!
  [rt]
  (swarm/execute!
   (:kernel rt)
   {:op :create-task
    :actor-id (:control-agent-id rt)
    :goal "concurrent board work"
    :remaining-work "keep this task"
    :done-when "the task is visible"}))

(defn- concurrent-task?
  [board]
  (some #(= "concurrent board work" (:goal %)) (vals (:tasks board))))

(deftest a-concurrent-board-command-survives-a-completion
  (support/with-temp-dir [root "hc-fleet-actors-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [rt (start-runtime! repo root "swarm-1")
            started (actors/start! {:runtime rt
                                    :models {:main "main" :task "task"}
                                    :slot-ids [:slot-a :slot-b]})
            attempt-id (->> (:effects started)
                            (filter #(= :model/start (:type %)))
                            first
                            :attempt-id)
            slot-id (get-in (:state started)
                            [:actors :calls attempt-id :slot-id])
            committed (atom nil)
            original mission-actors/accept-model-event]
        (with-redefs [mission-actors/accept-model-event
                      (fn [mission actors attempt-id event]
                        (when-not @committed
                          (reset! committed (concurrent-board-task! rt)))
                        (original mission actors attempt-id event))]
          (let [completed (actors/accept-event
                           (:state started) attempt-id
                           {:event :completed :text "done"})
                board (actors/board (:state completed))]
            (is (:ok? completed))
            (is (:ok? @committed))
            (is (concurrent-task? board)
                "the direct board command is not overwritten by the completion")
            (is (= (:task-id @committed)
                   (:task-id (first (filter #(= "concurrent board work"
                                                (:goal %))
                                            (vals (:tasks board)))))))
            (is (= "done"
                   (-> (get-in completed
                               [:state :actors :tenures "fleet-swarm-1"
                                :slots slot-id :context])
                       last
                       :text)))))))))

(deftest a-concurrent-board-command-survives-a-tool-continuation
  (support/with-temp-dir [root "hc-fleet-actors-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [rt (start-runtime! repo root "swarm-1")
            started (actors/start! {:runtime rt
                                    :models {:main "main" :task "task"}
                                    :slot-ids [:slot-a :slot-b]})
            attempt-id (->> (:effects started)
                            (filter #(= :model/start (:type %)))
                            first
                            :attempt-id)
            committed (atom nil)
            original mission-actors/wake-all]
        (with-redefs [mission-actors/wake-all
                      (fn [mission actors tenure-id]
                        (when-not @committed
                          (reset! committed (concurrent-board-task! rt)))
                        (original mission actors tenure-id))]
          (let [continued (actors/continue-after-tool!
                           (:state started) attempt-id
                           {:event :tool-call
                            :call-id "tool-1"
                            :name "cat"
                            :output-index 0}
                           {:status :succeeded :value 7})
                board (actors/board (:state continued))]
            (is (:ok? continued))
            (is (:ok? @committed))
            (is (concurrent-task? board)
                "the direct board command is not overwritten by the wake")
            (is (some #(= :model/start (:type %)) (:effects continued)))))))))

(deftest a-late-terminal-does-not-clear-the-next-attempt
  (support/with-temp-dir [root "hc-fleet-actors-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [rt (start-runtime! repo root "swarm-1")
            started (actors/start! {:runtime rt
                                    :models {:main "main" :task "task"}
                                    :slot-ids [:slot-a :slot-b]})
            attempt-a (->> (:effects started)
                           (filter #(= :model/start (:type %)))
                           first
                           :attempt-id)
            completed (actors/accept-event
                       (:state started) attempt-a
                       {:event :completed :text "first"})
            woken (actors/wake-all (:state completed))
            next-attempt (->> (:effects woken)
                              (filter #(= :model/start (:type %)))
                              first
                              :attempt-id)
            late (actors/accept-event
                  (:state woken) attempt-a
                  {:event :completed :text "late"})
            slot (get-in late [:state :actors :tenures "fleet-swarm-1"
                               :slots :slot-a])]
        (is (:ok? completed))
        (is (:ok? woken))
        (is (some? next-attempt))
        (is (:ok? late))
        (is (= :stale-terminal
               (get-in late [:state :actors :calls attempt-a :status])))
        (is (= next-attempt (:attempt-id slot))
            "the late terminal did not clear the running next attempt")
        (is (= ["first"]
               (->> (:context slot)
                    (filter #(= :assistant (:role %)))
                    (mapv :text)))
            "the late terminal did not append to the new context")))))

(deftest a-tool-call-is-claimed-once-and-a-terminal-waits-for-its-result
  (support/with-temp-dir [root "hc-fleet-actors-"]
    (let [repo (str root "/repo")]
      (.mkdirs (java.io.File. repo))
      (support/init-repo! repo)
      (let [rt (start-runtime! repo root "swarm-1")
            started (actors/start! {:runtime rt
                                    :models {:main "main" :task "task"}
                                    :slot-ids [:slot-a :slot-b]})
            attempt-a (->> (:effects started)
                           (filter #(= :model/start (:type %)))
                           first
                           :attempt-id)
            event {:event :tool-call
                   :call-id "tool-1"
                   :name "cat"
                   :output-index 0}
            claimed (actors/claim-tool-call
                     (:state started) attempt-a event)
            claimed-again (actors/claim-tool-call
                           (:state claimed) attempt-a event)
            terminal (actors/accept-event
                      (:state claimed) attempt-a
                      {:event :completed :text "late"})
            continued (actors/continue-after-tool!
                       (:state terminal) attempt-a event
                       {:status :succeeded :value 7})
            replay (actors/continue-after-tool!
                    (:state continued) attempt-a event
                    {:status :succeeded :value 7})]
        (is (:ok? claimed))
        (is (= :executing
               (get-in claimed [:state :actors :calls attempt-a
                                :tool-call-status])))
        (is (= :TOOL_CALL_ALREADY_CLAIMED
               (get-in claimed-again [:error :code]))
            "a second claim cannot run the side effect again")
        (is (:ok? terminal))
        (is (= :stale-terminal
               (get-in terminal [:state :actors :calls attempt-a :status]))
            "a terminal does not clear a turn that ended in a tool call")
        (is (= attempt-a
               (get-in terminal [:state :actors :tenures "fleet-swarm-1"
                                 :slots :slot-a :attempt-id]))
            "the tool continuation still owns the slot")
        (is (:ok? continued))
        (is (= 1 (count (filter #(= :model/start (:type %))
                                (:effects continued))))
            "the tool result starts exactly one next turn")
        (is (= :STALE_ATTEMPT (get-in replay [:error :code]))
            "a replayed result cannot start a second turn")))))
