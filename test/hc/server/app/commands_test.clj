(ns hc.server.app.commands-test
  "Router-level checks for the trusted Steward and holder program planes.

  Both planes are app-scoped and must be unreachable from a browser-shaped
  request.  The Steward commands exercise the real pure composition layer;
  the store is the only fake."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.fleet.service :as fleet-service]
            [hc.compose.owner.store :as owner-store]
            [hc.compose.steward :as steward]
            [hc.server.app.commands :as commands]
            [hc.server.gateway :as gateway]
            [hc.server.gateway.ports :as ports]
            [hc.server.sessions.registry :as registry]))

(defrecord MemoryStewardStore [cell]
  owner-store/Store
  (load-state [_] @cell)
  (commit! [_ expected-version next-state]
    (let [current @cell]
      (if (= (:version current) expected-version)
        (let [next {:version (inc (long (:version current)))
                    :state next-state}]
          (reset! cell next)
          {:ok? true :version (:version next)})
        {:ok? false
         :error {:code :VERSION_CONFLICT
                 :message "stored version does not match the expected version"
                 :details {:expected expected-version
                           :actual (:version current)}}})))
  (close! [_] {:ok? true :closed? true}))

(defn- memory-steward-store
  "A Store holding STATE at version 1, or empty at version 0."
  ([] (memory-steward-store nil))
  ([state]
   (->MemoryStewardStore (atom {:version (if state 1 0) :state state}))))

(defn- make-router
  ([] (make-router {}))
  ([opts]
   (commands/router (merge {:registry nil} opts))))

(defn- submit!
  [router request]
  (let [reply (promise)]
    (ports/submit-command! router request #(deliver reply %))
    (let [result (deref reply 3000 ::timeout)]
      (if (= ::timeout result)
        (throw (ex-info "command router did not reply" {:request request}))
        result))))

(defn- commit
  [commit-id]
  {:kind :git-commit
   :commit commit-id
   :parents []
   :changed-paths ["src/hc"]
   :subject (str "commit " commit-id)})

(def ^:private candidate
  {:candidate-id "candidate-1"
   :title "Improve reliability"
   :consequence 0.9
   :evidence-strength 0.9
   :dependency-cost 0.1
   :reversibility 0.9
   :opportunity-cost 0.1
   :demand 1
   :evidence-ids ["git:aaa"]
   :operator-aligned? true})

(defn- frontier-state
  "A Steward state with one observed commit, an intent, and a frontier."
  []
  (let [initial (steward/initial-state)
        observed (:state (steward/observe-world
                          initial
                          {:git-commits [(commit "aaa")]
                           :intent {:revision 1
                                    :text "Keep the repository healthy"
                                    :received-at 100}
                           :capacity {:slots-total 4
                                      :slots-allocated 4
                                      :min-spare 1
                                      :missions []}
                           :now 100}))]
    (:state (steward/evaluate-frontier observed))))

(deftest steward-commands-need-the-trusted-envelope
  (let [router (make-router {:steward-ref (atom (memory-steward-store
                                                 (steward/initial-state)))})]
    (testing "a browser-shaped request never reaches the Steward"
      (let [result (submit! router
                            {:command-id "browser-1"
                             :command-epoch (commands/app-command-epoch router)
                             :payload {:kind :steward/state}})]
        (is (false? (:ok? result)))
        (is (= :STEWARD_FORBIDDEN (get-in result [:error :code])))))
    (testing "an unknown steward kind is refused explicitly"
      (let [result (submit! router
                            (commands/steward-command-envelope
                             "kind-1" (commands/app-command-epoch router)
                             {:kind :steward/unknown}))]
        (is (false? (:ok? result)))
        (is (= :COMMAND_KIND_UNKNOWN (get-in result [:error :code])))))
    (testing "a trusted request without an assembled Steward is unavailable"
      (let [bare (make-router)
            result (submit! bare
                            (commands/steward-command-envelope
                             "bare-1" (commands/app-command-epoch bare)
                             {:kind :steward/state}))]
        (is (false? (:ok? result)))
        (is (= :STEWARD_UNAVAILABLE (get-in result [:error :code])))))))

(deftest steward-and-program-kinds-are-browser-forbidden
  (doseq [kind commands/steward-command-kinds]
    (is (contains? gateway/default-forbidden-browser-kinds kind)
        (str kind " must stay on the trusted tool plane")))
  (is (contains? gateway/default-forbidden-browser-kinds :program/abandon)
      "a browser must not originate a holder abandon"))

(deftest trusted-steward-can-read-and-decide
  (let [store (memory-steward-store (frontier-state))
        router (make-router {:steward-ref (atom store)})
        epoch (commands/app-command-epoch router)]
    (testing "a trusted state read returns the bounded snapshot"
      (let [result (submit! router
                            (commands/steward-command-envelope
                             "state-1" epoch {:kind :steward/state}))]
        (is (true? (:ok? result)) (pr-str result))
        (is (= :accepted (get-in result [:receipt :status])))
        (is (= "steward" (get-in result [:receipt :steward :steward-id])))
        (is (= "frontier-1"
               (get-in result [:receipt :steward
                               :frontier :frontier-id])))))
    (testing "a decision commits and replays by request id"
      (let [first (submit! router
                           (commands/steward-command-envelope
                            "decide-1" epoch
                            {:kind :steward/decide
                             :request-id "request-1"
                             :candidates [candidate]}))
            first-decision (get-in first [:receipt :result :decision])
            second (submit! router
                            (commands/steward-command-envelope
                             "decide-2" epoch
                             {:kind :steward/decide
                              :request-id "request-1"
                              :candidates [candidate]}))]
        (is (true? (:ok? first)) (pr-str first))
        (is (= :admit (:outcome first-decision)))
        (is (= ["mission-1"] (:admitted first-decision)))
        (is (true? (:ok? second)) (pr-str second))
        (is (= first-decision (get-in second [:receipt :result :decision])))
        (is (true? (get-in second [:receipt :result :replayed?])))
        (is (= ["mission-1"]
               (keys (get-in (:state @(:cell store)) [:links])))
            "the replay did not create a second mission")))))

(deftest steward-transition-moves-the-docket-once
  (let [decided (:state
                 (steward/decide (frontier-state)
                                 {:request-id "request-1"
                                  :candidates [candidate]}))
        store (memory-steward-store decided)
        router (make-router {:steward-ref (atom store)})
        epoch (commands/app-command-epoch router)
        result (submit! router
                        (commands/steward-command-envelope
                         "transition-1" epoch
                         {:kind :steward/transition
                          :mission-id "mission-1"
                          :to :planning
                          :reason "plan first"}))]
    (is (true? (:ok? result)) (pr-str result))
    (is (= :planning
           (get-in result [:receipt :result :mission :status])))
    (is (= :planning
           (get-in (:state @(:cell store))
                   [:docket :missions "mission-1" :status])))))

(deftest program-abandon-uses-the-host-workspace
  (let [calls (atom [])
        router (make-router {:fleet-ref (atom ::fleet)})
        epoch (commands/app-command-epoch router)]
    (with-redefs [fleet-service/abandon-by-workspace!
                  (fn [service workspace reason]
                    (swap! calls conj [service workspace reason])
                    {:ok? true
                     :kind :abandon
                     :swarm-id "swarm-1"
                     :status :abandoned
                     :reason reason})]
      (testing "a browser-shaped program request is refused"
        (let [result (submit! router
                              {:command-id "program-browser-1"
                               :command-epoch epoch
                               :payload {:kind :program/abandon
                                         :reason "conflict"}})]
          (is (false? (:ok? result)))
          (is (= :PROGRAM_FORBIDDEN (get-in result [:error :code])))
          (is (empty? @calls))))
      (testing "a holder request abandons by workspace, never by model id"
        (let [result (submit! router
                              (commands/program-command-envelope
                               "abandon-1" epoch "/work/slice-a"
                               {:kind :program/abandon
                                :reason "rebase conflict"
                                :swarm-id "model-chosen"}))]
          (is (true? (:ok? result)) (pr-str result))
          (is (= :accepted (get-in result [:receipt :status])))
          (is (= "swarm-1" (get-in result [:receipt :result :swarm-id])))
          (is (= :abandoned (get-in result [:receipt :result :status])))
          (is (false? (:tenure-abandoned? result)))
          (is (some #(= :TENURE_ABANDON_UNAVAILABLE (:code %))
                    (:diagnostics result))
              "a missing mission handle is reported without undoing the fleet abandon")
          (is (= [[::fleet "/work/slice-a" "rebase conflict"]]
                 @calls)
              "the workspace and reason are forwarded; :swarm-id is ignored")))
      (testing "a trusted request without a workspace is refused"
        (let [result (submit! router
                              (commands/program-command-envelope
                               "abandon-2" epoch nil
                               {:kind :program/abandon
                                :reason "conflict"}))]
          (is (false? (:ok? result)))
          (is (= :PROGRAM_WORKSPACE_REQUIRED
                 (get-in result [:error :code]))))))))

(deftest program-abandon-needs-a-running-fleet
  (let [router (make-router)
        result (submit! router
                        (commands/program-command-envelope
                         "abandon-3" (commands/app-command-epoch router)
                         "/work/slice-a"
                         {:kind :program/abandon
                          :reason "conflict"}))]
    (is (false? (:ok? result)))
    (is (= :FLEET_UNAVAILABLE (get-in result [:error :code])))))

(deftest program-abandon-marks-the-mission-tenure
  (let [fleet-calls (atom [])
        mission-calls (atom [])
        router (make-router
                {:fleet-ref (atom ::fleet)
                 :mission-ref
                 (atom (fn [request]
                         (swap! mission-calls conj request)
                         {:ok? true
                          :tenure-id "fleet-swarm-1"
                          :state {}}))})
        epoch (commands/app-command-epoch router)]
    (with-redefs [fleet-service/abandon-by-workspace!
                  (fn [service workspace reason]
                    (swap! fleet-calls conj [service workspace reason])
                    {:ok? true
                     :kind :abandon
                     :swarm-id "swarm-1"
                     :status :abandoned
                     :reason reason})
                  fleet-service/runtime-for-workspace
                  (fn [_service _workspace] {:slice-id "slice-a"})]
      (let [result (submit! router
                            (commands/program-command-envelope
                             "abandon-mission-1" epoch "/work/slice-a"
                             {:kind :program/abandon
                              :reason "rebase conflict"}))]
        (is (true? (:ok? result)) (pr-str result))
        (is (true? (:tenure-abandoned? result)))
        (is (= "fleet-swarm-1" (:tenure-id result)))
        (is (true? (get-in result [:receipt :result :tenure-abandoned?])))
        (is (= [{:swarm-id "swarm-1"
                 :slice-id "slice-a"
                 :reason "rebase conflict"}]
               (mapv #(select-keys % [:swarm-id :slice-id :reason])
                     @mission-calls)))
        (is (= 1 (count @fleet-calls)))
        (is (= 1 (count @mission-calls)))))))

;; ---------------------------------------------------------------------------
;; History full-text search wiring

(defn- untrusted-history-handlers
  [calls]
  {:history/search
   (fn [payload]
     (swap! calls conj [:search payload])
     {:status :ok
      :payload {:mode :search
                :query (:query payload)
                :hits []
                :count 0
                :truncated? false
                :index-generation 1}})
   :history/search-status
   (fn [payload]
     (swap! calls conj [:status payload])
     {:status :ok :payload {:state :ready :generation 1}})
   :history/reindex
   (fn [_]
     (swap! calls conj [:untrusted-reindex])
     {:status :error
      :error {:code :REINDEX_REQUIRES_TRUSTED_CALLER
              :message "reindex requires a trusted server-side caller"}})})

(defn- trusted-history-handlers
  [calls]
  {:history/reindex
   (fn [payload]
     (swap! calls conj [:reindex payload])
     {:status :ok
      :payload {:generation 7 :session-count 3}})})

(deftest history-search-uses-the-injected-untrusted-handlers
  (let [calls (atom [])
        router (make-router {:history-search-handlers
                             (untrusted-history-handlers calls)})
        result (submit! router
                        {:command-id "history-search-1"
                         :command-epoch (commands/app-command-epoch router)
                         :payload {:kind :history/search
                                   :query "migration"}})]
    (is (true? (:ok? result)) (pr-str result))
    (is (= :accepted (get-in result [:receipt :status])))
    (is (= :ok (get-in result [:receipt :result :status])))
    (is (= "migration"
           (get-in result [:receipt :result :payload :query])))
    (is (= [[:search {:kind :history/search :query "migration"}]]
           @calls)
        "the app-scoped search path calls the injected handler")))

(deftest history-search-handler-error-is-projected
  (let [router (make-router
                {:history-search-handlers
                 {:history/search
                  (fn [_]
                    {:status :error
                     :error {:code :SEARCH_QUERY_INVALID
                             :message "query must be a non-blank string"}})}})
        result (submit! router
                        {:command-id "history-search-2"
                         :command-epoch (commands/app-command-epoch router)
                         :payload {:kind :history/search :query ""}})]
    (is (false? (:ok? result)))
    (is (= :SEARCH_QUERY_INVALID (get-in result [:error :code])))
    (is (= "query must be a non-blank string"
           (get-in result [:error :message])))))

(deftest history-reindex-requires-a-trusted-envelope
  (let [calls (atom [])
        router (make-router
                {:history-search-handlers
                 (untrusted-history-handlers calls)
                 :history-search-trusted-handlers
                 (trusted-history-handlers calls)})
        epoch (commands/app-command-epoch router)
        refused (submit! router
                         {:command-id "reindex-browser-1"
                          :command-epoch epoch
                          :payload {:kind :history/reindex}})]
    (is (false? (:ok? refused)))
    (is (= :HISTORY_REINDEX_FORBIDDEN (get-in refused [:error :code])))
    (is (empty? @calls)
        "a refused caller must not reach either reindex handler")
    (let [accepted (submit! router
                            (commands/steward-command-envelope
                             "reindex-trusted-1" epoch
                             {:kind :history/reindex}))]
      (is (true? (:ok? accepted)) (pr-str accepted))
      (is (= :accepted (get-in accepted [:receipt :status])))
      (is (= :ok (get-in accepted [:receipt :result :status])))
      (is (= 7 (get-in accepted [:receipt :result :payload :generation])))
      (is (= [[:reindex {:kind :history/reindex}]] @calls)
          "only the trusted handler serves a trusted reindex"))))

(deftest history-reindex-is-browser-forbidden-but-search-is-not
  (is (contains? gateway/default-forbidden-browser-kinds :history/reindex))
  (is (not (contains? gateway/default-forbidden-browser-kinds
                      :history/search)))
  (is (not (contains? gateway/default-forbidden-browser-kinds
                      :history/search-status))))

(deftest history-search-result-survives-the-gateway-projection
  (let [calls (atom [])
        router (make-router {:history-search-handlers
                             (untrusted-history-handlers calls)})
        request {:command-id "history-search-projection-1"
                 :command-epoch (commands/app-command-epoch router)
                 :payload {:kind :history/search :query "migration"}}
        result (submit! router request)
        projected ((var-get #'gateway/public-command-result)
                   request result)]
    (is (true? (:ok? projected)) (pr-str projected))
    (is (= :accepted (get-in projected [:receipt :status])))
    (is (= :ok (get-in projected [:receipt :result :status])))
    (is (= [] (get-in projected [:receipt :result :payload :hits])))
    (is (= "migration"
           (get-in projected [:receipt :result :payload :query])))
    (is (= 1 (get-in projected [:receipt :result :payload
                                :index-generation])))))

(deftest history-branch-keeps-its-dedicated-handler
  (let [calls (atom [])
        router (make-router {:registry ::registry
                             :history-search-handlers
                             (untrusted-history-handlers calls)})
        result (with-redefs [registry/branch-session!
                             (fn [_ source _options]
                               (swap! calls conj [:branch source])
                               {:status :error
                                :error {:code :SESSION_NOT_FOUND
                                        :message "session does not exist"}})]
                 (submit! router
                          {:command-id "history-branch-1"
                           :command-epoch 1
                           :payload {:kind :history/branch
                                     :session-id "missing-session"
                                     :version 1}}))]
    (is (false? (:ok? result)))
    (is (= :SESSION_NOT_FOUND (get-in result [:error :code])))
    (is (= [[:branch "missing-session"]] @calls))
    (is (not-any? #(= :search (first %)) @calls)
        ":history/branch must not enter the search dispatch branch")))
