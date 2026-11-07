(ns hc.core.swarm-stm-responsibility-property-test
  "Property tests for the board responsibility set R(a): unfinished ordinary
  tasks, all unfinished token tasks while A is holder, and A's active
  actions.  Waiting tasks remain in R(a); :done tasks do not."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.core.swarm-stm.board :as board]))

(def ^:private active-phases
  #{:applying :running :uncertain})

(defn- task
  [id state owner needs-token]
  {:task-id id :state state :owner-id owner :needs-token needs-token})

(defn- action
  [id agent phase]
  {:action-id id :agent-id agent :phase phase})

(defn- board-of
  [tasks actions holder]
  {:tasks (into {} (map (juxt :task-id identity) tasks))
   :actions (into {} (map (juxt :action-id identity) actions))
   :token-slot {:holder-id holder :state (if holder :held :free)}})

(defn- expected
  [tasks actions holder agent]
  (into #{}
        (concat
         (for [t tasks
               :when (and (not= :done (:state t))
                          (not (:needs-token t))
                          (= agent (:owner-id t)))]
           (:task-id t))
         (when (= holder agent)
           (for [t tasks
                 :when (and (not= :done (:state t)) (:needs-token t))]
             (:task-id t)))
         (for [a actions
               :when (and (= agent (:agent-id a))
                          (contains? active-phases (:phase a)))]
           (:action-id a)))))

(defspec responsibility-matches-authoritative-records 50
  (prop/for-all [task-specs (gen/vector
                             (gen/tuple (gen/elements [:ready :waiting :done])
                                        (gen/elements [:a :b])
                                        gen/boolean)
                             0 8)
                 action-specs (gen/vector
                               (gen/tuple (gen/elements [:a :b])
                                          (gen/elements
                                           [:claimed :preparing :waiting-gate
                                            :applying :running :finished
                                            :uncertain]))
                               0 6)
                 holder (gen/elements [nil :a :b])]
    (let [tasks (mapv (fn [i [state owner needs-token]]
                        (task (keyword (str "t" i)) state owner needs-token))
                      (range) task-specs)
          actions (mapv (fn [i [agent phase]]
                          (action (keyword (str "e" i)) agent phase))
                        (range) action-specs)
          b (board-of tasks actions holder)]
      (doseq [agent [:a :b]]
        (let [actual (board/responsibility b agent)]
          (assert (= (expected tasks actions holder agent) actual)
                  (str "R(" agent ") mismatch: " (pr-str actual)))))
      true)))
