(ns hc.compose.mission.devops-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.devops :as devops]
            [hc.compose.mission.support :as support]
            [hc.core.ufss :as ufss]))

(defn scripted-model
  "A model gateway that plays one event vector per start request."
  [scripts]
  (let [requests (atom [])
        starts (atom 0)
        cancelled (atom [])]
    {:requests requests
     :cancelled cancelled
     :start! (fn [request deliver!]
               (swap! requests conj request)
               (let [index (swap! starts inc)
                     events (nth scripts (dec index) [])]
                 (future
                   (doseq [event events]
                     (deliver! (assoc event
                                      :attempt-id (:attempt-id request)
                                      :call-id (:call-id request)))))
                 {:attempt-id (:attempt-id request)
                  :call-id (:call-id request)}))
     :cancel! (fn [handle]
                (swap! cancelled conj handle))}))

(defn report-model
  [reports]
  (let [requests (atom [])
        counter (atom 0)]
    {:requests requests
     :start! (fn [request deliver!]
               (swap! requests conj request)
               (let [index @counter
                     report (nth reports index (last reports))]
                 (swap! counter inc)
                 (future
                   (deliver! {:event :chunk
                              :channel :text
                              :text report
                              :attempt-id (:attempt-id request)
                              :call-id (:call-id request)})
                   (deliver! {:event :completed
                              :text report
                              :attempt-id (:attempt-id request)
                              :call-id (:call-id request)})))
               {:attempt-id (:attempt-id request)
                :call-id (:call-id request)})
     :cancel! (fn [_] nil)}))

(deftest tool-surface-is-the-plan-worker-contract
  (is (= #{"cat" "stat" "ls" "where" "whereAll" "park" "unpark" "call"}
         (set (map :name devops/tool-specs))))
  (is (every? devops/allowed-tool?
              ["cat" "stat" "ls" "where" "whereAll" "park" "unpark" "call"]))
  (is (not (devops/allowed-tool? "write")))
  (is (not (devops/allowed-tool? "shell"))))

(deftest scope-schema-is-a-single-pointer
  (let [scope devops/scope-parameter-spec
        description (:description scope)]
    (is (= "Flat Scope annotation for this turn; see # Scope in the system prompt."
           description))
    (is (not (str/includes? description "reset-scope"))
        "the four kind rules stay in the system prompt, not the tool schema")
    (is (= #{:kind :new-scope-goal :new-scope-retention-budget :replacement}
           (set (keys (:properties scope)))))
    (is (= ["reset-scope" "keep-last" "remove-last" "me-too"]
           (get-in scope [:properties :kind :enum])))
    (is (= ["kind"] (:required scope)))))

(deftest task-model-is-enforced-before-any-run
  (let [model (scripted-model [])
        runner (devops/runner {:model model})
        result ((:run! runner)
                {:type :worker/spawn
                 :worker-id "worker-1"
                 :tenure-id "tenure-1"
                 :call-id "call-1"
                 :verbose-intent "inspect"
                 :model-role :main
                 :model-id "main-model"})]
    (is (= :failed (:status result)))
    (is (str/includes? (:report result) "task model"))
    (is (empty? @(:requests model)))))

(deftest synchronous-report-is-returned-verbatim-and-worker-is-reused
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        model (report-model ["first full report" "second full report"])
        runner (devops/runner {:model model})
        first-result (mission/devops! host {:tenure-id plan-id
                                            :call-id "devops-1"
                                            :verbose-intent "inspect the parser"
                                            :run (:run! runner)})
        first-worker (get-in first-result [:receipt :worker-id])
        second-result (mission/devops! host {:tenure-id plan-id
                                             :call-id "devops-2"
                                             :verbose-intent "inspect the tests"
                                             :run (:run! runner)})
        second-worker (get-in second-result [:receipt :worker-id])
        requests @(:requests model)
        worker (get-in (mission/snapshot host)
                       [:bindings :plan-worker :worker])]
    (is (:ok? first-result))
    (is (= :devops-completed (get-in first-result [:receipt :outcome])))
    (is (= "first full report" (get-in first-result [:receipt :report])))
    (is (= :task (get-in requests [0 :snapshot :role])))
    (is (= :plan-worker (get-in requests [0 :snapshot :mission-role])))
    (is (= :task (:model-role worker)))
    (is (= "task-model" (:model-id worker)))
    (is (= first-worker second-worker))
    (is (= "second full report" (get-in second-result [:receipt :report])))
    (is (> (count (get-in requests [1 :snapshot :messages]))
           (count (get-in requests [0 :snapshot :messages]))))
    (testing "the second request is a steer on the same worker context"
      (is (some #(= "inspect the tests" (:text %))
                (get-in requests [1 :snapshot :messages]))))))

(deftest tool-call-round-trip-and-forbidden-tool
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        calls (atom [])
        scripts [[{:event :completed
                   :text ""
                   :tool-calls [{:id "t1" :name "cat"
                                 :arguments {:path "README.md"}}]}]
                 [{:event :completed
                   :text "final after tool"}]]
        model (scripted-model scripts)
        runner (devops/runner
                {:model model
                 :tools {:execute! (fn [{:keys [name arguments]}]
                                     (swap! calls conj [name arguments])
                                     {:status :ok
                                      :text "README contents"})}})
        result (mission/devops! host {:tenure-id plan-id
                                      :call-id "devops-tool"
                                      :verbose-intent "read the README"
                                      :run (:run! runner)})
        requests @(:requests model)
        tool-message (some #(when (= :tool (:role %)) %)
                           (get-in requests [1 :snapshot :messages]))]
    (is (:ok? result))
    (is (= "final after tool" (get-in result [:receipt :report])))
    (is (= [["cat" {:path "README.md"}]] @calls))
    (is (= "README contents" (:text tool-message)))
    (is (false? (:error? tool-message)))))

(deftest forbidden-tool-is-reported-to-the-model-without-execution
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        called (atom [])
        scripts [[{:event :completed
                   :text ""
                   :tool-calls [{:id "t1" :name "write"
                                 :arguments {:path "x" :content "y"}}]}]
                 [{:event :completed :text "final"}]]
        model (scripted-model scripts)
        runner (devops/runner {:model model
                               :tools {:execute! (fn [call]
                                                   (swap! called conj call)
                                                   {:status :ok :text "bad"})}})
        result (mission/devops! host {:tenure-id plan-id
                                      :call-id "devops-forbidden"
                                      :verbose-intent "try a write"
                                      :run (:run! runner)})
        requests @(:requests model)
        tool-message (some #(when (= :tool (:role %)) %)
                           (get-in requests [1 :snapshot :messages]))]
    (is (= "final" (get-in result [:receipt :report])))
    (is (empty? @called))
    (is (true? (:error? tool-message)))
    (is (str/includes? (:text tool-message) "not permitted"))))

(deftest park-interrupts-and-unpark-resumes-the-same-worker
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        scripts
        [[{:event :completed
           :text ""
           :tool-calls [{:id "p1" :name "park"
                         :arguments {:name "park-1"
                                     :payload {:needle "missing"}}}]}]
         [{:event :completed
           :text ""
           :tool-calls [{:id "u1" :name "unpark"
                         :arguments {:name "park-1"
                                     :value [1 2]}}]}]
         [{:event :completed :text "resumed report"}]
         [{:event :completed
           :text ""
           :tool-calls [{:id "u2" :name "unpark"
                         :arguments {:name "park-1"
                                     :value [3 4]}}]}]
         [{:event :completed :text "stale handled"}]
         [{:event :completed
           :text ""
           :tool-calls [{:id "u3" :name "unpark"
                         :arguments {:name "missing"
                                     :value nil}}]}]
         [{:event :completed :text "unknown handled"}]]
        model (scripted-model scripts)
        runner (devops/runner {:model model})
        first-result (mission/devops! host {:tenure-id plan-id
                                            :call-id "devops-park"
                                            :verbose-intent "park the worker"
                                            :run (:run! runner)})
        first-worker (get-in first-result [:receipt :worker-id])
        second-result (mission/devops! host {:tenure-id plan-id
                                             :call-id "devops-resume"
                                             :verbose-intent "resume the worker"
                                             :run (:run! runner)})
        third-result (mission/devops! host {:tenure-id plan-id
                                            :call-id "devops-stale"
                                            :verbose-intent "try to resume twice"
                                            :run (:run! runner)})
        fourth-result (mission/devops! host {:tenure-id plan-id
                                             :call-id "devops-unknown"
                                             :verbose-intent "resume an unknown name"
                                             :run (:run! runner)})
        worker (get-in (mission/snapshot host)
                       [:bindings :plan-worker :worker])]
    (is (= :interrupted (get-in first-result [:receipt :worker-result-status])))
    (let [park-report (get-in first-result [:receipt :report])]
      (is (string? park-report))
      (is (str/includes? (str park-report) "park-1"))
      (when (string? park-report)
        (is (= {:name "park-1"
                :state :parked
                :reason :explicit
                :payload {:needle "missing"}}
               (ufss/read-str park-report))
            "the park receipt is one EDN+UFSS value")))
    (is (= :ok (get-in second-result [:receipt :worker-result-status])))
    (is (= "resumed report" (get-in second-result [:receipt :report])))
    (is (= first-worker (get-in second-result [:receipt :worker-id])))
    (is (= :ok (get-in third-result [:receipt :worker-result-status])))
    (is (= "stale handled" (get-in third-result [:receipt :report])))
    (is (= :ok (get-in fourth-result [:receipt :worker-result-status])))
    (is (= "unknown handled" (get-in fourth-result [:receipt :report])))
    (is (= :ok (:last-status worker)))
    (is (= :idle (:status worker)))
    (testing "the unpark receipt is one EDN+UFSS value"
      (let [tool-texts (->> (get-in @(:requests model) [2 :snapshot :messages])
                            (filter #(= :tool (:role %)))
                            (map :text))
            receipt-text (some #(when (and (str/includes? % "park-1")
                                           (str/includes? % ":resumed"))
                                  %)
                               tool-texts)]
        (is (string? receipt-text))
        (when (string? receipt-text)
          (is (= {:name "park-1" :state :resumed :value [1 2]}
                 (ufss/read-str receipt-text))))))))

(deftest call-tool-message-is-one-edn-ufss-value
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        structured (ufss/write-str {:stdout "a\nb"
                                    :quoted "say \"hi\""})
        scripts [[{:event :completed
                   :text ""
                   :tool-calls [{:id "c1" :name "call"
                                 :arguments {:name "structured"}}
                                {:id "c2" :name "call"
                                 :arguments {:name "raw"}}]}]
                 [{:event :completed :text "done"}]]
        model (scripted-model scripts)
        runner (devops/runner
                {:model model
                 :tools {:execute! (fn [{:keys [arguments]}]
                                     (if (= "raw" (:name arguments))
                                       {:status :ok
                                        :text "raw result\nsecond line"}
                                       {:status :ok :text structured}))}})
        result (mission/devops! host {:tenure-id plan-id
                                      :call-id "devops-call"
                                      :verbose-intent "probe the workspace"
                                      :run (:run! runner)})
        messages (get-in @(:requests model) [1 :snapshot :messages])
        tool-text (fn [id]
                    (some #(when (and (= :tool (:role %))
                                      (= id (:tool-call-id %)))
                             (:text %))
                          messages))]
    (is (:ok? result))
    (is (= "done" (get-in result [:receipt :report])))
    (let [structured-text (tool-text "c1")
          raw-text (tool-text "c2")]
      (is (string? structured-text))
      (is (string? raw-text))
      (when (and (string? structured-text) (string? raw-text))
        (let [structured-value (ufss/read-str structured-text)
              raw-value (ufss/read-str raw-text)]
          (is (= "a\nb" (:stdout structured-value)))
          (is (= "say \"hi\"" (:quoted structured-value)))
          (is (true? (:workspace-may-have-changed structured-value)))
          (is (str/includes? (:note structured-value)
                             "workspace may have changed"))
          (is (= "raw result\nsecond line" (:result raw-value)))
          (is (true? (:workspace-may-have-changed raw-value))))
        (is (not (str/includes? structured-text "\\\"")))
        (is (not (str/includes? raw-text "\\\"")))))))

(deftest model-failure-is-reported-and-worker-returns-idle
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        model (scripted-model [[{:event :failed
                                 :error {:message "provider exploded"}}]])
        runner (devops/runner {:model model})
        result (mission/devops! host {:tenure-id plan-id
                                      :call-id "devops-failed"
                                      :verbose-intent "inspect"
                                      :run (:run! runner)})
        worker (get-in (mission/snapshot host)
                       [:bindings :plan-worker :worker])]
    (is (str/includes? (get-in result [:receipt :report])
                       "provider exploded"))
    (is (= :failed (get-in result [:receipt :worker-result-status])))
    (is (= :failed (:last-status worker)))
    (is (= :idle (:status worker)))))

(deftest cancellation-returns-interrupted-with-partial-report
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        started (promise)
        model {:start! (fn [request _deliver!]
                         (deliver started (:call-id request))
                         {:attempt-id (:attempt-id request)
                          :call-id (:call-id request)})
               :cancel! (fn [_] nil)}
        runner (devops/runner {:model model})
        run (future
              (mission/devops! host {:tenure-id plan-id
                                     :call-id "devops-cancel"
                                     :verbose-intent "inspect slowly"
                                     :run (:run! runner)}))]
    (is (= "devops-cancel" (deref started 5000 ::timeout)))
    (is (:ok? ((:cancel! runner) {:worker-id "plan-worker-1"
                                  :reason :user-cancel})))
    (let [result (deref run 5000 {:receipt {:status :timeout}})
          worker (get-in (mission/snapshot host)
                         [:bindings :plan-worker :worker])]
      (is (= :interrupted (:last-status worker)))
      (is (= :interrupted (get-in result [:receipt :worker-result-status])))
      (is (string? (get-in result [:receipt :report])))
      (is (= :idle (:status worker))))))

(deftest large-report-is-not-truncated
  (let [host (support/host)
        plan-id (support/plan-tenure-id host)
        report (apply str (repeat 200000 "x"))
        model (report-model [report])
        runner (devops/runner {:model model})
        result (mission/devops! host {:tenure-id plan-id
                                      :call-id "devops-large"
                                      :verbose-intent "produce a large report"
                                      :run (:run! runner)})]
    (is (= (count report) (count (get-in result [:receipt :report]))))
    (is (= report (get-in result [:receipt :report])))))

(deftest unconfigured-task-model-returns-failed-report
  (let [model (scripted-model [])
        runner (devops/runner {:model model})
        result ((:run! runner) {:worker-id "plan-worker-1"
                                :tenure-id "plan-1"
                                :call-id "devops-no-task"
                                :model-role :task
                                :model-id nil
                                :verbose-intent "inspect"})]
    (is (= :failed (:status result)))
    (is (= :ROLE_UNCONFIGURED (get-in result [:error :code])))
    (is (= :configure-task-model (get-in result [:error :remedy])))
    (is (string? (:report result)))
    (is (empty? @(:requests model))
        "no model start is dispatched without a task model")))
