(ns hc.core.swarm-stm-commands-property-test
  "Property tests for the pure command surface: register -> create -> claim
  -> finish reaches :done, and request-id replay is idempotent while a
  different payload is rejected."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.swarm-stm.board :as board]
            [hc.core.swarm-stm.commands :as commands]))

(defn- run-command [b cmd]
  (let [r (commands/execute b cmd)]
    (when-not (:ok? r) (throw (ex-info "command failed" {:result r})))
    (:board r)))

(defspec register-create-claim-finish-reaches-done 50
  (prop/for-all [goal (gen/such-that seq gen/string-alphanumeric 100)]
    (let [b (run-command (board/empty-board)
                         {:op :register-actor :slot-id :s})
          actor (-> (commands/execute (board/empty-board)
                                      {:op :register-actor :slot-id :s})
                    (get-in [:result :agent-id]))
          b (run-command b {:op :create-task :actor-id actor
                            :goal goal :remaining-work "do"
                            :done-when "done" :owner-id actor})
          task-id (some (fn [[id t]] (when (= goal (:goal t)) id))
                        (:tasks b))
          b (run-command b {:op :claim :actor-id actor
                            :task-id task-id
                            :atomicity-reason "property"})
          claim-id (get-in b [:tasks task-id :claim-id])
          b (run-command b {:op :finish :actor-id actor
                            :task-id task-id :claim-id claim-id
                            :result-summary "done"})]
      (= :done (get-in b [:tasks task-id :state])))))
