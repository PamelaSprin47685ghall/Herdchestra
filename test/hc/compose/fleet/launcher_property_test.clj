(ns hc.compose.fleet.launcher-property-test
  "Property tests for the fleet launcher sequencing adapter.

  The launcher is an injected-effect dispatcher, not an executor.  The total
  function here is the returned `:launch-swarm!`; `make` itself keeps its
  documented precondition that `:start-effect!` is a function.  Failure
  results use the namespace's real shape `{:status :failed :error ...}`, not
  the `{:ok? false ...}` shape some callers might expect."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hc.compose.fleet.launcher :as launcher]))

(def ^:private gen-effect-type
  (gen/elements [:model/start
                 :model/cancel
                 :git/write
                 :process/fork
                 :process/join]))

(def ^:private gen-effect
  (gen/fmap (fn [[type attempt-id]]
              (cond-> {:type type}
                (some? attempt-id) (assoc :attempt-id attempt-id)))
            (gen/tuple gen-effect-type
                       (gen/fmap (fn [[present? text]]
                                   (when present? text))
                                 (gen/tuple gen/boolean
                                            gen/string-alphanumeric)))))

(def ^:private gen-effects
  (gen/vector gen-effect 0 6))

(def ^:private gen-actor
  (gen/fmap (fn [effects] {:effects effects})
            gen-effects))

(def ^:private gen-optional-actor
  (gen/fmap (fn [[absent? actor]]
              (when-not absent? actor))
            (gen/tuple gen/boolean gen-actor)))

(def ^:private gen-callback-behavior
  (gen/elements [:accept :reject :throw]))

(defn- invoke
  [actor start-behavior cancel-behavior observer-throws?]
  (let [starts (atom [])
        cancels (atom [])
        observed (atom [])
        start-effect! (fn [effect]
                        (swap! starts conj effect)
                        (case start-behavior
                          :accept true
                          :reject false
                          :throw (throw (ex-info "start failed"
                                                 {:effect effect}))))
        cancel-effect! (fn [effect]
                         (swap! cancels conj effect)
                         (case cancel-behavior
                           :accept true
                           :reject false
                           :throw (throw (ex-info "cancel failed" {}))))
        on-effect! (fn [effect]
                     (swap! observed conj effect)
                     (when observer-throws?
                       (throw (ex-info "observer failed" {}))))
        launch (launcher/make {:start-effect! start-effect!
                               :cancel-effect! cancel-effect!
                               :on-effect! on-effect!})
        result (try
                 (launch {:swarm-id "swarm-prop" :actor actor})
                 (catch Throwable throwable
                   {::thrown throwable}))]
    {:result result
     :starts starts
     :cancels cancels
     :observed observed}))

(defspec launched-effects-never-throw-and-have-a-tagged-shape 80
  (prop/for-all [actor gen-optional-actor
                 start-behavior gen-callback-behavior
                 cancel-behavior gen-callback-behavior
                 observer-throws? gen/boolean]
    (let [{:keys [result]} (invoke actor start-behavior cancel-behavior
                                   observer-throws?)]
      (and
       (not (contains? result ::thrown))
       (map? result)
       (if (nil? actor)
         (and (= :failed (:status result))
              (= :SWARM_ACTOR_MISSING (get-in result [:error :code])))
         (or (and (true? (:ok? result))
                  (contains? result :started))
             (= :SWARM_MODEL_START_FAILED
                (get-in result [:error :code]))))))))

(defspec launcher-forwards-only-model-effects-to-injected-callbacks 80
  (prop/for-all [effects gen-effects
                 observer-throws? gen/boolean]
    (let [{:keys [result starts cancels observed]}
          (invoke {:effects effects} :accept :accept observer-throws?)
          model-starts (filter #(= :model/start (:type %)) effects)
          model-cancels (filter #(= :model/cancel (:type %)) effects)
          forwarded (concat @starts @cancels)]
      (and
       (true? (:ok? result))
       (= (count model-starts) (:started result))
       (= (count model-cancels) (:cancelled result))
       (= (count effects) (:effects result))
       (= (count effects) (count @observed))
       (every? #(= :model/start (:type %)) @starts)
       (every? #(= :model/cancel (:type %)) @cancels)
       (every? #(= "swarm-prop" (:swarm-id %)) forwarded)
       (not-any? #(contains? #{:git/write :process/fork :process/join}
                             (:type %))
                 forwarded)))))

(deftest make-requires-an-injected-start-effect
  ;; This is the documented precondition, and it is the one case where
  ;; `make` throws instead of returning a launcher result.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires :start-effect!"
                        (launcher/make {}))))

(deftest launcher-failure-shape-is-explicit
  (let [launch (launcher/make {:start-effect! (fn [_effect] false)})
        result (launch {:swarm-id "swarm-failed"
                        :actor {:effects [{:type :model/start
                                           :attempt-id "a1"}]}})]
    (is (= :failed (:status result)))
    (is (= :SWARM_MODEL_START_FAILED (get-in result [:error :code])))
    (is (nil? (:ok? result)))))
