(ns hc.compose.scheduler.model-property-test
  "Property tests for the schedule state machine.

  A schedule moves only through the transitions declared by
  `hc.compose.scheduler.model`.  Illegal transitions must be refused without
  producing a schedule, and every accepted transition must land in the
  declared state set."
  (:refer-clojure :exclude [ensure])
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.scheduler.model :as model]
            [hc.compose.scheduler.support :as support]))

(def ^:private shanghai "Asia/Shanghai")

(def ^:private base-spec
  {:id "property-schedule"
   :name "Property schedule"
   :prompt "Summarize the workspace."
   :snapshot support/base-snapshot
   :execution {:mode :new-chat}
   :recurrence {:kind :weekly
                :days [1 3]
                :time {:hour 8 :minute 30}}
   :timezone {:mode :fixed :zone shanghai}})

(defn- new-schedule
  [state now]
  (model/new-schedule (assoc base-spec :state state :now now)))

(defn- ensure
  [pred message data]
  (when-not pred
    (throw (ex-info message data))))

(defn- apply-transition
  [schedule op now]
  (case op
    :enable (model/enable schedule now)
    :pause (model/pause schedule now)
    :update (model/update-schedule schedule {:prompt "updated body"} now)))

(defn- legal-transition?
  [state op]
  (case op
    :enable (contains? #{:draft :paused :needs-attention :enabled} state)
    :pause (contains? #{:enabled :needs-attention :paused} state)
    :update (contains? model/schedule-states state)))

(defn- expected-state
  [state op]
  (case op
    :enable :enabled
    :pause :paused
    :update state))

(defspec creation-accepts-only-declared-creation-states 50
  (prop/for-all [state (gen/elements (conj (vec model/schedule-states) :bogus))]
    (let [now (support/epoch shanghai "2026-11-03T09:00")
          result (model/new-schedule (assoc base-spec :state state :now now))
          allowed? (contains? model/creation-states state)]
      (and (= allowed? (boolean (:ok? result)))
           (if allowed?
             (and (= state (get-in result [:schedule :state]))
                  (contains? model/schedule-states
                             (get-in result [:schedule :state])))
             (nil? (:schedule result)))))))

(defspec transition-sequences-respect-the-declared-state-machine 100
  (prop/for-all [creation-state (gen/elements [:draft :paused])
                 ops (gen/vector (gen/elements [:enable :pause :update]) 1 12)]
    (let [now (support/epoch shanghai "2026-11-03T09:00")
          created (new-schedule creation-state now)]
      (ensure (:ok? created) "a declared creation state was refused"
              {:creation-state creation-state :result created})
      (loop [schedule (:schedule created)
             remaining ops
             step 0]
        (if-let [op (first remaining)]
          (let [before (:state schedule)
                legal? (legal-transition? before op)
                result (apply-transition schedule op now)
                accepted? (boolean (:ok? result))
                next-schedule (:schedule result)]
            (ensure (= legal? accepted?)
                    "the transition legality and acceptance disagree"
                    {:step step :op op :state before
                     :legal? legal? :result result})
            (if legal?
              (do
                (ensure (some? next-schedule)
                        "an accepted transition returned no schedule"
                        {:step step :op op :state before :result result})
                (ensure (contains? model/schedule-states (:state next-schedule))
                        "a transition produced an undeclared state"
                        {:step step :op op
                         :state (:state next-schedule)})
                (ensure (= (expected-state before op) (:state next-schedule))
                        "a transition landed in the wrong state"
                        {:step step :op op :before before
                         :after (:state next-schedule)})
                (recur next-schedule (rest remaining) (inc step)))
              (do
                (ensure (nil? next-schedule)
                        "a refused transition produced a schedule"
                        {:step step :op op :result result})
                (recur schedule (rest remaining) (inc step)))))
          true)))))
