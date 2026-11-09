(ns hc.server.app.runtime-assembly-contract-test
  "Contract guards for the application assembly assumptions.

  The static checks read the source text and assert only existence and
  relative order: they are mechanical drift guards, not a substitute for the
  compiler.  The runtime checks build a minimal fake system and exercise
  `system/stop!` itself, including the Store-close gate."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.service :as fleet]
            [hc.compose.supervisor :as supervisor]
            [hc.server.app.background-loops :as background-loops]
            [hc.server.app.fleet-gc :as fleet-gc]
            [hc.server.app.retention-gc :as retention-gc]
            [hc.server.app.steward-runtime :as steward-runtime]
            [hc.server.app.system :as system]
            [hc.server.storage :as storage]))

(defn- source-text
  [path]
  (let [resource (io/resource path)]
    (when-not resource
      (throw (ex-info "source resource is missing" {:path path})))
    (slurp resource)))

(defn- count-in
  [^String source ^String needle]
  (loop [from 0
         total 0]
    (let [at (.indexOf source needle (int from))]
      (if (neg? at)
        total
        (recur (+ at (count needle)) (inc total))))))

(defn- position-in
  [^String source ^String needle]
  (let [at (.indexOf source needle)]
    (is (not (neg? at)) (str "source is missing " (pr-str needle)))
    at))

(defn- stop!-source
  []
  (let [source (source-text "hc/server/app/system.clj")
        at (.indexOf ^String source "(defn stop!")]
    (is (not (neg? at)) "system.clj is missing (defn stop!)")
    (subs source at)))

(defn- arglist-option-keys
  [var]
  (->> (:arglists (meta var))
       (mapcat (fn [args]
                 (let [first-arg (first args)]
                   (when (map? first-arg)
                     (:keys first-arg)))))
       (map keyword)
       set))

(deftest stop-closes-the-store-after-background-writers
  (let [source (stop!-source)
        store-close (position-in source "(storage/open-close-store!")
        supervisor-stop (position-in source "(supervisor/stop!")
        steward-stop (position-in source "(steward-runtime/stop!")
        retention-stop (position-in source "(retention-gc/stop!")]
    ;; Text guard only: these calls exist in stop! and the first Store close
    ;; follows all three.  It does not prove runtime control flow.
    (is (< supervisor-stop store-close))
    (is (< steward-stop store-close))
    (is (< retention-stop store-close))))

(deftest retention-is-assembled-in-exactly-one-mode
  (let [source (source-text "hc/server/app/system.clj")
        direct-start (count-in source "retention-gc/start!")
        child-spec (count-in source "retention-gc-child")]
    (is (or (and (= 1 direct-start) (zero? child-spec))
            (and (zero? direct-start) (= 1 child-spec)))
        "retention must be either directly started or a supervisor child, once")
    (is (not (and (pos? direct-start) (pos? child-spec)))
        "retention must not be both directly started and a supervisor child")
    (when (pos? direct-start)
      (is (= 1 (count-in source "retention-gc/stop!"))
          "a directly started retention must be directly stopped"))))

(deftest steward-loop-uses-one-adapter
  (let [source (source-text "hc/server/app/system.clj")
        background-adapter
        (count-in source "background-loops/steward-loop-child")
        runtime-child-spec
        (count-in source "steward-runtime/child-spec")]
    (is (= 1 (+ background-adapter runtime-child-spec))
        "exactly one Steward loop adapter must be installed")
    (is (not (and (pos? background-adapter) (pos? runtime-child-spec)))
        "both Steward adapters would double-tick one runtime")))

(deftest retention-start-option-keys-match-the-real-arglists
  (let [start-var (ns-resolve 'hc.server.app.retention-gc 'start!)
        declared (set background-loops/retention-start-opt-keys)]
    (is (some? start-var) "retention-gc/start! must resolve")
    (is (= declared (arglist-option-keys start-var))
        "background_loops' allow-list must equal start!'s destructured keys")))

(defn- run-fake-stop
  [{:keys [supervisor-result steward-result retention-result
           fleet-gc-result fleet-result]
    :or {supervisor-result {:ok? true
                            :stopped [:steward-loop]
                            :timed-out []}
         steward-result {:ok? true
                         :stopped? true
                         :terminated? true
                         :store-closed? false}
         retention-result {:ok? true
                           :stopped? true
                           :terminated? true}
         fleet-gc-result {:ok? true
                          :stopped? true
                          :terminated? true}
         fleet-result {:ok? true
                       :stopped? true}}}]
  (let [stops (atom [])
        store {:open? (atom true)
               :close-calls (atom 0)}
        fake-system {:blocked? false
                     :partial? false
                     :supervisor {:fake true}
                     :steward-runtime {:fake true}
                     :retention-gc {:fake true}
                     :fleet-gc {:fake true}
                     :fleet {:fake true}
                     :store store
                     :maintenance {:supervisor-stop-timeout-ms 50
                                   :drain-timeout-ms 50}}]
    (with-redefs [storage/expect-ordered-close! (fn [_store] nil)
                  storage/store-open? (fn [_store] @(:open? store))
                  storage/open-close-store!
                  (fn [_store _opts]
                    (swap! (:close-calls store) inc)
                    (swap! stops conj :store)
                    (reset! (:open? store) false)
                    {:closed? true})
                  supervisor/stop!
                  (fn [_supervisor _opts]
                    (swap! stops conj :supervisor)
                    supervisor-result)
                  steward-runtime/stop!
                  (fn [_runtime]
                    (swap! stops conj :steward)
                    steward-result)
                  retention-gc/stop!
                  (fn [_service]
                    (swap! stops conj :retention)
                    retention-result)
                  fleet-gc/stop!
                  (fn [_service]
                    (swap! stops conj :fleet-gc)
                    fleet-gc-result)
                  fleet/stop!
                  (fn [_service]
                    (swap! stops conj :fleet)
                    fleet-result)]
      {:result (system/stop! fake-system)
       :stops @stops
       :store store})))

(deftest stop-runs-background-writers-before-the-store
  (let [{:keys [result stops store]} (run-fake-stop {})]
    (is (true? (:stopped? result)))
    (is (true? (:store-closed? result)))
    (is (empty? (:failures result)))
    ;; The Steward is stopped by the supervisor child; the supervisor path
    ;; synthesizes its termination evidence instead of a second direct stop.
    (let [positions (zipmap stops (range))]
      (is (some? (:supervisor positions)))
      (is (< (:supervisor positions) (:retention positions)))
      (is (< (:retention positions) (:fleet-gc positions)))
      (is (< (:fleet-gc positions) (:fleet positions)))
      (is (< (:fleet positions) (:store positions)))
      (is (not (some #{:steward} stops))
          "no independent Steward stop event on the supervisor path"))
    (is (= :supervisor
           (get-in result [:components :steward-runtime :via])))
    (is (true? (get-in result [:components :steward-runtime :terminated?])))
    (is (= 1 @(:close-calls store)))
    (doseq [component [:supervisor :steward-runtime :retention-gc
                       :fleet-gc :fleet :store]]
      (is (some? (get-in result [:components component]))
          (str "missing component evidence for " component)))))

(deftest a-failed-child-stop-is-visible-and-keeps-the-store-open
  (let [{:keys [result stops store]}
        (run-fake-stop
         {:supervisor-result {:ok? false
                                          :error
                              {:code :FAKE_SUPERVISOR_CHILD_STOP_FAILED}}})]
    (is (seq (:failures result)))
    (is (some #(= :supervisor (:component %)) (:failures result))
        "the supervisor child-stop failure must be visible")
    (is (false? (:store-closed? result)))
    (is (zero? @(:close-calls store)))
    (is (= :components-failed
           (get-in result [:store-close-skipped :reason])))
    (is (not (and (:store-closed? result) (seq (:failures result))))
        "a failed component must not be reported with a closed store")
    ;; A failed supervisor stop falls back to one explicit Steward stop; the
    ;; Store never closes.
    (let [positions (zipmap stops (range))]
      (is (< (:supervisor positions) (:steward positions)))
      (is (< (:steward positions) (:retention positions)))
      (is (< (:retention positions) (:fleet-gc positions)))
      (is (< (:fleet-gc positions) (:fleet positions)))
      (is (not (some #{:store} stops))))))

(deftest a-non-terminated-retention-stop-keeps-the-store-open
  (let [{:keys [result store]}
        (run-fake-stop
         {:retention-result {:ok? true
                             :stopped? true
                             :terminated? false}})]
    (is (seq (:failures result)))
    (is (some #(= :retention-gc (:component %)) (:failures result)))
    (is (false? (:store-closed? result)))
    (is (zero? @(:close-calls store)))
    (is (= :components-failed
           (get-in result [:store-close-skipped :reason])))))
