(ns hc.server.app.devops-owner-integration-test
  "Actual Owner model-turn path: Plan receives the devops tool, calls it,
  the Owner dispatches the real worker executor, and the report returns."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hc.compose.mission :as mission]
            [hc.compose.mission.devops :as mission-devops]
            [hc.compose.owner :as owner]
            [hc.server.app.devops :as app-devops]
            [hc.server.files :as files])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- plan-gateway
  [requests]
  (let [starts (atom 0)]
    {:start! (fn [request deliver!]
               (swap! requests conj request)
               (let [n (swap! starts inc)]
                 (future
                   (if (= n 1)
                     (do
                       (deliver! {:event :tool-call
                                  :call-id "tool-devops-1"
                                  :tool-name "devops"
                                  :arguments
                                  {:verbose-intent "inspect the workspace"
                                   :scope {:kind :reset-scope
                                           :new-scope-goal "devops"
                                           :new-scope-retention-budget 4}}})
                       (deliver! {:event :completed
                                  :text "Plan requested devops."}))
                     (deliver! {:event :completed
                                :text "Plan final after devops."}))))
               {:handle true})
     :cancel! (fn [_] nil)}))

(defn- worker-gateway
  []
  {:start! (fn [request deliver!]
             (future
               (deliver! {:event :chunk
                          :channel :text
                          :text "worker report: "})
               (deliver! {:event :completed
                          :text "worker report: inspected the workspace"}))
             {:handle true})
   :cancel! (fn [_] nil)})

(deftest owner-dispatches-real-plan-devops-tool
  (let [root (temp-dir "hc-devops-owner-")
        _ (spit (str root "/a.txt") "owner-visible contents")
        plan-requests (atom [])
        owner-ref (atom nil)
        command!
        (fn [command]
          (let [op (:op command)
                owner @owner-ref
                epoch (get-in ((:snapshot owner))
                              [:state :commands :command-epoch] 1)]
            ((:submit-command! owner)
             {:command-id (str "devops-" (name op) "-" (:call-id command))
              :command-epoch epoch
              :payload (assoc command :kind op)})))
        worker-runner
        (app-devops/runner
         {:model-gateway (worker-gateway)
          :files-service (files/service {:root root})})
        devops-exec
        {:run (fn [effect]
                (let [request (:request effect)]
                  (app-devops/run-devops!
                   {:runner worker-runner :command! command!}
                   {:tenure-id (:tenure-id request)
                    :call-id (:call-id request)
                    :verbose-intent (:verbose-intent request)})))
         :cancel (fn [_] nil)}
        handle (owner/start-owner! {:ephemeral? true
                                    :session-id "devops-owner-test"
                                    :user-input "Build"
                                    :models {:main "main-model"
                                             :task "task-model"}
                                    :config {:plan-tools
                                             [mission-devops/devops-tool-spec]
                                             :workspace-root root}
                                    :model-gateway (plan-gateway plan-requests)
                                    :executors {:devops devops-exec}
                                    :tool-result-renderer pr-str})]
    (reset! owner-ref handle)
    (try
      (let [epoch (get-in ((:snapshot handle))
                          [:state :commands :command-epoch] 1)
            submitted ((:submit-command! handle)
                       {:command-id "user-1"
                        :command-epoch epoch
                        :payload {:kind :user/message
                                  :text "Investigate the repository"}})
            deadline (+ (System/currentTimeMillis) 8000)]
        (is (:status submitted))
        (loop []
          (let [state (get-in ((:snapshot handle)) [:state])
                tool-message (some #(when (and (= :tool (:role %))
                                               (= "devops" (:member %)))
                                      %)
                                   (:conversation state))]
            (when (and (not tool-message)
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 25)
              (recur))))
        (let [state (get-in ((:snapshot handle)) [:state])
              tool-message (some #(when (and (= :tool (:role %))
                                             (= "devops" (:member %)))
                                    %)
                                 (:conversation state))
              first-request (first @plan-requests)
              tools (set (map :name (:tools (:snapshot first-request))))
              worker (get-in state [:mission :bindings :plan-worker :worker])
              tool-call (some #(when (= "devops" (:member %)) %)
                              (vals (:calls state)))]
          (is (contains? tools "devops"))
          (is (= "inspect the workspace"
                 (:verbose-intent (:request tool-call))))
          (is (= :devops (:kind tool-call)))
          (is (= :devops (get-in tool-call [:request :executor])))
          (is (= :task (:model-role worker)))
          (is (= "task-model" (:model-id worker)))
          (is (= :ok (:last-status worker)))
          (is (= "worker report: inspected the workspace"
                 (:last-report worker)))
          (is (some? tool-message))
          (is (str/includes? (:text tool-message)
                             "worker report: inspected the workspace"))))
      (finally
        ((:stop! handle))))))

(deftest devops-failure-becomes-outcome-unknown-without-retry
  (let [plan-requests (atom [])
        owner-ref (atom nil)
        devops-exec
        {:run (fn [_effect]
                {:ok? false
                 :error {:code :DEVOPS_FAILED
                         :message "worker transport failed"}})
         :cancel (fn [_] nil)}
        handle (owner/start-owner! {:ephemeral? true
                                    :session-id "devops-owner-unknown-test"
                                    :user-input "Build"
                                    :models {:main "main-model"
                                             :task "task-model"}
                                    :config {:plan-tools
                                             [mission-devops/devops-tool-spec]}
                                    :model-gateway (plan-gateway plan-requests)
                                    :executors {:devops devops-exec}
                                    :tool-result-renderer pr-str})]
    (reset! owner-ref handle)
    (try
      (let [epoch (get-in ((:snapshot handle))
                          [:state :commands :command-epoch] 1)
            submitted ((:submit-command! handle)
                       {:command-id "user-unknown-1"
                        :command-epoch epoch
                        :payload {:kind :user/message
                                  :text "Investigate the repository"}})
            deadline (+ (System/currentTimeMillis) 8000)]
        (is (:status submitted))
        (loop []
          (let [call (get-in ((:snapshot handle))
                             [:state :calls "call-1/tool-0"])]
            (when (and (not (contains? #{:outcome-unknown :failed}
                                       (:state call)))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 25)
              (recur))))
        (let [call (get-in ((:snapshot handle))
                           [:state :calls "call-1/tool-0"])]
          (is (= :devops (:kind call)))
          (is (= :outcome-unknown (:state call)))
          (is (= :outcome-unknown (get-in call [:retry :phase])))
          (is (= 1 (get-in call [:retry :attempts-used])))
          (is (nil? (get-in call [:retry :retry-at])))))
      (finally
        ((:stop! handle))))))

(deftest devops-tool-requires-the-flat-scope-annotation
  (let [spec mission-devops/devops-tool-spec
        parameters (:parameters spec)]
    (is (= #{"verbose-intent" "scope"} (set (:required parameters)))
        "the provider schema must require :scope like clj-plan")
    (is (= ["kind"] (get-in parameters [:properties :scope :required])))
    (is (= ["reset-scope" "keep-last" "remove-last" "me-too"]
           (get-in parameters [:properties :scope :properties :kind :enum])))))
